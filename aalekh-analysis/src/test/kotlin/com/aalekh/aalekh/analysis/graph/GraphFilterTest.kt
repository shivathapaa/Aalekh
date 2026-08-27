package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphFilterTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    // :app -> :feature:a -> :core, :app -> :feature:b -> :test:util
    private val graph = ModuleDependencyGraph(
        projectName = "m",
        modules = listOf(
            node(":app"), node(":feature:a"), node(":feature:b"), node(":core"), node(":test:util"),
        ),
        edges = listOf(
            edge(":app", ":feature:a"),
            edge(":app", ":feature:b"),
            edge(":feature:a", ":core"),
            edge(":feature:b", ":test:util"),
        ),
    )

    private fun paths(g: ModuleDependencyGraph) = g.modules.map { it.path }.toSet()

    @Test
    fun `no filters returns the same graph unchanged`() {
        assertSame(graph, GraphFilter.filter(graph, emptyList(), emptyList(), 1))
    }

    @Test
    fun `exclude drops matching modules and their incident edges`() {
        val filtered = GraphFilter.filter(graph, emptyList(), listOf(":test:**"), 1)
        assertEquals(setOf(":app", ":feature:a", ":feature:b", ":core"), paths(filtered))
        assertTrue(
            filtered.edges.none { it.from == ":test:util" || it.to == ":test:util" },
            "edges touching an excluded module must be dropped",
        )
    }

    @Test
    fun `focus at depth 0 keeps only the focused modules`() {
        val filtered = GraphFilter.filter(graph, listOf(":feature:a"), emptyList(), 0)
        assertEquals(setOf(":feature:a"), paths(filtered))
        assertTrue(filtered.edges.isEmpty(), "no edge has both endpoints inside a single-node focus")
    }

    @Test
    fun `focus at depth 1 adds direct neighbours in both directions`() {
        val filtered = GraphFilter.filter(graph, listOf(":feature:a"), emptyList(), 1)
        assertEquals(setOf(":feature:a", ":app", ":core"), paths(filtered))
        assertTrue(filtered.edges.any { it.from == ":app" && it.to == ":feature:a" })
        assertTrue(filtered.edges.any { it.from == ":feature:a" && it.to == ":core" })
        assertTrue(
            filtered.edges.none { it.to == ":feature:b" },
            "a module two hops away must not appear at depth 1",
        )
    }

    @Test
    fun `focus at depth 2 reaches neighbours of neighbours`() {
        val filtered = GraphFilter.filter(graph, listOf(":feature:a"), emptyList(), 2)
        assertEquals(setOf(":feature:a", ":app", ":core", ":feature:b"), paths(filtered))
    }

    @Test
    fun `focus and exclude compose - exclude wins`() {
        val filtered = GraphFilter.filter(graph, listOf(":app"), listOf(":core"), 5)
        assertTrue(":core" !in paths(filtered), "an excluded module is dropped even if the focus reaches it")
        assertTrue(":feature:a" in paths(filtered))
    }

    @Test
    fun `a focus that matches nothing yields an empty graph`() {
        val filtered = GraphFilter.filter(graph, listOf(":does:not:exist"), emptyList(), 3)
        assertTrue(filtered.modules.isEmpty())
        assertTrue(filtered.edges.isEmpty())
    }
}
