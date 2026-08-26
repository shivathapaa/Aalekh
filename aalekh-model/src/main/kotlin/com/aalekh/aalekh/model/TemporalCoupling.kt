package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * How often a single module changed across the analysed commit window.
 *
 * A high commit count marks a **change hotspot** - a module that keeps churning and is therefore
 * a prime refactoring and test-hardening target.
 *
 * @param module Fully-qualified Gradle path, e.g. `":feature:login:data"`.
 * @param commits Number of commits in the window that touched at least one file in this module.
 */
@Serializable
public data class ModuleChurn(
    val module: String,
    val commits: Int,
)

/**
 * Two modules that changed together (co-changed) across the analysed commit window.
 *
 * Temporal (change) coupling is orthogonal to the static dependency graph: it measures how the
 * code actually evolves rather than what it declares. Pairs are canonicalised so [moduleA] is
 * always lexicographically less than [moduleB], giving one deterministic row per unordered pair.
 *
 * @param moduleA First module path (the lexicographically smaller of the pair).
 * @param moduleB Second module path.
 * @param sharedCommits Number of commits that touched **both** modules.
 * @param degree Coupling strength in `[0.0, 1.0]`: `sharedCommits / min(commitsA, commitsB)`.
 *   `1.0` means the less-churned module never changed without the other.
 * @param declared True when a production (non-test) dependency edge exists between the two
 *   modules in either direction - i.e. the co-change is backed by a declared structural link.
 */
@Serializable
public data class CoChange(
    val moduleA: String,
    val moduleB: String,
    val sharedCommits: Int,
    val degree: Double,
    val declared: Boolean,
)

/**
 * A declared production dependency edge, referenced by its endpoints.
 *
 * Used in [TemporalCouplingReport.deadStructure] to name edges that exist in the build graph
 * but never manifest as a co-change - candidate *dead structure* to review or remove.
 *
 * @param from Source module path.
 * @param to Target module path.
 */
@Serializable
public data class DeclaredEdgeRef(
    val from: String,
    val to: String,
)

/**
 * The result of a git temporal-coupling analysis over a window of recent commits.
 *
 * Produced by `TemporalCouplingAnalyzer` in `aalekh-analysis` from plain commit data extracted by
 * the Gradle plugin, and rendered as a local Markdown / JSON artefact by `aalekh-report`. All
 * fields default to empty so reports written by older plugin versions still deserialize cleanly.
 *
 * @param commitsAnalyzed Number of commits that mapped to at least one module (empty commits and
 *   commits touching only non-module files are excluded).
 * @param churn Per-module commit counts, sorted most-churned first. The change hotspots.
 * @param coChanges All co-changing pairs above the shared-commit threshold, strongest first.
 * @param hiddenCoupling The subset of [coChanges] that co-change strongly yet have **no** declared
 *   dependency - implicit coupling the static graph cannot see.
 * @param deadStructure Declared production edges whose endpoints both changed in the window but
 *   never co-changed - structure that may no longer reflect how the code evolves.
 */
@Serializable
public data class TemporalCouplingReport(
    val commitsAnalyzed: Int,
    val churn: List<ModuleChurn> = emptyList(),
    val coChanges: List<CoChange> = emptyList(),
    val hiddenCoupling: List<CoChange> = emptyList(),
    val deadStructure: List<DeclaredEdgeRef> = emptyList(),
) {
    public companion object {
        /** An empty report - returned when no git history is available or nothing mapped to a module. */
        public val EMPTY: TemporalCouplingReport = TemporalCouplingReport(commitsAnalyzed = 0)
    }
}
