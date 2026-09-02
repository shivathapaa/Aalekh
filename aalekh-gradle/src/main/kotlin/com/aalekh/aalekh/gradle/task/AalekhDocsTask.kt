package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.rules.LayerSpecParser
import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.TemporalCouplingReport
import com.aalekh.aalekh.report.ReportCoordinator
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes the project's architecture documentation as Markdown.
 *
 * The HTML report is for exploring; these files are for **reading and reviewing**. They render on
 * GitHub with no build step, so a reviewer sees them without cloning, and they diff line by line, so
 * an architectural change appears in a pull request next to the code that caused it.
 *
 * Run: `./gradlew aalekhDocs`
 * Output: `<projectRoot>/build/reports/aalekh/docs/`
 *
 * The output is **deterministic** - no timestamps, no run ids, sorted throughout - which is what
 * makes committing it worthwhile: re-running on an unchanged project rewrites the same bytes, so any
 * diff is a real architectural change rather than noise from the tool having run again. Teams that
 * want the documentation reviewed alongside the code copy the directory into the repository and add
 * a CI step that fails when a re-run produces a diff.
 */
@CacheableTask
public abstract class AalekhDocsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    /** Serialized `layers { }` declarations; groups the regions document by the declared architecture. */
    @get:Input
    public abstract val layerEntries: ListProperty<String>

    @get:Input
    public abstract val featurePattern: Property<String>

    @get:Input
    public abstract val featureAllowedPairs: ListProperty<String>

    @get:Input
    public abstract val ruleEntries: ListProperty<String>

    @get:Input
    public abstract val forbidEntries: ListProperty<String>

    @get:Input
    public abstract val reachabilityEntries: ListProperty<String>

    @get:Input
    public abstract val sourceSetEntries: ListProperty<String>

    /** Serialized `teams { }` map; supplies ownership in the module catalogue. */
    @get:Input
    public abstract val teamEntries: Property<String>

    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    init {
        group = "aalekh"
        description = "Writes architecture documentation as Markdown. Run: ./gradlew aalekhDocs"
        teamEntries.convention("")
    }

    @TaskAction
    public fun generate() {
        val graph: ModuleDependencyGraph = docsJson.decodeFromString(graphJsonFile.get().asFile.readText())
        val ruleEngine = RuleEngine.fromConfig(
            layerEntries = layerEntries.get(),
            featurePattern = featurePattern.getOrElse(""),
            featureAllowedPairs = featureAllowedPairs.get(),
            ruleEntries = ruleEntries.get(),
            forbidEntries = forbidEntries.get(),
            reachabilityEntries = reachabilityEntries.get(),
            sourceSetEntries = sourceSetEntries.get(),
        )
        val coordinator = ReportCoordinator(graph, ruleEngine.evaluate(graph), projectName.get())

        // The temporal report is optional - it exists only after aalekhTemporal has run - and adds
        // churn-aware findings when present. Its absence is normal, not an error.
        val temporal = readTemporal()

        val documents = coordinator.generateDocs(
            teamOwners = parseTeamEntries(teamEntries.getOrElse("")),
            hiddenCoupling = temporal?.hiddenCoupling.orEmpty(),
            churn = temporal?.churn.orEmpty(),
            layers = LayerSpecParser.parse(layerEntries.get()),
        )

        val dir = outputDir.get().asFile
        dir.mkdirs()
        // Remove documents from a previous run that this one no longer produces, so the directory
        // never accumulates a stale file describing something the project has since dropped.
        dir.listFiles { file -> file.isFile && file.name.endsWith(".md") }
            ?.filterNot { it.name in documents.keys }
            ?.forEach { it.delete() }

        documents.forEach { (name, content) -> dir.resolve(name).writeText(content) }

        logger.lifecycle(
            "Aalekh docs → ${documents.size} file(s) in file://${dir.absolutePath}"
        )
    }

    private fun readTemporal(): TemporalCouplingReport? {
        val file = outputDir.get().asFile.parentFile?.resolve("aalekh-temporal.json")
        if (file == null || !file.exists()) return null
        return runCatching {
            docsJson.decodeFromString<TemporalCouplingReport>(file.readText())
        }.getOrElse {
            logger.info("Aalekh: could not read temporal data for the docs - ${it.message}")
            null
        }
    }

    /** Inverse of `TeamOwnershipConfig.toInputString`; mirrors `AalekhReportTask`. */
    private fun parseTeamEntries(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";")
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val patterns = entry.substring(separator + 1)
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (patterns.isEmpty()) null else entry.substring(0, separator) to patterns
            }
            .toMap()
    }
}

private val docsJson = Json { ignoreUnknownKeys = true }
