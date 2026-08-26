package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffectedGraphAnalyzerTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
        buildFilePath = "${path.trimStart(':').replace(':', '/')}/build.gradle.kts",
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    // :a -> :b -> :c ; :d depends on :c only through a test configuration.
    private val graph = ModuleDependencyGraph(
        projectName = "affected",
        modules = listOf(node(":a"), node(":b"), node(":c"), node(":d")),
        edges = listOf(
            edge(":a", ":b"),
            edge(":b", ":c"),
            edge(":d", ":c", "testImplementation"),
        ),
    )

    private fun file(module: String) = "${module.trimStart(':').replace(':', '/')}/src/File.kt"

    @Test
    fun `no changed files yields the empty result`() {
        val result = AffectedGraphAnalyzer.analyze(graph, emptyList())
        assertEquals(4, result.totalModules)
        assertTrue(result.changed.isEmpty())
        assertTrue(result.affected.isEmpty())
    }

    @Test
    fun `files outside every module are ignored`() {
        val result = AffectedGraphAnalyzer.analyze(graph, listOf("README.md", "gradle/libs.versions.toml"))
        assertTrue(result.changed.isEmpty())
        assertTrue(result.affected.isEmpty())
    }

    @Test
    fun `a change expands to its production dependents`() {
        val result = AffectedGraphAnalyzer.analyze(graph, listOf(file(":c")))
        assertEquals(listOf(":c"), result.changed)
        // :b depends on :c, :a depends on :b - both must rebuild. :d is only a test dependent.
        assertEquals(listOf(":a", ":b", ":c"), result.affected)
    }

    @Test
    fun `a test-only dependent is not affected`() {
        val result = AffectedGraphAnalyzer.analyze(graph, listOf(file(":c")))
        assertFalse(result.affected.contains(":d"), "a test-only dependent is not part of the production blast radius")
    }

    @Test
    fun `a leaf change affects only itself`() {
        val result = AffectedGraphAnalyzer.analyze(graph, listOf(file(":a")))
        assertEquals(listOf(":a"), result.changed)
        assertEquals(listOf(":a"), result.affected)
    }

    @Test
    fun `multiple changed modules are unioned`() {
        val result = AffectedGraphAnalyzer.analyze(graph, listOf(file(":a"), file(":c")))
        assertEquals(listOf(":a", ":c"), result.changed)
        assertEquals(listOf(":a", ":b", ":c"), result.affected)
    }

    @Test
    fun `affected always includes the changed modules`() {
        val result = AffectedGraphAnalyzer.analyze(graph, listOf(file(":b")))
        assertTrue(result.affected.containsAll(result.changed))
        assertEquals(listOf(":a", ":b"), result.affected)
    }
}
