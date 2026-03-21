package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.model.ModuleDependencyGraph

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
 * The score is intentionally non-configurable - its value comes from being a consistent
 * signal across projects, not a team-specific threshold.
 */
public object HealthScoreCalculator {

    // God module thresholds - same as GraphAnalyzer.godModules() defaults
    private const val GOD_FAN_IN_THRESHOLD = 5
    private const val GOD_FAN_OUT_THRESHOLD = 5

    // Transitive dep count where the score component hits zero
    private const val TRANSITIVE_MAX = 50

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

    private fun isGodModule(path: String, graph: ModuleDependencyGraph): Boolean =
        graph.fanIn(path) >= GOD_FAN_IN_THRESHOLD && graph.fanOut(path) >= GOD_FAN_OUT_THRESHOLD
}