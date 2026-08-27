package com.aalekh.aalekh.analysis.spi

import com.aalekh.aalekh.model.CustomMetric
import com.aalekh.aalekh.model.CustomMetricReport
import com.aalekh.aalekh.model.ModuleDependencyGraph
import java.util.ServiceLoader

/**
 * Discovers [MetricProvider]s from the classpath and runs them against a graph, fail-silent.
 *
 * Discovery uses the JDK [ServiceLoader], so any jar on the plugin's runtime classpath that ships a
 * `META-INF/services/com.aalekh.aalekh.analysis.spi.MetricProvider` file contributes automatically.
 * Every failure mode - a provider whose class won't instantiate, a blank or duplicate metric id, a
 * provider that throws while computing - is captured as a note in
 * [CustomMetricReport.providerFailures] rather than propagated, mirroring the fail-silent contract
 * of the rest of Aalekh's extraction and analysis.
 */
public object CustomMetricEngine {

    /**
     * Loads all [MetricProvider]s visible to [classLoader]. A provider that cannot be instantiated
     * (missing class, throwing constructor, malformed service entry) is skipped; discovery continues
     * with the rest. Never throws.
     */
    public fun load(classLoader: ClassLoader): List<MetricProvider> {
        val providers = mutableListOf<MetricProvider>()
        runCatching {
            val iterator = ServiceLoader.load(MetricProvider::class.java, classLoader).iterator()
            while (iterator.hasNext()) {
                runCatching { iterator.next() }.onSuccess { providers += it }
            }
        }
        return providers
    }

    /**
     * Runs [providers] against [graph], assembling each provider's raw contribution into a
     * [CustomMetric]. Providers are processed in order; a blank id, an id already emitted, or a
     * provider that throws is recorded in [CustomMetricReport.providerFailures] and skipped.
     */
    public fun compute(
        graph: ModuleDependencyGraph,
        providers: List<MetricProvider>,
    ): CustomMetricReport {
        val seen = mutableSetOf<String>()
        val metrics = mutableListOf<CustomMetric>()
        val failures = mutableListOf<String>()
        providers.forEach { provider ->
            val id = runCatching { provider.id.trim() }.getOrDefault("")
            when {
                id.isEmpty() -> failures += "${provider.javaClass.name}: blank metric id"
                !seen.add(id) -> failures += "$id: duplicate metric id (ignored)"
                else -> runCatching { toMetric(id, provider, graph) }
                    .onSuccess { metrics += it }
                    .onFailure { failures += "$id: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
        return CustomMetricReport(metrics, failures)
    }

    /**
     * Discovers providers via [load] and runs them with [compute] in one step - the entry point the
     * `aalekhMetrics` task uses.
     */
    public fun loadAndCompute(
        graph: ModuleDependencyGraph,
        classLoader: ClassLoader,
    ): CustomMetricReport = compute(graph, load(classLoader))

    private fun toMetric(
        id: String,
        provider: MetricProvider,
        graph: ModuleDependencyGraph,
    ): CustomMetric {
        val contribution = provider.compute(graph)
        return CustomMetric(
            providerId = id,
            displayName = provider.displayName,
            description = provider.description,
            unit = provider.unit,
            systemValue = contribution.systemValue,
            moduleValues = contribution.moduleValues,
        )
    }
}
