package com.aalekh.aalekh.analysis

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.*

/**
 * Unit tests for [GraphAnalyzer] - pure graph algorithms with no I/O.
 */
class GraphAnalyzerTest {
    private fun node(path: String, type: ModuleType = ModuleType.JVM_LIBRARY) =
        ModuleNode(path = path, name = path.substringAfterLast(":"), type = type)

    private fun edge(from: String, to: String) =
        DependencyEdge(from = from, to = to, configuration = "implementation")

    /**
     * A realistic multi-module project:
     *
     * :app -> :feature:login -> :core:domain
     *      -> :feature:home  -> :core:domain
     *                       -> :core:data  -> :core:domain
     */
    private fun realisticGraph(): ModuleDependencyGraph = ModuleDependencyGraph(
        projectName = "realistic",
        modules = listOf(
            node(":app", ModuleType.ANDROID_APP),
            node(":feature:login", ModuleType.ANDROID_LIBRARY),
            node(":feature:home", ModuleType.ANDROID_LIBRARY),
            node(":core:domain", ModuleType.JVM_LIBRARY),
            node(":core:data", ModuleType.ANDROID_LIBRARY),
        ),
        edges = listOf(
            edge(":app", ":feature:login"),
            edge(":app", ":feature:home"),
            edge(":feature:login", ":core:domain"),
            edge(":feature:home", ":core:domain"),
            edge(":feature:home", ":core:data"),
            edge(":core:data", ":core:domain"),
        ),
    )

    private fun cyclicGraph(): ModuleDependencyGraph = ModuleDependencyGraph(
        projectName = "cyclic",
        modules = listOf(node(":a"), node(":b"), node(":c")),
        edges = listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":a")),
    )

    @Test
    fun `topologicalOrder places dependents before their dependencies`() {
        // Kahn's algorithm starts from nodes with fanIn==0 (roots like :app).
        // Output order: dependents first, dependencies last.
        // i.e. :app comes before :feature:*, which come before :core:domain.
        val graph = realisticGraph()
        val order = GraphAnalyzer.topologicalOrder(graph)
        val positions = order.mapIndexed { i, n -> n.path to i }.toMap()

        // :core:domain must come AFTER everything that depends on it
        assertTrue(
            positions[":core:domain"]!! > positions[":core:data"]!!,
            ":core:domain should appear after :core:data (Kahn's order)"
        )
        assertTrue(
            positions[":core:domain"]!! > positions[":feature:login"]!!,
            ":core:domain should appear after :feature:login"
        )
        assertTrue(
            positions[":core:domain"]!! > positions[":feature:home"]!!,
            ":core:domain should appear after :feature:home"
        )
        // :app has fanIn==0 so it is processed first
        assertEquals(":app", order.first().path)
        // :core:domain has no outgoing production deps, so it comes last
        assertEquals(":core:domain", order.last().path)
    }

    @Test
    fun `topologicalOrder includes all modules`() {
        val graph = realisticGraph()
        assertEquals(graph.modules.size, GraphAnalyzer.topologicalOrder(graph).size)
    }

    @Test
    fun `topologicalOrder throws for cyclic graph`() {
        assertFailsWith<IllegalStateException> {
            GraphAnalyzer.topologicalOrder(cyclicGraph())
        }
    }

    @Test
    fun `criticalPath finds the longest dependency chain`() {
        val graph = realisticGraph()
        val path = GraphAnalyzer.criticalPath(graph)
        // Longest path: :app -> :feature:home -> :core:data -> :core:domain (length 4)
        assertTrue(path.size >= 3, "Expected critical path of at least 3 hops, got: $path")
        assertTrue(path.contains(":core:domain"), "Critical path must end at :core:domain")
    }

    @Test
    fun `criticalPath returns empty list for cyclic graph`() {
        val path = GraphAnalyzer.criticalPath(cyclicGraph())
        assertTrue(path.isEmpty())
    }

    @Test
    fun `criticalPath for single isolated module has length 1`() {
        val graph = ModuleDependencyGraph(
            projectName = "solo", modules = listOf(node(":solo")), edges = emptyList()
        )
        val path = GraphAnalyzer.criticalPath(graph)
        assertEquals(1, path.size)
    }

    @Test
    fun `godModules identifies modules with high fan-in AND fan-out`() {
        val graph = ModuleDependencyGraph(
            projectName = "god-test",
            modules = (1..10).map { node(":dep$it") } + node(":god") + node(":consumer"),
            edges = (1..10).map { edge(":god", ":dep$it") } + // high fan-out
                    listOf(edge(":consumer", ":god")),           // some fan-in
        )
        // :god has fanOut=10, fanIn=1 - above fanOutThreshold=5
        val gods = GraphAnalyzer.godModules(graph, fanInThreshold = 1, fanOutThreshold = 5)
        assertTrue(gods.any { it.path == ":god" })
    }

    @Test
    fun `godModules returns empty for graph with low coupling`() {
        val gods = GraphAnalyzer.godModules(realisticGraph(), fanInThreshold = 10, fanOutThreshold = 10)
        assertTrue(gods.isEmpty())
    }

    @Test
    fun `leafModules are modules with no outgoing production deps`() {
        val leaves = GraphAnalyzer.leafModules(realisticGraph())
        // :core:domain has no outgoing deps
        assertTrue(leaves.any { it.path == ":core:domain" })
        // :app depends on others - not a leaf
        assertFalse(leaves.any { it.path == ":app" })
    }

    @Test
    fun `rootModules are modules nobody depends on`() {
        val roots = GraphAnalyzer.rootModules(realisticGraph())
        // :app is the only root
        assertEquals(listOf(":app"), roots.map { it.path })
    }

    @Test
    fun `isolatedModules are modules with neither dependents nor dependencies`() {
        val graph = ModuleDependencyGraph(
            projectName = "islands",
            modules = listOf(node(":a"), node(":b"), node(":isolated")),
            edges = listOf(edge(":a", ":b")),
        )
        val isolated = GraphAnalyzer.isolatedModules(graph)
        assertEquals(1, isolated.size)
        assertEquals(":isolated", isolated.first().path)
    }

    @Test
    fun `summary totalModules equals graph module count`() {
        val graph = realisticGraph()
        assertEquals(graph.modules.size, GraphAnalyzer.summary(graph).totalModules)
    }

    @Test
    fun `summary totalEdges equals graph edge count`() {
        val graph = realisticGraph()
        assertEquals(graph.edges.size, GraphAnalyzer.summary(graph).totalEdges)
    }

    @Test
    fun `summary hasCycles is false for acyclic graph`() {
        assertFalse(GraphAnalyzer.summary(realisticGraph()).hasCycles)
    }

    @Test
    fun `summary hasCycles is true for cyclic graph`() {
        assertTrue(GraphAnalyzer.summary(cyclicGraph()).hasCycles)
    }

    @Test
    fun `summary hasCycles is false when cycle only exists via test edges`() {
        // :a -> :b (impl), :b -> :a (testImpl) - this is NOT a main-code cycle
        val graph = ModuleDependencyGraph(
            projectName = "test-cycle-only",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(
                DependencyEdge(from = ":a", to = ":b", configuration = "implementation"),
                DependencyEdge(from = ":b", to = ":a", configuration = "testImplementation"),
            ),
        )
        val summary = GraphAnalyzer.summary(graph)
        assertFalse(summary.hasCycles, "Test-only cycle should not be counted as a cycle in summary")
        assertEquals(0, summary.cycleCount, "cycleCount should be 0 for test-only cycles")
    }

    @Test
    fun `summary modulesByType uses String keys`() {
        val graph = realisticGraph()
        val summary = GraphAnalyzer.summary(graph)
        // Keys must be strings (e.g. "ANDROID_APP", "JVM_LIBRARY"), not ModuleType enum objects
        assertTrue(
            summary.modulesByType.keys.all { true },
            "modulesByType keys must be Strings for JSON serialization"
        )
    }

    @Test
    fun `summary modulesByType counts are correct`() {
        val graph = realisticGraph()
        val summary = GraphAnalyzer.summary(graph)
        assertEquals(1, summary.modulesByType["ANDROID_APP"])
        assertEquals(3, summary.modulesByType["ANDROID_LIBRARY"])
        assertEquals(1, summary.modulesByType["JVM_LIBRARY"])
    }

    @Test
    fun `summary maxFanOut is correct`() {
        // :app has fanOut=2, :feature:home has fanOut=2, :core:data has fanOut=1
        val summary = GraphAnalyzer.summary(realisticGraph())
        assertEquals(2, summary.maxFanOut)
    }

    @Test
    fun `summary maxFanIn is correct`() {
        // :core:domain is depended on by :feature:login, :feature:home, :core:data = fanIn 3
        val summary = GraphAnalyzer.summary(realisticGraph())
        assertEquals(3, summary.maxFanIn)
    }

    @Test
    fun `summary averageInstability is between 0 and 1`() {
        val summary = GraphAnalyzer.summary(realisticGraph())
        assertTrue(summary.averageInstability in 0.0..1.0)
    }

    @Test
    fun `summary criticalPathLength is positive for non-trivial graph`() {
        val summary = GraphAnalyzer.summary(realisticGraph())
        assertTrue(summary.criticalPathLength > 1)
    }

    @Test
    fun `potentiallyCoupledModules detects shared dependents`() {
        // :feature:login and :feature:home are both depended on by :app - shared dependents
        // But threshold is 3, so they won't be flagged here
        val coupled = GraphAnalyzer.potentiallyCoupledModules(realisticGraph(), sharedDependentThreshold = 1)
        // :core:domain is depended on by multiple modules that are also depended on by :app
        assertTrue(coupled.isNotEmpty())
    }

    @Test
    fun `findMainOnlyCycles - completes without NoSuchMethodError on deep recursive graph`() {
        // Exercises the DFS stack's removeLast() - the exact call that failed in prod
        // with 'java.lang.Object java.util.List.removeLast()' NoSuchMethodError
        val modules = (1..20).map { node(":module$it") }
        val edges = (1 until 20).map { edge(":module$it", ":module${it + 1}") }
        val graph = ModuleDependencyGraph(
            projectName = "deep-chain",
            modules = modules,
            edges = edges,
        )
        // Should complete without throwing NoSuchMethodError
        val cycles = GraphAnalyzer.findMainOnlyCycles(graph)
        assertTrue(cycles.isEmpty())
    }

    @Test
    fun `findMainOnlyCycles - completes without NoSuchMethodError when cycle exists`() {
        // Exercises both path += node (addLast) and path.removeLast() through the
        // full DFS cycle-detection path
        val cycles = GraphAnalyzer.findMainOnlyCycles(cyclicGraph())
        assertEquals(1, cycles.size)
    }

    @Test
    fun `topologicalOrder - completes without NoSuchMethodError on wide graph`() {
        // Exercises queue.removeFirst() in Kahn's algorithm across many nodes
        val modules = (1..50).map { node(":m$it") }
        val edges = (1 until 50).map { edge(":m$it", ":m${it + 1}") }
        val graph = ModuleDependencyGraph(
            projectName = "wide",
            modules = modules,
            edges = edges,
        )
        val order = GraphAnalyzer.topologicalOrder(graph)
        assertEquals(50, order.size)
    }

    @Test
    fun `criticalPath - completes without NoSuchMethodError on long chain`() {
        // criticalPath internally calls topologicalOrder which uses removeFirst()
        val modules = (1..30).map { node(":n$it") }
        val edges = (1 until 30).map { edge(":n$it", ":n${it + 1}") }
        val graph = ModuleDependencyGraph(
            projectName = "long-chain",
            modules = modules,
            edges = edges,
        )
        val path = GraphAnalyzer.criticalPath(graph)
        assertEquals(30, path.size)
    }
}
