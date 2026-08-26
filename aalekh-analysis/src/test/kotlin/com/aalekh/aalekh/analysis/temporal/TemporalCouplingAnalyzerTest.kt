package com.aalekh.aalekh.analysis.temporal

import com.aalekh.aalekh.model.DeclaredEdgeRef
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.TemporalCouplingReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalCouplingAnalyzerTest {

    private fun node(path: String, buildFile: String? = null) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
        buildFilePath = buildFile,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    /** File under a module's conventional directory. */
    private fun file(dir: String) = "$dir/src/File.kt"

    private val domain = file("core/domain")
    private val data = file("core/data")
    private val login = file("feature/login")
    private val util = file("core/util")

    /**
     * Graph with declared edges. `:core:util` is structurally unconnected so a strong co-change
     * with it surfaces as hidden coupling; `:legacy` never changes so its edge is not dead.
     */
    private val graph = ModuleDependencyGraph(
        projectName = "temporal",
        modules = listOf(
            node(":core:domain", "core/domain/build.gradle.kts"),
            node(":core:data", "core/data/build.gradle.kts"),
            node(":feature:login", "feature/login/build.gradle.kts"),
            node(":core:util", "core/util/build.gradle.kts"),
            node(":legacy", "legacy/build.gradle.kts"),
        ),
        edges = listOf(
            edge(":core:data", ":core:domain"),
            edge(":feature:login", ":core:data"),
            edge(":feature:login", ":core:domain"),
            edge(":core:domain", ":legacy"),
        ),
    )

    private fun commits(vararg files: List<String>): List<CommitChange> = files.map { CommitChange(it) }

    private val scenario = commits(
        listOf(domain, data),
        listOf(domain, data),
        listOf(domain, data),
        listOf(domain, login),
        listOf(domain, login),
        listOf(domain, util),
        listOf(domain, util),
        listOf(data),
        listOf("README.md"),
        listOf(login),
    )

    @Test
    fun `empty commits yield the empty report`() {
        assertEquals(TemporalCouplingReport.EMPTY, TemporalCouplingAnalyzer.analyze(graph, emptyList()))
    }

    @Test
    fun `commits touching no module are excluded from the analysed count`() {
        val report = TemporalCouplingAnalyzer.analyze(graph, scenario)
        // 10 commits, but the README-only commit maps to no module.
        assertEquals(9, report.commitsAnalyzed)
    }

    @Test
    fun `churn ranks the most-committed module first and omits unchanged modules`() {
        val report = TemporalCouplingAnalyzer.analyze(graph, scenario)
        assertEquals(":core:domain", report.churn.first().module)
        assertEquals(7, report.churn.first().commits)
        assertEquals(mapOf(":core:domain" to 7, ":core:data" to 4, ":feature:login" to 3, ":core:util" to 2),
            report.churn.associate { it.module to it.commits })
        assertFalse(report.churn.any { it.module == ":legacy" }, "a module with no commits must not appear in churn")
    }

    @Test
    fun `degree is shared over the smaller churn`() {
        val report = TemporalCouplingAnalyzer.analyze(graph, scenario)
        val domainData = report.coChanges.single { it.moduleA == ":core:data" && it.moduleB == ":core:domain" }
        assertEquals(3, domainData.sharedCommits)
        assertEquals(0.75, domainData.degree) // 3 / min(7, 4)
        assertTrue(domainData.declared, "data -> domain is a declared edge")
    }

    @Test
    fun `strong co-change without a declared edge is hidden coupling`() {
        val report = TemporalCouplingAnalyzer.analyze(graph, scenario)
        assertEquals(1, report.hiddenCoupling.size)
        val hidden = report.hiddenCoupling.single()
        assertEquals(":core:domain", hidden.moduleA)
        assertEquals(":core:util", hidden.moduleB)
        assertEquals(1.0, hidden.degree) // 2 / min(7, 2)
        assertFalse(hidden.declared)
    }

    @Test
    fun `a declared edge whose modules never co-change is dead structure`() {
        val report = TemporalCouplingAnalyzer.analyze(graph, scenario)
        // login and data both churn, but never in the same commit.
        assertEquals(listOf(DeclaredEdgeRef(":feature:login", ":core:data")), report.deadStructure)
    }

    @Test
    fun `an edge to a never-changed module is not dead structure`() {
        val report = TemporalCouplingAnalyzer.analyze(graph, scenario)
        // domain -> legacy: legacy never changes, so absence of co-change is not evidence of decay.
        assertFalse(report.deadStructure.any { it.to == ":legacy" })
    }

    @Test
    fun `minSharedCommits filters incidental single-commit overlaps`() {
        val once = commits(listOf(domain, data))
        val report = TemporalCouplingAnalyzer.analyze(graph, once, minSharedCommits = 2)
        assertTrue(report.coChanges.isEmpty(), "a single shared commit is below the default threshold")
    }

    @Test
    fun `nested modules map a file to the deepest owning module`() {
        val nested = ModuleDependencyGraph(
            projectName = "nested",
            modules = listOf(
                node(":app", "app/build.gradle.kts"),
                node(":app:feature", "app/feature/build.gradle.kts"),
            ),
            edges = emptyList(),
        )
        val report = TemporalCouplingAnalyzer.analyze(
            nested,
            commits(listOf("app/feature/src/X.kt"), listOf("app/feature/src/X.kt")),
            minSharedCommits = 1,
        )
        assertEquals(listOf(":app:feature"), report.churn.map { it.module })
    }

    @Test
    fun `module directory falls back to the path convention when build file is unknown`() {
        val noBuildFile = ModuleDependencyGraph(
            projectName = "fallback",
            modules = listOf(node(":core:domain"), node(":core:data")),
            edges = emptyList(),
        )
        val report = TemporalCouplingAnalyzer.analyze(
            noBuildFile,
            commits(listOf(domain, data), listOf(domain, data)),
            minSharedCommits = 1,
        )
        assertEquals(setOf(":core:domain", ":core:data"), report.churn.map { it.module }.toSet())
    }

    @Test
    fun `test-only edges do not count as declared coupling`() {
        val testEdgeGraph = ModuleDependencyGraph(
            projectName = "test-edge",
            modules = listOf(node(":a", "a/build.gradle.kts"), node(":b", "b/build.gradle.kts")),
            edges = listOf(edge(":a", ":b", "testImplementation")),
        )
        val report = TemporalCouplingAnalyzer.analyze(
            testEdgeGraph,
            commits(listOf(file("a"), file("b")), listOf(file("a"), file("b"))),
            minSharedCommits = 1,
        )
        val pair = report.coChanges.single()
        assertFalse(pair.declared, "a test-only edge must not mark a pair as declared")
        // Undeclared and degree 1.0 -> hidden coupling; and no dead structure (no production edge).
        assertEquals(1, report.hiddenCoupling.size)
        assertTrue(report.deadStructure.isEmpty())
    }
}
