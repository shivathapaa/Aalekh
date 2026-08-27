package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForbiddenTransitiveDependencyRuleTest {

    private fun node(path: String, type: ModuleType = ModuleType.JVM_LIBRARY) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = type,
        buildFilePath = "${path.trimStart(':').replace(':', '/')}/build.gradle.kts",
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun rule(from: String, to: String, severity: Severity = Severity.ERROR) =
        ForbiddenTransitiveDependencyRule(from, to, reason = "keep it clean", defaultSeverity = severity)

    @Test
    fun `flags an indirect dependency two hops away`() {
        // domain -> util -> android; the direct forbid { } rule would miss the android leak.
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":core:domain"), node(":core:util"), node(":platform:android")),
            edges = listOf(edge(":core:domain", ":core:util"), edge(":core:util", ":platform:android")),
        )
        val violations = rule(":core:domain", ":platform:**").evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals("forbidden-transitive-dependency", violations.single().ruleId)
        assertEquals(":core:domain", violations.single().moduleHint)
        assertTrue(violations.single().message.contains(":platform:android"))
    }

    @Test
    fun `no violation when the target is never reached`() {
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":core:domain"), node(":core:util")),
            edges = listOf(edge(":core:domain", ":core:util")),
        )
        assertTrue(rule(":core:domain", ":platform:**").evaluate(graph).isEmpty())
    }

    @Test
    fun `test-only edges do not create reachability`() {
        // domain reaches android only through a testImplementation edge - production is clean.
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":core:domain"), node(":platform:android")),
            edges = listOf(edge(":core:domain", ":platform:android", "testImplementation")),
        )
        assertTrue(rule(":core:domain", ":platform:**").evaluate(graph).isEmpty())
    }

    @Test
    fun `carries the configured severity`() {
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b")),
        )
        val violation = rule(":a", ":b", Severity.WARNING).evaluate(graph).single()
        assertEquals(Severity.WARNING, violation.severity)
        assertTrue(violation.message.contains("keep it clean"))
    }

    @Test
    fun `fromConfig reconstructs a forbidReachable rule`() {
        val graph = ModuleDependencyGraph(
            projectName = "reach",
            modules = listOf(node(":core:domain"), node(":core:util"), node(":platform:android")),
            edges = listOf(edge(":core:domain", ":core:util"), edge(":core:util", ":platform:android")),
        )
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
            reachabilityEntries = listOf("forbid|:core:domain|:platform:**|ERROR|keep domain pure"),
        )
        val forbidden = engine.evaluate(graph).violations
            .filter { it.ruleId == "forbidden-transitive-dependency" }
        assertEquals(1, forbidden.size)
        assertTrue(forbidden.single().message.contains("keep domain pure"))
    }
}
