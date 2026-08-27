package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.metrics.MainSequenceAnalyzer
import com.aalekh.aalekh.gradle.extractor.TypeAbstractnessScanner
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.mainsequence.MainSequenceReportGenerator
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
import java.io.File

/**
 * Computes each module's **distance from the main sequence** and writes it as a local artefact.
 *
 * For every module it estimates abstractness (A) from a coarse Kotlin/Java source scan, reads
 * instability (I) from the dependency graph, and reports the distance `D = |A + I - 1|` - how far the
 * module sits from the ideal balance of the two. Two files are written next to the HTML report:
 * - `aalekh-main-sequence.md` - a reviewable table, worst-distance first, with the zone-of-pain and
 *   zone-of-uselessness modules called out.
 * - `aalekh-main-sequence.json` - the same data, machine-readable.
 *
 * Run: `./gradlew aalekhMainSequence`
 *
 * ### Caching
 * Caching is disabled: the abstractness estimate depends on source file contents, which are not
 * declared task inputs (declaring every source file would make this - and the extract task - re-run on
 * every code change). The task is still configuration-cache safe; every input is a plain value.
 */
@DisableCachingByDefault(because = "abstractness depends on source file contents, which are not declared inputs")
public abstract class AalekhMainSequenceTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    /** Absolute path of the root project directory, used to locate each module's source tree. */
    @get:Input
    public abstract val rootDir: Property<String>

    /** Machine-readable report, written to `aalekh-main-sequence.json`. */
    @get:OutputFile
    public abstract val jsonFile: RegularFileProperty

    /** Reviewable Markdown, written to `aalekh-main-sequence.md`. */
    @get:OutputFile
    public abstract val markdownFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Computes each module's abstractness, instability and distance from the main " +
                "sequence (.md + .json). Run: ./gradlew aalekhMainSequence"
    }

    @TaskAction
    public fun analyze() {
        val graph = readGraph()
        val rootFile = File(rootDir.get())
        val abstractness = graph.modules.associate { module ->
            module.path to TypeAbstractnessScanner.scan(moduleDir(rootFile, module.path, module.buildFilePath), logger)
        }
        val report = MainSequenceAnalyzer.analyze(graph, abstractness)

        val md = markdownFile.get().asFile
        md.parentFile.mkdirs()
        md.writeText(MainSequenceReportGenerator.markdown(report, projectName.get()))

        val json = jsonFile.get().asFile
        json.parentFile.mkdirs()
        json.writeText(MainSequenceReportGenerator.json(report))

        if (report.modules.isEmpty()) {
            logger.lifecycle("Aalekh main sequence: no countable types found → file://${md.absolutePath}")
        } else {
            logger.lifecycle(
                "Aalekh main sequence: ${report.modules.size} module(s) analysed, average distance " +
                        "${"%.2f".format(report.averageDistance)} → file://${md.absolutePath}"
            )
        }
    }

    /**
     * Resolves a module's source directory. Prefers the parent of its recorded build file (the true
     * project directory), falling back to the Gradle path convention (`:a:b` -> `a/b`).
     */
    private fun moduleDir(rootFile: File, modulePath: String, buildFilePath: String?): File {
        val relative = buildFilePath?.substringBeforeLast('/', "")
            ?: modulePath.trimStart(':').replace(':', '/')
        return if (relative.isBlank()) rootFile else rootFile.resolve(relative)
    }

    private fun readGraph(): ModuleDependencyGraph =
        mainSequenceJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val mainSequenceJson = Json { ignoreUnknownKeys = true }
