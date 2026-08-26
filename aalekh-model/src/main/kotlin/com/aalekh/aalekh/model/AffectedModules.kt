package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * The modules impacted by a set of changed files - the *affected graph* of a diff.
 *
 * Given the files a pull request changed, Aalekh maps them to the modules that own them ([changed])
 * and then walks the dependency graph to every module that (transitively) depends on a changed one
 * ([affected]). [affected] is the "what CI must rebuild / retest" set and is always a superset of
 * [changed]. Both are sorted for deterministic, diffable output.
 *
 * @param totalModules Total modules in the graph - the denominator for "N of M affected".
 * @param changed Modules that own at least one changed file, directly.
 * @param affected Changed modules plus every production dependent reachable from them.
 */
@Serializable
public data class AffectedModules(
    val totalModules: Int,
    val changed: List<String> = emptyList(),
    val affected: List<String> = emptyList(),
) {
    public companion object {
        /** An empty result - no file mapped to a module (or nothing changed). */
        public fun none(totalModules: Int): AffectedModules = AffectedModules(totalModules)
    }
}
