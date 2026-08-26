package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.AffectedModules
import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Computes the **affected graph** for a diff: which modules a set of changed files touches, and the
 * downstream blast radius that a build must therefore rebuild and retest.
 *
 * A change to module `X` forces everything that depends on `X` to rebuild, so the affected set is
 * the changed modules plus every **production dependent** reachable from them (reverse reachability
 * over non-test edges). This turns Aalekh's static blast-radius metric into a per-pull-request
 * signal. Pure - the changed file list is supplied by the caller (the plugin reads it from
 * `git diff`).
 */
public object AffectedGraphAnalyzer {

    /**
     * Maps [changedFiles] to modules and expands to their downstream production dependents.
     *
     * @return the [AffectedModules] for this diff, or [AffectedModules.none] when no changed file
     *   falls inside a module directory.
     */
    public fun analyze(graph: ModuleDependencyGraph, changedFiles: List<String>): AffectedModules {
        val total = graph.modules.size
        if (changedFiles.isEmpty() || total == 0) return AffectedModules.none(total)

        val directories = ModuleFileIndex.directories(graph)
        val changed = ModuleFileIndex.modulesTouched(changedFiles, directories)

        // No changed file maps to a module -> both sets are empty, i.e. AffectedModules.none(total).
        val affected = sortedSetOf<String>()
        changed.forEach { module ->
            affected += module
            affected += dependentsOf(graph, module)
        }

        return AffectedModules(
            totalModules = total,
            changed = changed.sorted(),
            affected = affected.toList(),
        )
    }

    /** Every module that transitively depends on [start] via **main** (non-test) edges. */
    private fun dependentsOf(graph: ModuleDependencyGraph, start: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        fun enqueueDependents(node: String) {
            graph.edgesTo(node)
                .asSequence()
                .filter { it.from != node && !it.isTest }
                .forEach { queue.addLast(it.from) }
        }

        enqueueDependents(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (visited.add(current)) enqueueDependents(current)
        }
        return visited
    }
}
