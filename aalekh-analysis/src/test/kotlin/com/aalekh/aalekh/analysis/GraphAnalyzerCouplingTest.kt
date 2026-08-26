package com.aalekh.aalekh.analysis

import com.aalekh.aalekh.analysis.graph.CouplingAnalyzer
import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the Lakos system-coupling metrics ([CouplingAnalyzer.systemCoupling]),
 * the strongly-connected-component partition, and graph height.
 */
class GraphAnalyzerCouplingTest {

    private fun node(path: String) =
        ModuleNode(path = path, name = path.substringAfterLast(":"), type = ModuleType.JVM_LIBRARY)

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun chain(vararg paths: String) = ModuleDependencyGraph(
        projectName = "chain",
        modules = paths.map { node(it) },
        edges = paths.toList().zipWithNext { a, b -> edge(a, b) },
    )

    // CCD / ACD / NCCD

    @Test
    fun `ccd sums each module dependency-set size on a chain`() {
        // a->b->c : DependsOn = {a,b,c}=3, {b,c}=2, {c}=1 -> CCD 6
        val coupling = CouplingAnalyzer.systemCoupling(chain(":a", ":b", ":c"))
        assertEquals(6L, coupling.ccd)
        assertEquals(2.0, coupling.acd, 1e-9)
    }

    @Test
    fun `nccd compares ccd against a balanced binary tree`() {
        // balanced-tree CCD for 3 nodes = 1 + 2 + 2 = 5, actual CCD = 6 -> 1.2
        val coupling = CouplingAnalyzer.systemCoupling(chain(":a", ":b", ":c"))
        assertEquals(1.2, coupling.nccd, 1e-9)
    }

    @Test
    fun `single isolated module has ccd 1 and nccd 1`() {
        val graph = ModuleDependencyGraph("solo", listOf(node(":solo")), emptyList())
        val coupling = CouplingAnalyzer.systemCoupling(graph)
        assertEquals(1L, coupling.ccd)
        assertEquals(1.0, coupling.acd, 1e-9)
        assertEquals(1.0, coupling.nccd, 1e-9)
        assertEquals(0.0, coupling.tanglePercent, 1e-9)
    }

    @Test
    fun `empty graph yields all-zero coupling`() {
        val coupling = CouplingAnalyzer.systemCoupling(ModuleDependencyGraph("empty", emptyList(), emptyList()))
        assertEquals(0L, coupling.ccd)
        assertEquals(0.0, coupling.acd, 1e-9)
        assertEquals(0.0, coupling.nccd, 1e-9)
        assertEquals(0.0, coupling.tanglePercent, 1e-9)
        assertEquals(0, coupling.cyclicComponentCount)
    }

    @Test
    fun `test-only edges do not inflate ccd`() {
        // a -> b (main), b -> a (test). Only the main edge counts toward dependency sets.
        val graph = ModuleDependencyGraph(
            projectName = "test-edge",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a", "testImplementation")),
        )
        val coupling = CouplingAnalyzer.systemCoupling(graph)
        // DependsOn(a)={a,b}=2, DependsOn(b)={b}=1 -> CCD 3 (no tangle from the test edge)
        assertEquals(3L, coupling.ccd)
        assertEquals(0.0, coupling.tanglePercent, 1e-9)
        assertEquals(0, coupling.cyclicComponentCount)
    }

    // Tangle

    @Test
    fun `two-cycle is fully tangled`() {
        val graph = ModuleDependencyGraph(
            projectName = "cycle2",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a")),
        )
        val coupling = CouplingAnalyzer.systemCoupling(graph)
        assertEquals(4L, coupling.ccd)  // both DependsOn sets are {a,b}
        assertEquals(100.0, coupling.tanglePercent, 1e-9)
        assertEquals(1, coupling.cyclicComponentCount)
    }

    @Test
    fun `tangle percent reflects the fraction of modules inside a cycle`() {
        // a<->b form a cycle; c is independent. 2 of 3 modules are tangled.
        val graph = ModuleDependencyGraph(
            projectName = "partial-tangle",
            modules = listOf(node(":a"), node(":b"), node(":c")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a"), edge(":c", ":a")),
        )
        val coupling = CouplingAnalyzer.systemCoupling(graph)
        assertEquals(2.0 / 3.0 * 100.0, coupling.tanglePercent, 1e-9)
        assertEquals(1, coupling.cyclicComponentCount)
    }

    // Strongly connected components

    @Test
    fun `scc partitions an acyclic graph into singletons`() {
        val graph = chain(":a", ":b", ":c")
        val components = CouplingAnalyzer.stronglyConnectedComponents(graph)
        assertEquals(3, components.size)
        assertTrue(components.all { it.size == 1 })
    }

    @Test
    fun `scc groups a full cycle into one component`() {
        val graph = ModuleDependencyGraph(
            projectName = "cycle3",
            modules = listOf(node(":a"), node(":b"), node(":c")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":a")),
        )
        val components = CouplingAnalyzer.stronglyConnectedComponents(graph)
        val cyclic = components.first { it.size >= 2 }
        assertEquals(setOf(":a", ":b", ":c"), cyclic.toSet())
    }

    @Test
    fun `scc is safe on a deep chain`() {
        val paths = (1..500).map { ":m$it" }
        val components = CouplingAnalyzer.stronglyConnectedComponents(chain(*paths.toTypedArray()))
        assertEquals(500, components.size)
    }

    // Graph height

    @Test
    fun `graph height equals the longest chain length`() {
        assertEquals(5, GraphAnalyzer.graphHeight(chain(":a", ":b", ":c", ":d", ":e")))
    }

    @Test
    fun `graph height is zero for a cyclic graph`() {
        val graph = ModuleDependencyGraph(
            projectName = "cyclic",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a")),
        )
        assertEquals(0, GraphAnalyzer.graphHeight(graph))
    }

    // Summary wiring

    @Test
    fun `summary carries the coupling metrics`() {
        val summary = GraphAnalyzer.summary(chain(":a", ":b", ":c"))
        assertEquals(6L, summary.ccd)
        assertEquals(2.0, summary.acd, 1e-9)
        assertEquals(1.2, summary.nccd, 1e-9)
        assertEquals(0.0, summary.tanglePercent, 1e-9)
    }
}
