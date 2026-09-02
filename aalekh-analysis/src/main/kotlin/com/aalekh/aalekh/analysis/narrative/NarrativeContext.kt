package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.analysis.metrics.GraphMetricSet
import com.aalekh.aalekh.analysis.rules.GlobMatcher
import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.analysis.rules.LayerSpecParser
import com.aalekh.aalekh.analysis.spi.ExtensionResult
import com.aalekh.aalekh.model.BuildInventory
import com.aalekh.aalekh.model.CoChange
import com.aalekh.aalekh.model.ModuleChurn
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Violation

/**
 * Everything the narrative finders read, gathered once.
 *
 * A finder is a pure function of this context, so adding one never changes what the others see and
 * the whole narrative can be regenerated from a graph plus the optional side reports. Fields that
 * come from a task the user may not have run (churn, hidden coupling) default to empty rather than
 * being nullable - a finder that needs them simply produces nothing, which is the honest result.
 *
 * @param graph The module dependency graph.
 * @param metrics Structural metrics, computed once by `GraphMetrics.compute`.
 * @param summary Whole-graph summary (cycles, coupling, god modules).
 * @param violations Rule violations from the current run, if rules are configured.
 * @param layers Declared layers, in declaration order. Empty when none are declared.
 * @param teams Declared team → module glob patterns. Empty when none are declared.
 * @param churn Per-module commit counts from `aalekhTemporal`. Empty when it has not run.
 * @param hiddenCoupling Strongly co-changing pairs with no declared dependency, from `aalekhTemporal`.
 * @param inventory How the project is built: plugins, catalogs, toolchains, targets, CODEOWNERS, and
 *   declared module metadata. [BuildInventory.EMPTY] for a graph extracted before this existed.
 * @param extensions Contributions from third-party `FindingProvider`s and `ModuleClassifier`s.
 *   [ExtensionResult.EMPTY] when no extension jar is on the classpath.
 */
public data class NarrativeContext(
    val graph: ModuleDependencyGraph,
    val metrics: GraphMetricSet,
    val summary: GraphSummary,
    val violations: List<Violation> = emptyList(),
    val layers: List<LayerSpec> = emptyList(),
    val teams: Map<String, List<String>> = emptyMap(),
    val churn: List<ModuleChurn> = emptyList(),
    val hiddenCoupling: List<CoChange> = emptyList(),
    val inventory: BuildInventory = BuildInventory.EMPTY,
    val extensions: ExtensionResult = ExtensionResult.EMPTY,
) {
    /** Modules sorted by influence, most foundational first. */
    public val byInfluence: List<String> by lazy {
        metrics.modules.values
            .sortedWith(compareByDescending<com.aalekh.aalekh.analysis.metrics.ModuleGraphMetrics> { it.influence }
                .thenBy { it.path })
            .map { it.path }
    }

    /** Commit counts keyed by module, empty when `aalekhTemporal` has not run. */
    public val churnByModule: Map<String, Int> by lazy { churn.associate { it.module to it.commits } }

    /**
     * The layer a module belongs to, or null when nothing assigns one.
     *
     * Declarations outrank extensions, which outrank Aalekh's own guesses: an explicit
     * `.aalekh/modules.json` entry, then the `layers { }` block, then a `ModuleClassifier` that knows
     * the team's convention. The path-segment heuristic lives in the report and is only reached when
     * none of these answer.
     */
    public fun layerOf(path: String): String? =
        inventory.declaredMetadata[path]?.layer
            ?: LayerSpecParser.layerOf(layers, path)?.name
            ?: extensions.classifications[path]?.layer

    /**
     * The team owning a module, or null when nothing claims it.
     *
     * Three sources, most specific first: a module named explicitly in `.aalekh/modules.json`, then
     * a `teams { }` glob, then `CODEOWNERS`. A team that took the trouble to name one module by hand
     * outranks a pattern that happens to cover it, and both outrank a file convention.
     */
    public fun teamOf(path: String): String? =
        inventory.declaredMetadata[path]?.owner
            ?: teams.entries.firstOrNull { (_, patterns) -> GlobMatcher.matchesAny(patterns, path) }?.key
            ?: extensions.classifications[path]?.team
            ?: inventory.codeowners[path]?.firstOrNull()

    /** True when any ownership source is configured at all - otherwise ownership findings stay quiet. */
    public val hasOwnershipData: Boolean
        get() = teams.isNotEmpty() || inventory.codeowners.isNotEmpty() ||
                inventory.declaredMetadata.values.any { it.owner != null } ||
                extensions.classifications.values.any { it.team != null }

    /**
     * What a module is for, when anyone has said.
     *
     * Only a human or a team's own registry can answer this, so there is no fallback to a heuristic:
     * a guessed "purpose" would be the least trustworthy sentence in the whole report.
     */
    public fun purposeOf(path: String): String? =
        inventory.declaredMetadata[path]?.purpose ?: extensions.classifications[path]?.purpose

    /** True when the project is large enough for a claim about proportions to mean anything. */
    public val isMeaningfullySized: Boolean get() = graph.modules.size >= MIN_MEANINGFUL_MODULES

    private companion object {
        const val MIN_MEANINGFUL_MODULES = 4
    }
}
