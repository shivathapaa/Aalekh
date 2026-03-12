package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * The complete module dependency graph for a Gradle project.
 *
 * This is the **central data structure** that flows through the entire Aalekh pipeline:
 *
 * ```
 * Gradle Project Model
 *   → [GraphExtractor]
 *   → ModuleDependencyGraph (this class)
 *   → [RuleEngine]  → List<Violation>
 *   → [HtmlReportGenerator / JUnitXmlWriter / JsonReporter]
 * ```
 *
 * All fields are serializable to JSON for tooling interoperability and
 * configuration-cache compatibility. All operations are pure - no I/O, no mutation.
 *
 * @param projectName Root project name from `settings.gradle.kts`
 * @param modules     All subproject modules discovered in the build
 * @param edges       All inter-module dependency relationships
 * @param metadata    Build context: Gradle version, AGP version, extraction timestamp, etc.
 */
@Serializable
public data class ModuleDependencyGraph(
    val projectName: String,
    val modules: List<ModuleNode>,
    val edges: List<DependencyEdge>,
    val metadata: Map<String, String> = emptyMap(),
) {
    // Index (lazy, computed once)
    private val moduleIndex: Map<String, ModuleNode> by lazy {
        modules.associateBy { it.path }
    }

    /** Finds a module by its Gradle project path, or null if not found.*/
    public fun moduleByPath(path: String): ModuleNode? = moduleIndex[path]

    /** All edges leaving a module (what it directly depends on).*/
    public fun edgesFrom(path: String): List<DependencyEdge> =
        edges.filter { it.from == path }

    /** All edges arriving at a module (what directly depends on it).*/
    public fun edgesTo(path: String): List<DependencyEdge> =
        edges.filter { it.to == path }

    // Structural metrics (used by GraphAnalyzer and HTML sidebar)
    /** Fan-out: number of modules this module directly depends on.*/
    public fun fanOut(path: String): Int = edgesFrom(path).count { !it.isTest }

    /** Fan-in: number of modules that directly depend on this one.*/
    public fun fanIn(path: String): Int = edgesTo(path).count { !it.isTest }

    /**
     * Instability index: `fanOut / (fanIn + fanOut)`.
     * Range: 0.0 (maximally stable) to 1.0 (maximally unstable).
     * Core/domain modules should be near 0.0; leaf feature modules near 1.0.
     */
    public fun instability(path: String): Double {
        val out = fanOut(path).toDouble()
        val inn = fanIn(path).toDouble()
        return if (out + inn == 0.0) 0.0 else out / (out + inn)
    }

    /**
     * All modules reachable by following edges from [path] (BFS).
     * Returns paths only - excludes [path] itself.
     */
    public fun transitiveDependencies(path: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        // Seed with direct deps (not self)
        edgesFrom(path).forEach { queue.plusAssign(it.to) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (visited.add(current)) {
                edgesFrom(current).forEach { queue.plusAssign(it.to) }
            }
        }
        return visited
    }

    /** Number of transitively reachable modules from [path].*/
    public fun transitiveCount(path: String): Int = transitiveDependencies(path).size

    /**
     * Returns true if the graph contains at least one cycle.
     * Uses iterative DFS with an explicit recursion stack to avoid stack overflow
     * on large project graphs.
     */
    public fun hasCycle(): Boolean {
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun dfs(node: String): Boolean {
            if (node in stack) return true
            if (node in visited) return false
            visited += node
            stack += node
            val hasCycle = edgesFrom(node)
                .filter { it.to != node }  // skip self-loops
                .any { dfs(it.to) }
            stack -= node
            return hasCycle
        }

        return modules.any { dfs(it.path) }
    }

    /**
     * Finds all cycles in the graph and returns them as lists of module paths.
     * Returns an empty list if the graph is acyclic.
     */
    public fun findCycles(): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        val onPath = mutableSetOf<String>()

        fun dfs(node: String) {
            if (node in onPath) {
                val cycleStart = path.indexOf(node)
                if (cycleStart >= 0) {
                    val cycle = path.subList(cycleStart, path.size).toList()
                    if (cycle.size >= 2) cycles += cycle  // skip self-loops (size 1)
                }
                return
            }
            if (node in visited) return
            visited += node
            onPath += node
            path += node
            edgesFrom(node).forEach { dfs(it.to) }
            path.removeLast()
            onPath -= node
        }

        modules.forEach { dfs(it.path) }
        return cycles
    }
}