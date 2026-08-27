package com.aalekh.aalekh.report.dot

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertTrue

class DotGraphGeneratorTest {

    private fun node(path: String) = ModuleNode(path, path.substringAfterLast(":"), ModuleType.JVM_LIBRARY)

    private fun graph(vararg edges: DependencyEdge) = ModuleDependencyGraph(
        projectName = "dot",
        modules = listOf(node(":app"), node(":core")),
        edges = edges.toList(),
    )

    @Test
    fun `renders a digraph with quoted nodes and a production edge`() {
        val dot = DotGraphGenerator.generate(graph(DependencyEdge(":app", ":core", "implementation")))
        assertTrue(dot.startsWith("//"))
        assertTrue(dot.contains("digraph Aalekh {"))
        assertTrue(dot.contains("\":app\" [fillcolor="))
        assertTrue(dot.contains("\":app\" -> \":core\";"))
        assertTrue(dot.trimEnd().endsWith("}"))
    }

    @Test
    fun `test-only edges render dashed`() {
        val dot = DotGraphGenerator.generate(graph(DependencyEdge(":app", ":core", "testImplementation")))
        assertTrue(dot.contains("\":app\" -> \":core\" [style=dashed];"))
    }

    @Test
    fun `output is deterministic for the same graph`() {
        val g = graph(DependencyEdge(":app", ":core", "implementation"))
        assertTrue(DotGraphGenerator.generate(g) == DotGraphGenerator.generate(g))
    }
}
