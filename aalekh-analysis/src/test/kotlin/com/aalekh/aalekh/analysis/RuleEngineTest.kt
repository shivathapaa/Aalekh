package com.aalekh.aalekh.analysis

import com.aalekh.aalekh.analysis.rules.ArchRule
import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEngineTest {

    private fun node(path: String) =
        ModuleNode(path = path, name = path.substringAfterLast(":"), type = ModuleType.JVM_LIBRARY)

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun acyclicGraph() = ModuleDependencyGraph(
        projectName = "acyclic",
        modules = listOf(node(":a"), node(":b"), node(":c")),
        edges = listOf(edge(":a", ":b"), edge(":b", ":c")),
    )

    private fun cyclicGraph() = ModuleDependencyGraph(
        projectName = "cyclic",
        modules = listOf(node(":a"), node(":b"), node(":c")),
        edges = listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":a")),
    )

    // Test-only cycle: :a -> :b (impl), :b -> :a (testImpl). Should NOT produce an ERROR.
    private fun testOnlyCycleGraph() = ModuleDependencyGraph(
        projectName = "test-cycle",
        modules = listOf(node(":a"), node(":b")),
        edges = listOf(
            edge(":a", ":b", "implementation"),
            edge(":b", ":a", "testImplementation"),
        ),
    )

    // Main cycle :a -> :b -> :a plus test dep :c -> :a. Only the main cycle should be an ERROR.
    private fun mixedCycleGraph() = ModuleDependencyGraph(
        projectName = "mixed-cycle",
        modules = listOf(node(":a"), node(":b"), node(":c")),
        edges = listOf(
            edge(":a", ":b", "implementation"),
            edge(":b", ":a", "implementation"),
            edge(":c", ":a", "testImplementation"),
        ),
    )

    // :core:datastore-test -> :core:datastore via testImpl is NOT a cycle.
    private fun datastoreTestGraph() = ModuleDependencyGraph(
        projectName = "datastore-test",
        modules = listOf(node(":core:datastore"), node(":core:datastore-test")),
        edges = listOf(edge(":core:datastore-test", ":core:datastore", "testImplementation")),
    )

    @Test
    fun `empty engine produces no violations`() {
        val result = RuleEngine.empty().evaluate(cyclicGraph())
        assertTrue(result.violations.isEmpty())
        assertEquals(0, result.rulesEvaluated)
    }

    @Test
    fun `empty engine has no build failure`() {
        assertFalse(RuleEngine.empty().evaluate(cyclicGraph()).hasBuildFailure)
    }

    @Test
    fun `builtin rules pass on acyclic graph`() {
        val result = RuleEngine.withBuiltinRules().evaluate(acyclicGraph())
        assertTrue(result.violations.isEmpty())
        assertFalse(result.hasBuildFailure)
    }

    @Test
    fun `builtin rules detect cycle`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        assertTrue(result.violations.isNotEmpty())
        assertTrue(result.hasBuildFailure)
    }

    @Test
    fun `cycle violation has ERROR severity`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        assertTrue(result.violations.filter { it.severity == Severity.ERROR }.isNotEmpty())
    }

    @Test
    fun `cycle violation message contains module paths`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        val msg = result.violations.first().message
        assertTrue(msg.contains("→"), "Violation message should show dependency chain")
    }

    @Test
    fun `cycle violation ruleId is stable`() {
        assertEquals(
            "no-cyclic-dependencies",
            RuleEngine.withBuiltinRules().evaluate(cyclicGraph()).violations.first().ruleId
        )
    }

    @Test
    fun `cycle violation has moduleHint`() {
        val violations = RuleEngine.withBuiltinRules().evaluate(cyclicGraph()).violations
        val error = violations.first { it.severity == Severity.ERROR }
        assertTrue(
            error.moduleHint != null,
            "Cycle violation must carry a moduleHint for graph navigation"
        )
    }

    @Test
    fun `cycle violation has plainLanguageExplanation`() {
        val violations = RuleEngine.withBuiltinRules().evaluate(cyclicGraph()).violations
        val error = violations.first { it.severity == Severity.ERROR }
        assertFalse(error.plainLanguageExplanation.isNullOrBlank())
    }

    @Test
    fun `errorCount counts only ERROR violations`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        assertEquals(result.violations.count { it.severity == Severity.ERROR }, result.errorCount)
    }

    @Test
    fun `rulesEvaluated tracks the number of rules run`() {
        assertTrue(RuleEngine.withBuiltinRules().evaluate(acyclicGraph()).rulesEvaluated >= 1)
    }

    @Test
    fun `rule engine captures exceptions from faulty rules gracefully`() {
        val faultyEngine = RuleEngine(
            listOf(object : ArchRule {
                override val id = "always-throws"
                override val description = "This rule always throws"
                override val defaultSeverity = Severity.ERROR
                override val plainLanguageExplanation = "Explanation for the faulty rule."
                override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
                    throw RuntimeException("Simulated rule crash")
            })
        )
        val result = faultyEngine.evaluate(acyclicGraph())
        assertEquals(1, result.violations.size)
        assertEquals(Severity.ERROR, result.violations.first().severity)
        assertTrue(result.violations.first().message.contains("Simulated rule crash"))
    }

    @Test
    fun `test-only cycle does NOT produce ERROR`() {
        val result = RuleEngine.withBuiltinRules().evaluate(testOnlyCycleGraph())
        val errors = result.violations.filter { it.severity == Severity.ERROR }
        assertTrue(
            errors.isEmpty(),
            "Test-only cycle should not produce ERROR violations, got: $errors"
        )
        assertFalse(result.hasBuildFailure)
    }

    @Test
    fun `test-only cycle produces INFO violation`() {
        val result = RuleEngine.withBuiltinRules().evaluate(testOnlyCycleGraph())
        val infos = result.violations.filter { it.severity == Severity.INFO }
        assertTrue(infos.isNotEmpty(), "Test-only cycle should produce INFO violation")
        assertEquals("test-cyclic-dependency", infos.first().ruleId)
    }

    @Test
    fun `main code cycle still produces ERROR`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        assertTrue(result.violations.any { it.severity == Severity.ERROR })
        assertTrue(result.hasBuildFailure)
    }

    @Test
    fun `mixed cycle graph - main cycle is ERROR, test dep is not a cycle`() {
        val result = RuleEngine.withBuiltinRules().evaluate(mixedCycleGraph())
        val errors = result.violations.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isNotEmpty())
        assertEquals(1, errors.size, "Should have exactly one main cycle error")
    }

    @Test
    fun `datastore test pattern is NOT a cycle`() {
        val result = RuleEngine.withBuiltinRules().evaluate(datastoreTestGraph())
        assertTrue(result.violations.isEmpty())
        assertFalse(result.hasBuildFailure)
    }

    @Test
    fun `cycle violation message mentions main code`() {
        val error = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
            .violations.first { it.severity == Severity.ERROR }
        assertTrue(error.message.contains("main code"))
    }

    @Test
    fun `test cycle INFO message explains it is test-only`() {
        val info = RuleEngine.withBuiltinRules().evaluate(testOnlyCycleGraph())
            .violations.first { it.severity == Severity.INFO }
        assertTrue(info.message.contains("test"))
    }
}