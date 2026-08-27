package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Production-only transitive reachability, shared by the reachability rules.
 *
 * [ModuleDependencyGraph.transitiveDependencies] follows every edge including test edges;
 * the architecture rules must reason about production structure only (the same `!isTest`
 * distinction the cycle and layer rules keep). This helper walks the graph over production
 * edges exclusively.
 */
internal object GraphReachability {

    /**
     * All modules reachable from [start] by following production (non-test) edges. BFS; the
     * returned set excludes [start] itself. A self-loop edge is skipped so it never appears
     * in the result.
     */
    fun productionReachable(graph: ModuleDependencyGraph, start: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        graph.edgesFrom(start).asSequence()
            .filter { !it.isTest && it.to != start }
            .forEach { queue.addLast(it.to) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (visited.add(current)) {
                graph.edgesFrom(current).asSequence()
                    .filter { !it.isTest && it.to != current }
                    .forEach { queue.addLast(it.to) }
            }
        }
        return visited
    }
}
