package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceSetDependencyRuleTest {

    private fun node(path: String, type: ModuleType) = ModuleNode(path, path.substringAfterLast(":"), type)

    // :shared has an iosMain edge to an Android module and an androidMain edge to a JVM module.
    private val graph = ModuleDependencyGraph(
        projectName = "m",
        modules = listOf(
            node(":shared", ModuleType.KMP),
            node(":androidonly", ModuleType.ANDROID_LIBRARY),
            node(":desktoponly", ModuleType.JVM_LIBRARY),
        ),
        edges = listOf(
            DependencyEdge(":shared", ":androidonly", "iosMainImplementation", sourceSet = "iosMain"),
            DependencyEdge(":shared", ":desktoponly", "androidMainImplementation", sourceSet = "androidMain"),
        ),
    )

    @Test
    fun `flags an edge from the named source set to a module of the forbidden type`() {
        val rule = SourceSetDependencyRule(
            sourceSet = "iosMain",
            to = ModuleMatcher.Type(ModuleType.ANDROID_LIBRARY),
            reason = "iOS code must not pull in Android",
            defaultSeverity = Severity.ERROR,
        )
        val violations = rule.evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals("source-set-dependency", violations.single().ruleId)
        assertEquals(":shared", violations.single().moduleHint)
        assertTrue(violations.single().message.contains("iosMain"))
    }

    @Test
    fun `flags by path glob and ignores other source sets`() {
        // `:*` is a single-segment wildcard; only the iosMain-owned edge is in scope, so despite
        // matching every top-level path it flags just the :androidonly target.
        val rule = SourceSetDependencyRule(
            sourceSet = "iosMain",
            to = ModuleMatcher.Path(":*"),
            reason = "",
            defaultSeverity = Severity.ERROR,
        )
        val violations = rule.evaluate(graph)
        assertEquals(listOf(":shared → :androidonly"), violations.map { it.source })
    }

    @Test
    fun `an edge owned by a different source set does not match`() {
        val rule = SourceSetDependencyRule(
            sourceSet = "androidMain",
            to = ModuleMatcher.Type(ModuleType.ANDROID_LIBRARY),
            reason = "",
            defaultSeverity = Severity.ERROR,
        )
        // The only androidMain edge targets a JVM module, not an Android one.
        assertTrue(rule.evaluate(graph).isEmpty())
    }

    @Test
    fun `test and self-loop edges are ignored`() {
        val g = graph.copy(
            edges = listOf(
                DependencyEdge(":shared", ":androidonly", "iosMainTestImplementation", sourceSet = "iosMain"),
                DependencyEdge(":shared", ":shared", "iosMainImplementation", sourceSet = "iosMain"),
            ),
        )
        val rule = SourceSetDependencyRule(
            sourceSet = "iosMain",
            to = ModuleMatcher.Type(ModuleType.ANDROID_LIBRARY),
            reason = "",
            defaultSeverity = Severity.ERROR,
        )
        assertTrue(rule.evaluate(g).isEmpty())
    }

    @Test
    fun `fromConfig reconstructs a source-set rule from a serialized entry`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
            sourceSetEntries = listOf("iosMain|type|ANDROID_LIBRARY|ERROR|no android in ios"),
        )
        val violations = engine.evaluate(graph).violations.filter { it.ruleId == "source-set-dependency" }
        assertEquals(1, violations.size)
        assertTrue(violations.single().message.contains("no android in ios"))
    }

    @Test
    fun `fromConfig ignores a malformed source-set entry`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
            sourceSetEntries = listOf("only-two|fields"),
        )
        assertTrue(engine.evaluate(graph).violations.none { it.ruleId == "source-set-dependency" })
    }
}
