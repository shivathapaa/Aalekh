package com.aalekh.aalekh.analysis

import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.model.*
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

    /**
     * Test-only cycle: :a -> :b (impl), :b -> :a (testImpl)
     * This should NOT produce an ERROR - only an INFO.
     */
    private fun testOnlyCycleGraph() = ModuleDependencyGraph(
        projectName = "test-cycle",
        modules = listOf(node(":a"), node(":b")),
        edges = listOf(
            edge(":a", ":b", "implementation"),
            edge(":b", ":a", "testImplementation"),
        ),
    )

    /**
     * Mixed: main cycle :a -> :b -> :a, plus test dep :c -> :a (testImpl)
     * Should produce ERROR for :a -> :b cycle, no extra error for test dep
     */
    private fun mixedCycleGraph() = ModuleDependencyGraph(
        projectName = "mixed-cycle",
        modules = listOf(node(":a"), node(":b"), node(":c")),
        edges = listOf(
            edge(":a", ":b", "implementation"),
            edge(":b", ":a", "implementation"),
            edge(":c", ":a", "testImplementation"),
        ),
    )

    /**
     * Datastore pattern: :core:datastore -> (nothing), :core:datastore-test -> :core:datastore (testImpl)
     * This is NOT a cycle.
     */
    private fun datastoreTestGraph() = ModuleDependencyGraph(
        projectName = "datastore-test",
        modules = listOf(node(":core:datastore"), node(":core:datastore-test")),
        edges = listOf(
            edge(":core:datastore-test", ":core:datastore", "testImplementation"),
        ),
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
        assertTrue(result.violations.all { it.severity == Severity.ERROR })
    }

    @Test
    fun `cycle violation message is descriptive and contains module paths`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        val msg = result.violations.first().message
        assertTrue(msg.contains("→"), "Violation message should show dependency chain")
    }

    @Test
    fun `cycle violation ruleId is stable`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        assertEquals("no-cyclic-dependencies", result.violations.first().ruleId)
    }

    @Test
    fun `errorCount counts only ERROR violations`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        assertEquals(result.violations.count { it.severity == Severity.ERROR }, result.errorCount)
    }

    @Test
    fun `rulesEvaluated tracks the number of rules run`() {
        val result = RuleEngine.withBuiltinRules().evaluate(acyclicGraph())
        assertTrue(result.rulesEvaluated >= 1)
    }

    @Test
    fun `rule engine captures exceptions from faulty rules gracefully`() {
        // A rule that always throws should produce an ERROR violation, not crash the build
        val faultyEngine = RuleEngine(
            listOf(object : com.aalekh.aalekh.analysis.rules.ArchRule {
                override val id = "always-throws"
                override val description = "This rule always throws"
                override val defaultSeverity = Severity.ERROR
                override fun evaluate(graph: ModuleDependencyGraph) =
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
        // :a -> :b (impl), :b -> :a (testImpl) - test code depending on another module is not a real cycle
        val result = RuleEngine.withBuiltinRules().evaluate(testOnlyCycleGraph())
        val errors = result.violations.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "Test-only cycle should not produce ERROR violations, got: $errors")
        assertFalse(result.hasBuildFailure, "Test-only cycles should not fail the build")
    }

    @Test
    fun `test-only cycle produces INFO violation`() {
        val result = RuleEngine.withBuiltinRules().evaluate(testOnlyCycleGraph())
        val infos = result.violations.filter { it.severity == Severity.INFO }
        assertTrue(infos.isNotEmpty(), "Test-only cycle should produce INFO violation")
        assertEquals(infos.first().ruleId, "test-cyclic-dependency")
    }

    @Test
    fun `main code cycle still produces ERROR`() {
        // :a -> :b -> :c -> :a - all implementation deps, this is a real cycle
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        val errors = result.violations.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isNotEmpty(), "Main-code cycle should produce ERROR")
        assertTrue(result.hasBuildFailure, "Main-code cycle should fail the build")
    }

    @Test
    fun `mixed cycle graph - main cycle detected as ERROR, test dep is not cycle`() {
        val result = RuleEngine.withBuiltinRules().evaluate(mixedCycleGraph())
        val errors = result.violations.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isNotEmpty(), "Main cycle :a → :b → :a should produce ERROR")
        // The test dep :c -> :a (testImpl) should not create an extra error
        assertEquals(1, errors.size, "Should have exactly one main cycle error")
    }

    @Test
    fun `datastore test pattern is NOT a cycle`() {
        // :core:datastore-test -> :core:datastore (testImpl) is NOT a cycle
        val result = RuleEngine.withBuiltinRules().evaluate(datastoreTestGraph())
        assertTrue(result.violations.isEmpty(), "Datastore test pattern should produce no violations")
        assertFalse(result.hasBuildFailure)
    }

    @Test
    fun `cycle violation message mentions main code explicitly`() {
        val result = RuleEngine.withBuiltinRules().evaluate(cyclicGraph())
        val error = result.violations.first { it.severity == Severity.ERROR }
        assertTrue(error.message.contains("main code"), "Error message should mention 'main code'")
    }

    @Test
    fun `test cycle INFO message explains it is test-only`() {
        val result = RuleEngine.withBuiltinRules().evaluate(testOnlyCycleGraph())
        val info = result.violations.first { it.severity == Severity.INFO }
        assertTrue(info.message.contains("test code"), "INFO message should mention 'test code'")
    }
}
