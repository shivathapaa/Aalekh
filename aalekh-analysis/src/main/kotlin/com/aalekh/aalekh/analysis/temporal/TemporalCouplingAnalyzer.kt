package com.aalekh.aalekh.analysis.temporal

import com.aalekh.aalekh.analysis.graph.ModuleFileIndex
import com.aalekh.aalekh.model.CoChange
import com.aalekh.aalekh.model.DeclaredEdgeRef
import com.aalekh.aalekh.model.ModuleChurn
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.TemporalCouplingReport
import kotlin.math.round

/**
 * One commit's worth of change data: the repo-relative paths of the files it touched.
 *
 * This is the plain, Gradle-free input the temporal analysis consumes. The Gradle plugin reads it
 * from `git log` at execution time and hands it down; the analysis itself never touches git or I/O.
 *
 * @param changedFiles Repo-relative file paths (forward-slash separated) changed by this commit.
 */
public data class CommitChange(val changedFiles: List<String>)

/**
 * Derives **temporal (change) coupling** from a window of recent commits.
 *
 * Where [com.aalekh.aalekh.analysis.graph.GraphAnalyzer] reasons about the *declared* structure,
 * this analyzer reasons about how the code *actually evolves*: which modules keep changing
 * together. It surfaces three signals a static graph cannot:
 *
 * - **Hotspots** - the modules that churn the most ([TemporalCouplingReport.churn]).
 * - **Hidden coupling** - pairs that co-change strongly but declare no dependency.
 * - **Dead structure** - declared edges whose endpoints both changed yet never co-changed.
 *
 * The analysis is a pure function of `(graph, commits)`: deterministic, no I/O, no wall-clock.
 */
public object TemporalCouplingAnalyzer {

    /** Default minimum shared commits for a pair to be reported at all. */
    public const val DEFAULT_MIN_SHARED_COMMITS: Int = 2

    /** Default coupling degree at or above which an undeclared pair counts as *hidden coupling*. */
    public const val DEFAULT_HIDDEN_COUPLING_THRESHOLD: Double = 0.6

    private const val DEGREE_SCALE = 1000.0

    /**
     * Analyses temporal coupling for [graph] over [commits].
     *
     * @param minSharedCommits Pairs with fewer shared commits than this are ignored as noise.
     * @param hiddenCouplingThreshold Undeclared pairs with [CoChange.degree] at or above this are
     *   flagged as hidden coupling.
     * @return A [TemporalCouplingReport], or [TemporalCouplingReport.EMPTY] when there is nothing
     *   to analyse (no commits, no modules, or no file mapped to a module).
     */
    public fun analyze(
        graph: ModuleDependencyGraph,
        commits: List<CommitChange>,
        minSharedCommits: Int = DEFAULT_MIN_SHARED_COMMITS,
        hiddenCouplingThreshold: Double = DEFAULT_HIDDEN_COUPLING_THRESHOLD,
    ): TemporalCouplingReport {
        if (commits.isEmpty() || graph.modules.isEmpty()) return TemporalCouplingReport.EMPTY

        val directories = ModuleFileIndex.directories(graph)
        // When no commit maps to a module, every list below is empty and the result equals EMPTY.
        val touchedPerCommit = commits
            .map { commit -> ModuleFileIndex.modulesTouched(commit.changedFiles, directories) }
            .filter { it.isNotEmpty() }

        val churn = HashMap<String, Int>()
        val shared = HashMap<PairKey, Int>()
        touchedPerCommit.forEach { modules ->
            modules.forEach { churn.merge(it, 1, Int::plus) }
            accumulatePairs(modules, shared)
        }

        val declaredPairs = declaredPairs(graph)
        val coChanges = coChanges(shared, churn, declaredPairs, minSharedCommits)

        return TemporalCouplingReport(
            commitsAnalyzed = touchedPerCommit.size,
            churn = churnRanking(churn),
            coChanges = coChanges,
            hiddenCoupling = coChanges.filter { !it.declared && it.degree >= hiddenCouplingThreshold },
            deadStructure = deadStructure(graph, churn, shared),
        )
    }

    /** Increments the shared-commit count for every unordered module pair in one commit. */
    private fun accumulatePairs(modules: Set<String>, shared: MutableMap<PairKey, Int>) {
        val ordered = modules.sorted()
        for (i in ordered.indices) {
            for (j in i + 1 until ordered.size) {
                shared.merge(PairKey(ordered[i], ordered[j]), 1, Int::plus)
            }
        }
    }

    /** Production (non-test) edges as canonical unordered pairs, self-loops removed. */
    private fun declaredPairs(graph: ModuleDependencyGraph): Set<PairKey> =
        graph.edges
            .asSequence()
            .filter { !it.isTest && it.from != it.to }
            .map { PairKey.of(it.from, it.to) }
            .toSet()

    private fun coChanges(
        shared: Map<PairKey, Int>,
        churn: Map<String, Int>,
        declaredPairs: Set<PairKey>,
        minSharedCommits: Int,
    ): List<CoChange> =
        shared.asSequence()
            .filter { it.value >= minSharedCommits }
            .map { (pair, count) ->
                CoChange(
                    moduleA = pair.a,
                    moduleB = pair.b,
                    sharedCommits = count,
                    degree = degree(count, churn[pair.a], churn[pair.b]),
                    declared = pair in declaredPairs,
                )
            }
            .sortedWith(
                compareByDescending<CoChange> { it.degree }
                    .thenByDescending { it.sharedCommits }
                    .thenBy { it.moduleA }
                    .thenBy { it.moduleB }
            )
            .toList()

    private fun degree(shared: Int, churnA: Int?, churnB: Int?): Double {
        val denominator = minOf(churnA ?: shared, churnB ?: shared)
        if (denominator <= 0) return 0.0
        return round(shared.toDouble() / denominator * DEGREE_SCALE) / DEGREE_SCALE
    }

    private fun churnRanking(churn: Map<String, Int>): List<ModuleChurn> =
        churn.map { ModuleChurn(it.key, it.value) }
            .sortedWith(compareByDescending<ModuleChurn> { it.commits }.thenBy { it.module })

    /**
     * Declared production edges whose two modules both changed in the window but never together.
     * Edges where one endpoint never changed are excluded - the absence of co-change there is just
     * the absence of change, not evidence of dead structure.
     */
    private fun deadStructure(
        graph: ModuleDependencyGraph,
        churn: Map<String, Int>,
        shared: Map<PairKey, Int>,
    ): List<DeclaredEdgeRef> =
        graph.edges
            .asSequence()
            .filter { !it.isTest && it.from != it.to }
            .filter { (churn[it.from] ?: 0) > 0 && (churn[it.to] ?: 0) > 0 }
            .filter { (shared[PairKey.of(it.from, it.to)] ?: 0) == 0 }
            .map { DeclaredEdgeRef(it.from, it.to) }
            .distinct()
            .sortedWith(compareBy<DeclaredEdgeRef> { it.from }.thenBy { it.to })
            .toList()

    /** An unordered pair of module paths, canonicalised so [a] <= [b]. */
    private data class PairKey(val a: String, val b: String) {
        companion object {
            fun of(x: String, y: String): PairKey = if (x <= y) PairKey(x, y) else PairKey(y, x)
        }
    }
}
