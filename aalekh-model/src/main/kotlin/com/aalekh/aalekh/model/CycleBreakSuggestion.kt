package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A concrete, actionable suggestion for breaking a dependency cycle: the single edge to remove.
 *
 * Aalekh detects cycles; this names *what to do about them*. For each strongly connected component
 * (cycle), a feedback-arc-set heuristic picks a small set of edges whose removal makes the component
 * acyclic. Each such edge becomes one suggestion, pointing at the exact declaration to delete.
 *
 * Because minimum feedback arc set is NP-hard, this is a good greedy approximation - a *suggested*
 * cut, not a proven-minimal one. Removing every suggested edge for a component is guaranteed to break
 * all of its cycles.
 *
 * @param from Source module of the edge to remove (the module whose build file you edit).
 * @param to Target module the dependency points at.
 * @param configuration The Gradle configuration the edge was declared on, e.g. `"implementation"`.
 * @param buildFilePath Relative path to [from]'s build file, e.g. `"feature/login/data/build.gradle.kts"`.
 *   Null when it could not be resolved.
 * @param declarationLine 1-based line of the declaration in [buildFilePath], when known; else null.
 * @param cycleSize Number of modules in the cycle (strongly connected component) this edge breaks.
 */
@Serializable
public data class CycleBreakSuggestion(
    val from: String,
    val to: String,
    val configuration: String,
    val buildFilePath: String? = null,
    val declarationLine: Int? = null,
    val cycleSize: Int,
)
