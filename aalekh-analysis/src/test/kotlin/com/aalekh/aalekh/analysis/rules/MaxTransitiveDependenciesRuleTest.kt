package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaxTransitiveDependenciesRuleTest {

    private fun node(path: String, buildFile: String? = null) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
        buildFilePath = buildFile,
    )

    private fun edge(from: String, to: String) =
        DependencyEdge(from = from, to = to, configuration = "implementation")

    private fun chainGraph(length: Int): ModuleDependencyGraph {
        val modules = (0 until length).map { node(":m$it") }
        val edges = modules.zipWithNext { a, b -> edge(a.path, b.path) }
        return ModuleDependencyGraph("chain", modules, edges)
    }

    @Test
    fun `no violation when all modules are under threshold`() {
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 10).evaluate(graph)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `violation when module exceeds threshold`() {
        // :m0 has 4 transitive deps in a 5-module chain
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 3).evaluate(graph)
        assertTrue(violations.any { it.source == ":m0" })
    }

    @Test
    fun `violation severity is WARNING by default`() {
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 0).evaluate(graph)
        assertTrue(violations.isNotEmpty())
        assertTrue(violations.all { it.severity == Severity.WARNING })
    }

    @Test
    fun `violation ruleId is stable`() {
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 0).evaluate(graph)
        assertTrue(violations.all { it.ruleId == "max-transitive-dependencies" })
    }

    @Test
    fun `violation message contains module path and counts`() {
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 0).evaluate(graph)
        val v = violations.first { it.source == ":m0" }
        assertTrue(v.message.contains(":m0"))
        assertTrue(v.message.contains("limit: 0"))
    }

    @Test
    fun `violation message includes build file path when available`() {
        val modules = listOf(
            node(":a", "a/build.gradle.kts"),
            node(":b"),
            node(":c"),
            node(":d"),
        )
        val edges = listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":d"))
        val graph = ModuleDependencyGraph("test", modules, edges)

        val violations = MaxTransitiveDependenciesRule(maxCount = 1).evaluate(graph)
        val v = violations.firstOrNull { it.source == ":a" }
        assertTrue(v != null)
        assertTrue(v.message.contains("a/build.gradle.kts"), "Message: ${v.message}")
    }

    @Test
    fun `moduleHint is the offending module path`() {
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 0).evaluate(graph)
        violations.forEach { v -> assertEquals(v.source, v.moduleHint) }
    }

    @Test
    fun `no violations on empty graph`() {
        val graph = ModuleDependencyGraph("empty", emptyList(), emptyList())
        assertTrue(MaxTransitiveDependenciesRule(maxCount = 0).evaluate(graph).isEmpty())
    }

    @Test
    fun `threshold of exactly the count produces no violation`() {
        // :m0 has exactly 4 transitive deps in a 5-module chain
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 4).evaluate(graph)
        assertFalse(violations.any { it.source == ":m0" })
    }

    @Test
    fun `plainLanguageExplanation is set`() {
        val graph = chainGraph(5)
        val violations = MaxTransitiveDependenciesRule(maxCount = 0).evaluate(graph)
        assertTrue(violations.first().plainLanguageExplanation?.isNotBlank() == true)
    }
}