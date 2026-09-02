package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.model.MetricSnapshot
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import kotlin.math.round

/**
 * A single structural metric that a quality gate can ratchet on. For every gate, **higher is worse**,
 * so it fails when the current value exceeds the baseline. The [key] is the stable, kebab-case token
 * used in the DSL and serialized task input; it is a public contract and must not change.
 */
public enum class MetricGate(public val key: String, public val label: String) {
    CYCLES("cycles", "cycle count"),
    GOD_MODULES("god-modules", "god-module count"),
    CCD("ccd", "cumulative dependency (CCD)"),
    TANGLE("tangle", "tangle %"),
    INSTABILITY("instability", "average instability"),
    CRITICAL_PATH("critical-path", "critical-path length");

    public companion object {
        /** All valid gate keys, for DSL validation and error messages. */
        public val KEYS: List<String> = entries.map { it.key }

        /** Resolves a [MetricGate] by its [key], or null if unknown. */
        public fun fromKey(key: String): MetricGate? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Compares the current graph's metrics against a committed baseline and reports any that regressed.
 *
 * This generalizes the cycle-only regression check to any structural metric: teams can ratchet
 * architecture quality in one direction only ("no backsliding"). Pure - no I/O; the baseline snapshot
 * and current summary are supplied by the caller.
 */
public object MetricGateEvaluator {

    /** The rule id under which metric regressions are reported. A stable public contract. */
    public const val RULE_ID: String = "metric-regression"

    private const val EPSILON = 1e-9

    /** Extracts the gate-able metrics from a computed [GraphSummary]. */
    public fun snapshot(summary: GraphSummary): MetricSnapshot = MetricSnapshot(
        cycleCount = summary.cycleCount,
        godModuleCount = summary.godModuleCount,
        ccd = summary.ccd,
        tanglePercent = summary.tanglePercent,
        averageInstability = summary.averageInstability,
        criticalPathLength = summary.criticalPathLength,
    )

    /**
     * Returns one [Violation] per enabled gate whose [current] value is worse than [baseline].
     *
     * @param baseline The committed baseline metrics, or null when none is recorded - in which case
     *   no gate can fire (there is nothing to compare against), mirroring cycle `preventRegression`.
     * @param gates The metrics to enforce.
     * @param severity The severity to assign to each regression violation.
     */
    public fun evaluate(
        current: MetricSnapshot,
        baseline: MetricSnapshot?,
        gates: Set<MetricGate>,
        severity: Severity,
    ): List<Violation> {
        if (baseline == null || gates.isEmpty()) return emptyList()
        return gates
            .sortedBy { it.ordinal }
            .mapNotNull { gate -> regression(gate, current, baseline, severity) }
    }

    private fun regression(
        gate: MetricGate,
        current: MetricSnapshot,
        baseline: MetricSnapshot,
        severity: Severity,
    ): Violation? {
        val now = value(gate, current)
        val was = value(gate, baseline)
        val regressed = isComparable(gate, current, baseline) && now > was + EPSILON
        if (!regressed) return null
        return Violation(
            ruleId = RULE_ID,
            severity = severity,
            message = "${gate.label} regressed: ${format(was)} → ${format(now)} versus the baseline. " +
                    "Reduce it or refresh the baseline with ./gradlew aalekhBaseline.",
            source = gate.key,
            moduleHint = null,
            plainLanguageExplanation = "A quality gate keeps this metric from getting worse than the " +
                    "committed baseline, so architecture quality only ratchets in one direction.",
        )
    }

    /**
     * True when a gate's two values mean the same thing and can be compared.
     *
     * A cyclic graph has no topological order, so its critical path is recorded as `0` - meaning "not
     * computable", not "zero-length". Comparing that sentinel against a real length would fail the
     * build for **breaking a cycle**, which is the opposite of what the gate is for. The comparison is
     * therefore skipped whenever either side was cyclic; the `cycles` gate is what guards that case.
     */
    private fun isComparable(
        gate: MetricGate,
        current: MetricSnapshot,
        baseline: MetricSnapshot,
    ): Boolean = gate != MetricGate.CRITICAL_PATH ||
            (current.cycleCount == 0 && baseline.cycleCount == 0)

    private fun value(gate: MetricGate, snapshot: MetricSnapshot): Double = when (gate) {
        MetricGate.CYCLES -> snapshot.cycleCount.toDouble()
        MetricGate.GOD_MODULES -> snapshot.godModuleCount.toDouble()
        MetricGate.CCD -> snapshot.ccd.toDouble()
        MetricGate.TANGLE -> snapshot.tanglePercent
        MetricGate.INSTABILITY -> snapshot.averageInstability
        MetricGate.CRITICAL_PATH -> snapshot.criticalPathLength.toDouble()
    }

    /** Whole numbers print without a decimal; fractional metrics print to two places. */
    private fun format(value: Double): String {
        val rounded = round(value * HUNDRED) / HUNDRED
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }

    private const val HUNDRED = 100.0
}
