package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.report.html.HtmlReportGenerator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [HtmlReportGenerator].
 *
 * These tests verify the generated HTML is well-formed, contains the expected
 * data placeholders replaced, and embeds module/edge data from the graph.
 */
class HtmlReportGeneratorTest {

    private fun sampleGraph(): ModuleDependencyGraph = ModuleDependencyGraph(
        projectName = "test-project",
        modules = listOf(
            ModuleNode(":app", "app", ModuleType.ANDROID_APP),
            ModuleNode(":core:domain", "domain", ModuleType.JVM_LIBRARY),
            ModuleNode(":feature:login", "login", ModuleType.ANDROID_LIBRARY),
        ),
        edges = listOf(
            DependencyEdge(":app", ":feature:login", "implementation"),
            DependencyEdge(":feature:login", ":core:domain", "implementation"),
        ),
        metadata = mapOf("gradleVersion" to "9.0", "aalekhVersion" to "0.1.0"),
    )

    private fun generateHtml(graph: ModuleDependencyGraph = sampleGraph()): String {
        val summary = GraphAnalyzer.summary(graph)
        return HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = summary,
        )
    }

    // Data injection
    @Test
    fun `generated HTML contains graph data script tag`() {
        val html = generateHtml()
        assertTrue(
            html.contains("""id="aalekh-graph-data""""),
            "HTML must contain the <script id='aalekh-graph-data'> tag"
        )
    }

    @Test
    fun `generated HTML contains summary data script tag`() {
        val html = generateHtml()
        assertTrue(
            html.contains("""id="aalekh-summary-data""""),
            "HTML must contain the <script id='aalekh-summary-data'> tag"
        )
    }

    @Test
    fun `generated HTML contains module paths from graph`() {
        val html = generateHtml()
        assertTrue(html.contains(":app"), "HTML should embed :app module path")
        assertTrue(html.contains(":core:domain"), "HTML should embed :core:domain module path")
        assertTrue(html.contains(":feature:login"), "HTML should embed :feature:login module path")
    }

    @Test
    fun `generated HTML contains dependency edge data`() {
        val html = generateHtml()
        // Edges are serialized inside the graph JSON
        assertTrue(html.contains("implementation"), "HTML should contain edge configuration type")
    }

    // Placeholder replacement
    @Test
    fun `project name placeholder is replaced in HTML title`() {
        val html = generateHtml()
        assertTrue(html.contains("test-project"), "Project name should appear in the HTML")
        assertFalse(html.contains("{{PROJECT_NAME}}"), "PROJECT_NAME placeholder must be replaced")
    }

    @Test
    fun `generated_at placeholder is replaced`() {
        val html = generateHtml()
        assertFalse(html.contains("{{GENERATED_AT}}"), "GENERATED_AT placeholder must be replaced")
    }

    @Test
    fun `aalekh_version placeholder is replaced`() {
        val html = generateHtml()
        assertFalse(html.contains("{{AALEKH_VERSION}}"), "AALEKH_VERSION placeholder must be replaced")
    }

    @Test
    fun `no raw comment placeholders remain in output`() {
        val html = generateHtml()
        // Old-style comment placeholders must NOT appear in output
        assertFalse(html.contains("/* AALEKH_GRAPH_DATA */"), "Old comment placeholder must be replaced")
        assertFalse(html.contains("/* AALEKH_SUMMARY_DATA */"), "Old comment placeholder must be replaced")
    }

    // HTML structure
    @Test
    fun `generated HTML is a well-formed document`() {
        val html = generateHtml()
        assertTrue(html.trimStart().startsWith("<!DOCTYPE html>"), "HTML must start with DOCTYPE")
        assertTrue(html.contains("</html>"), "HTML must close the root element")
        assertTrue(html.contains("</body>"), "HTML must close body")
    }

    @Test
    fun `data script tags appear before parseScriptJson call`() {
        val html = generateHtml()
        val dataTagIndex = html.indexOf("""id="aalekh-graph-data"""")
        val parseScriptIndex = html.indexOf("function parseScriptJson")
        assertTrue(
            dataTagIndex < parseScriptIndex,
            "Data script tag must appear BEFORE parseScriptJson() - otherwise getElementById returns null"
        )
    }

    @Test
    fun `generated HTML size is reasonable for a small graph`() {
        val html = generateHtml()
        // Template alone is ~60KB; with data it should be at least 50KB
        assertTrue(html.length > 50_000, "HTML seems too small: ${html.length} bytes")
    }

    // HTML escaping
    @Test
    fun `project name with special chars is HTML-escaped in title`() {
        val graph = ModuleDependencyGraph(
            projectName = "my<project>&co",
            modules = emptyList(),
            edges = emptyList(),
        )
        val summary = GraphAnalyzer.summary(graph)
        val html = HtmlReportGenerator.generate("my<project>&co", graph, summary)
        // The escaped form must appear in the title area; raw < must not
        assertTrue(html.contains("my&lt;project&gt;&amp;co"), "Special chars must be HTML-escaped")
    }

    // Empty graph
    @Test
    fun `empty graph produces valid HTML without errors`() {
        val graph = ModuleDependencyGraph(
            projectName = "empty",
            modules = emptyList(),
            edges = emptyList(),
        )
        val html = HtmlReportGenerator.generate("empty", graph, GraphAnalyzer.summary(graph))
        assertTrue(html.contains("</html>"))
        assertTrue(html.contains("""id="aalekh-graph-data""""))
    }

    // Violations in report
    @Test
    fun `report with violations includes violation data in summary JSON`() {
        val graph = sampleGraph()
        val summary = GraphAnalyzer.summary(graph)
        val violations = listOf(
            com.aalekh.aalekh.model.Violation(
                ruleId = "test-rule",
                severity = com.aalekh.aalekh.model.Severity.ERROR,
                message = "Test violation message",
                source = ":app",
            )
        )
        val html = HtmlReportGenerator.generate("test", graph, summary, violations)
        assertTrue(html.contains("test-rule"), "Violation ruleId should be in report")
        assertTrue(html.contains("Test violation message"), "Violation message should be in report")
    }

    @Test
    fun `report with test cycle has mainCycleNodes properly computed`() {
        // Graph with test-only cycle: :a → :b (impl), :b → :a (testImpl)
        val graph = ModuleDependencyGraph(
            projectName = "test-cycle",
            modules = listOf(
                ModuleNode(":a", "a", ModuleType.JVM_LIBRARY),
                ModuleNode(":b", "b", ModuleType.JVM_LIBRARY),
            ),
            edges = listOf(
                DependencyEdge(":a", ":b", "implementation"),
                DependencyEdge(":b", ":a", "testImplementation"),
            ),
        )
        val html = generateHtml(graph)
        // Main-only cycles should be empty - the cycle only exists with test edges
        assertTrue(html.contains("\"mainCycleNodes\":[]"), "Test-only cycle should NOT appear in mainCycleNodes")
    }

    @Test
    fun `report with real cycle has mainCycleNodes populated`() {
        val graph = ModuleDependencyGraph(
            projectName = "real-cycle",
            modules = listOf(
                ModuleNode(":a", "a", ModuleType.JVM_LIBRARY),
                ModuleNode(":b", "b", ModuleType.JVM_LIBRARY),
            ),
            edges = listOf(
                DependencyEdge(":a", ":b", "implementation"),
                DependencyEdge(":b", ":a", "implementation"),
            ),
        )
        val html = generateHtml(graph)
        // Main cycles should contain :a and :b
        assertTrue(html.contains(":a"), "Main cycle node :a should be in report")
        assertTrue(html.contains(":b"), "Main cycle node :b should be in report")
        assertFalse(html.contains("\"mainCycleNodes\":[]"), "Main cycle should populate mainCycleNodes")
    }

    // Deeply nested modules
    @Test
    fun `deeply nested module paths are preserved in HTML`() {
        val graph = ModuleDependencyGraph(
            projectName = "nested",
            modules = listOf(
                ModuleNode(":core:ui:presentation:utils", "utils", ModuleType.ANDROID_LIBRARY),
                ModuleNode(":feature:login:data:remote", "remote", ModuleType.ANDROID_LIBRARY),
            ),
            edges = listOf(
                DependencyEdge(":feature:login:data:remote", ":core:ui:presentation:utils", "implementation"),
            ),
        )
        val html = generateHtml(graph)
        assertTrue(html.contains(":core:ui:presentation:utils"), "Deeply nested path should be in report")
        assertTrue(html.contains(":feature:login:data:remote"), "Deeply nested path should be in report")
    }
}
