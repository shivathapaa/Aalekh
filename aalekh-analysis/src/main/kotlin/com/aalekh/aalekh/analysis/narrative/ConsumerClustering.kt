package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Groups a module's consumers by whether they have anything else in common.
 *
 * This is the evidence behind a split recommendation. "Many modules depend on X" is not a reason to
 * split X - a foundation module is *supposed* to have many consumers. The reason to split is that its
 * consumers fall into groups with nothing else in common, which means each group is almost certainly
 * using a different part of X and dragging in the rest for nothing.
 *
 * Two consumers are placed in the same group when they share **any other** dependency besides the
 * module under examination. The groups are the connected components of that relation, computed with
 * union-find and returned in a deterministic order (each group sorted, groups ordered by their first
 * member).
 */
internal object ConsumerClustering {

    /**
     * Partitions the direct production dependents of [modulePath] into groups that share at least one
     * other dependency. A single group means the consumers are related and the module is probably
     * cohesive; several groups mean they are not.
     */
    fun groupConsumers(graph: ModuleDependencyGraph, modulePath: String): List<List<String>> {
        val consumers = graph.edgesTo(modulePath)
            .asSequence()
            .filter { !it.isTest && it.from != modulePath }
            .map { it.from }
            .distinct()
            .sorted()
            .toList()
        if (consumers.size < MIN_CONSUMERS) return listOf(consumers)

        val otherDependencies = consumers.associateWith { consumer ->
            graph.edgesFrom(consumer)
                .asSequence()
                .filter { !it.isTest && it.to != modulePath && it.to != consumer }
                .map { it.to }
                .toSet()
        }

        val parent = IntArray(consumers.size) { it }
        for (i in consumers.indices) {
            for (j in i + 1 until consumers.size) {
                val shared = otherDependencies.getValue(consumers[i])
                    .any { it in otherDependencies.getValue(consumers[j]) }
                if (shared) union(parent, i, j)
            }
        }

        return consumers.indices
            .groupBy { find(parent, it) }
            .values
            .map { indices -> indices.map { consumers[it] }.sorted() }
            .sortedBy { it.first() }
    }

    private fun find(parent: IntArray, node: Int): Int {
        var current = node
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }

    private fun union(parent: IntArray, a: Int, b: Int) {
        val rootA = find(parent, a)
        val rootB = find(parent, b)
        if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
    }

    /** Below this many consumers there is nothing to partition. */
    private const val MIN_CONSUMERS = 2
}
