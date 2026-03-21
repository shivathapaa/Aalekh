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

class NoFeatureToFeatureDependencyRuleTest {

    private fun node(path: String, buildFile: String? = null) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
        buildFilePath = buildFile,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun rule(pattern: String = ":feature:**", vararg allowed: String) =
        NoFeatureToFeatureDependencyRule(
            featurePattern = pattern,
            allowedPairs = allowed.toList(),
        )

    // Basic detection

    @Test
    fun `detects feature to feature dependency`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(
                node(":feature:login"),
                node(":feature:settings"),
            ),
            edges = listOf(edge(":feature:login", ":feature:settings")),
        )
        val violations = rule().evaluate(graph)
        assertEquals(1, violations.size)
        assertEquals(Severity.ERROR, violations[0].severity)
    }

    @Test
    fun `no violation when feature depends on core`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(
                node(":feature:login"),
                node(":core:domain"),
            ),
            edges = listOf(edge(":feature:login", ":core:domain")),
        )
        assertTrue(rule().evaluate(graph).isEmpty())
    }

    @Test
    fun `no violation when no feature modules present`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":core:a"), node(":core:b")),
            edges = listOf(edge(":core:a", ":core:b")),
        )
        assertTrue(rule().evaluate(graph).isEmpty())
    }

    @Test
    fun `no violation with only one feature module`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":feature:login"), node(":core:domain")),
            edges = listOf(edge(":feature:login", ":core:domain")),
        )
        assertTrue(rule().evaluate(graph).isEmpty())
    }

    // Violation content

    @Test
    fun `violation ruleId is stable`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":feature:a"), node(":feature:b")),
            edges = listOf(edge(":feature:a", ":feature:b")),
        )
        assertEquals("no-feature-to-feature", rule().evaluate(graph)[0].ruleId)
    }

    @Test
    fun `violation moduleHint is the from module`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":feature:login"), node(":feature:settings")),
            edges = listOf(edge(":feature:login", ":feature:settings")),
        )
        assertEquals(":feature:login", rule().evaluate(graph)[0].moduleHint)
    }

    @Test
    fun `violation message includes build file path when available`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(
                node(":feature:login", "feature/login/build.gradle.kts"),
                node(":feature:settings"),
            ),
            edges = listOf(edge(":feature:login", ":feature:settings")),
        )
        val msg = rule().evaluate(graph)[0].message
        assertTrue(msg.contains("feature/login/build.gradle.kts"), "Message: $msg")
    }

    @Test
    fun `violation has plainLanguageExplanation`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":feature:a"), node(":feature:b")),
            edges = listOf(edge(":feature:a", ":feature:b")),
        )
        assertFalse(rule().evaluate(graph)[0].plainLanguageExplanation.isNullOrBlank())
    }

    // Allow-list

    @Test
    fun `allowed pair is not a violation`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":feature:login"), node(":feature:shared")),
            edges = listOf(edge(":feature:login", ":feature:shared")),
        )
        val r = rule(allowed = arrayOf(":feature:login->:feature:shared"))
        assertTrue(r.evaluate(graph).isEmpty())
    }

    @Test
    fun `allowed pair with glob pattern`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(
                node(":feature:login"),
                node(":feature:home"),
                node(":feature:shared"),
            ),
            edges = listOf(
                edge(":feature:login", ":feature:shared"),
                edge(":feature:home", ":feature:shared"),
            ),
        )
        // Allow any feature to depend on :feature:shared
        val r = rule(allowed = arrayOf(":feature:**->:feature:shared"))
        assertTrue(r.evaluate(graph).isEmpty())
    }

    @Test
    fun `non-allowed pair is still a violation`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(
                node(":feature:login"),
                node(":feature:home"),
                node(":feature:shared"),
            ),
            edges = listOf(
                edge(":feature:login", ":feature:home"),    // VIOLATION - not in allow-list
                edge(":feature:login", ":feature:shared"),  // allowed
            ),
        )
        val r = rule(allowed = arrayOf(":feature:**->:feature:shared"))
        val violations = r.evaluate(graph)
        assertEquals(1, violations.size)
        assertTrue(violations[0].message.contains(":feature:home"))
    }

    // Test dependencies excluded

    @Test
    fun `test dependency between features is not a violation`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":feature:login"), node(":feature:settings")),
            edges = listOf(edge(":feature:login", ":feature:settings", "testImplementation")),
        )
        assertTrue(rule().evaluate(graph).isEmpty())
    }

    // Pattern specificity

    @Test
    fun `only modules matching featurePattern are considered features`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(
                node(":screens:login"),
                node(":screens:home"),
            ),
            edges = listOf(edge(":screens:login", ":screens:home")),
        )
        // Rule uses :feature:** - screens modules are not features
        assertTrue(rule(":feature:**").evaluate(graph).isEmpty())
    }
}