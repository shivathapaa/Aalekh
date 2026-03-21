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

class LayerDependencyRuleTest {

    private fun node(path: String, buildFile: String? = null) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
        buildFilePath = buildFile,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    /**
     * Standard three-layer project:
     * presentation → data → domain (only direction allowed)
     * presentation → domain (allowed)
     * data → presentation (VIOLATION)
     * presentation → presentation cross-module (same layer, OK)
     */
    private fun threeLayerGraph() = ModuleDependencyGraph(
        projectName = "three-layer",
        modules = listOf(
            node(":app"),
            node(":feature:login:ui", "feature/login/ui/build.gradle.kts"),
            node(":feature:home:ui", "feature/home/ui/build.gradle.kts"),
            node(":feature:login:data", "feature/login/data/build.gradle.kts"),
            node(":core:domain"),
        ),
        edges = listOf(
            edge(":app", ":feature:login:ui"),
            edge(":feature:login:ui", ":core:domain"),
            edge(":feature:login:data", ":core:domain"),
            edge(":feature:login:data", ":feature:login:ui"),  // VIOLATION: data → presentation
        ),
    )

    private fun ruleForThreeLayers() = LayerDependencyRule.fromSerializedLayers(
        listOf(
            "domain|:core:domain||false",
            "data|:feature:*:data|domain|true",
            "presentation|:feature:*:ui,:app|domain,data|true",
        )
    )

    // Basic violation detection

    @Test
    fun `no violations when all deps follow layer rules`() {
        val graph = ModuleDependencyGraph(
            projectName = "clean",
            modules = listOf(node(":feature:login:ui"), node(":core:domain")),
            edges = listOf(edge(":feature:login:ui", ":core:domain")),
        )
        // presentation can depend on domain
        val rule = LayerDependencyRule.fromSerializedLayers(
            listOf(
                "domain|:core:domain||false",
                "presentation|:feature:*:ui|domain|true",
            )
        )
        assertTrue(rule.evaluate(graph).isEmpty())
    }

    @Test
    fun `violation when data depends on presentation`() {
        val violations = ruleForThreeLayers().evaluate(threeLayerGraph())
        val error = violations.firstOrNull { it.severity == Severity.ERROR }
        assertTrue(
            error != null,
            "Expected ERROR violation for data → presentation dependency"
        )
        assertTrue(error.message.contains(":feature:login:data"))
        assertTrue(error.message.contains(":feature:login:ui"))
    }

    @Test
    fun `violation ruleId is stable`() {
        val violations = ruleForThreeLayers().evaluate(threeLayerGraph())
        assertTrue(violations.any { it.ruleId == "layer-dependency" })
    }

    @Test
    fun `violation moduleHint is the from module`() {
        val violations = ruleForThreeLayers().evaluate(threeLayerGraph())
        val error = violations.first { it.severity == Severity.ERROR }
        assertEquals(":feature:login:data", error.moduleHint)
    }

    @Test
    fun `violation message includes build file path when available`() {
        val violations = ruleForThreeLayers().evaluate(threeLayerGraph())
        val error = violations.first { it.severity == Severity.ERROR }
        assertTrue(
            error.message.contains("feature/login/data/build.gradle.kts"),
            "Message should reference the build file: ${error.message}"
        )
    }

    @Test
    fun `violation message includes exact dependency to remove`() {
        val violations = ruleForThreeLayers().evaluate(threeLayerGraph())
        val error = violations.first { it.severity == Severity.ERROR }
        assertTrue(
            error.message.contains(":feature:login:ui"),
            "Message should name the dependency to remove"
        )
    }

    // Test dependencies excluded

    @Test
    fun `test dependencies do not trigger layer violations`() {
        val graph = ModuleDependencyGraph(
            projectName = "test-dep",
            modules = listOf(
                node(":feature:login:data"),
                node(":feature:login:ui"),
            ),
            edges = listOf(
                edge(":feature:login:data", ":feature:login:ui", "testImplementation"),
            ),
        )
        val rule = LayerDependencyRule.fromSerializedLayers(
            listOf(
                "data|:feature:*:data|domain|true",
                "presentation|:feature:*:ui|domain,data|true",
            )
        )
        assertTrue(rule.evaluate(graph).isEmpty(), "Test deps must not trigger layer violations")
    }

    // Glob patterns

    @Test
    fun `glob pattern matches multiple feature modules`() {
        val graph = ModuleDependencyGraph(
            projectName = "multi-feature",
            modules = listOf(
                node(":feature:login:data"),
                node(":feature:home:data"),
                node(":feature:login:ui"),
            ),
            edges = listOf(
                edge(":feature:login:data", ":feature:login:ui"),  // VIOLATION
                edge(":feature:home:data", ":feature:login:ui"),   // VIOLATION
            ),
        )
        val rule = LayerDependencyRule.fromSerializedLayers(
            listOf(
                "data|:feature:*:data|domain|true",
                "presentation|:feature:*:ui|domain,data|true",
            )
        )
        val errors = rule.evaluate(graph).filter { it.severity == Severity.ERROR }
        assertEquals(2, errors.size, "Both data modules should produce violations")
    }

    @Test
    fun `unrestricted layer produces no violations`() {
        val graph = ModuleDependencyGraph(
            projectName = "unrestricted",
            modules = listOf(node(":core:domain"), node(":core:data")),
            edges = listOf(edge(":core:domain", ":core:data")),
        )
        // domain layer has no restriction (hasRestriction = false)
        val rule = LayerDependencyRule.fromSerializedLayers(
            listOf(
                "domain|:core:domain||false",
                "data|:core:data||false",
            )
        )
        assertTrue(rule.evaluate(graph).isEmpty())
    }

    // No layers configured

    @Test
    fun `empty layer list produces no violations`() {
        val rule = LayerDependencyRule.fromSerializedLayers(emptyList())
        val graph = ModuleDependencyGraph(
            projectName = "empty",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b")),
        )
        assertTrue(rule.evaluate(graph).isEmpty())
    }

    // plainLanguageExplanation

    @Test
    fun `violations carry plainLanguageExplanation`() {
        val violations = ruleForThreeLayers().evaluate(threeLayerGraph())
        val error = violations.first { it.severity == Severity.ERROR }
        assertFalse(
            error.plainLanguageExplanation.isNullOrBlank(),
            "Violation must carry a plain-language explanation"
        )
    }
}