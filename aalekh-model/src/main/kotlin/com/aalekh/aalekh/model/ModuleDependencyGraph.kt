package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * The complete module dependency graph for a Gradle project.
 *
 * This is the **central data structure** that flows through the entire Aalekh pipeline:
 *
 * ```
 * Gradle Project Model
 *   → aalekh-gradle extraction (AalekhExtractTask)
 *   → ModuleDependencyGraph (this class)
 *   → RuleEngine  → List<Violation>
 *   → HtmlReportGenerator / JUnitXmlWriter / JsonReporter / SarifReporter
 * ```
 *
 * All fields are serializable to JSON for tooling interoperability and
 * configuration-cache compatibility. All operations are pure - no I/O, no mutation.
 *
 * @param projectName Root project name from `settings.gradle.kts`
 * @param modules     All subproject modules discovered in the build
 * @param edges       All inter-module dependency relationships
 * @param externalDependencies All declared external (third-party) dependencies, keyed by declaring
 *   module via [ExternalDependency.module]. Empty when external-dependency capture is disabled or
 *   when deserializing a graph file produced by an older plugin version.
 * @param buildInventory How the project is *built* rather than how it is wired: plugins and their
 *   versions, version catalogs, toolchains, KMP targets, test layout, CODEOWNERS, and any metadata
 *   the team declared in `.aalekh/modules.json`. [BuildInventory.EMPTY] when deserializing a graph
 *   file produced by an older plugin version.
 * @param metadata    Build context: Gradle version, AGP version, extraction timestamp, etc.
 */
// The query surface (moduleByPath, edgesFrom/To, fanIn/Out, instability, transitive*, cycle
// helpers, externalDependenciesOf) is the central graph API every consumer reads through; the
// method count reflects that breadth, not a class doing unrelated jobs.
@Suppress("TooManyFunctions")
@Serializable
public data class ModuleDependencyGraph(
    val projectName: String,
    val modules: List<ModuleNode>,
    val edges: List<DependencyEdge>,
    val externalDependencies: List<ExternalDependency> = emptyList(),
    val buildInventory: BuildInventory = BuildInventory.EMPTY,
    val metadata: Map<String, String> = emptyMap(),
) {
    // Indices (lazy, computed once). Avoid O(E) scans inside hot graph algorithms;
    // for graphs with thousands of modules the linear filter would dominate analysis time.
    private val moduleIndex: Map<String, ModuleNode> by lazy {
        modules.associateBy { it.path }
    }
    private val edgesFromIndex: Map<String, List<DependencyEdge>> by lazy {
        edges.groupBy { it.from }
    }
    private val edgesToIndex: Map<String, List<DependencyEdge>> by lazy {
        edges.groupBy { it.to }
    }
    private val externalDepsByModule: Map<String, List<ExternalDependency>> by lazy {
        externalDependencies.groupBy { it.module }
    }

    /** Finds a module by its Gradle project path, or null if not found.*/
    public fun moduleByPath(path: String): ModuleNode? = moduleIndex[path]

    /** All external (third-party) dependencies declared by a module.*/
    public fun externalDependenciesOf(path: String): List<ExternalDependency> =
        externalDepsByModule[path] ?: emptyList()

    /** All edges leaving a module (what it directly depends on).*/
    public fun edgesFrom(path: String): List<DependencyEdge> =
        edgesFromIndex[path] ?: emptyList()

    /** All edges arriving at a module (what directly depends on it).*/
    public fun edgesTo(path: String): List<DependencyEdge> =
        edgesToIndex[path] ?: emptyList()

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
     *
     * Iterative DFS over an explicit frame stack of outgoing-edge iterators - safe on graphs
     * deep enough to blow a recursive call stack. Self-loops are skipped at the edge level so
     * a `project(":self")` declaration does not register as a cycle.
     */
    public fun hasCycle(): Boolean {
        val visited = mutableSetOf<String>()
        val onStack = mutableSetOf<String>()
        val frames = ArrayDeque<Iterator<String>>()
        val nodes = ArrayDeque<String>()

        for (root in modules) {
            if (root.path in visited) continue
            nodes.addLast(root.path)
            onStack += root.path
            frames.addLast(edgesFrom(root.path).map { it.to }.iterator())
            while (frames.isNotEmpty()) {
                val it = frames.last()
                val current = nodes.last()
                if (it.hasNext()) {
                    val next = it.next()
                    if (next == current) continue  // self-loop
                    if (next in onStack) return true
                    if (next in visited) continue
                    nodes.addLast(next)
                    onStack += next
                    frames.addLast(edgesFrom(next).map { it.to }.iterator())
                } else {
                    val leaving = nodes.removeLast()
                    onStack -= leaving
                    visited += leaving
                    frames.removeLast()
                }
            }
        }
        return false
    }

    /**
     * Finds all cycles in the graph and returns them as lists of module paths.
     * Returns an empty list if the graph is acyclic.
     *
     * Iterative DFS - safe on graphs deep enough to blow a recursive call stack. Each
     * back-edge to an on-stack node emits the sub-path between that node and the current
     * frame as one cycle. Cycles of length 1 (self-loops) are filtered out.
     */
    public fun findCycles(): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val pathList = mutableListOf<String>()
        val onPath = mutableSetOf<String>()
        val frames = ArrayDeque<Iterator<String>>()

        for (root in modules) {
            if (root.path in visited || root.path in onPath) continue
            pathList += root.path
            onPath += root.path
            frames.addLast(edgesFrom(root.path).map { it.to }.iterator())
            while (frames.isNotEmpty()) {
                val it = frames.last()
                val current = pathList.last()
                if (it.hasNext()) {
                    val next = it.next()
                    if (next == current) continue  // self-loop, size-1 cycle filtered anyway
                    if (next in onPath) {
                        val start = pathList.indexOf(next)
                        if (start >= 0) {
                            val cycle = pathList.subList(start, pathList.size).toList()
                            if (cycle.size >= 2) cycles += cycle
                        }
                        continue
                    }
                    if (next in visited) continue
                    pathList += next
                    onPath += next
                    frames.addLast(edgesFrom(next).map { it.to }.iterator())
                } else {
                    val leaving = pathList.removeAt(pathList.size - 1)
                    onPath -= leaving
                    visited += leaving
                    frames.removeLast()
                }
            }
        }
        return cycles
    }
}