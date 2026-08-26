package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Fails when the longest chain of production dependencies (the graph *height*) exceeds [maxHeight].
 *
 * Graph height is the number of modules on the critical path - the single longest sequence of
 * `A depends on B depends on C ...` edges. It is the hard floor on how much of the build can run
 * in parallel: a height of 8 means at least 8 sequential compile steps no matter how many cores
 * are available. Capping it keeps the module graph shallow and the build fast.
 *
 * Only **main** edges are considered. A graph with a production cycle has no well-defined height
 * (no topological order), so the rule produces no violation in that case - the cycle rule handles it.
 */
internal class MaxGraphHeightRule(private val maxHeight: Int) : ArchRule {

    override val id = "max-graph-height"
    override val description = "The module graph's longest dependency chain must not exceed $maxHeight modules."
    override val defaultSeverity = Severity.WARNING
    override val plainLanguageExplanation =
        "The longest dependency chain sets the minimum number of sequential build steps, " +
                "so a tall graph slows every build regardless of available cores. Flatten it by " +
                "depending on shared abstractions directly instead of chaining through intermediates."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        val criticalPath = GraphAnalyzer.criticalPath(graph)
        val height = criticalPath.size
        if (height <= maxHeight) return emptyList()

        return listOf(
            Violation(
                ruleId = id,
                severity = defaultSeverity,
                message = "Module graph height is $height (limit: $maxHeight). Longest chain: " +
                        "${criticalPath.joinToString(" → ")}. Shorten it by collapsing intermediate " +
                        "modules or depending on shared abstractions directly.",
                source = criticalPath.joinToString(" → "),
                moduleHint = criticalPath.lastOrNull(),
                plainLanguageExplanation = plainLanguageExplanation,
            )
        )
    }
}
