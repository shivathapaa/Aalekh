package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.model.MetricSnapshot
import com.aalekh.aalekh.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricGateEvaluatorTest {

    private val allGates = MetricGate.entries.toSet()

    @Test
    fun `no baseline means no gate can fire`() {
        val result = MetricGateEvaluator.evaluate(
            MetricSnapshot(ccd = 999), baseline = null, allGates, Severity.ERROR,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `no gates means no violations`() {
        val result = MetricGateEvaluator.evaluate(
            MetricSnapshot(ccd = 999), MetricSnapshot(ccd = 1), emptySet(), Severity.ERROR,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a metric that stayed the same does not regress`() {
        val same = MetricSnapshot(cycleCount = 1, ccd = 10)
        assertTrue(MetricGateEvaluator.evaluate(same, same, allGates, Severity.ERROR).isEmpty())
    }

    @Test
    fun `an improved metric does not regress`() {
        val result = MetricGateEvaluator.evaluate(
            MetricSnapshot(ccd = 5), MetricSnapshot(ccd = 10), allGates, Severity.ERROR,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a worse metric produces one violation for that gate`() {
        val result = MetricGateEvaluator.evaluate(
            MetricSnapshot(ccd = 20), MetricSnapshot(ccd = 10), setOf(MetricGate.CCD), Severity.ERROR,
        )
        assertEquals(1, result.size)
        val violation = result.single()
        assertEquals(MetricGateEvaluator.RULE_ID, violation.ruleId)
        assertEquals("ccd", violation.source)
        assertEquals(Severity.ERROR, violation.severity)
        assertTrue(violation.message.contains("10"))
        assertTrue(violation.message.contains("20"))
    }

    @Test
    fun `only the enabled gate is checked`() {
        val current = MetricSnapshot(cycleCount = 5, ccd = 20)
        val baseline = MetricSnapshot(cycleCount = 0, ccd = 10)
        val result = MetricGateEvaluator.evaluate(current, baseline, setOf(MetricGate.CCD), Severity.ERROR)
        assertEquals(1, result.size, "cycles regressed too but only the ccd gate was enabled")
        assertEquals("ccd", result.single().source)
    }

    @Test
    fun `multiple regressions are reported in gate order`() {
        val current = MetricSnapshot(cycleCount = 2, godModuleCount = 3, ccd = 50)
        val baseline = MetricSnapshot(cycleCount = 1, godModuleCount = 1, ccd = 10)
        val result = MetricGateEvaluator.evaluate(current, baseline, allGates, Severity.WARNING)
        assertEquals(listOf("cycles", "god-modules", "ccd"), result.map { it.source })
        assertTrue(result.all { it.severity == Severity.WARNING })
    }

    @Test
    fun `fractional metrics regress on any increase`() {
        val current = MetricSnapshot(tanglePercent = 25.0, averageInstability = 0.61)
        val baseline = MetricSnapshot(tanglePercent = 25.0, averageInstability = 0.60)
        val result = MetricGateEvaluator.evaluate(current, baseline, allGates, Severity.ERROR)
        assertEquals(listOf("instability"), result.map { it.source })
    }

    @Test
    fun `snapshot maps the summary metrics`() {
        val summary = GraphSummary(
            totalModules = 4,
            totalEdges = 3,
            modulesByType = emptyMap(),
            hasCycles = true,
            cycleCount = 2,
            maxFanOut = 3,
            maxFanIn = 2,
            averageInstability = 0.5,
            criticalPathLength = 4,
            godModuleCount = 1,
            isolatedModuleCount = 0,
            ccd = 12,
            acd = 3.0,
            nccd = 1.1,
            tanglePercent = 40.0,
            cyclicComponentCount = 1,
        )
        val snapshot = MetricGateEvaluator.snapshot(summary)
        assertEquals(MetricSnapshot(2, 1, 12, 40.0, 0.5, 4), snapshot)
    }

    @Test
    fun `breaking a cycle does not fail the critical-path gate`() {
        // A cyclic graph has no topological order, so its critical path is recorded as 0 - "not
        // computable", not "zero-length". Before this was handled, fixing a cycle made the length
        // jump from 0 to a real value and failed the build for improving the architecture.
        val baseline = MetricSnapshot(cycleCount = 2, criticalPathLength = 0)
        val current = MetricSnapshot(cycleCount = 0, criticalPathLength = 7)

        val violations = MetricGateEvaluator.evaluate(
            current = current,
            baseline = baseline,
            gates = setOf(MetricGate.CRITICAL_PATH),
            severity = Severity.ERROR,
        )

        assertTrue(violations.isEmpty(), "fixing a cycle must never trip the critical-path gate")
    }

    @Test
    fun `the critical-path gate still fires when both sides are acyclic`() {
        val violations = MetricGateEvaluator.evaluate(
            current = MetricSnapshot(cycleCount = 0, criticalPathLength = 9),
            baseline = MetricSnapshot(cycleCount = 0, criticalPathLength = 6),
            gates = setOf(MetricGate.CRITICAL_PATH),
            severity = Severity.ERROR,
        )

        assertEquals(1, violations.size)
    }
}
