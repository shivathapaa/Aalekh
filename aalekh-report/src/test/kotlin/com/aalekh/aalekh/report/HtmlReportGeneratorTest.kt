package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.rules.AppliedRule
import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.model.CoChange
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ExternalDependency
import com.aalekh.aalekh.model.MainSequenceZone
import com.aalekh.aalekh.model.ModuleChurn
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleMainSequence
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
    fun `summary JSON carries the Lakos system-coupling metrics`() {
        val html = generateHtml()
        assertTrue(html.contains("\"ccd\":"), "summary must inject CCD for the KPI board")
        assertTrue(html.contains("\"nccd\":"), "summary must inject NCCD")
        assertTrue(html.contains("\"tanglePercent\":"), "summary must inject %Tangle")
    }

    @Test
    fun `report renders the coupling KPI cards`() {
        val html = generateHtml()
        assertTrue(html.contains("% Tangled"), "the KPI board must include the %Tangle card")
        assertTrue(html.contains("NCCD"), "the KPI board must include the NCCD card")
    }

    @Test
    fun `main-sequence points are injected for the A-I scatter when provided`() {
        val graph = sampleGraph()
        val html = HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = GraphAnalyzer.summary(graph),
            mainSequence = listOf(
                ModuleMainSequence(":app", 0.9, 0.1, 0.0, MainSequenceZone.MAIN_SEQUENCE, 1, 9),
            ),
        )
        assertTrue(
            html.contains("\"mainSequence\":[{\"path\":\":app\""),
            "the scatter points must be injected into the summary data",
        )
    }

    @Test
    fun `main-sequence is an empty array when no scatter data is supplied`() {
        assertTrue(generateHtml().contains("\"mainSequence\":[]"))
    }

    @Test
    fun `hidden coupling pairs are injected when provided`() {
        val graph = sampleGraph()
        val html = HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = GraphAnalyzer.summary(graph),
            hiddenCoupling = listOf(CoChange(":a", ":b", 5, 0.8, declared = false)),
        )
        assertTrue(
            html.contains("\"hiddenCoupling\":[{\"a\":\":a\",\"b\":\":b\""),
            "hidden-coupling pairs must be injected for the temporal alert card",
        )
    }

    @Test
    fun `hidden coupling is an empty array when no temporal data is supplied`() {
        assertTrue(generateHtml().contains("\"hiddenCoupling\":[]"))
    }

    @Test
    fun `applied rules are injected into the summary for the Rules panel`() {
        val graph = sampleGraph()
        val html = HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = GraphAnalyzer.summary(graph),
            appliedRules = listOf(
                AppliedRule(
                    id = "layer-dependency",
                    description = "Modules must only depend on permitted layers.",
                    severity = Severity.ERROR,
                    explanation = "Layered architecture keeps concerns separate.",
                    ruleCount = 1,
                    violationCount = 0,
                ),
            ),
        )
        assertTrue(
            html.contains("\"rules\":[{\"id\":\"layer-dependency\""),
            "the active rule set must be injected into the summary data",
        )
        assertTrue(
            html.contains("\"severity\":\"ERROR\"") && html.contains("\"violations\":0"),
            "each applied rule must carry its effective severity and violation count",
        )
    }

    @Test
    fun `applied rules is an empty array when no rules are configured`() {
        assertTrue(generateHtml().contains("\"rules\":[]"))
    }

    @Test
    fun `report ships the Rules tab and panel shell`() {
        val html = generateHtml()
        assertTrue(html.contains("""data-p="rules""""), "the Rules tab must be present in the nav")
        assertTrue(html.contains("""id="panel-rules""""), "the Rules panel container must be present")
    }

    @Test
    fun `per-module churn is injected for the inspector when provided`() {
        val graph = sampleGraph()
        val html = HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = GraphAnalyzer.summary(graph),
            churn = listOf(ModuleChurn(":app", 12)),
        )
        assertTrue(
            html.contains("\"churn\":{\":app\":12"),
            "per-module churn must be injected so the inspector can show it",
        )
    }

    @Test
    fun `external dependencies are embedded in the graph data for the inspector`() {
        val graph = sampleGraph().copy(
            externalDependencies = listOf(
                ExternalDependency(":app", "androidx.core", "core-ktx", "1.13.1", "implementation"),
            ),
        )
        val html = generateHtml(graph)
        assertTrue(html.contains("androidx.core"), "HTML should embed the external dependency group")
        assertTrue(html.contains("core-ktx"), "HTML should embed the external dependency name")
        assertTrue(html.contains("1.13.1"), "HTML should embed the external dependency version")
    }

    @Test
    fun `external dependencies is an empty array when none are captured`() {
        assertTrue(generateHtml().contains("\"externalDependencies\":[]"))
    }

    @Test
    fun `dependencies tab and aggregate panel are present in the report shell`() {
        val html = generateHtml()
        assertTrue(html.contains("""data-p="deps""""), "Dependencies outer tab must be in the nav")
        assertTrue(html.contains("""id="panel-deps""""), "Dependencies panel container must exist")
        assertTrue(html.contains("""id="deps-content""""), "Dependencies content host must exist")
        assertTrue(html.contains("function buildDeps"), "buildDeps aggregator must be embedded")
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

    // Team ownership overlay

    @Test
    fun `team owners map is embedded in the summary JSON`() {
        val graph = sampleGraph()
        val html = HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = GraphAnalyzer.summary(graph),
            teamOwners = mapOf(
                "auth-team" to listOf(":feature:login:**", ":core:auth"),
                "data-team" to listOf(":core:domain"),
            ),
        )
        assertTrue(
            html.contains(""""auth-team":[":feature:login:**",":core:auth"]"""),
            "teamOwners must embed each team with its glob patterns"
        )
        assertTrue(
            html.contains(""""data-team":[":core:domain"]"""),
            "Every declared team must appear in teamOwners"
        )
    }

    @Test
    fun `no teams declared yields an empty teamOwners object`() {
        // Default generate() passes no teamOwners; the overlay stays disabled.
        val html = generateHtml()
        assertTrue(
            html.contains(""""teamOwners":{}"""),
            "An empty team map must serialize as an empty object so the overlay is off"
        )
    }

    // System-coupling (Lakos) metrics

    @Test
    fun `summary JSON embeds the Lakos coupling metrics`() {
        // :app -> :feature:login -> :core:domain : dependency sets 3 + 2 + 1 = CCD 6.
        val html = generateHtml()
        assertTrue(html.contains("\"ccd\":6"), "CCD must be embedded in the summary JSON")
        assertTrue(html.contains("\"tanglePercent\":0"), "A clean DAG must report zero tangle")
        assertTrue(html.contains("\"nccd\":"), "NCCD must be present in the summary JSON")
    }

    // Declared layers
    //
    // The report groups its Architecture swimlanes and layer purity table by these. Without them
    // it falls back to guessing from module path segments, so what reaches the HTML decides whether
    // the report shows the enforced architecture or an inference.

    private fun htmlWithLayers(layers: List<LayerSpec>): String {
        val graph = sampleGraph()
        return HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = GraphAnalyzer.summary(graph),
            layers = layers,
        )
    }

    @Test
    fun `declared layers are embedded with their patterns and allowed lists`() {
        val html = htmlWithLayers(
            listOf(
                LayerSpec("domain", listOf(":core:domain"), emptyList(), hasRestriction = false),
                LayerSpec("presentation", listOf(":app", ":feature:*"), listOf("domain"), hasRestriction = true),
            )
        )

        assertTrue(
            html.contains("""{"name":"domain","patterns":[":core:domain"],"allowed":[],"restricted":false}"""),
            "Each declared layer must embed its name, patterns, allowed list, and restriction flag"
        )
        assertTrue(
            html.contains(
                """{"name":"presentation","patterns":[":app",":feature:*"],""" +
                    """"allowed":["domain"],"restricted":true}"""
            ),
            "A restricted layer must embed its canOnlyDependOn allowlist"
        )
    }

    @Test
    fun `declared layers mark the layer source as declared`() {
        val html = htmlWithLayers(
            listOf(LayerSpec("domain", listOf(":core:domain"), emptyList(), hasRestriction = false))
        )

        assertTrue(
            html.contains(""""layerSource":"OBSERVED""""),
            "With layers declared the report must not present the grouping as inferred"
        )
    }

    @Test
    fun `no declared layers marks the layer source as inferred`() {
        val html = generateHtml()

        assertTrue(html.contains(""""layers":[]"""), "No declared layers must serialize as an empty array")
        assertTrue(
            html.contains(""""layerSource":"INFERRED""""),
            "Without declared layers the report must say the grouping was inferred"
        )
    }

    // Project health

    @Test
    fun `summary JSON embeds the project health score with its components`() {
        val html = generateHtml()

        assertTrue(html.contains(""""health":{"score":"""), "Project health must be embedded")
        assertTrue(html.contains(""""band":"Healthy""""), "A clean sample graph must band as Healthy")
        assertTrue(
            html.contains(""""label":"Cycles""""),
            "Health components must be embedded so the dial can explain the score"
        )
    }

    @Test
    fun `project health reflects violations passed to the generator`() {
        val graph = sampleGraph()
        val html = HtmlReportGenerator.generate(
            projectName = graph.projectName,
            graph = graph,
            summary = GraphAnalyzer.summary(graph),
            violations = List(3) {
                Violation("layer-dependency", Severity.ERROR, "message $it", ":app → :core:domain")
            },
        )

        // 3 ERROR violations x 5 points = 15 deducted by the blocking-violations signal.
        assertTrue(html.contains(""""health":{"score":85"""), "ERROR violations must lower project health")
    }
}