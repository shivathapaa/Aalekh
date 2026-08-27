package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnreachableModuleRuleTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
        buildFilePath = "${path.trimStart(':').replace(':', '/')}/build.gradle.kts",
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun rule(module: String, from: String, severity: Severity = Severity.ERROR) =
        UnreachableModuleRule(module, from, reason = "wire it in", defaultSeverity = severity)

    @Test
    fun `flags a feature not reachable from the app root`() {
        // app depends on featureA; featureB is wired to nobody.
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":app"), node(":feature:a"), node(":feature:b")),
            edges = listOf(edge(":app", ":feature:a")),
        )
        val violations = rule(":feature:*", ":app").evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals("unreachable-module", violations.single().ruleId)
        assertEquals(":feature:b", violations.single().moduleHint)
    }

    @Test
    fun `a transitively reachable feature passes`() {
        // app -> core -> featureB, so featureB is reachable even though app does not depend on it directly.
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":app"), node(":core"), node(":feature:b")),
            edges = listOf(edge(":app", ":core"), edge(":core", ":feature:b")),
        )
        assertTrue(rule(":feature:*", ":app").evaluate(graph).isEmpty())
    }

    @Test
    fun `test-only wiring does not rescue a module`() {
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":app"), node(":feature:b")),
            edges = listOf(edge(":app", ":feature:b", "testImplementation")),
        )
        assertEquals(1, rule(":feature:*", ":app").evaluate(graph).size)
    }

    @Test
    fun `fromConfig reconstructs a mustBeReachableFrom rule at the given severity`() {
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":app"), node(":feature:a"), node(":feature:b")),
            edges = listOf(edge(":app", ":feature:a")),
        )
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
            reachabilityEntries = listOf("require|:app|:feature:*|WARNING|wire every feature in"),
        )
        val unreachable = engine.evaluate(graph).violations.filter { it.ruleId == "unreachable-module" }
        assertEquals(1, unreachable.size)
        assertEquals(Severity.WARNING, unreachable.single().severity)
        assertEquals(":feature:b", unreachable.single().moduleHint)
    }
}
