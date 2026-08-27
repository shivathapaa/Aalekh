package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricsEngineTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    @Test
    fun `project metrics aggregate module counts`() {
        val graph = ModuleDependencyGraph(
            projectName = "m",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b")),
        )
        val metrics = MetricsEngine.computeProjectMetrics(graph)
        assertEquals(2, metrics.totalModules)
        assertEquals(1, metrics.totalEdges)
        assertFalse(metrics.hasCycles)
    }

    @Test
    fun `a production cycle is reported`() {
        val graph = ModuleDependencyGraph(
            projectName = "m",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a")),
        )
        assertTrue(MetricsEngine.computeProjectMetrics(graph).hasCycles)
    }

    @Test
    fun `a test-only cycle is not counted as a cycle`() {
        // a -> b in production; b -> a only through a test edge. Not a production cycle.
        val graph = ModuleDependencyGraph(
            projectName = "m",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a", "testImplementation")),
        )
        assertFalse(
            MetricsEngine.computeProjectMetrics(graph).hasCycles,
            "structural metrics count production edges only - a test-only cycle must not register",
        )
    }
}
