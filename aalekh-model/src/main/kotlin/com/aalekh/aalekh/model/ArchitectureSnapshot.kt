package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A compact, committable record of a project's architecture at one point in time.
 *
 * Deliberately **not** the whole graph. A snapshot exists to be committed to the repository and
 * compared against on every pull request, so it has to stay small enough to review and stable enough
 * that an unrelated change does not rewrite it: module paths, dependency pairs, and the handful of
 * numbers worth ratcheting. Everything derivable is left out and recomputed on demand.
 *
 * All fields default, so a snapshot written by an older plugin version still deserializes and simply
 * reports less.
 *
 * @param modules Module paths, sorted.
 * @param edges Production dependency pairs as `"from>to"`, sorted. The arrow form keeps the file
 *   diffable line by line, which is the point of committing it.
 * @param cycles Module paths that sit inside a production dependency cycle, sorted.
 * @param entryPoints Modules nothing depends on, sorted.
 * @param layers Declared layer name per module, for the modules a layer claims.
 * @param metrics The structural numbers worth watching over time.
 * @param aalekhVersion The plugin version that wrote this snapshot.
 */
@Serializable
public data class ArchitectureSnapshot(
    val modules: List<String> = emptyList(),
    val edges: List<String> = emptyList(),
    val cycles: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList(),
    val layers: Map<String, String> = emptyMap(),
    val metrics: MetricSnapshot = MetricSnapshot(),
    val aalekhVersion: String = "",
) {
    /** True when nothing was recorded - a snapshot file that is absent or empty. */
    public val isEmpty: Boolean get() = modules.isEmpty()

    public companion object {
        /** The conventional committed location, relative to the root project. */
        public const val DEFAULT_PATH: String = "aalekh-snapshot.json"

        /** An absent snapshot. */
        public val EMPTY: ArchitectureSnapshot = ArchitectureSnapshot()

        /** Encodes a dependency as the diffable `"from>to"` form used in [edges]. */
        public fun edgeKey(from: String, to: String): String = "$from>$to"
    }
}

/**
 * What changed between two snapshots of an architecture.
 *
 * Ordered so the most consequential change is read first: a new cycle matters more than a new module,
 * and a removed dependency matters more than a renamed one. Every list is sorted, so the same pair of
 * snapshots always produces the same report.
 *
 * @param addedModules Modules present now and not before.
 * @param removedModules Modules present before and not now.
 * @param addedEdges Dependencies added, as `"from>to"`.
 * @param removedEdges Dependencies removed, as `"from>to"`.
 * @param newCycles Modules newly caught in a cycle.
 * @param resolvedCycles Modules no longer in a cycle.
 * @param layerChanges Modules that moved from one declared layer to another, as
 *   `module to (before, after)`. A module entering or leaving layer coverage counts too.
 * @param metricDeltas Structural metrics that moved, keyed by metric name, as `before to after`.
 */
@Serializable
public data class ArchitectureDiff(
    val addedModules: List<String> = emptyList(),
    val removedModules: List<String> = emptyList(),
    val addedEdges: List<String> = emptyList(),
    val removedEdges: List<String> = emptyList(),
    val newCycles: List<String> = emptyList(),
    val resolvedCycles: List<String> = emptyList(),
    val layerChanges: Map<String, LayerChange> = emptyMap(),
    val metricDeltas: Map<String, MetricDelta> = emptyMap(),
) {
    /** True when the two snapshots describe the same architecture. */
    public val isEmpty: Boolean
        get() = addedModules.isEmpty() && removedModules.isEmpty() &&
                addedEdges.isEmpty() && removedEdges.isEmpty() &&
                newCycles.isEmpty() && resolvedCycles.isEmpty() &&
                layerChanges.isEmpty() && metricDeltas.isEmpty()

    /** True when something got structurally worse - a new cycle, or a metric that regressed. */
    public val hasRegression: Boolean
        get() = newCycles.isNotEmpty() || metricDeltas.values.any { it.isWorse }
}

/** A module's move between declared layers. `null` means it belonged to no layer at that point. */
@Serializable
public data class LayerChange(val before: String? = null, val after: String? = null)

/**
 * A structural metric's movement between two snapshots.
 *
 * @param before Value in the earlier snapshot.
 * @param after Value now.
 * @param isWorse True when the movement is in the bad direction. Every metric in
 *   [MetricSnapshot] is one where higher is worse, so this is simply `after > before` - stated
 *   explicitly so a caller never has to remember which way each metric runs.
 */
@Serializable
public data class MetricDelta(
    val before: Double,
    val after: Double,
    val isWorse: Boolean,
)
