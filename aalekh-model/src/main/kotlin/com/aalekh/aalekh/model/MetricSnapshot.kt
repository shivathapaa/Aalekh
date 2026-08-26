package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A snapshot of the structural metrics that quality gates ratchet on.
 *
 * Recorded in the committed baseline (`aalekh-baseline.json`) by `aalekhBaseline` and compared
 * against the current graph by `aalekhCheck`. For every metric here, **higher is worse**, so a gate
 * fails when the current value exceeds the baseline.
 *
 * All fields default so a baseline written by an older plugin version (without a metrics block) still
 * deserializes - a missing snapshot simply disables the gates until the baseline is regenerated.
 *
 * @param cycleCount Number of main-code dependency cycles.
 * @param godModuleCount Number of god modules (high fan-in and high fan-out).
 * @param ccd Cumulative Component Dependency - the headline coupling number.
 * @param tanglePercent Percentage of modules inside a dependency cycle.
 * @param averageInstability Mean instability across all modules.
 * @param criticalPathLength Longest production dependency chain, in modules.
 */
@Serializable
public data class MetricSnapshot(
    val cycleCount: Int = 0,
    val godModuleCount: Int = 0,
    val ccd: Long = 0,
    val tanglePercent: Double = 0.0,
    val averageInstability: Double = 0.0,
    val criticalPathLength: Int = 0,
)
