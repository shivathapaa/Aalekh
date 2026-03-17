package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import kotlinx.serialization.Serializable

/**
 * Stateless graph analysis algorithms that operate on a [ModuleDependencyGraph].
 *
 * All functions are pure - no side effects, no I/O, no mutation.
 * This is the computational heart of Aalekh: every metric, every insight,
 * every rule evaluation starts here.
 */
public object GraphAnalyzer {
    /**
     * Returns modules in topological order (dependencies before dependents).
     * Throws [IllegalStateException] if the graph contains cycles.
     * Uses Kahn's algorithm (BFS-based) for deterministic ordering.
     *
     * Only **main** (non-test) edges are considered - consistent with [fanIn]/[fanOut].
     */
    public fun topologicalOrder(graph: ModuleDependencyGraph): List<ModuleNode> {
        val inDegree = graph.modules.associate { it.path to graph.fanIn(it.path) }.toMutableMap()
        val queue = ArrayDeque(graph.modules.filter { inDegree[it.path] == 0 })
        val result = mutableListOf<ModuleNode>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result += node
            graph.edgesFrom(node.path)
                .filter { !it.isTest }
                .forEach { edge ->
                    inDegree[edge.to] = (inDegree[edge.to] ?: 0) - 1
                    if (inDegree[edge.to] == 0) {
                        graph.moduleByPath(edge.to)?.let { queue += it }
                    }
                }
        }

        check(result.size == graph.modules.size) {
            "Graph contains cycles - cannot produce a topological ordering. " +
                    "Run findCycles() first to identify them."
        }
        return result
    }

    /**
     * Returns the longest dependency chain (critical path) in the project.
     * Useful for understanding which modules most constrain build parallelism.
     *
     * Only **main** (non-test) edges are followed - test edges do not affect
     * production build order.
     */
    public fun criticalPath(graph: ModuleDependencyGraph): List<String> {
        val dist = mutableMapOf<String, Int>()
        val prev = mutableMapOf<String, String?>()

        // Process in topological order so we always have the answer for deps before dependents
        val ordered = try {
            topologicalOrder(graph)
        } catch (_: IllegalStateException) {
            return emptyList()
        }

        ordered.forEach { node ->
            dist[node.path] = 0
            prev[node.path] = null
        }

        ordered.forEach { node ->
            graph.edgesFrom(node.path)
                .filter { !it.isTest }
                .forEach { edge ->
                    val newDist = (dist[node.path] ?: 0) + 1
                    if (newDist > (dist[edge.to] ?: 0)) {
                        dist[edge.to] = newDist
                        prev[edge.to] = node.path
                    }
                }
        }

        // Reconstruct path from the node with maximum depth
        val deepest = dist.maxByOrNull { it.value }?.key ?: return emptyList()
        val path = mutableListOf<String>()
        var current: String? = deepest
        while (current != null) {
            path.add(0, current)
            current = prev[current]
        }
        return path
    }

    /**
     * Identifies "god modules" - modules with both high fan-in AND high fan-out.
     * These are architectural hotspots that are hard to change and test.
     *
     * @param fanInThreshold  Minimum fan-in to be considered a god module (default: 5)
     * @param fanOutThreshold Minimum fan-out to be considered a god module (default: 5)
     */
    public fun godModules(
        graph: ModuleDependencyGraph,
        fanInThreshold: Int = 5,
        fanOutThreshold: Int = 5,
    ): List<ModuleNode> = graph.modules.filter { module ->
        graph.fanIn(module.path) >= fanInThreshold &&
                graph.fanOut(module.path) >= fanOutThreshold
    }

    /**
     * Identifies "leaf modules" - modules with no outgoing production dependencies.
     * These should ideally be domain/model modules.
     */
    public fun leafModules(graph: ModuleDependencyGraph): List<ModuleNode> =
        graph.modules.filter { graph.fanOut(it.path) == 0 }

    /**
     * Identifies "root modules" - modules that nobody depends on.
     * There should typically be exactly one (the app module).
     */
    public fun rootModules(graph: ModuleDependencyGraph): List<ModuleNode> =
        graph.modules.filter { graph.fanIn(it.path) == 0 }

    /**
     * Identifies "isolated modules" - modules with neither dependents nor dependencies.
     * These are candidates for removal.
     */
    public fun isolatedModules(graph: ModuleDependencyGraph): List<ModuleNode> =
        graph.modules.filter { graph.fanIn(it.path) == 0 && graph.fanOut(it.path) == 0 }

    /**
     * Returns all pairs of modules that share a significant number of common dependents,
     * suggesting they might be tightly coupled and should be merged or extracted.
     *
     * @param sharedDependentThreshold Minimum shared dependents to flag as coupled
     */
    public fun potentiallyCoupledModules(
        graph: ModuleDependencyGraph,
        sharedDependentThreshold: Int = 3,
    ): List<Pair<ModuleNode, ModuleNode>> {
        val result = mutableListOf<Pair<ModuleNode, ModuleNode>>()
        val modules = graph.modules

        for (i in modules.indices) {
            for (j in i + 1 until modules.size) {
                val a = modules[i]
                val b = modules[j]
                val aDependents = graph.edgesTo(a.path).map { it.from }.toSet()
                val bDependents = graph.edgesTo(b.path).map { it.from }.toSet()
                val shared = aDependents intersect bDependents
                if (shared.size >= sharedDependentThreshold) {
                    result += a to b
                }
            }
        }
        return result
    }

    /**
     * Computes a summary of the graph suitable for the report header and metadata.
     * Cycle detection uses **main-only** edges (test deps excluded).
     */
    public fun summary(graph: ModuleDependencyGraph): GraphSummary {
        val mainOnlyCycles = findMainOnlyCycles(graph)
        return GraphSummary(
            totalModules = graph.modules.size,
            totalEdges = graph.edges.size,
            modulesByType = graph.modules.groupBy { it.type.name }.mapValues { it.value.size },
            hasCycles = mainOnlyCycles.isNotEmpty(),
            cycleCount = mainOnlyCycles.size,
            maxFanOut = graph.modules.maxOfOrNull { graph.fanOut(it.path) } ?: 0,
            maxFanIn = graph.modules.maxOfOrNull { graph.fanIn(it.path) } ?: 0,
            averageInstability = graph.modules
                .map { graph.instability(it.path) }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0,
            criticalPathLength = criticalPath(graph).size,
            godModuleCount = godModules(graph).size,
            isolatedModuleCount = isolatedModules(graph).size,
        )
    }

    /**
     * Finds cycles using only main (non-test) edges.
     * Test dependencies (testImplementation, etc.) are excluded because
     * test code legitimately creates apparent cycles that aren't real.
     */
    public fun findMainOnlyCycles(graph: ModuleDependencyGraph): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        val onPath = mutableSetOf<String>()

        fun dfs(node: String) {
            if (node in onPath) {
                val start = path.indexOf(node)
                if (start >= 0) {
                    val cycle = path.subList(start, path.size).toList()
                    if (cycle.size >= 2) cycles += cycle
                }
                return
            }
            if (node in visited) return
            visited += node; onPath += node; path += node
            graph.edgesFrom(node)
                .filter { it.to != node && !it.isTest }
                .forEach { dfs(it.to) }
            path.removeAt(path.size - 1)
            onPath -= node
        }

        graph.modules.forEach { if (it.path !in visited) dfs(it.path) }
        return cycles
    }
}

@Serializable
data class GraphSummary(
    val totalModules: Int,
    val totalEdges: Int,
    val modulesByType: Map<String, Int>,  // String keys for clean JSON output
    val hasCycles: Boolean,
    val cycleCount: Int,
    val maxFanOut: Int,
    val maxFanIn: Int,
    val averageInstability: Double,
    val criticalPathLength: Int,
    val godModuleCount: Int,
    val isolatedModuleCount: Int,
)