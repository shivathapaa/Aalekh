package com.aalekh.aalekh.report.html

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Violation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Generates the self-contained Aalekh HTML interactive report.
 *
 * The output is a single `.html` file with:
 * - D3.js v7 force-directed graph (loaded from CDN; can be bundled offline later)
 * - All graph data injected inline as `const GRAPH_DATA = {...}`
 * - Zero runtime server, zero external dependencies beyond the browser
 */
public object HtmlReportGenerator {
    private val json = Json {
        prettyPrint = false          // Minified for embedding in HTML
        encodeDefaults = true
    }

    private const val NAME_PLACEHOLDER = "{{PROJECT_NAME}}"
    private const val TIME_PLACEHOLDER = "{{GENERATED_AT}}"
    private const val VERSION_PLACEHOLDER = "{{AALEKH_VERSION}}"

    private const val DATA_INJECT_PLACEHOLDER = "<!-- AALEKH_DATA_INJECT -->"

    /**
     * Generates the full HTML string for the interactive dependency report.
     *
     * @param projectName Root project name shown in the report title
     * @param graph       The complete module dependency graph
     * @param summary     Pre-computed summary statistics
     * @param violations  Rule violations (empty in Phase 1, populated in Phase 2+)
     */
    public fun generate(
        projectName: String,
        graph: ModuleDependencyGraph,
        summary: GraphSummary,
        violations: List<Violation> = emptyList(),
    ): String {
        val template = loadTemplate()
        val graphJson = json.encodeToString(graph)
        val summaryJson = buildSummaryJson(summary, violations, graph)

        val dataScriptTags = buildString {
            appendLine("""<script type="application/json" id="aalekh-graph-data">""")
            appendLine(graphJson)
            appendLine("</script>")
            appendLine("""<script type="application/json" id="aalekh-summary-data">""")
            appendLine(summaryJson)
            appendLine("</script>")
        }

        return template
            .replace("<script>\nfunction parseScriptJson", "$dataScriptTags<script>\nfunction parseScriptJson")
            .replace("<script>\n    function parseScriptJson", "$dataScriptTags<script>\n    function parseScriptJson")
            .replace(NAME_PLACEHOLDER, escapeHtml(projectName))
            .replace(TIME_PLACEHOLDER, Instant.now().toString())
//            .replace(VERSION_PLACEHOLDER, CURRENT) // will update later
    }

    private fun buildSummaryJson(
        summary: GraphSummary,
        violations: List<Violation>,
        graph: ModuleDependencyGraph
    ): String {
        val byType = summary.modulesByType.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        val violationsJson = violations.joinToString(",") { v ->
            """{"ruleId":"${escapeJson(v.ruleId)}","severity":"${v.severity.name}","message":"${escapeJson(v.message)}","source":"${
                escapeJson(
                    v.source
                )
            }"}"""
        }
        // Compute data for the HTML report
        val criticalPathModules = try {
            GraphAnalyzer.criticalPath(graph)
        } catch (_: Exception) {
            emptyList()
        }
        val godModulePaths = GraphAnalyzer.godModules(graph).map { it.path }
        val isolatedPaths = GraphAnalyzer.isolatedModules(graph).map { it.path }

        // Compute main-only cycles (test edges excluded from cycle detection)
        val mainCycles = GraphAnalyzer.findMainOnlyCycles(graph)
        val mainCycleNodesJson = mainCycles.flatten().toSet().joinToString(",") { "\"${escapeJson(it)}\"" }
        val mainCycleEdgesJson = mainCycles.flatMap { cycle ->
            cycle.indices.map { i ->
                val a = cycle[i];
                val b = cycle[(i + 1) % cycle.size]; "\"${escapeJson(a)}\u2192${escapeJson(b)}\""
            }
        }.joinToString(",")

        val critPathJson = criticalPathModules.joinToString(",") { "\"${escapeJson(it)}\"" }
        val godPathJson = godModulePaths.joinToString(",") { "\"${escapeJson(it)}\"" }
        val isolPathJson = isolatedPaths.joinToString(",") { "\"${escapeJson(it)}\"" }

        return """{"totalModules":${summary.totalModules},"totalEdges":${summary.totalEdges},"hasCycles":${summary.hasCycles},"cycleCount":${summary.cycleCount},"maxFanOut":${summary.maxFanOut},"maxFanIn":${summary.maxFanIn},"averageInstability":${summary.averageInstability},"criticalPathLength":${summary.criticalPathLength},"godModuleCount":${summary.godModuleCount},"isolatedModuleCount":${summary.isolatedModuleCount},"violationCount":${violations.count { it.severity.name != "INFO" }},"errorCount":${violations.count { it.severity.name == "ERROR" }},"warningCount":${violations.count { it.severity.name == "WARNING" }},"infoCount":${violations.count { it.severity.name == "INFO" }},"modulesByType":{$byType},"violations":[$violationsJson],"criticalPathModules":[$critPathJson],"godModulePaths":[$godPathJson],"isolatedModulePaths":[$isolPathJson],"mainCycleNodes":[$mainCycleNodesJson],"mainCycleEdges":[$mainCycleEdgesJson]}"""
    }

    private fun loadTemplate(): String {
        val resourcePath = "aalekh-report-template.html"
        val stream = HtmlReportGenerator::class.java.classLoader
            ?.getResourceAsStream(resourcePath)
            ?: HtmlReportGenerator::class.java
                .getResourceAsStream(resourcePath)  // fallback: same-package lookup
            ?: error(
                "Aalekh: HTML report template not found in JAR resources.\n" +
                        "Expected resource path: $resourcePath\n" +
                        "Classloader: ${HtmlReportGenerator::class.java.classLoader}\n" +
                        "This is a packaging bug - please file an issue at " +
                        "https://github.com/shivathapaa/aalekh/issues"
            )
        return stream.bufferedReader().use { it.readText() }
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
