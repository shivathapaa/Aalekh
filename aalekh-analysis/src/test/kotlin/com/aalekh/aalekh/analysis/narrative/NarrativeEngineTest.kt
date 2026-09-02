package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.metrics.GraphMetrics
import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.model.CoChange
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleChurn
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Provenance
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NarrativeEngineTest {

    private fun graphOf(
        edges: List<Pair<String, String>>,
        extraModules: List<String> = emptyList(),
        externalDependencies: List<com.aalekh.aalekh.model.ExternalDependency> = emptyList(),
    ): ModuleDependencyGraph {
        val paths = (edges.flatMap { listOf(it.first, it.second) } + extraModules).distinct()
        return ModuleDependencyGraph(
            projectName = "demo",
            modules = paths.map { ModuleNode(it, it.substringAfterLast(":"), ModuleType.JVM_LIBRARY) },
            edges = edges.map { DependencyEdge(it.first, it.second, "implementation") },
            externalDependencies = externalDependencies,
        )
    }

    private fun contextOf(
        graph: ModuleDependencyGraph,
        layers: List<LayerSpec> = emptyList(),
        teams: Map<String, List<String>> = emptyMap(),
        churn: List<ModuleChurn> = emptyList(),
        hiddenCoupling: List<CoChange> = emptyList(),
    ) = NarrativeContext(
        graph = graph,
        metrics = GraphMetrics.compute(graph),
        summary = GraphAnalyzer.summary(graph),
        layers = layers,
        teams = teams,
        churn = churn,
        hiddenCoupling = hiddenCoupling,
    )

    /**
     * A layered demo project, deliberately above the reading-order threshold so the fixture
     * exercises every finder rather than tripping the small-project guards.
     */
    private fun layeredProject() = graphOf(
        listOf(
            ":app" to ":feature:login", ":app" to ":feature:profile", ":app" to ":core:ui",
            ":app" to ":core:analytics",
            ":feature:login" to ":core:ui", ":feature:login" to ":core:data",
            ":feature:profile" to ":core:ui", ":feature:profile" to ":core:data",
            ":core:ui" to ":core:model", ":core:data" to ":core:model",
            ":core:data" to ":core:network",
            ":core:analytics" to ":core:network",
            ":core:network" to ":core:model",
        )
    )

    @Test
    fun `an empty graph produces an empty narrative`() {
        val graph = ModuleDependencyGraph("empty", emptyList(), emptyList())

        assertEquals(
            com.aalekh.aalekh.model.NarrativeReport.EMPTY,
            NarrativeEngine.analyze(contextOf(graph)),
        )
    }

    @Test
    fun `the summary names the project, its size, and its entry point`() {
        val report = NarrativeEngine.analyze(contextOf(layeredProject()))

        assertTrue(report.summary.startsWith("demo is a Gradle project of 8 modules"), report.summary)
        assertTrue(report.summary.contains("Execution starts at :app"), report.summary)
        assertTrue(report.summary.contains("acyclic"), report.summary)
    }

    @Test
    fun `the summary reports cycles instead of claiming the graph is sound`() {
        val cyclic = graphOf(listOf(":a" to ":b", ":b" to ":c", ":c" to ":a", ":d" to ":a"))
        val report = NarrativeEngine.analyze(contextOf(cyclic))

        assertFalse(report.summary.contains("acyclic"), report.summary)
        assertTrue(report.summary.contains("issue"), report.summary)
    }

    // Determinism - the property that makes findings safe to commit and diff.

    @Test
    fun `the same graph always produces byte-identical narrative text`() {
        val first = NarrativeEngine.analyze(contextOf(layeredProject()))
        val second = NarrativeEngine.analyze(contextOf(layeredProject()))

        assertEquals(first, second)
    }

    @Test
    fun `narrative does not depend on module declaration order`() {
        val forward = layeredProject()
        val reversed = forward.copy(modules = forward.modules.reversed(), edges = forward.edges.reversed())

        assertEquals(
            NarrativeEngine.analyze(contextOf(forward)).findings,
            NarrativeEngine.analyze(contextOf(reversed)).findings,
        )
    }

    // Ordering

    @Test
    fun `actionable findings are ordered before descriptive ones`() {
        val cyclic = graphOf(listOf(":a" to ":b", ":b" to ":a", ":app" to ":a", ":c" to ":a", ":d" to ":a"))
        val report = NarrativeEngine.analyze(contextOf(cyclic))
        val severities = report.findings.map { it.severity.ordinal }

        assertEquals(severities.sorted(), severities, "findings must be ordered most severe first")
    }

    // Individual finders

    @Test
    fun `structure findings describe shape, entry points and foundation`() {
        val ids = NarrativeEngine.analyze(contextOf(layeredProject())).findings.map { it.id }

        assertTrue("project-shape" in ids, ids.toString())
        assertTrue("entry-points" in ids, ids.toString())
        assertTrue("foundation" in ids, ids.toString())
    }

    @Test
    fun `the shape finding is inferred without declared layers and computed with them`() {
        val inferred = NarrativeEngine.analyze(contextOf(layeredProject()))
            .findings.single { it.id == "project-shape" }
        val declared = NarrativeEngine.analyze(
            contextOf(
                layeredProject(),
                layers = listOf(
                    LayerSpec("ui", listOf(":app", ":feature:**"), emptyList(), hasRestriction = false),
                    LayerSpec("core", listOf(":core:**"), emptyList(), hasRestriction = false),
                ),
            )
        ).findings.single { it.id == "project-shape" }

        assertEquals(Provenance.INFERRED, inferred.provenance)
        assertNotNull(inferred.confidence, "an inferred claim must state its confidence")
        assertEquals(Provenance.COMPUTED, declared.provenance)
        assertTrue(declared.title.contains("layered"), declared.title)
    }

    @Test
    fun `cycles are reported as a blocking finding naming the tangled modules`() {
        val cyclic = graphOf(listOf(":a" to ":b", ":b" to ":a"))
        val finding = NarrativeEngine.analyze(contextOf(cyclic)).findings.single { it.id == "dependency-cycles" }

        assertEquals(Severity.ERROR, finding.severity)
        assertEquals(listOf(":a", ":b"), finding.subjects)
        assertNotNull(finding.action)
    }

    @Test
    fun `a module the whole project depends on gets a blast radius finding`() {
        val finding = NarrativeEngine.analyze(contextOf(layeredProject()))
            .findings.singleOrNull { it.id == "wide-blast-radius" }

        assertNotNull(finding)
        assertTrue(":core:model" in finding.subjects, finding.subjects.toString())
        assertTrue(finding.detail.contains("rebuild and retest"), finding.detail)
    }

    @Test
    fun `unowned modules are reported only when teams are declared`() {
        val withoutTeams = NarrativeEngine.analyze(contextOf(layeredProject()))
        val withTeams = NarrativeEngine.analyze(
            contextOf(layeredProject(), teams = mapOf("core-team" to listOf(":core:**")))
        )

        assertTrue(withoutTeams.findings.none { it.id == "unowned-modules" })
        val finding = withTeams.findings.single { it.id == "unowned-modules" }
        assertTrue(":app" in finding.subjects, finding.subjects.toString())
        assertFalse(":core:ui" in finding.subjects, finding.subjects.toString())
    }

    @Test
    fun `unclassified modules are reported only when layers are declared`() {
        val finding = NarrativeEngine.analyze(
            contextOf(
                layeredProject(),
                layers = listOf(LayerSpec("core", listOf(":core:**"), emptyList(), hasRestriction = false)),
            )
        ).findings.single { it.id == "unclassified-modules" }

        assertTrue(":app" in finding.subjects)
        assertFalse(":core:model" in finding.subjects)
    }

    @Test
    fun `version fragmentation names the library and its versions`() {
        val graph = graphOf(
            listOf(":app" to ":core"),
            externalDependencies = listOf(
                external(":app", "com.squareup.okhttp3", "okhttp", "4.12.0"),
                external(":core", "com.squareup.okhttp3", "okhttp", "4.9.0"),
            ),
        )
        val finding = NarrativeEngine.analyze(contextOf(graph)).findings
            .single { it.id == "version-fragmentation" }

        assertEquals(Severity.WARNING, finding.severity)
        assertTrue(finding.evidence.any { it.value.contains("4.9.0") && it.value.contains("4.12.0") })
        assertTrue(finding.evidence.all { it.howObtained == Provenance.OBSERVED })
    }

    @Test
    fun `churn plus reach identifies the riskiest module in the project`() {
        val finding = NarrativeEngine.analyze(
            contextOf(layeredProject(), churn = listOf(ModuleChurn(":core:model", 42)))
        ).findings.single { it.id == "volatile-foundation" }

        assertTrue(finding.title.startsWith(":core:model"), finding.title)
        assertTrue(finding.evidence.any { it.howObtained == Provenance.OBSERVED })
    }

    @Test
    fun `hidden coupling is reported when the temporal analysis supplies it`() {
        val finding = NarrativeEngine.analyze(
            contextOf(
                layeredProject(),
                hiddenCoupling = listOf(CoChange(":feature:login", ":feature:profile", 9, 0.9, declared = false)),
            )
        ).findings.single { it.id == "hidden-coupling" }

        assertTrue(":feature:login" in finding.subjects)
        assertNotNull(finding.action)
    }

    // Recommendations

    @Test
    fun `every recommendation is marked as a suggestion with a confidence`() {
        val report = NarrativeEngine.analyze(contextOf(layeredProject()))
        val suggestions = report.findings.filter { it.provenance == Provenance.SUGGESTED }

        assertTrue(suggestions.all { it.confidence != null }, "a suggestion must state its confidence")
        assertTrue(suggestions.all { it.action != null }, "a suggestion must say what to do")
    }

    @Test
    fun `a module whose consumers share nothing else is a split candidate`() {
        // Two independent halves that share only :shared - nothing else connects them.
        val graph = graphOf(
            listOf(
                ":a1" to ":shared", ":a2" to ":shared", ":a1" to ":aCommon", ":a2" to ":aCommon",
                ":b1" to ":shared", ":b2" to ":shared", ":b1" to ":bCommon", ":b2" to ":bCommon",
            )
        )
        val finding = NarrativeEngine.analyze(contextOf(graph)).findings
            .singleOrNull { it.id == "split-candidate" }

        assertNotNull(finding, "consumers falling into disjoint groups must surface a split candidate")
        assertTrue(finding.title.startsWith(":shared"), finding.title)
        assertEquals(Provenance.SUGGESTED, finding.provenance)
    }

    @Test
    fun `a module whose consumers are all related is not a split candidate`() {
        // Every consumer of :shared also depends on :common, so they form one group.
        val graph = graphOf(
            listOf(
                ":a" to ":shared", ":b" to ":shared", ":c" to ":shared", ":d" to ":shared",
                ":a" to ":common", ":b" to ":common", ":c" to ":common", ":d" to ":common",
            )
        )

        assertTrue(NarrativeEngine.analyze(contextOf(graph)).findings.none { it.id == "split-candidate" })
    }

    @Test
    fun `two modules with identical neighbourhoods are a merge candidate`() {
        val graph = graphOf(
            listOf(
                ":app" to ":twinA", ":app" to ":twinB",
                ":twinA" to ":base", ":twinB" to ":base",
            )
        )
        val finding = NarrativeEngine.analyze(contextOf(graph)).findings
            .singleOrNull { it.id == "merge-candidate" }

        assertNotNull(finding)
        assertEquals(listOf(":twinA", ":twinB"), finding.subjects)
    }

    // Reading order

    @Test
    fun `reading order starts at the entry point and spans the project`() {
        val steps = NarrativeEngine.analyze(contextOf(layeredProject())).readingOrder

        assertTrue(steps.isNotEmpty())
        assertEquals(":app", steps.first().module)
        assertEquals((1..steps.size).toList(), steps.map { it.position })
        assertTrue(steps.all { it.reason.isNotBlank() })
        assertEquals(steps.map { it.module }.distinct(), steps.map { it.module })
    }

    @Test
    fun `a small project gets no reading order`() {
        val small = graphOf(listOf(":a" to ":b"))

        assertTrue(NarrativeEngine.analyze(contextOf(small)).readingOrder.isEmpty())
    }

    // Lookups

    @Test
    fun `findings can be looked up per module`() {
        val report = NarrativeEngine.analyze(contextOf(layeredProject()))
        val forModel = NarrativeEngine.findingsFor(report, ":core:model")

        assertTrue(forModel.isNotEmpty())
        assertTrue(forModel.all { ":core:model" in it.subjects })
    }

    @Test
    fun `findings group by category with empty categories dropped`() {
        val grouped = NarrativeEngine.byCategory(NarrativeEngine.analyze(contextOf(layeredProject())))

        assertTrue(grouped.values.all { it.isNotEmpty() })
        assertTrue(grouped.containsKey(com.aalekh.aalekh.model.FindingCategory.STRUCTURE))
    }

    // Every finding must carry its evidence - that is the accuracy contract.

    @Test
    fun `every finding states a title, a detail and its evidence`() {
        val report = NarrativeEngine.analyze(
            contextOf(
                layeredProject(),
                teams = mapOf("core-team" to listOf(":core:**")),
                churn = listOf(ModuleChurn(":core:model", 42)),
            )
        )

        assertTrue(report.findings.isNotEmpty())
        report.findings.forEach { finding ->
            assertTrue(finding.id.isNotBlank(), "finding has no id")
            assertTrue(finding.title.isNotBlank(), "${finding.id} has no title")
            assertTrue(finding.detail.length > MIN_DETAIL, "${finding.id} detail is too short")
            assertTrue(finding.evidence.isNotEmpty(), "${finding.id} has no evidence")
            assertTrue(finding.evidence.all { it.value.isNotBlank() }, "${finding.id} has blank evidence")
        }
    }

    @Test
    fun `generated prose is grammatical`() {
        val report = NarrativeEngine.analyze(
            contextOf(
                layeredProject(),
                teams = mapOf("core-team" to listOf(":core:**")),
                churn = listOf(ModuleChurn(":core:model", 42)),
            )
        )
        val prose = (report.findings.map { it.title + " " + it.detail } + report.summary)
            .joinToString(" ")

        // "3 modules are depend on it" is what happens when the is/are helper is used for a lexical
        // verb. The word boundary matters: "are declared" is correct passive voice, "are declare"
        // is the bug - so the check has to distinguish a bare infinitive from a past participle.
        val bareInfinitiveAfterBe = Regex(
            "\\b(is|are) (depend|have|match|support|declare|use|apply|sit|carry|contain|belong)\\b"
        )
        val slip = bareInfinitiveAfterBe.find(prose)

        assertTrue(slip == null, "ungrammatical phrase \"${slip?.value}\" in: $prose")
    }

    @Test
    fun `finding ids are unique per subject group`() {
        val report = NarrativeEngine.analyze(contextOf(layeredProject()))
        val duplicated = report.findings
            .groupBy { it.id to it.subjects }
            .filterValues { it.size > 1 }

        assertTrue(duplicated.isEmpty(), "duplicate findings: ${duplicated.keys}")
    }

    private fun external(module: String, group: String, name: String, version: String) =
        com.aalekh.aalekh.model.ExternalDependency(module, group, name, version, "implementation")

    private companion object {
        const val MIN_DETAIL = 40
    }
}
