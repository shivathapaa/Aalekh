package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Computes architecture health metrics for a [ModuleDependencyGraph].
 *
 * This file defines the data contracts now so
 * the JSON baseline format is stable before metrics are fully implemented.
 *
 * Metrics computed per build:
 * - Afferent/efferent coupling (Ca/Ce) per module
 * - Instability index (Ce / (Ca + Ce))
 * - Abstractness (not computable from the graph alone - needs KSP in v1.0)
 * - Distance from the main sequence (|A + I - 1|)
 * - Project-wide coupling factor
*/
public object MetricsEngine {
    /**
     * Computes per-module metrics for all modules in the graph.
    */
    public fun computeModuleMetrics(graph: ModuleDependencyGraph): List<ModuleMetrics> =
        graph.modules.map { module ->
            ModuleMetrics(
                modulePath = module.path,
                fanIn = graph.fanIn(module.path),
                fanOut = graph.fanOut(module.path),
                instability = graph.instability(module.path),
                transitiveDepCount = graph.transitiveCount(module.path),
            )
        }

    /**
     * Computes project-wide aggregate metrics.
    */
    public fun computeProjectMetrics(graph: ModuleDependencyGraph): ProjectMetrics {
        val moduleMetrics = computeModuleMetrics(graph)
        return ProjectMetrics(
            totalModules = graph.modules.size,
            totalEdges = graph.edges.size,
            averageInstability = moduleMetrics.map { it.instability }.average().takeIf { !it.isNaN() } ?: 0.0,
            averageFanOut = moduleMetrics.map { it.fanOut }.average().takeIf { !it.isNaN() } ?: 0.0,
            hasCycles = graph.hasCycle(),
            moduleMetrics = moduleMetrics,
        )
    }
}

/** Architecture metrics for a single module. Stored in the JSON baseline.*/
public data class ModuleMetrics(
    val modulePath: String,
    val fanIn: Int,
    val fanOut: Int,
    val instability: Double,
    val transitiveDepCount: Int,
)

/** Project-wide aggregate metrics snapshot. One per build in the baseline store.*/
public data class ProjectMetrics(
    val totalModules: Int,
    val totalEdges: Int,
    val averageInstability: Double,
    val averageFanOut: Double,
    val hasCycles: Boolean,
    val moduleMetrics: List<ModuleMetrics>,
)