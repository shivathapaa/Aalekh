package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UncoveredModuleRuleTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
        buildFilePath = "${path.trimStart(':').replace(':', '/')}/build.gradle.kts",
    )

    private fun graphOf(vararg paths: String) = ModuleDependencyGraph(
        projectName = "cover",
        modules = paths.map { node(it) },
        edges = emptyList(),
    )

    @Test
    fun `flags a module matched by no layer pattern`() {
        val graph = graphOf(":core:domain", ":core:data", ":stray")
        val violations = UncoveredModuleRule(listOf(":core:**")).evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals("uncovered-module", violations.single().ruleId)
        assertEquals(":stray", violations.single().moduleHint)
        assertEquals(Severity.WARNING, violations.single().severity)
    }

    @Test
    fun `no violation when every module is covered`() {
        val graph = graphOf(":core:domain", ":feature:a")
        val rule = UncoveredModuleRule(listOf(":core:**", ":feature:**"))
        assertTrue(rule.evaluate(graph).isEmpty())
    }

    @Test
    fun `does nothing when no layers are declared`() {
        val graph = graphOf(":core:domain", ":stray")
        assertTrue(UncoveredModuleRule(emptyList()).evaluate(graph).isEmpty())
    }

    @Test
    fun `fromConfig activates only via the requireLayerForAllModules option`() {
        val graph = graphOf(":core:domain", ":stray")
        val layers = listOf(":core:**").let { patterns ->
            listOf("core|${patterns.joinToString(",")}||false")
        }
        val engine = RuleEngine.fromConfig(
            layerEntries = layers,
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf("uncovered-module:option:enabled"),
        )
        val uncovered = engine.evaluate(graph).violations.filter { it.ruleId == "uncovered-module" }
        assertEquals(1, uncovered.size)
        assertEquals(":stray", uncovered.single().moduleHint)
    }

    @Test
    fun `fromConfig leaves the rule off when the option is absent`() {
        val graph = graphOf(":core:domain", ":stray")
        val engine = RuleEngine.fromConfig(
            layerEntries = listOf("core|:core:**||false"),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        assertTrue(engine.evaluate(graph).violations.none { it.ruleId == "uncovered-module" })
    }
}
