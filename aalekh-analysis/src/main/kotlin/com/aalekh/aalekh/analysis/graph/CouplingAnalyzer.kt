package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Lakos-style system-wide coupling metrics and the strongly-connected-component partition that
 * underpins them. All computation is over **main** (non-test) edges only, consistent with the rest
 * of [GraphAnalyzer]. Pure functions - no I/O, no mutation of the input.
 */
public object CouplingAnalyzer {

    /** A strongly connected component of this size or larger is a dependency cycle. */
    private const val MIN_CYCLE_SIZE = 2

    /** Multiplier to turn a 0..1 fraction into a percentage. */
    private const val PERCENT_SCALE = 100.0

    /**
     * Lakos-style system-wide coupling metrics, computed over **main** (non-test) edges only.
     *
     * - **CCD** (Cumulative Component Dependency): the sum, over every module, of the size of its
     *   dependency set (the module itself plus everything it can reach). The single number that
     *   captures how coupled the whole system is.
     * - **ACD** (Average Component Dependency): `CCD / moduleCount` - the average number of modules
     *   each module drags in, itself included.
     * - **NCCD** (Normalized CCD): `CCD` divided by the CCD of a balanced binary tree with the same
     *   module count. `~1.0` is tree-like and healthy; `> 1.0` is more tangled than a balanced tree;
     *   `< 1.0` is flatter (more independent modules).
     * - **tanglePercent**: the percentage of modules that sit inside a dependency cycle (a strongly
     *   connected component of size >= 2). `0` for a clean DAG.
     *
     * All values are 0 for an empty graph.
     */
    public fun systemCoupling(graph: ModuleDependencyGraph): SystemCoupling {
        val moduleCount = graph.modules.size
        if (moduleCount == 0) return SystemCoupling(0, 0.0, 0.0, 0.0, 0)

        var ccd = 0L
        for (module in graph.modules) {
            val reachable = mainReachableSet(graph, module.path)
            ccd += reachable.size + if (module.path in reachable) 0 else 1
        }

        val acd = ccd.toDouble() / moduleCount
        val balancedTreeCcd = balancedBinaryTreeCcd(moduleCount)
        val nccd = if (balancedTreeCcd == 0L) 0.0 else ccd.toDouble() / balancedTreeCcd

        val cyclicComponents = stronglyConnectedComponents(graph).filter { it.size >= MIN_CYCLE_SIZE }
        val tangledModuleCount = cyclicComponents.sumOf { it.size }
        val tanglePercent = tangledModuleCount.toDouble() / moduleCount * PERCENT_SCALE

        return SystemCoupling(
            ccd = ccd,
            acd = acd,
            nccd = nccd,
            tanglePercent = tanglePercent,
            cyclicComponentCount = cyclicComponents.size,
        )
    }

    /**
     * Partitions the graph into strongly connected components over **main** (non-test) edges using
     * an iterative Tarjan's algorithm - safe on graphs deep enough to blow a recursive call stack.
     * Each returned list is one component; a component of size >= 2 is a dependency cycle. Single
     * module components (the common case) are acyclic. Self-loops are ignored.
     */
    public fun stronglyConnectedComponents(graph: ModuleDependencyGraph): List<List<String>> =
        TarjanScc(graph).components()

    /**
     * The set of modules reachable from [start] by following **main** (non-test) edges. The set may
     * include [start] itself when [start] participates in a cycle. Self-loops are ignored.
     */
    private fun mainReachableSet(graph: ModuleDependencyGraph, start: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        fun enqueueSuccessors(node: String) {
            graph.edgesFrom(node)
                .asSequence()
                .filter { it.to != node && !it.isTest }
                .forEach { queue.addLast(it.to) }
        }

        enqueueSuccessors(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (visited.add(current)) enqueueSuccessors(current)
        }
        return visited
    }

    /**
     * The CCD of a perfectly balanced binary tree with [n] nodes: the sum of every node's depth
     * (1-based) when the nodes are laid out level by level. Used as the NCCD baseline.
     */
    private fun balancedBinaryTreeCcd(n: Int): Long {
        var sum = 0L
        // Node i sits at depth floor(log2(i)) + 1 in a level-filled binary tree.
        for (i in 1..n) sum += ((Integer.SIZE - 1 - Integer.numberOfLeadingZeros(i)) + 1).toLong()
        return sum
    }
}

/**
 * Lakos-style system-wide coupling numbers returned by [CouplingAnalyzer.systemCoupling].
 *
 * @param ccd Cumulative Component Dependency - sum of every module's dependency-set size.
 * @param acd Average Component Dependency - `ccd / moduleCount`.
 * @param nccd Normalized CCD against a balanced binary tree of the same size (~1.0 is tree-like).
 * @param tanglePercent Percentage of modules that sit inside a dependency cycle.
 * @param cyclicComponentCount Number of non-trivial strongly connected components (cycles).
 */
public data class SystemCoupling(
    val ccd: Long,
    val acd: Double,
    val nccd: Double,
    val tanglePercent: Double,
    val cyclicComponentCount: Int,
)

/**
 * Iterative Tarjan's strongly-connected-components over the main edges of a [ModuleDependencyGraph].
 * State is encapsulated so the DFS is expressed as a handful of small steps rather than one deeply
 * nested loop.
 */
private class TarjanScc(private val graph: ModuleDependencyGraph) {

    private class Frame(val node: String, val successors: Iterator<String>)

    private val index = HashMap<String, Int>()
    private val lowLink = HashMap<String, Int>()
    private val onStack = HashSet<String>()
    private val componentStack = ArrayDeque<String>()
    private val callStack = ArrayDeque<Frame>()
    private val components = mutableListOf<List<String>>()
    private var counter = 0

    fun components(): List<List<String>> {
        for (root in graph.modules) {
            if (root.path !in index) traverse(root.path)
        }
        return components
    }

    private fun traverse(start: String) {
        push(start)
        while (callStack.isNotEmpty()) {
            val frame = callStack.last()
            if (frame.successors.hasNext()) advance(frame) else close(frame.node)
        }
    }

    private fun advance(frame: Frame) {
        val next = frame.successors.next()
        when {
            next !in index -> push(next)
            next in onStack -> lowLink[frame.node] = minOf(lowLink.getValue(frame.node), index.getValue(next))
        }
    }

    private fun close(node: String) {
        callStack.removeLast()
        if (lowLink.getValue(node) == index.getValue(node)) emitComponent(node)
        callStack.lastOrNull()?.let { parent ->
            lowLink[parent.node] = minOf(lowLink.getValue(parent.node), lowLink.getValue(node))
        }
    }

    private fun push(node: String) {
        index[node] = counter
        lowLink[node] = counter
        counter++
        componentStack.addLast(node)
        onStack += node
        callStack.addLast(Frame(node, successors(node)))
    }

    private fun emitComponent(root: String) {
        val component = mutableListOf<String>()
        while (true) {
            val popped = componentStack.removeLast()
            onStack -= popped
            component += popped
            if (popped == root) break
        }
        components += component
    }

    private fun successors(path: String): Iterator<String> =
        graph.edgesFrom(path).asSequence().filter { it.to != path && !it.isTest }.map { it.to }.iterator()
}
