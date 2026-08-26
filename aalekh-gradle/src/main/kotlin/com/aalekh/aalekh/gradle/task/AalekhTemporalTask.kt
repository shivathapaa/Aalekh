package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.gradle.git.GitHistoryReader
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.ReportCoordinator
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Computes **git temporal (change) coupling** and writes it as a local, diffable artefact.
 *
 * Reads the recent commit window via `git log` (offline, at execution time), maps each changed file
 * to the module that owns it, and reports:
 * - **Change hotspots** - the most-committed modules.
 * - **Hidden coupling** - pairs that change together but declare no dependency.
 * - **Dead structure** - declared edges whose modules both change yet never co-change.
 *
 * Two files are written next to the HTML report:
 * - `aalekh-temporal.md` - a reviewable Markdown report, ready to commit or paste into a PR.
 * - `aalekh-temporal.json` - the same data, machine-readable.
 *
 * Run: `./gradlew aalekhTemporal`
 *
 * ### Caching
 * Caching is disabled: the output depends on live git `HEAD`, which is not a declared task input, so
 * a build-cache hit could serve a stale report. Reading history is cheap enough to always re-run.
 * The task is still configuration-cache safe - every input is a plain value.
 */
@DisableCachingByDefault(because = "output depends on live git history (HEAD), which is not a declared input")
public abstract class AalekhTemporalTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    /** Absolute path of the git working tree to read history from (the root project directory). */
    @get:Input
    public abstract val rootDir: Property<String>

    /** Number of recent non-merge commits to analyse. */
    @get:Input
    public abstract val commitWindow: Property<Int>

    /** Minimum commits a module pair must share before it is reported. */
    @get:Input
    public abstract val minSharedCommits: Property<Int>

    /** Coupling degree at or above which an undeclared pair is flagged as hidden coupling. */
    @get:Input
    public abstract val hiddenCouplingThreshold: Property<Double>

    /** Machine-readable report, written to `aalekh-temporal.json`. */
    @get:OutputFile
    public abstract val jsonFile: RegularFileProperty

    /** Reviewable Markdown report, written to `aalekh-temporal.md`. */
    @get:OutputFile
    public abstract val markdownFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Analyses git history for temporal (change) coupling and hotspots (.md + .json). " +
                "Run: ./gradlew aalekhTemporal"
    }

    @TaskAction
    public fun analyze() {
        val graph = readGraph()
        val commits = GitHistoryReader.read(rootDir.get(), commitWindow.get(), logger)

        // No rules are needed for a temporal export; drive the same report facade the other tasks use.
        val coordinator = ReportCoordinator(graph, RuleEngineResult(emptyList(), 0), projectName.get())
        val report = coordinator.analyzeTemporal(
            commits = commits,
            minSharedCommits = minSharedCommits.get(),
            hiddenCouplingThreshold = hiddenCouplingThreshold.get(),
        )

        val md = markdownFile.get().asFile
        md.parentFile.mkdirs()
        md.writeText(coordinator.temporalMarkdown(report))

        val json = jsonFile.get().asFile
        json.parentFile.mkdirs()
        json.writeText(coordinator.temporalJson(report))

        if (report.commitsAnalyzed == 0) {
            logger.warn(
                "Aalekh temporal: no git history mapped to a module (shallow clone or no commits) - " +
                        "the report is empty."
            )
        } else {
            logger.lifecycle(
                "Aalekh temporal: ${report.commitsAnalyzed} commit(s), " +
                        "${report.hiddenCoupling.size} hidden-coupling pair(s), " +
                        "${report.deadStructure.size} dead-structure edge(s)."
            )
        }
        logger.lifecycle("Aalekh temporal → file://${md.absolutePath}")
    }

    private fun readGraph(): ModuleDependencyGraph =
        temporalJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val temporalJson = Json { ignoreUnknownKeys = true }
