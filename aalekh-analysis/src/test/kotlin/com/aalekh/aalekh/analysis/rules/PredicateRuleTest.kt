package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredicateRuleTest {

    private fun node(path: String, type: ModuleType = ModuleType.JVM_LIBRARY) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = type,
        buildFilePath = "${path.trimStart(':').replace(':', '/')}/build.gradle.kts",
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun rule(from: ModuleMatcher, to: ModuleMatcher, reason: String = "", severity: Severity = Severity.ERROR) =
        PredicateRule(from, to, reason, severity)

    @Test
    fun `flags a path-to-path forbidden dependency`() {
        val graph = ModuleDependencyGraph(
            projectName = "predicate",
            modules = listOf(node(":feature:a"), node(":feature:b")),
            edges = listOf(edge(":feature:a", ":feature:b")),
        )
        val violations = rule(ModuleMatcher.Path(":feature:**"), ModuleMatcher.Path(":feature:**"))
            .evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals(PredicateRule.RULE_ID, violations.single().ruleId)
        assertEquals(":feature:a", violations.single().moduleHint)
    }

    @Test
    fun `flags a path-to-type forbidden dependency`() {
        val graph = ModuleDependencyGraph(
            projectName = "predicate",
            modules = listOf(node(":core:domain"), node(":ui", ModuleType.ANDROID_LIBRARY)),
            edges = listOf(edge(":core:domain", ":ui")),
        )
        val violations = rule(ModuleMatcher.Path(":core:**"), ModuleMatcher.Type(ModuleType.ANDROID_LIBRARY))
            .evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals(":core:domain", violations.single().moduleHint)
    }

    @Test
    fun `no violation when the target type does not match`() {
        val graph = ModuleDependencyGraph(
            projectName = "predicate",
            modules = listOf(node(":core:domain"), node(":core:data")),
            edges = listOf(edge(":core:domain", ":core:data")),
        )
        val violations = rule(ModuleMatcher.Path(":core:**"), ModuleMatcher.Type(ModuleType.ANDROID_LIBRARY))
            .evaluate(graph)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `test edges are ignored`() {
        val graph = ModuleDependencyGraph(
            projectName = "predicate",
            modules = listOf(node(":feature:a"), node(":feature:b")),
            edges = listOf(edge(":feature:a", ":feature:b", "testImplementation")),
        )
        assertTrue(
            rule(ModuleMatcher.Path(":feature:**"), ModuleMatcher.Path(":feature:**")).evaluate(graph).isEmpty(),
        )
    }

    @Test
    fun `message carries the reason and severity`() {
        val graph = ModuleDependencyGraph(
            projectName = "predicate",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b")),
        )
        val violation = rule(
            ModuleMatcher.Path(":a"),
            ModuleMatcher.Path(":b"),
            reason = "layering",
            severity = Severity.WARNING,
        ).evaluate(graph).single()
        assertEquals(Severity.WARNING, violation.severity)
        assertTrue(violation.message.contains("layering"))
        assertTrue(violation.message.contains(":a"))
        assertTrue(violation.message.contains(":b"))
    }

    @Test
    fun `fromConfig reconstructs a predicate rule from its serialized form`() {
        val graph = ModuleDependencyGraph(
            projectName = "predicate",
            modules = listOf(node(":feature:a"), node(":feature:b")),
            edges = listOf(edge(":feature:a", ":feature:b")),
        )
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
            forbidEntries = listOf("path|:feature:**|path|:feature:**|ERROR|no feature to feature"),
        )
        val result = engine.evaluate(graph)
        val forbidden = result.violations.filter { it.ruleId == PredicateRule.RULE_ID }
        assertEquals(1, forbidden.size)
        assertTrue(forbidden.single().message.contains("no feature to feature"))
    }
}
