package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.rules.LayerSpecParser
import com.aalekh.aalekh.analysis.snapshot.SnapshotAnalyzer
import com.aalekh.aalekh.model.AalekhBuildConfig
import com.aalekh.aalekh.model.ArchitectureDiff
import com.aalekh.aalekh.model.ArchitectureSnapshot
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.diff.ArchitectureDiffReporter
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Records the current architecture as a committable snapshot.
 *
 * Run: `./gradlew aalekhSnapshot`
 * Output: `<projectRoot>/aalekh-snapshot.json` - commit it.
 *
 * The snapshot is what `aalekhDiff` compares against, which is how an architectural change becomes
 * visible in a pull request. Adding one line to a `dependencies { }` block can wire two subsystems
 * together, and a normal review shows one line; a snapshot diff shows what it did.
 *
 * The file is deliberately small and sorted so it diffs line by line, and it holds module paths,
 * dependency pairs, cycle membership, layer assignments, and the structural metrics worth watching -
 * not the whole graph, which would rewrite itself on every unrelated change.
 */
@DisableCachingByDefault(
    because = "writes a committed baseline into the source tree; must always reflect the current graph"
)
public abstract class AalekhSnapshotTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    /** Serialized `layers { }` declarations, so the snapshot remembers which layer claimed a module. */
    @get:Input
    public abstract val layerEntries: ListProperty<String>

    @get:OutputFile
    public abstract val snapshotFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Records the current architecture as a committable snapshot for aalekhDiff."
    }

    @TaskAction
    public fun capture() {
        val graph: ModuleDependencyGraph = snapshotJson.decodeFromString(
            graphJsonFile.get().asFile.readText()
        )
        val snapshot = SnapshotAnalyzer.capture(
            graph = graph,
            layers = LayerSpecParser.parse(layerEntries.get()),
            aalekhVersion = AalekhBuildConfig.VERSION,
        )

        val file = snapshotFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(prettyJson.encodeToString(ArchitectureSnapshot.serializer(), snapshot))

        logger.lifecycle(
            "Aalekh snapshot → ${snapshot.modules.size} modules, ${snapshot.edges.size} dependencies " +
                "→ file://${file.absolutePath}\n" +
                "  Commit this file so aalekhDiff can report what a change does to the architecture."
        )
    }
}

/**
 * Reports what the current architecture changed relative to the committed snapshot.
 *
 * Run: `./gradlew aalekhDiff`
 * Output: `aalekh-diff.md` (a ready-to-post pull-request comment) and `aalekh-diff.json`.
 *
 * With no snapshot committed the task explains how to create one and succeeds - the first run of a
 * new tool must never fail a build. Aalekh writes local files only; posting the comment is the
 * consumer's CI job, which is deliberate: a build tool that reaches out to a code host is a build
 * tool that needs credentials.
 */
@DisableCachingByDefault(
    because = "compares against a source-tree file the task deliberately does not declare as an input"
)
public abstract class AalekhDiffTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    @get:Input
    public abstract val layerEntries: ListProperty<String>

    /**
     * The committed snapshot to compare against.
     *
     * Read manually rather than declared as an `@InputFile` because it is optional - absent until the
     * first `aalekhSnapshot` run - and this task never caches, so fingerprinting it would add nothing.
     */
    @get:Internal
    public abstract val snapshotFile: RegularFileProperty

    /** When true, the task fails on a structural regression - a new cycle or a metric that got worse. */
    @get:Input
    public abstract val failOnRegression: Property<Boolean>

    @get:OutputFile
    public abstract val markdownFile: RegularFileProperty

    @get:OutputFile
    public abstract val jsonFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Reports what this change did to the architecture, against the committed snapshot."
        failOnRegression.convention(false)
    }

    @TaskAction
    public fun compare() {
        val graph: ModuleDependencyGraph = snapshotJson.decodeFromString(
            graphJsonFile.get().asFile.readText()
        )
        val layers = LayerSpecParser.parse(layerEntries.get())
        val current = SnapshotAnalyzer.capture(graph, layers, AalekhBuildConfig.VERSION)
        val baseline = readSnapshot()

        if (baseline == null) {
            logger.lifecycle(
                "Aalekh: no committed snapshot found at " +
                    "${snapshotFile.orNull?.asFile?.name ?: ArchitectureSnapshot.DEFAULT_PATH}.\n" +
                    "  Run ./gradlew aalekhSnapshot and commit the file; from then on aalekhDiff " +
                    "reports what each change does to the architecture."
            )
        }

        val diff = SnapshotAnalyzer.diff(baseline ?: ArchitectureSnapshot.EMPTY, current)
        writeOutputs(diff)
        logResult(diff, baseline != null)

        check(!(failOnRegression.getOrElse(false) && diff.hasRegression)) {
            "\nAalekh: this change makes the architecture structurally worse. " +
                "See ${markdownFile.get().asFile.absolutePath}"
        }
    }

    private fun readSnapshot(): ArchitectureSnapshot? {
        val file = snapshotFile.orNull?.asFile?.takeIf { it.isFile } ?: return null
        return runCatching {
            snapshotJson.decodeFromString(ArchitectureSnapshot.serializer(), file.readText())
                .takeIf { !it.isEmpty }
        }.getOrElse { ex ->
            logger.warn("Aalekh: could not read ${file.name} - ${ex.message}. Treating it as absent.")
            null
        }
    }

    private fun writeOutputs(diff: ArchitectureDiff) {
        val markdown = markdownFile.get().asFile
        val json = jsonFile.get().asFile
        markdown.parentFile?.mkdirs()
        markdown.writeText(ArchitectureDiffReporter.markdown(diff, projectName.get()))
        json.writeText(ArchitectureDiffReporter.json(diff))
    }

    private fun logResult(diff: ArchitectureDiff, hadBaseline: Boolean) {
        if (!hadBaseline) return
        if (diff.isEmpty) {
            logger.lifecycle("Aalekh: no architectural change against the committed snapshot.")
            return
        }
        logger.lifecycle(
            "Aalekh diff → " +
                "+${diff.addedEdges.size}/-${diff.removedEdges.size} dependencies, " +
                "+${diff.addedModules.size}/-${diff.removedModules.size} modules" +
                (if (diff.newCycles.isNotEmpty()) ", ${diff.newCycles.size} module(s) newly in a cycle" else "") +
                "\n  → file://${markdownFile.get().asFile.absolutePath}"
        )
    }
}

private val snapshotJson = Json { ignoreUnknownKeys = true }
private val prettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
}
