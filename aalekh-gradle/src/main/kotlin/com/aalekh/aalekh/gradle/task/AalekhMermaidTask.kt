package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.ReportCoordinator
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Exports the module dependency graph as [Mermaid](https://mermaid.js.org) text.
 *
 * Two files are written next to the HTML report:
 * - `aalekh-graph.mmd` - the raw Mermaid definition, for `mermaid-cli` or manual embedding.
 * - `aalekh-graph.md` - the same graph inside a ` ```mermaid ` fenced block, which renders as a
 *   diagram directly on GitHub. Commit it next to your code to keep a diffable, versioned graph.
 *
 * Run: `./gradlew aalekhMermaid`
 *
 * ### Caching
 * Output is fully determined by the extracted `graph.json`, so the task is [CacheableTask].
 */
@CacheableTask
public abstract class AalekhMermaidTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    /** Raw Mermaid graph definition, written to `aalekh-graph.mmd`. */
    @get:OutputFile
    public abstract val mermaidFile: RegularFileProperty

    /** Markdown wrapper with a fenced `mermaid` block, written to `aalekh-graph.md`. */
    @get:OutputFile
    public abstract val markdownFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Exports the module graph as diffable Mermaid text (.mmd + .md). " +
                "Run: ./gradlew aalekhMermaid"
    }

    @TaskAction
    public fun generate() {
        val graph = readGraph()
        // No rules are needed for a pure graph export; drive the same report facade the other
        // tasks use rather than reaching into the generator directly.
        val coordinator = ReportCoordinator(graph, RuleEngineResult(emptyList(), 0), projectName.get())

        val mmd = mermaidFile.get().asFile
        mmd.parentFile.mkdirs()
        mmd.writeText(coordinator.generateMermaid())

        val md = markdownFile.get().asFile
        md.parentFile.mkdirs()
        md.writeText(coordinator.generateMermaidMarkdown())

        logger.lifecycle("Aalekh Mermaid → file://${mmd.absolutePath}")
        logger.lifecycle("Aalekh Mermaid (Markdown) → file://${md.absolutePath}")
    }

    private fun readGraph(): ModuleDependencyGraph =
        mermaidJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val mermaidJson = Json { ignoreUnknownKeys = true }
