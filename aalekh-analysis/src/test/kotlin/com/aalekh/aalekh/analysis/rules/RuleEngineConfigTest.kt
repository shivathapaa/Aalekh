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

class RuleEngineConfigTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun cyclicGraph() = ModuleDependencyGraph(
        projectName = "cyclic",
        modules = listOf(node(":a"), node(":b")),
        edges = listOf(edge(":a", ":b"), edge(":b", ":a")),
    )

    private fun featureGraph() = ModuleDependencyGraph(
        projectName = "features",
        modules = listOf(
            node(":feature:login"),
            node(":feature:settings"),
            node(":core:domain"),
        ),
        edges = listOf(
            edge(":feature:login", ":feature:settings"),  // would be violation
            edge(":feature:login", ":core:domain"),
        ),
    )

    // Severity overrides

    @Test
    fun `severity override downgrades ERROR to WARNING`() {
        val engine = RuleEngine(
            rules = listOf(NoCyclicDependenciesRule()),
            severityOverrides = mapOf("no-cyclic-dependencies" to Severity.WARNING),
        )
        val result = engine.evaluate(cyclicGraph())
        val errors = result.violations.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "Override to WARNING should remove all ERRORs")
        assertFalse(result.hasBuildFailure)
    }

    @Test
    fun `severity override does not affect INFO violations`() {
        // INFO violations (test-cycle) should never be promoted to ERROR by overrides
        val testCycleGraph = ModuleDependencyGraph(
            projectName = "test-cycle",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(
                edge(":a", ":b"),
                edge(":b", ":a", "testImplementation"),
            ),
        )
        val engine = RuleEngine(
            rules = listOf(NoCyclicDependenciesRule()),
            severityOverrides = mapOf("test-cyclic-dependency" to Severity.ERROR),
        )
        val result = engine.evaluate(testCycleGraph)
        // The INFO test-cycle violation must remain INFO, not be promoted to ERROR
        val testCycleViolations = result.violations.filter { it.ruleId == "test-cyclic-dependency" }
        assertTrue(testCycleViolations.all { it.severity == Severity.INFO })
    }

    @Test
    fun `override for unknown rule id is silently ignored`() {
        val engine = RuleEngine(
            rules = listOf(NoCyclicDependenciesRule()),
            severityOverrides = mapOf("nonexistent-rule" to Severity.WARNING),
        )
        val result = engine.evaluate(cyclicGraph())
        assertTrue(result.violations.any { it.severity == Severity.ERROR })
    }

    // Suppressions

    @Test
    fun `suppression by module pattern removes matching violations`() {
        val engine = RuleEngine(
            rules = listOf(NoCyclicDependenciesRule()),
            suppressions = mapOf("no-cyclic-dependencies" to listOf(":a")),
        )
        val result = engine.evaluate(cyclicGraph())
        val cycleViolations = result.violations.filter { it.ruleId == "no-cyclic-dependencies" }
        assertTrue(cycleViolations.isEmpty(), "Violation from :a should be suppressed")
    }

    @Test
    fun `suppression with glob pattern`() {
        val engine = RuleEngine(
            rules = listOf(NoCyclicDependenciesRule()),
            suppressions = mapOf("no-cyclic-dependencies" to listOf(":legacy:**")),
        )
        val legacyCycleGraph = ModuleDependencyGraph(
            projectName = "legacy",
            modules = listOf(node(":legacy:a"), node(":legacy:b")),
            edges = listOf(edge(":legacy:a", ":legacy:b"), edge(":legacy:b", ":legacy:a")),
        )
        val result = engine.evaluate(legacyCycleGraph)
        val cycleViolations = result.violations.filter { it.ruleId == "no-cyclic-dependencies" }
        assertTrue(cycleViolations.isEmpty())
    }

    @Test
    fun `suppression for one rule does not affect other rules`() {
        val engine = RuleEngine(
            rules = listOf(
                NoCyclicDependenciesRule(),
                NoFeatureToFeatureDependencyRule(":feature:**", emptyList()),
            ),
            suppressions = mapOf("no-cyclic-dependencies" to listOf(":**")),
        )
        val result = engine.evaluate(featureGraph())
        val featureViolations = result.violations.filter { it.ruleId == "no-feature-to-feature" }
        assertTrue(
            featureViolations.isNotEmpty(),
            "Feature rule must still fire when only cycle rule is suppressed"
        )
    }

    // fromConfig factory

    @Test
    fun `fromConfig with no config produces builtin rules only`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        val result = engine.evaluate(cyclicGraph())
        // NoCyclicDependenciesRule must be active
        assertTrue(result.violations.any { it.ruleId == "no-cyclic-dependencies" })
    }

    @Test
    fun `fromConfig wires severity override from serialized entry`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf("no-cyclic-dependencies:severity:WARNING"),
        )
        val result = engine.evaluate(cyclicGraph())
        assertFalse(result.hasBuildFailure, "Downgraded to WARNING, should not fail build")
        assertTrue(result.violations.any { it.severity == Severity.WARNING })
    }

    @Test
    fun `fromConfig wires suppression from serialized entry`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf("no-cyclic-dependencies:suppress::a"),
        )
        val result = engine.evaluate(cyclicGraph())
        val cycleViolations = result.violations.filter { it.ruleId == "no-cyclic-dependencies" }
        assertTrue(cycleViolations.isEmpty())
    }

    @Test
    fun `fromConfig activates feature isolation rule when pattern provided`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = ":feature:**",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        val result = engine.evaluate(featureGraph())
        assertTrue(result.violations.any { it.ruleId == "no-feature-to-feature" })
    }

    @Test
    fun `fromConfig feature rule is inactive when pattern is blank`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        val result = engine.evaluate(featureGraph())
        assertFalse(result.violations.any { it.ruleId == "no-feature-to-feature" })
    }

    @Test
    fun `fromConfig activates layer rule when entries provided`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = listOf(
                "domain|:core:domain||false",
                "infra|:core:data||false",           // :core:data is in the infra layer
                "feature|:feature:**|domain|true",   // feature may only depend on domain
            ),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        val graph = ModuleDependencyGraph(
            projectName = "layer-test",
            modules = listOf(node(":feature:login"), node(":core:domain"), node(":core:data")),
            edges = listOf(
                edge(":feature:login", ":core:domain"),  // OK - domain is allowed
                edge(":feature:login", ":core:data"),    // VIOLATION - infra is not in allowed list
            ),
        )
        assertTrue(engine.evaluate(graph).violations.any { it.ruleId == "layer-dependency" })
    }

    @Test
    fun `fromConfig activates max-graph-height rule from a threshold entry`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf("max-graph-height:threshold:2"),
        )
        // :a -> :b -> :c is a height-3 chain, over the limit of 2.
        val chain = ModuleDependencyGraph(
            projectName = "chain",
            modules = listOf(node(":a"), node(":b"), node(":c")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":c")),
        )
        assertTrue(engine.evaluate(chain).violations.any { it.ruleId == "max-graph-height" })
    }

    @Test
    fun `fromConfig max-graph-height inactive when no threshold entry`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        val chain = ModuleDependencyGraph(
            projectName = "chain",
            modules = listOf(node(":a"), node(":b"), node(":c")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":c")),
        )
        assertFalse(engine.evaluate(chain).violations.any { it.ruleId == "max-graph-height" })
    }

    @Test
    fun `fromConfig activates no-orphan-modules rule from an option entry`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf("no-orphan-modules:option:enabled"),
        )
        val graph = ModuleDependencyGraph(
            projectName = "orphan",
            modules = listOf(node(":a"), node(":b"), node(":orphan")),
            edges = listOf(edge(":a", ":b")),
        )
        assertTrue(engine.evaluate(graph).violations.any { it.ruleId == "no-orphan-modules" })
    }

    @Test
    fun `fromConfig no-orphan-modules inactive when not enabled`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        val graph = ModuleDependencyGraph(
            projectName = "orphan",
            modules = listOf(node(":a"), node(":b"), node(":orphan")),
            edges = listOf(edge(":a", ":b")),
        )
        assertFalse(engine.evaluate(graph).violations.any { it.ruleId == "no-orphan-modules" })
    }

    // rulesEvaluated count

    @Test
    fun `rulesEvaluated reflects the number of rules configured`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = listOf("domain|:core:*||false"),
            featurePattern = ":feature:**",
            featureAllowedPairs = emptyList(),
            ruleEntries = emptyList(),
        )
        // NoCyclicDependenciesRule + LayerDependencyRule + NoFeatureToFeatureDependencyRule = 3
        val result = engine.evaluate(featureGraph())
        assertEquals(3, result.rulesEvaluated)
    }

    // Custom rule registration

    @Test
    fun `fromConfig loads custom rule by fully qualified class name`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf(
                "custom:class:com.aalekh.aalekh.analysis.rules.AlwaysFiringCustomRuleFixture"
            ),
        )
        val result = engine.evaluate(featureGraph())
        assertTrue(
            result.violations.any { it.ruleId == "custom-fixture" },
            "Custom rule should fire and emit its violation"
        )
    }

    @Test
    fun `fromConfig surfaces missing custom rule class as ERROR violation`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf("custom:class:com.example.NoSuchRule"),
        )
        val result = engine.evaluate(featureGraph())
        val failures = result.violations.filter { it.ruleId == "aalekh-custom-rule" }
        assertEquals(1, failures.size, "Missing custom rule must surface as one ERROR violation")
        assertEquals(Severity.ERROR, failures.first().severity)
        assertTrue(failures.first().message.contains("com.example.NoSuchRule"))
    }

    @Test
    fun `fromConfig rejects custom rule class that does not implement ArchRule`() {
        val engine = RuleEngine.fromConfig(
            layerEntries = emptyList(),
            featurePattern = "",
            featureAllowedPairs = emptyList(),
            ruleEntries = listOf(
                "custom:class:com.aalekh.aalekh.analysis.rules.NotAnArchRuleFixture"
            ),
        )
        val result = engine.evaluate(featureGraph())
        val failures = result.violations.filter { it.ruleId == "aalekh-custom-rule" }
        assertEquals(1, failures.size)
        assertTrue(
            failures.first().message.contains("does not implement") ||
                    failures.first().message.contains("ArchRule"),
        )
    }
}

/** Test fixture: always emits one ERROR violation when evaluated. */
class AlwaysFiringCustomRuleFixture : ArchRule {
    override val id: String = "custom-fixture"
    override val description: String = "Always-firing test fixture."
    override val defaultSeverity: Severity = Severity.ERROR
    override val plainLanguageExplanation: String = "Fixture for custom-rule registration test."

    override fun evaluate(graph: com.aalekh.aalekh.model.ModuleDependencyGraph) = listOf(
        com.aalekh.aalekh.model.Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "fixture-fired",
            source = "fixture",
            moduleHint = "fixture",
            plainLanguageExplanation = plainLanguageExplanation,
        )
    )
}

/** Test fixture: does NOT implement [ArchRule] - used to verify type-cast guard. */
class NotAnArchRuleFixture {
    val marker: String = "not-an-arch-rule"
}
