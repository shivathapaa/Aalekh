package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * One penalty applied to the whole-project health score, with the reason it fired.
 *
 * The report lists these under the score so it can be read as "38 lost to cycles and blocking
 * violations" rather than as an opaque verdict.
 *
 * @param label Short name of the signal, e.g. `"Cycles"`.
 * @param penalty Points deducted, already capped at [maxPenalty].
 * @param maxPenalty The most this signal can ever deduct.
 * @param detail One-line explanation of what produced the penalty.
 */
public data class HealthComponent(
    val label: String,
    val penalty: Int,
    val maxPenalty: Int,
    val detail: String,
)

/**
 * The whole-project architecture health score and the penalties that produced it.
 *
 * @param score Health in `[0, 100]`; higher is healthier.
 * @param band Readable classification: `"Healthy"`, `"Fair"`, or `"At risk"`.
 * @param components Every penalty signal, including those that deducted nothing, in a fixed order.
 */
public data class ProjectHealth(
    val score: Int,
    val band: String,
    val components: List<HealthComponent>,
)

/**
 * Computes a 0–100 architecture health score for each module.
 *
 * The score is a weighted composite of four signals:
 *
 * | Signal                  | Weight | Rationale |
 * |-------------------------|--------|-----------|
 * | Instability index       |  30%   | Measures how dependent vs depended-upon a module is |
 * | God module status       |  25%   | High fan-in AND fan-out = hard to change, hard to test |
 * | Cycle participation     |  25%   | Cycles prevent independent builds and refactoring |
 * | Transitive dep count    |  20%   | Proxy for hidden coupling and build-time impact |
 *
 * A score of 100 means: stable, no coupling hotspot, not in any cycle, few transitive deps.
 * A score below 40 is a strong signal that the module needs architectural attention.
 *
 * [projectScore] computes a **separate** whole-project score from project-level signals (cycles,
 * violations, coupling hubs, average instability). The two are deliberately different measures and
 * are never interchangeable: a project of uniformly healthy modules can still score poorly if they
 * are tangled together. Both are single-sourced here so the report, the CSV, and the docs cannot
 * drift apart.
 *
 * Both scores are intentionally non-configurable - their value comes from being a consistent
 * signal across projects, not a team-specific threshold.
 */
public object HealthScoreCalculator {

    // Transitive dep count where the score component hits zero
    private const val TRANSITIVE_MAX = 50

    // Whole-project penalty weights and caps. These are the numbers the report used to hardcode in
    // JavaScript; they live here so the dial, the JSON summary, and the docs cannot drift apart.
    private const val CYCLE_WEIGHT = 12
    private const val CYCLE_CAP = 40
    private const val ERROR_WEIGHT = 5
    private const val ERROR_CAP = 25
    private const val WARNING_WEIGHT = 2
    private const val WARNING_CAP = 10
    private const val GOD_WEIGHT = 4
    private const val GOD_CAP = 15
    private const val INSTABILITY_CAP = 15
    private const val INSTABILITY_SCALE = 30.0

    // Average instability below this is not penalised at all - half the modules depending on the
    // other half is the expected shape of a healthy graph, not a defect.
    private const val INSTABILITY_FLOOR = 0.5

    private const val HEALTHY_THRESHOLD = 80
    private const val FAIR_THRESHOLD = 55

    /**
     * Returns a health score in [0, 100] for the given module path.
     * Higher is healthier.
     */
    public fun score(path: String, graph: ModuleDependencyGraph, cycleNodes: Set<String>): Int {
        val instabilityPenalty = graph.instability(path) * 30
        val godPenalty = if (isGodModule(path, graph)) 25.0 else 0.0
        val cyclePenalty = if (path in cycleNodes) 25.0 else 0.0
        val transitivePenalty = minOf(graph.transitiveCount(path).toDouble() / TRANSITIVE_MAX, 1.0) * 20

        val totalPenalty = instabilityPenalty + godPenalty + cyclePenalty + transitivePenalty
        return (100 - totalPenalty).toInt().coerceIn(0, 100)
    }

    /**
     * Returns the whole-project health score - a **different measure** from the per-module [score].
     *
     * Where [score] asks "is this module in a healthy position in the graph?", this asks "is this
     * project's architecture in good shape right now?", so it weighs project-level facts a single
     * module cannot express: cycle count, rule violations, the number of coupling hubs, and how
     * unstable the graph is on average. Each signal is capped, so no one problem can drive the score
     * to zero on its own, and every penalty is returned in [ProjectHealth.components] so the number
     * can explain itself.
     *
     * @param summary The current graph summary - supplies cycle count, god-module count, and average
     *   instability.
     * @param errorCount ERROR-severity violations from the rule engine.
     * @param warningCount WARNING-severity violations from the rule engine.
     */
    public fun projectScore(summary: GraphSummary, errorCount: Int, warningCount: Int): ProjectHealth {
        val instabilityPenalty =
            ((summary.averageInstability - INSTABILITY_FLOOR) * INSTABILITY_SCALE)
                .coerceIn(0.0, INSTABILITY_CAP.toDouble())
                .toInt()

        val components = listOf(
            component("Cycles", summary.cycleCount, CYCLE_WEIGHT, CYCLE_CAP, "main-code dependency loop"),
            component("Blocking violations", errorCount, ERROR_WEIGHT, ERROR_CAP, "ERROR-severity violation"),
            component("Advisories", warningCount, WARNING_WEIGHT, WARNING_CAP, "WARNING-severity violation"),
            component("Coupling hubs", summary.godModuleCount, GOD_WEIGHT, GOD_CAP, "god module"),
            HealthComponent(
                label = "Average instability",
                penalty = instabilityPenalty,
                maxPenalty = INSTABILITY_CAP,
                detail = "average instability is %.2f; nothing is deducted at or below %.2f"
                    .format(summary.averageInstability, INSTABILITY_FLOOR),
            ),
        )

        val score = (100 - components.sumOf { it.penalty }).coerceIn(0, 100)
        val band = when {
            score >= HEALTHY_THRESHOLD -> "Healthy"
            score >= FAIR_THRESHOLD -> "Fair"
            else -> "At risk"
        }
        return ProjectHealth(score, band, components)
    }

    private fun component(
        label: String,
        count: Int,
        weight: Int,
        cap: Int,
        noun: String,
    ): HealthComponent = HealthComponent(
        label = label,
        penalty = minOf(cap, count * weight),
        maxPenalty = cap,
        detail = "$count $noun${if (count == 1) "" else "s"} × $weight points, capped at $cap",
    )

    // Uses the same god-module thresholds as GraphAnalyzer.godModules() - single-sourced there.
    private fun isGodModule(path: String, graph: ModuleDependencyGraph): Boolean =
        graph.fanIn(path) >= GraphAnalyzer.DEFAULT_GOD_FAN_IN_THRESHOLD &&
                graph.fanOut(path) >= GraphAnalyzer.DEFAULT_GOD_FAN_OUT_THRESHOLD
}