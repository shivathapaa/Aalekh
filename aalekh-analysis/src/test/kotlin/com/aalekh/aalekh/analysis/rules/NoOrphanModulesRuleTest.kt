package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoOrphanModulesRuleTest {

    private fun node(path: String, buildFile: String? = null) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
        buildFilePath = buildFile,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    @Test
    fun `no violation when every module is connected`() {
        val graph = ModuleDependencyGraph(
            projectName = "connected",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b")),
        )
        assertTrue(NoOrphanModulesRule().evaluate(graph).isEmpty())
    }

    @Test
    fun `flags a module with no dependents and no dependencies`() {
        val graph = ModuleDependencyGraph(
            projectName = "orphan",
            modules = listOf(node(":a"), node(":b"), node(":orphan")),
            edges = listOf(edge(":a", ":b")),
        )
        val violations = NoOrphanModulesRule().evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals(":orphan", violations.first().moduleHint)
    }

    @Test
    fun `a module wired in only via a test edge is still an orphan`() {
        // :orphan is reached only through testImplementation, so it contributes nothing to production.
        val graph = ModuleDependencyGraph(
            projectName = "test-only",
            modules = listOf(node(":a"), node(":b"), node(":orphan")),
            edges = listOf(edge(":a", ":b"), edge(":a", ":orphan", "testImplementation")),
        )
        assertTrue(NoOrphanModulesRule().evaluate(graph).any { it.moduleHint == ":orphan" })
    }

    @Test
    fun `violation severity is WARNING and ruleId is stable`() {
        val graph = ModuleDependencyGraph("orphan", listOf(node(":orphan")), emptyList())
        val violation = NoOrphanModulesRule().evaluate(graph).first()
        assertEquals(Severity.WARNING, violation.severity)
        assertEquals("no-orphan-modules", violation.ruleId)
    }

    @Test
    fun `message includes the build file when known`() {
        val graph = ModuleDependencyGraph(
            projectName = "orphan",
            modules = listOf(node(":orphan", "orphan/build.gradle.kts")),
            edges = emptyList(),
        )
        assertTrue(NoOrphanModulesRule().evaluate(graph).first().message.contains("orphan/build.gradle.kts"))
    }

    @Test
    fun `plainLanguageExplanation is set`() {
        val graph = ModuleDependencyGraph("orphan", listOf(node(":orphan")), emptyList())
        assertTrue(NoOrphanModulesRule().evaluate(graph).first().plainLanguageExplanation?.isNotBlank() == true)
    }
}
