package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.CycleBreakSuggestion
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CycleAdvisorTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
        buildFilePath = "${path.trimStart(':').replace(':', '/')}/build.gradle.kts",
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun graphOf(paths: List<String>, edges: List<DependencyEdge>) =
        ModuleDependencyGraph("advice", paths.map { node(it) }, edges)

    /** Rebuilds the graph with the suggested edges removed and asserts it is now acyclic. */
    private fun assertBreaksAllCycles(graph: ModuleDependencyGraph, suggestions: List<CycleBreakSuggestion>) {
        val cut = suggestions.map { it.from to it.to }.toSet()
        val remaining = graph.edges.filterNot { (it.from to it.to) in cut }
        val reduced = ModuleDependencyGraph(graph.projectName, graph.modules, remaining)
        assertFalse(reduced.hasCycle(), "removing the suggested edges must leave an acyclic graph")
    }

    @Test
    fun `an acyclic graph yields no suggestions`() {
        val graph = graphOf(listOf(":a", ":b", ":c"), listOf(edge(":a", ":b"), edge(":b", ":c")))
        assertTrue(CycleAdvisor.suggestBreaks(graph).isEmpty())
    }

    @Test
    fun `a two-module cycle yields exactly one cut`() {
        val graph = graphOf(listOf(":a", ":b"), listOf(edge(":a", ":b"), edge(":b", ":a")))
        val suggestions = CycleAdvisor.suggestBreaks(graph)
        assertEquals(1, suggestions.size)
        val only = suggestions.single()
        assertEquals(2, only.cycleSize)
        assertTrue((only.from to only.to) in setOf(":a" to ":b", ":b" to ":a"))
        assertEquals("implementation", only.configuration)
        assertEquals("${only.from.trimStart(':')}/build.gradle.kts", only.buildFilePath)
        assertBreaksAllCycles(graph, suggestions)
    }

    @Test
    fun `a three-module cycle is broken by removing one edge`() {
        val graph = graphOf(
            listOf(":a", ":b", ":c"),
            listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":a")),
        )
        val suggestions = CycleAdvisor.suggestBreaks(graph)
        assertEquals(1, suggestions.size)
        assertEquals(3, suggestions.single().cycleSize)
        assertBreaksAllCycles(graph, suggestions)
    }

    @Test
    fun `a component with two interlocking cycles is fully broken`() {
        // a -> b -> c -> a  and  b -> a (a<->b chord), one SCC of size 3 with two cycles.
        val graph = graphOf(
            listOf(":a", ":b", ":c"),
            listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":a"), edge(":b", ":a")),
        )
        val suggestions = CycleAdvisor.suggestBreaks(graph)
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.cycleSize == 3 })
        assertBreaksAllCycles(graph, suggestions)
    }

    @Test
    fun `larger cycles are suggested before smaller ones`() {
        // Two disjoint cycles: a size-3 SCC {x,y,z} and a size-2 SCC {p,q}.
        val graph = graphOf(
            listOf(":x", ":y", ":z", ":p", ":q"),
            listOf(
                edge(":x", ":y"), edge(":y", ":z"), edge(":z", ":x"),
                edge(":p", ":q"), edge(":q", ":p"),
            ),
        )
        val suggestions = CycleAdvisor.suggestBreaks(graph)
        assertEquals(3, suggestions.first().cycleSize, "the larger cycle's advice must come first")
        assertBreaksAllCycles(graph, suggestions)
    }

    @Test
    fun `test-only cycles produce no advice`() {
        val graph = graphOf(
            listOf(":a", ":b"),
            listOf(edge(":a", ":b"), edge(":b", ":a", "testImplementation")),
        )
        assertTrue(
            CycleAdvisor.suggestBreaks(graph).isEmpty(),
            "a cycle closed only by a test edge is not a production cycle",
        )
    }

    @Test
    fun `advice is deterministic across runs`() {
        val graph = graphOf(
            listOf(":a", ":b", ":c"),
            listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":a"), edge(":b", ":a")),
        )
        assertEquals(CycleAdvisor.suggestBreaks(graph), CycleAdvisor.suggestBreaks(graph))
    }
}
