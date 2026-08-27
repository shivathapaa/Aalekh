package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.analysis.rules.GlobMatcher
import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Produces a **subset of a module graph** for export - the machinery behind the `mermaid { }`
 * focus/exclude filters that keep a large graph's diagram readable.
 *
 * Two independent selectors, applied in order:
 * 1. **focus** - if any focus glob is given, start from the modules that match and grow outward by up
 *    to [neighborDepth] hops along dependency edges *in either direction* (dependencies and
 *    dependents), so the focused modules keep their immediate context. An empty focus list keeps
 *    every module.
 * 2. **exclude** - then drop any surviving module matching an exclude glob (e.g. `:test:**`).
 *
 * An edge is kept only when *both* of its endpoints survive. The result carries the original
 * `projectName` and `metadata`. This is a pure function - no Gradle, no I/O - so it is fully
 * unit-tested and reused unchanged by the Mermaid and DOT generators.
 */
public object GraphFilter {

    /**
     * Returns [graph] restricted by [focus] / [exclude] globs. When both lists are empty the graph is
     * returned unchanged (the common no-filter case), so existing exports are byte-for-byte identical.
     */
    public fun filter(
        graph: ModuleDependencyGraph,
        focus: List<String>,
        exclude: List<String>,
        neighborDepth: Int,
    ): ModuleDependencyGraph {
        if (focus.isEmpty() && exclude.isEmpty()) return graph
        val kept = focusedPaths(graph, focus, neighborDepth)
            .filterNot { path -> GlobMatcher.matchesAny(exclude, path) }
            .toSet()
        return ModuleDependencyGraph(
            projectName = graph.projectName,
            modules = graph.modules.filter { it.path in kept },
            edges = graph.edges.filter { it.from in kept && it.to in kept },
            metadata = graph.metadata,
        )
    }

    /** The focus seed set grown by up to [neighborDepth] undirected hops, or every path if no focus. */
    private fun focusedPaths(
        graph: ModuleDependencyGraph,
        focus: List<String>,
        neighborDepth: Int,
    ): Set<String> {
        val allPaths = graph.modules.map { it.path }
        if (focus.isEmpty()) return allPaths.toSet()
        val adjacency = undirectedAdjacency(graph)
        val kept = allPaths.filter { GlobMatcher.matchesAny(focus, it) }.toMutableSet()
        var frontier: Set<String> = kept.toSet()
        var hopsLeft = maxOf(0, neighborDepth)
        while (hopsLeft > 0 && frontier.isNotEmpty()) {
            frontier = frontier.flatMap { adjacency[it].orEmpty() }.filterTo(mutableSetOf()) { kept.add(it) }
            hopsLeft--
        }
        return kept
    }

    /** Adjacency over all edges treated as undirected, so neighbourhood growth follows both directions. */
    private fun undirectedAdjacency(graph: ModuleDependencyGraph): Map<String, Set<String>> {
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        graph.edges.forEach { edge ->
            adjacency.getOrPut(edge.from) { mutableSetOf() }.add(edge.to)
            adjacency.getOrPut(edge.to) { mutableSetOf() }.add(edge.from)
        }
        return adjacency
    }
}
