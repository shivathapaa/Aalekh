package com.aalekh.aalekh.analysis.spi

import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.ModuleDependencyGraph
import java.util.ServiceLoader

/**
 * The result of running third-party extensions against a graph.
 *
 * @param findings Findings contributed by [FindingProvider]s, in provider order.
 * @param classifications Module classifications contributed by [ModuleClassifier]s, keyed by module
 *   path. The first classifier to answer for a module wins, so discovery order is the tie-break.
 * @param failures Human-readable `"<id or class>: <reason>"` notes for extensions that were skipped -
 *   a blank id, a duplicate, or one that threw. Never fatal.
 */
public data class ExtensionResult(
    val findings: List<Finding> = emptyList(),
    val classifications: Map<String, ModuleClassification> = emptyMap(),
    val failures: List<String> = emptyList(),
) {
    public companion object {
        /** Nothing was discovered, or nothing produced a result. */
        public val EMPTY: ExtensionResult = ExtensionResult()
    }
}

/**
 * Discovers and runs the narrative extension points, fail-silent.
 *
 * The counterpart to [CustomMetricEngine] for the two SPIs that shape what Aalekh *says* rather than
 * what it measures: [FindingProvider] and [ModuleClassifier]. Discovery is the JDK [ServiceLoader],
 * so any jar on the plugin's runtime classpath contributes automatically.
 *
 * Every failure mode - a class that will not instantiate, a blank or duplicate id, an extension that
 * throws - becomes a note in [ExtensionResult.failures] rather than an exception. A third party's bug
 * must never break someone else's build, and a report that is missing one section is a far better
 * outcome than a build that does not run.
 */
public object ExtensionEngine {

    /** Loads every extension of type [T] visible to [classLoader]. Never throws. */
    public inline fun <reified T : Any> load(classLoader: ClassLoader): List<T> {
        val found = mutableListOf<T>()
        runCatching {
            val iterator = ServiceLoader.load(T::class.java, classLoader).iterator()
            while (iterator.hasNext()) {
                runCatching { iterator.next() }.onSuccess { found += it }
            }
        }
        return found
    }

    /**
     * Runs the given extensions against [graph].
     *
     * @param graph The graph to analyse.
     * @param findingProviders Providers contributing findings.
     * @param classifiers Classifiers contributing module metadata. The first to answer for a module
     *   wins.
     */
    public fun run(
        graph: ModuleDependencyGraph,
        findingProviders: List<FindingProvider>,
        classifiers: List<ModuleClassifier>,
    ): ExtensionResult {
        val failures = mutableListOf<String>()
        return ExtensionResult(
            findings = collectFindings(graph, findingProviders, failures),
            classifications = collectClassifications(graph, classifiers, failures),
            failures = failures,
        )
    }

    /** Discovers both SPIs on [classLoader] and runs them in one step. */
    public fun loadAndRun(graph: ModuleDependencyGraph, classLoader: ClassLoader): ExtensionResult =
        run(
            graph = graph,
            findingProviders = load<FindingProvider>(classLoader),
            classifiers = load<ModuleClassifier>(classLoader),
        )

    private fun collectFindings(
        graph: ModuleDependencyGraph,
        providers: List<FindingProvider>,
        failures: MutableList<String>,
    ): List<Finding> {
        val seen = mutableSetOf<String>()
        val findings = mutableListOf<Finding>()
        providers.forEach { provider ->
            val id = runCatching { provider.id.trim() }.getOrDefault("")
            when {
                id.isEmpty() -> failures += "${provider.javaClass.name}: blank finding provider id"
                !seen.add(id) -> failures += "$id: duplicate finding provider id (ignored)"
                else -> runCatching { provider.find(graph) }
                    .onSuccess { findings += it }
                    .onFailure { failures += "$id: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
        return findings
    }

    private fun collectClassifications(
        graph: ModuleDependencyGraph,
        classifiers: List<ModuleClassifier>,
        failures: MutableList<String>,
    ): Map<String, ModuleClassification> {
        val usable = classifiers.filter { classifier ->
            val id = runCatching { classifier.id.trim() }.getOrDefault("")
            if (id.isEmpty()) failures += "${classifier.javaClass.name}: blank classifier id"
            id.isNotEmpty()
        }
        if (usable.isEmpty()) return emptyMap()

        return graph.modules
            .mapNotNull { module -> classify(module.path, graph, usable, failures) }
            .toMap()
    }

    private fun classify(
        path: String,
        graph: ModuleDependencyGraph,
        classifiers: List<ModuleClassifier>,
        failures: MutableList<String>,
    ): Pair<String, ModuleClassification>? {
        classifiers.forEach { classifier ->
            val result = runCatching { classifier.classify(path, graph) }
                .onFailure { failures += "${classifier.id}: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
            if (result != null) return path to result
        }
        return null
    }
}
