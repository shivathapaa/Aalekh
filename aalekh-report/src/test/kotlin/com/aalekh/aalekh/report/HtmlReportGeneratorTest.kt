package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
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
        assertTrue(html.contains("implementation"), "HTML should contain edge configuration type")
    }

    // Injection marker (regression guard for Fix 3)

    @Test
    fun `injection marker comment is consumed and not present in output`() {
        // The marker comment is the anchor used to inject data tags.
        // After injection it must be gone - if it remains, injection failed.
        val html = generateHtml()
        assertFalse(
            html.contains("DATA INJECTED BY KOTLIN GENERATOR BEFORE THIS SCRIPT TAG"),
            "The injection marker comment must be replaced by data tags, not left in the output"
        )
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
    fun `data script tags use application-json type attribute`() {
        // type="application/json" prevents the browser from executing the tag as JS
        val html = generateHtml()
        assertTrue(
            html.contains("""<script type="application/json" id="aalekh-graph-data">"""),
            "Graph data tag must use type='application/json'"
        )
        assertTrue(
            html.contains("""<script type="application/json" id="aalekh-summary-data">"""),
            "Summary data tag must use type='application/json'"
        )
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
    fun `generated HTML size is reasonable for a small graph`() {
        val html = generateHtml()
        assertTrue(html.length > 50_000, "HTML seems too small: ${html.length} bytes")
    }

    @Test
    fun `generated HTML is fully offline - no CDN or font references`() {
        val html = generateHtml()
        assertFalse(
            html.contains("cdn.jsdelivr.net") || html.contains("unpkg.com"),
            "Report must not reference an external JS CDN; D3 must be inlined."
        )
        assertFalse(
            html.contains("fonts.googleapis.com") || html.contains("fonts.gstatic.com"),
            "Report must not reference Google Fonts; system font stack only."
        )
        assertFalse(
            html.contains("{{D3_INLINE}}"),
            "D3 placeholder must be substituted with the bundled d3.min.js contents."
        )
        assertTrue(html.contains("d3js.org"), "Inlined D3 copyright banner expected in output.")
    }

    // ---- Blueprint redesign markers ---------------------------------------
    // These guard the new dual-theme + 6-panel IA + UX layer added in the
    // blueprint refactor. They fail loudly if anyone accidentally regresses
    // the visual identity or removes a panel.

    @Test
    fun `blueprint theme preamble sets data-theme on html before body JS runs`() {
        val html = generateHtml()
        assertTrue(
            html.contains("document.documentElement.setAttribute('data-theme'"),
            "Theme preamble must set data-theme attribute on <html> early in <head>."
        )
        assertTrue(
            html.contains("[data-theme=\"dark\"]") && html.contains("[data-theme=\"light\"]"),
            "Both dark and light theme palettes must be declared."
        )
        assertTrue(
            html.contains("prefers-color-scheme: dark"),
            "Default theme should follow the user's OS preference."
        )
    }

    @Test
    fun `blueprint exposes a theme toggle button`() {
        val html = generateHtml()
        assertTrue(html.contains("""id="btn-theme""""), "Theme toggle button must be present.")
    }

    @Test
    fun `blueprint information architecture is six panels`() {
        val html = generateHtml()
        val expectedTabs = listOf("overview", "map", "browse", "health", "violations", "diff")
        expectedTabs.forEach { name ->
            assertTrue(
                html.contains("""data-p="$name""""),
                "Expected outer tab data-p=\"$name\" in new IA."
            )
        }
        // Architecture/Explore/Explorer/Matrix were folded into Map and Browse — they
        // should no longer surface as top-level outer tabs.
        assertFalse(
            html.contains("""data-p="arch""""),
            "Architecture is now a subview of Map, not an outer tab."
        )
        assertFalse(
            html.contains("""data-p="graph""""),
            "Force graph is now a subview of Map, not an outer tab."
        )
        assertFalse(
            html.contains("""data-p="explorer""""),
            "Tree explorer is now a subview of Browse, not an outer tab."
        )
        assertFalse(
            html.contains("""data-p="metrics""""),
            "Metrics outer tab was renamed to Health (data-p=\"health\")."
        )
    }

    @Test
    fun `blueprint preserves inner panel IDs the render layer depends on`() {
        // The unified IA wraps existing panels rather than replacing them.
        // Make sure every panel JS still queries by ID is present somewhere.
        val html = generateHtml()
        val required = listOf(
            "panel-overview", "panel-map", "panel-browse", "panel-health",
            "panel-violations", "panel-diff",
            "panel-arch", "panel-graph", "panel-explorer", "panel-matrix", "panel-metrics"
        )
        required.forEach { id ->
            assertTrue(
                html.contains("""id="$id""""),
                "Required panel id=\"$id\" must remain in markup."
            )
        }
    }

    @Test
    fun `blueprint ships a command palette and keyboard help overlay`() {
        val html = generateHtml()
        assertTrue(html.contains("""id="palette-overlay""""), "Command palette overlay must be present.")
        assertTrue(html.contains("""id="palette-input""""), "Command palette search input must be present.")
        assertTrue(html.contains("""id="kbd-overlay""""), "Keyboard shortcuts overlay must be present.")
    }

    @Test
    fun `blueprint ships a print stylesheet so the report doubles as a deliverable`() {
        val html = generateHtml()
        assertTrue(
            html.contains("@media print"),
            "A print stylesheet is required so the report can be archived as PDF."
        )
    }

    @Test
    fun `blueprint Overview is the default landing tab`() {
        val html = generateHtml()
        // The Overview tab carries .active on first render so the landing
        // page is what users see before any interaction.
        val ovTabIdx = html.indexOf("""data-p="overview"""")
        val ovActiveIdx = html.indexOf("""class="tab active" data-p="overview"""")
        assertTrue(ovTabIdx >= 0, "Overview tab must be defined in the nav.")
        assertTrue(
            ovActiveIdx >= 0,
            "Overview tab must carry .active so it's the default landing view."
        )
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
            Violation(
                ruleId = "test-rule",
                severity = Severity.ERROR,
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
        assertTrue(
            html.contains("\"mainCycleNodes\":[]"),
            "Test-only cycle should NOT appear in mainCycleNodes"
        )
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