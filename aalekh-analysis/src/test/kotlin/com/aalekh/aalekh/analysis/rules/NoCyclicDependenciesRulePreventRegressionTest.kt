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

class NoCyclicDependenciesRulePreventRegressionTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private fun cleanGraph() = ModuleDependencyGraph(
        "clean", listOf(node(":a"), node(":b")), listOf(edge(":a", ":b"))
    )

    private fun cyclicGraph() = ModuleDependencyGraph(
        "cyclic", listOf(node(":a"), node(":b")), listOf(edge(":a", ":b"), edge(":b", ":a"))
    )

    private fun twoCycleGraph() = ModuleDependencyGraph(
        "two-cycle",
        listOf(node(":a"), node(":b"), node(":c"), node(":d")),
        listOf(edge(":a", ":b"), edge(":b", ":a"), edge(":c", ":d"), edge(":d", ":c")),
    )

    // preventRegression=false (default) - existing behaviour unchanged

    @Test
    fun `no regression check when preventRegression is false`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = false)
        // Adding a cycle when previous was 0 should NOT trigger regression violation
        // because preventRegression is off - only the cycle itself fires
        val violations = rule.evaluate(cyclicGraph())
        assertFalse(violations.any { it.source == "cycle-regression" })
        assertTrue(violations.any { it.ruleId == "no-cyclic-dependencies" && it.severity == Severity.ERROR })
    }

    @Test
    fun `no regression check when previousCycleCount is null`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = null, preventRegression = true)
        val violations = rule.evaluate(cyclicGraph())
        // Cycle is still reported, but no regression violation
        assertFalse(violations.any { it.source == "cycle-regression" })
    }

    // preventRegression=true with previousCycleCount provided

    @Test
    fun `no regression violation when cycle count unchanged`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = 1, preventRegression = true)
        val violations = rule.evaluate(cyclicGraph())
        assertFalse(violations.any { it.source == "cycle-regression" })
    }

    @Test
    fun `no regression violation when cycle count decreased`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = 3, preventRegression = true)
        val violations = rule.evaluate(cyclicGraph())
        assertFalse(violations.any { it.source == "cycle-regression" })
    }

    @Test
    fun `regression violation when cycle count increased`() {
        // Previously 0 cycles, now 1 - regression
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = true)
        val violations = rule.evaluate(cyclicGraph())
        assertTrue(violations.any { it.source == "cycle-regression" && it.severity == Severity.ERROR })
    }

    @Test
    fun `regression violation message is descriptive`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = true)
        val violations = rule.evaluate(cyclicGraph())
        val regression = violations.first { it.source == "cycle-regression" }
        assertTrue(regression.message.contains("new cycle"))
        assertTrue(regression.message.contains("0"))  // previous count
    }

    @Test
    fun `regression violation has ERROR severity`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = true)
        val regressionViolations = rule.evaluate(cyclicGraph())
            .filter { it.source == "cycle-regression" }
        assertTrue(regressionViolations.isNotEmpty())
        assertEquals(Severity.ERROR, regressionViolations.first().severity)
    }

    @Test
    fun `regression violation ruleId matches cycle rule id`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = true)
        val regression = rule.evaluate(cyclicGraph()).first { it.source == "cycle-regression" }
        assertEquals("no-cyclic-dependencies", regression.ruleId)
    }

    @Test
    fun `regression reports correct delta when multiple new cycles added`() {
        // previousCycleCount=0, current=2 - two new cycles
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = true)
        val violations = rule.evaluate(twoCycleGraph())
        val regression = violations.firstOrNull { it.source == "cycle-regression" }
        assertTrue(regression != null)
        assertTrue(regression.message.contains("2"))
    }

    @Test
    fun `clean graph with preventRegression enabled produces no violations`() {
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = true)
        val violations = rule.evaluate(cleanGraph())
            .filter { it.severity != Severity.INFO }
        assertTrue(violations.isEmpty())
    }

    // Interaction: existing cycle violations still fire alongside regression violation

    @Test
    fun `both cycle violation and regression violation are present together`() {
        // previousCycleCount=0, current graph has a cycle
        val rule = NoCyclicDependenciesRule(previousCycleCount = 0, preventRegression = true)
        val violations = rule.evaluate(cyclicGraph())
        val cycleViolation =
            violations.any { it.ruleId == "no-cyclic-dependencies" && it.source != "cycle-regression" }
        val regressionViolation = violations.any { it.source == "cycle-regression" }
        assertTrue(cycleViolation, "Cycle violation must still be reported")
        assertTrue(regressionViolation, "Regression violation must also be reported")
    }
}