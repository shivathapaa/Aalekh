package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KmpCommonMainRuleTest {

    private fun node(path: String, type: ModuleType) = ModuleNode(path, path.substringAfterLast(":"), type)

    private fun commonMainEdge(from: String, to: String) =
        DependencyEdge(from, to, "commonMainImplementation", sourceSet = "commonMain")

    @Test
    fun `flags a commonMain dependency on a JVM-only module`() {
        val g = ModuleDependencyGraph(
            projectName = "kmp",
            modules = listOf(node(":shared", ModuleType.KMP), node(":jvmlib", ModuleType.JVM_LIBRARY)),
            edges = listOf(commonMainEdge(":shared", ":jvmlib")),
        )
        val violations = KmpCommonMainRule().evaluate(g)
        assertEquals(1, violations.size)
        assertEquals("kmp-common-main-platform-dependency", violations.single().ruleId)
        assertEquals(":shared", violations.single().moduleHint)
        assertTrue(violations.single().message.contains(":jvmlib"))
    }

    @Test
    fun `flags a commonMain dependency on an Android module`() {
        val g = ModuleDependencyGraph(
            projectName = "kmp",
            modules = listOf(node(":shared", ModuleType.KMP), node(":androidlib", ModuleType.ANDROID_LIBRARY)),
            edges = listOf(commonMainEdge(":shared", ":androidlib")),
        )
        assertEquals(1, KmpCommonMainRule().evaluate(g).size)
    }

    @Test
    fun `allows a commonMain dependency on another multiplatform module`() {
        val g = ModuleDependencyGraph(
            projectName = "kmp",
            modules = listOf(node(":shared", ModuleType.KMP), node(":core", ModuleType.KMP)),
            edges = listOf(commonMainEdge(":shared", ":core")),
        )
        assertTrue(KmpCommonMainRule().evaluate(g).isEmpty())
    }

    @Test
    fun `ignores a platform source-set dependency on a platform module`() {
        // androidMain -> JVM/Android is fine; only commonMain is constrained.
        val g = ModuleDependencyGraph(
            projectName = "kmp",
            modules = listOf(node(":shared", ModuleType.KMP), node(":androidlib", ModuleType.ANDROID_LIBRARY)),
            edges = listOf(
                DependencyEdge(":shared", ":androidlib", "androidMainImplementation", sourceSet = "androidMain"),
            ),
        )
        assertTrue(KmpCommonMainRule().evaluate(g).isEmpty())
    }

    @Test
    fun `skips unknown-typed targets to avoid false positives`() {
        val g = ModuleDependencyGraph(
            projectName = "kmp",
            modules = listOf(node(":shared", ModuleType.KMP), node(":mystery", ModuleType.UNKNOWN)),
            edges = listOf(commonMainEdge(":shared", ":mystery")),
        )
        assertTrue(KmpCommonMainRule().evaluate(g).isEmpty())
    }

    @Test
    fun `fromConfig activates the rule only via its option`() {
        val g = ModuleDependencyGraph(
            projectName = "kmp",
            modules = listOf(node(":shared", ModuleType.KMP), node(":jvmlib", ModuleType.JVM_LIBRARY)),
            edges = listOf(commonMainEdge(":shared", ":jvmlib")),
        )
        val off = RuleEngine.fromConfig(emptyList(), "", emptyList(), emptyList())
        assertTrue(off.evaluate(g).violations.none { it.ruleId == "kmp-common-main-platform-dependency" })

        val on = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf("kmp-common-main-platform-dependency:option:enabled"),
        )
        assertEquals(1, on.evaluate(g).violations.count { it.ruleId == "kmp-common-main-platform-dependency" })
    }
}
