package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * One metric contributed by a third-party `MetricProvider` (the metrics extension point of the
 * Aalekh SPI). A metric can be *system-wide* ([systemValue]), *per-module* ([moduleValues]), or
 * both - a provider populates whichever dimensions make sense for it.
 *
 * This is the serialized, machine-readable shape written to `aalekh-custom-metrics.json`; the
 * provider interface (`com.aalekh.aalekh.analysis.spi.MetricProvider`) produces the raw values and
 * the engine assembles them into this envelope.
 *
 * @param providerId Stable identifier of the contributing provider (its `id`). Used as the column
 *   key and must be unique across loaded providers - a duplicate is dropped.
 * @param displayName Human-readable label for reports and the KPI panel.
 * @param description Optional one-line explanation of what the metric measures.
 * @param unit Optional unit label (e.g. `"%"`, `"edges"`); empty for a bare number.
 * @param systemValue The single whole-graph value, or `null` if the metric is per-module only.
 * @param moduleValues Per-module values keyed by module Gradle path; empty if the metric is
 *   system-only.
 */
@Serializable
public data class CustomMetric(
    val providerId: String,
    val displayName: String,
    val description: String = "",
    val unit: String = "",
    val systemValue: Double? = null,
    val moduleValues: Map<String, Double> = emptyMap(),
)

/**
 * The result of running every discovered `MetricProvider` against the graph.
 *
 * @param metrics One entry per provider that produced a value without error, in discovery order.
 * @param providerFailures Human-readable `"<id or class>: <reason>"` notes for providers that were
 *   skipped - a blank id, a duplicate id, or an exception thrown while computing. Never fatal; a
 *   broken provider is reported here and the rest still run.
 */
@Serializable
public data class CustomMetricReport(
    val metrics: List<CustomMetric> = emptyList(),
    val providerFailures: List<String> = emptyList(),
) {
    public companion object {
        /** No providers were discovered, or none produced a value. */
        public val EMPTY: CustomMetricReport = CustomMetricReport()
    }
}
