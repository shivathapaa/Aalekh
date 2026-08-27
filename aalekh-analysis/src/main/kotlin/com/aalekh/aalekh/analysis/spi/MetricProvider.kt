package com.aalekh.aalekh.analysis.spi

import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Extension point for contributing a **custom metric** to Aalekh.
 *
 * Custom rules (`rules { custom(...) }`) let you *enforce* structure; a `MetricProvider` lets you
 * *measure* it. Implement this interface, register it with the JDK [java.util.ServiceLoader]
 * mechanism (a `META-INF/services/com.aalekh.aalekh.analysis.spi.MetricProvider` file listing your
 * class), and put the jar on the plugin's runtime classpath - the same classpath used for custom
 * rules (a `buildscript { dependencies { classpath(...) } }` entry, or an `includeBuild` composite).
 * The `aalekhMetrics` task then discovers every provider automatically and writes their values to
 * `aalekh-custom-metrics.json` / `.md`.
 *
 * A provider is a **pure function of the graph**: given a [ModuleDependencyGraph] it returns numbers.
 * It must not touch the filesystem, the network, or the Gradle API - the graph carries everything
 * analysis is allowed to see. A provider that throws is skipped and reported in the run's failures;
 * it never breaks the build.
 *
 * ```kotlin
 * class LeafRatioMetric : MetricProvider {
 *     override val id = "leaf-ratio"
 *     override val displayName = "Leaf module ratio"
 *     override val description = "Share of modules that nothing depends on."
 *     override val unit = "%"
 *     override fun compute(graph: ModuleDependencyGraph): MetricContribution {
 *         val leaves = graph.modules.count { m -> graph.edges.none { it.to == m.path && !it.isTest } }
 *         val ratio = if (graph.modules.isEmpty()) 0.0 else leaves * 100.0 / graph.modules.size
 *         return MetricContribution(systemValue = ratio)
 *     }
 * }
 * ```
 */
public interface MetricProvider {

    /**
     * Stable, unique identifier for this metric, kebab-case by convention (e.g. `"leaf-ratio"`).
     * Used as the metric's key in the JSON output; a blank id, or a second provider reusing an id
     * already loaded, is skipped and reported as a failure.
     */
    public val id: String

    /** Human-readable label shown in reports and the KPI panel. */
    public val displayName: String

    /** Optional one-line explanation of what the metric measures. Empty by default. */
    public val description: String get() = ""

    /** Optional unit label (e.g. `"%"`, `"edges"`). Empty by default for a bare number. */
    public val unit: String get() = ""

    /**
     * Computes this metric over [graph]. Return system-wide and/or per-module values via
     * [MetricContribution]. Must be pure and side-effect free; may return an empty contribution when
     * the metric does not apply to a given graph.
     */
    public fun compute(graph: ModuleDependencyGraph): MetricContribution
}

/**
 * The raw values a [MetricProvider] produces for one graph.
 *
 * @param systemValue A single whole-graph number, or `null` if this metric is per-module only.
 * @param moduleValues Per-module numbers keyed by module Gradle path; empty if system-only.
 */
public data class MetricContribution(
    val systemValue: Double? = null,
    val moduleValues: Map<String, Double> = emptyMap(),
)
