package com.aalekh.aalekh.analysis.snapshot

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.metrics.GraphMetrics
import com.aalekh.aalekh.analysis.metrics.MetricGateEvaluator
import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.analysis.rules.LayerSpecParser
import com.aalekh.aalekh.model.ArchitectureDiff
import com.aalekh.aalekh.model.ArchitectureSnapshot
import com.aalekh.aalekh.model.LayerChange
import com.aalekh.aalekh.model.MetricDelta
import com.aalekh.aalekh.model.MetricSnapshot
import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Records an architecture as a committable snapshot, and compares two of them.
 *
 * A branch can change the dependency graph without that being visible in the diff. Committing a
 * snapshot and comparing against it makes the change reviewable - "this pull request adds a
 * dependency from the domain layer to the UI layer" becomes a line in the review.
 *
 * The snapshot is small and sorted so it diffs line by line, and both functions are pure: the same
 * graph always produces the same file, and the same pair always produces the same report.
 */
public object SnapshotAnalyzer {

    /**
     * Records [graph] as a snapshot.
     *
     * @param layers Declared layers, so the snapshot remembers which layer claimed each module and a
     *   later diff can report a module moving between them.
     */
    public fun capture(
        graph: ModuleDependencyGraph,
        layers: List<LayerSpec> = emptyList(),
        aalekhVersion: String = "",
    ): ArchitectureSnapshot {
        val metrics = GraphMetrics.compute(graph)
        return ArchitectureSnapshot(
            modules = graph.modules.map { it.path }.sorted(),
            edges = graph.edges
                .asSequence()
                .filter { !it.isTest && it.from != it.to }
                .map { ArchitectureSnapshot.edgeKey(it.from, it.to) }
                .distinct()
                .sorted()
                .toList(),
            cycles = GraphAnalyzer.findMainOnlyCycles(graph).flatten().distinct().sorted(),
            entryPoints = metrics.project.entryPoints,
            layers = graph.modules
                .mapNotNull { module ->
                    LayerSpecParser.layerOf(layers, module.path)?.let { module.path to it.name }
                }
                .toMap()
                .toSortedMap(),
            metrics = MetricGateEvaluator.snapshot(GraphAnalyzer.summary(graph)),
            aalekhVersion = aalekhVersion,
        )
    }

    /**
     * Compares a recorded snapshot with the current one.
     *
     * @param before The committed snapshot - what the architecture looked like on the base branch.
     * @param after The current snapshot.
     */
    public fun diff(before: ArchitectureSnapshot, after: ArchitectureSnapshot): ArchitectureDiff {
        // An absent baseline is not an empty architecture: reporting every module as "added" on the
        // first run would bury the real signal the very first time anyone looks.
        if (before.isEmpty) return ArchitectureDiff()

        val beforeCycles = before.cycles.toSet()
        val afterCycles = after.cycles.toSet()

        return ArchitectureDiff(
            addedModules = (after.modules - before.modules.toSet()).sorted(),
            removedModules = (before.modules - after.modules.toSet()).sorted(),
            addedEdges = (after.edges - before.edges.toSet()).sorted(),
            removedEdges = (before.edges - after.edges.toSet()).sorted(),
            newCycles = (afterCycles - beforeCycles).sorted(),
            resolvedCycles = (beforeCycles - afterCycles).sorted(),
            layerChanges = layerChanges(before, after),
            metricDeltas = metricDeltas(before.metrics, after.metrics),
        )
    }

    /**
     * Modules that moved between declared layers, including into or out of layer coverage.
     *
     * Only modules present in both snapshots are considered: a module that was added or removed is
     * already reported as such, and calling that a "layer change" as well would double-count it.
     */
    private fun layerChanges(
        before: ArchitectureSnapshot,
        after: ArchitectureSnapshot,
    ): Map<String, LayerChange> {
        val shared = before.modules.toSet() intersect after.modules.toSet()
        return shared.sorted()
            .mapNotNull { path ->
                val was = before.layers[path]
                val now = after.layers[path]
                if (was == now) null else path to LayerChange(was, now)
            }
            .toMap()
    }

    /** Structural metrics that moved, with the direction of the movement resolved. */
    private fun metricDeltas(before: MetricSnapshot, after: MetricSnapshot): Map<String, MetricDelta> =
        buildMap {
            delta("cycles", before.cycleCount.toDouble(), after.cycleCount.toDouble())
            delta("god-modules", before.godModuleCount.toDouble(), after.godModuleCount.toDouble())
            delta("ccd", before.ccd.toDouble(), after.ccd.toDouble())
            delta("tangle", before.tanglePercent, after.tanglePercent)
            delta("instability", before.averageInstability, after.averageInstability)
            // A cyclic graph has no topological order, so its critical path is recorded as 0 -
            // meaning "not computable", not "zero-length". Comparing that sentinel against a real
            // length would report breaking a cycle as a regression, which is precisely backwards.
            if (before.cycleCount == 0 && after.cycleCount == 0) {
                delta(
                    "critical-path",
                    before.criticalPathLength.toDouble(),
                    after.criticalPathLength.toDouble(),
                )
            }
        }

    /**
     * Records a metric only when it actually moved.
     *
     * Every metric in `MetricSnapshot` is one where higher is worse, which is what makes a single
     * direction rule correct here rather than a per-metric table.
     */
    private fun MutableMap<String, MetricDelta>.delta(name: String, before: Double, after: Double) {
        if (before == after) return
        put(name, MetricDelta(before, after, isWorse = after > before))
    }
}
