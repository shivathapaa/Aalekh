package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Everything Aalekh knows about one module's position in the dependency graph.
 *
 * @param path Module Gradle path.
 * @param fanIn Modules that directly depend on this one.
 * @param fanOut Modules this one directly depends on.
 * @param instability `fanOut / (fanIn + fanOut)` - 0 stable, 1 unstable.
 * @param transitiveDependencies Modules reachable by following dependencies. Also the module's
 *   *comprehension cost*: how much of the project you must hold in your head to understand it.
 * @param blastRadius Modules that transitively depend on this one - what a breaking change here
 *   forces to rebuild, retest, and review.
 * @param blastRadiusPercent [blastRadius] as a share of the project, in `[0, 100]`.
 * @param influence PageRank over the dependency graph, normalised so the mean module scores 1.0.
 *   Unlike raw fan-in it weighs *who* depends on you: being depended on by the app matters more than
 *   being depended on by a leaf.
 * @param betweenness Share of shortest dependency paths that run through this module, in `[0, 1]`.
 *   High betweenness marks a choke point - a module most of the project's structure routes through.
 * @param depthFromEntry Shortest hop count from the nearest entry point, or -1 when unreachable from
 *   any of them. The natural reading order for someone new to the codebase.
 * @param apiSurfaceRatio Share of this module's outgoing production edges declared `api`, in
 *   `[0, 1]` - how much of what it depends on it re-exports onto its own consumers.
 * @param isArticulationPoint True when removing this module would split the project into
 *   disconnected pieces. A structural bottleneck rather than merely a busy one.
 * @param isEntryPoint True when nothing depends on this module - a place execution starts.
 * @param isFoundation True when this module depends on nothing - the bottom of the graph.
 */
public data class ModuleGraphMetrics(
    val path: String,
    val fanIn: Int,
    val fanOut: Int,
    val instability: Double,
    val transitiveDependencies: Int,
    val blastRadius: Int,
    val blastRadiusPercent: Double,
    val influence: Double,
    val betweenness: Double,
    val depthFromEntry: Int,
    val apiSurfaceRatio: Double,
    val isArticulationPoint: Boolean,
    val isEntryPoint: Boolean,
    val isFoundation: Boolean,
)

/**
 * A dependency that points the wrong way along the stability gradient.
 *
 * Robert Martin's Stable Dependencies Principle: depend in the direction of stability. When a stable
 * module (many dependents, few dependencies) depends on an unstable one, every change to the volatile
 * module ripples into something the rest of the project relies on. Inverting the dependency - putting
 * an interface in the stable module and implementing it in the unstable one - removes the ripple.
 *
 * @param from The more stable module, the one doing the depending.
 * @param to The less stable module it depends on.
 * @param fromInstability Instability of [from].
 * @param toInstability Instability of [to], always greater than [fromInstability].
 */
public data class StabilityViolation(
    val from: String,
    val to: String,
    val fromInstability: Double,
    val toInstability: Double,
)

/**
 * Whole-graph metrics that describe the shape of the project rather than any one module.
 *
 * @param moduleCount Modules in the graph.
 * @param fanInGini Concentration of fan-in across modules, in `[0, 1]`. 0 means every module is
 *   depended on equally; approaching 1 means a handful of modules absorb nearly all dependencies.
 * @param maxDepth Longest shortest-path from an entry point - how many layers deep the project goes.
 * @param entryPoints Modules nothing depends on, the ones reaching furthest into the project
 *   first. A build has several - an app shell, a benchmark, a test-support module - and only the
 *   ones with real reach are worth naming.
 * @param foundation Modules that depend on nothing, sorted.
 * @param articulationPoints Modules whose removal disconnects the graph, sorted.
 * @param stabilityViolations Edges pointing against the stability gradient, worst gap first.
 */
public data class ProjectGraphMetrics(
    val moduleCount: Int,
    val fanInGini: Double,
    val maxDepth: Int,
    val entryPoints: List<String>,
    val foundation: List<String>,
    val articulationPoints: List<String>,
    val stabilityViolations: List<StabilityViolation>,
)

/**
 * The complete metric set for a graph: per-module and whole-project, computed together.
 *
 * @param modules Per-module metrics, keyed by path.
 * @param project Whole-graph metrics.
 */
public data class GraphMetricSet(
    val modules: Map<String, ModuleGraphMetrics>,
    val project: ProjectGraphMetrics,
) {
    /** Metrics for one module, or null when it is not in the graph. */
    public fun of(path: String): ModuleGraphMetrics? = modules[path]

    /**
     * The percentile rank of [path] for a metric, in `[0, 100]` - the share of modules scoring at or
     * below it. Percentiles travel across projects in a way absolute thresholds do not: "99th
     * percentile fan-in *for this project*" means something on a 20-module build and a 900-module one.
     */
    public fun percentileOf(path: String, selector: (ModuleGraphMetrics) -> Double): Double {
        // An unknown path also covers the empty-map case, so there is no separate size guard.
        val target = modules[path]?.let(selector) ?: return 0.0
        return modules.values.count { selector(it) <= target } * PERCENT / modules.size.toDouble()
    }

    private companion object {
        const val PERCENT = 100.0
    }
}

/**
 * Computes every structural metric Aalekh derives from the dependency graph, in one pass.
 *
 * Metrics are computed together rather than on demand because they share expensive intermediates -
 * the index maps, the reachability closures, the shortest-path trees. Computing them one at a time
 * would redo that work for every caller, which is what made the naive per-module helpers on
 * [ModuleDependencyGraph] unsuitable for large graphs.
 *
 * Everything here is **main-edge only** (test dependencies excluded) and deterministic: iteration
 * order is fixed by sorted module order, never by hash order, so the same graph always yields the
 * same numbers and the same tie-breaks. Pure - no I/O, no mutation of the input.
 */
public object GraphMetrics {

    /** Damping factor for [ModuleGraphMetrics.influence]; the standard PageRank value. */
    private const val DAMPING = 0.85

    /** PageRank iterations. Converges well before this on dependency graphs, which are shallow. */
    private const val PAGERANK_ITERATIONS = 30

    /**
     * Above this module count, betweenness is estimated from a sample of source modules rather than
     * all of them. Exact Brandes is `O(V * E)`, which is fine for a few hundred modules and wasteful
     * for a few thousand when the ranking barely moves.
     */
    private const val EXACT_BETWEENNESS_LIMIT = 400

    /** Source modules sampled when the graph exceeds [EXACT_BETWEENNESS_LIMIT]. */
    private const val BETWEENNESS_SAMPLE = 400

    private const val PERCENT = 100.0

    /** A distribution needs at least two values before concentration means anything. */
    private const val MIN_GINI_VALUES = 2

    /** Computes the full metric set for [graph]. Returns empty metrics for an empty graph. */
    public fun compute(graph: ModuleDependencyGraph): GraphMetricSet {
        val index = GraphIndex(graph)
        if (index.size == 0) return emptySet(index)

        val transitive = index.forwardReachCounts()
        val blast = index.reverseReachCounts()
        val influence = index.pageRank()
        val betweenness = index.betweenness()
        val depth = index.depthFromEntryPoints()
        val articulation = index.articulationPoints()

        val modules = (0 until index.size).associate { i ->
            val path = index.pathOf(i)
            val fanIn = index.reverse[i].size
            val fanOut = index.forward[i].size
            path to ModuleGraphMetrics(
                path = path,
                fanIn = fanIn,
                fanOut = fanOut,
                instability = instability(fanIn, fanOut),
                transitiveDependencies = transitive[i],
                blastRadius = blast[i],
                blastRadiusPercent = blast[i] * PERCENT / index.size,
                influence = influence[i],
                betweenness = betweenness[i],
                depthFromEntry = depth[i],
                apiSurfaceRatio = index.apiRatio(i),
                isArticulationPoint = i in articulation,
                // A module wired to nothing at all is not an entry point and not a foundation - it
                // is isolated. Gradle creates an empty parent project for every nested path, so
                // ":core" exists alongside ":core:domain" with no dependencies in either direction;
                // calling those "places execution starts" would be actively misleading.
                isEntryPoint = fanIn == 0 && fanOut > 0,
                isFoundation = fanOut == 0 && fanIn > 0,
            )
        }

        return GraphMetricSet(
            modules = modules,
            project = ProjectGraphMetrics(
                moduleCount = index.size,
                fanInGini = gini(modules.values.map { it.fanIn.toDouble() }),
                maxDepth = depth.max(),
                entryPoints = modules.values.filter { it.isEntryPoint }
                    .sortedWith(compareByDescending<ModuleGraphMetrics> { it.transitiveDependencies }
                        .thenBy { it.path })
                    .map { it.path },
                foundation = modules.values.filter { it.isFoundation }.map { it.path }.sorted(),
                articulationPoints = articulation.map { index.pathOf(it) }.sorted(),
                stabilityViolations = stabilityViolations(index, modules),
            ),
        )
    }

    private fun emptySet(index: GraphIndex): GraphMetricSet = GraphMetricSet(
        modules = emptyMap(),
        project = ProjectGraphMetrics(
            moduleCount = index.size,
            fanInGini = 0.0,
            maxDepth = 0,
            entryPoints = emptyList(),
            foundation = emptyList(),
            articulationPoints = emptyList(),
            stabilityViolations = emptyList(),
        ),
    )

    private fun instability(fanIn: Int, fanOut: Int): Double {
        val total = fanIn + fanOut
        return if (total == 0) 0.0 else fanOut.toDouble() / total
    }

    /**
     * The Gini coefficient of a distribution, in `[0, 1]`. Used for fan-in concentration: whether
     * dependency is spread evenly or absorbed by a few modules.
     */
    internal fun gini(values: List<Double>): Double {
        val sorted = values.sorted()
        val total = sorted.sum()
        // One value has no spread to measure, and an all-zero distribution has no share to divide.
        if (sorted.size < MIN_GINI_VALUES || total <= 0.0) return 0.0
        var weighted = 0.0
        sorted.forEachIndexed { i, v -> weighted += (i + 1) * v }
        val n = sorted.size
        return (2 * weighted) / (n * total) - (n + 1.0) / n
    }

    private fun stabilityViolations(
        index: GraphIndex,
        modules: Map<String, ModuleGraphMetrics>,
    ): List<StabilityViolation> = index.mainEdgePairs()
        .mapNotNull { (fromIdx, toIdx) ->
            val from = modules.getValue(index.pathOf(fromIdx))
            val to = modules.getValue(index.pathOf(toIdx))
            if (from.instability >= to.instability) return@mapNotNull null
            StabilityViolation(from.path, to.path, from.instability, to.instability)
        }
        .sortedWith(
            compareByDescending<StabilityViolation> { it.toInstability - it.fromInstability }
                .thenBy { it.from }
                .thenBy { it.to }
        )

    /**
     * Integer-indexed adjacency over the graph's **main** edges.
     *
     * Every algorithm below walks the graph many times; doing that over `Map<String, List<Edge>>`
     * pays string hashing on every hop. Indices are assigned in sorted path order, so all output is
     * deterministic.
     */
    // Every function here is one step of the same traversal machinery over one shared adjacency
    // representation; splitting them across classes would mean rebuilding or passing that
    // representation around, which is the cost this class exists to avoid.
    @Suppress("TooManyFunctions")
    private class GraphIndex(private val graph: ModuleDependencyGraph) {
        private val paths: List<String> = graph.modules.map { it.path }.sorted()
        private val indexOf: Map<String, Int> = paths.withIndex().associate { (i, p) -> p to i }

        val size: Int = paths.size
        val forward: Array<IntArray>
        val reverse: Array<IntArray>

        init {
            val out = Array(size) { mutableSetOf<Int>() }
            val inn = Array(size) { mutableSetOf<Int>() }
            graph.edges.asSequence()
                .filter { !it.isTest && it.from != it.to }
                .forEach { edge ->
                    val f = indexOf[edge.from] ?: return@forEach
                    val t = indexOf[edge.to] ?: return@forEach
                    out[f] += t
                    inn[t] += f
                }
            forward = Array(size) { out[it].sorted().toIntArray() }
            reverse = Array(size) { inn[it].sorted().toIntArray() }
        }

        fun pathOf(i: Int): String = paths[i]

        fun mainEdgePairs(): List<Pair<Int, Int>> =
            (0 until size).flatMap { f -> forward[f].map { t -> f to t } }

        /** Share of a module's outgoing production edges declared `api`. */
        fun apiRatio(i: Int): Double {
            val outgoing = graph.edgesFrom(paths[i]).filter { !it.isTest && it.from != it.to }
            if (outgoing.isEmpty()) return 0.0
            return outgoing.count { it.isApi }.toDouble() / outgoing.size
        }

        fun forwardReachCounts(): IntArray = reachCounts(forward)
        fun reverseReachCounts(): IntArray = reachCounts(reverse)

        /**
         * Number of **other** nodes reachable from each node.
         *
         * The start node is stamped before the walk begins, so a cycle that leads back to it never
         * counts it as one of its own dependencies - a module is not a dependency of itself, even
         * when the graph says you can get there from here.
         *
         * One BFS per node over a reused visit-stamp array: `O(V * (V + E))` without the allocation
         * churn of a fresh visited set per node, which dominates at a few hundred modules.
         */
        private fun reachCounts(adjacency: Array<IntArray>): IntArray {
            val counts = IntArray(size)
            val stamp = IntArray(size) { -1 }
            val queue = IntArray(size)
            for (start in 0 until size) {
                stamp[start] = start
                counts[start] = reachedFrom(adjacency, start, stamp, queue)
            }
            return counts
        }

        /**
         * Breadth-first walk from [start], returning how many distinct other nodes it reached.
         *
         * [stamp] and [queue] are caller-owned scratch arrays reused across every start node;
         * stamping with the start index rather than clearing a visited set is what keeps this
         * allocation-free across `V` walks.
         */
        private fun reachedFrom(
            adjacency: Array<IntArray>,
            start: Int,
            stamp: IntArray,
            queue: IntArray,
        ): Int {
            var head = 0
            var tail = 0

            fun expand(node: Int) {
                adjacency[node].forEach { next ->
                    if (stamp[next] != start) {
                        stamp[next] = start
                        queue[tail++] = next
                    }
                }
            }

            expand(start)
            while (head < tail) expand(queue[head++])
            return tail
        }

        /**
         * PageRank over the dependency edges, normalised so the mean module scores 1.0.
         *
         * Rank flows along `dependent -> dependency`, so it accumulates in the modules the rest of
         * the project rests on. Dangling nodes (modules that depend on nothing) redistribute their
         * rank uniformly, the standard treatment - otherwise rank leaks out of the foundation, which
         * is exactly where it belongs.
         */
        fun pageRank(): DoubleArray {
            var rank = DoubleArray(size) { 1.0 / size }
            repeat(PAGERANK_ITERATIONS) {
                val next = DoubleArray(size)
                var dangling = 0.0
                for (i in 0 until size) {
                    if (forward[i].isEmpty()) {
                        dangling += rank[i]
                    } else {
                        val share = rank[i] / forward[i].size
                        forward[i].forEach { j -> next[j] += share }
                    }
                }
                val spread = (1 - DAMPING) / size + DAMPING * dangling / size
                for (i in 0 until size) next[i] = spread + DAMPING * next[i]
                rank = next
            }
            return DoubleArray(size) { rank[it] * size }
        }

        /**
         * Brandes betweenness centrality on the unweighted dependency graph, normalised to `[0, 1]`.
         *
         * Above [EXACT_BETWEENNESS_LIMIT] modules the sources are sampled on a fixed stride rather
         * than randomly, so a large graph stays affordable and the result stays reproducible.
         */
        fun betweenness(): DoubleArray {
            val score = DoubleArray(size)
            if (size < MIN_BETWEENNESS_NODES) return score

            val sources = betweennessSources()
            sources.forEach { source -> accumulateBetweenness(source, score) }

            // Normalise by the number of ordered pairs the sampled sources could have connected.
            val pairs = sources.size.toDouble() * (size - 1)
            return if (pairs <= 0.0) score else DoubleArray(size) { score[it] / pairs }
        }

        private fun betweennessSources(): List<Int> {
            if (size <= EXACT_BETWEENNESS_LIMIT) return (0 until size).toList()
            val stride = size.toDouble() / BETWEENNESS_SAMPLE
            return (0 until BETWEENNESS_SAMPLE).map { (it * stride).toInt().coerceIn(0, size - 1) }.distinct()
        }

        private fun accumulateBetweenness(source: Int, score: DoubleArray) {
            val sigma = DoubleArray(size)
            val distance = IntArray(size) { -1 }
            val predecessors = Array(size) { mutableListOf<Int>() }
            val order = ArrayDeque<Int>()
            val queue = ArrayDeque<Int>()

            sigma[source] = 1.0
            distance[source] = 0
            queue.addLast(source)
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                order.addLast(v)
                forward[v].forEach { w ->
                    if (distance[w] < 0) {
                        distance[w] = distance[v] + 1
                        queue.addLast(w)
                    }
                    if (distance[w] == distance[v] + 1) {
                        sigma[w] += sigma[v]
                        predecessors[w] += v
                    }
                }
            }

            val delta = DoubleArray(size)
            while (order.isNotEmpty()) {
                val w = order.removeLast()
                predecessors[w].forEach { v ->
                    delta[v] += sigma[v] / sigma[w] * (1 + delta[w])
                }
                if (w != source) score[w] += delta[w]
            }
        }

        /** Shortest hop count from the nearest entry point, or -1 when no entry point reaches it. */
        fun depthFromEntryPoints(): IntArray {
            val depth = IntArray(size) { -1 }
            val queue = ArrayDeque<Int>()
            for (i in 0 until size) {
                if (reverse[i].isEmpty()) {
                    depth[i] = 0
                    queue.addLast(i)
                }
            }
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                forward[v].forEach { w ->
                    if (depth[w] < 0) {
                        depth[w] = depth[v] + 1
                        queue.addLast(w)
                    }
                }
            }
            return depth
        }

        /**
         * Articulation points of the **undirected** projection: modules whose removal would split the
         * project into disconnected pieces.
         *
         * Direction is dropped on purpose. The question is "does the codebase fall apart without
         * this module", which is about connectivity, not about which way the arrows point. Iterative
         * Tarjan low-link, so a deep graph cannot blow the call stack.
         */
        fun articulationPoints(): Set<Int> = ArticulationScan(undirectedAdjacency()).cutVertices()

        private fun undirectedAdjacency(): Array<IntArray> {
            val sets = Array(size) { sortedSetOf<Int>() }
            for (f in 0 until size) {
                forward[f].forEach { t ->
                    sets[f] += t
                    sets[t] += f
                }
            }
            return Array(size) { sets[it].toIntArray() }
        }

        private companion object {
            /** Betweenness is meaningless below this many nodes - there are no paths to route. */
            const val MIN_BETWEENNESS_NODES = 3
        }
    }

    /**
     * Finds the cut vertices of an undirected graph - the nodes whose removal would disconnect it.
     *
     * Iterative Tarjan low-link over an explicit frame stack, so a deep graph cannot blow the call
     * stack. State is encapsulated so the DFS reads as a few small steps rather than one deeply
     * nested loop, the same shape `CouplingAnalyzer`'s SCC scan uses.
     */
    private class ArticulationScan(private val adjacency: Array<IntArray>) {

        private class Frame(val node: Int, var nextNeighbour: Int)

        private val size = adjacency.size
        private val discovery = IntArray(size) { -1 }
        private val low = IntArray(size)
        private val parent = IntArray(size) { -1 }
        private val cut = mutableSetOf<Int>()
        private val stack = ArrayDeque<Frame>()
        private var timer = 0

        fun cutVertices(): Set<Int> {
            for (root in 0 until size) {
                if (discovery[root] < 0) scanFrom(root)
            }
            return cut
        }

        private fun scanFrom(root: Int) {
            var rootChildren = 0
            push(root)
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                val neighbours = adjacency[frame.node]
                if (frame.nextNeighbour < neighbours.size) {
                    val next = neighbours[frame.nextNeighbour++]
                    if (visit(frame.node, next) && frame.node == root) rootChildren++
                } else {
                    close(frame.node, root)
                }
            }
            // The root is a cut vertex only when its removal orphans more than one DFS subtree.
            if (rootChildren > 1) cut += root
        }

        /** Returns true when [next] became a new child of [node] rather than a back edge. */
        private fun visit(node: Int, next: Int): Boolean {
            if (discovery[next] >= 0) {
                if (next != parent[node]) low[node] = minOf(low[node], discovery[next])
                return false
            }
            parent[next] = node
            push(next)
            return true
        }

        private fun close(node: Int, root: Int) {
            stack.removeLast()
            val p = parent[node]
            if (p < 0) return
            low[p] = minOf(low[p], low[node])
            // A non-root is a cut vertex when one of its subtrees cannot reach above it.
            if (p != root && low[node] >= discovery[p]) cut += p
        }

        private fun push(node: Int) {
            discovery[node] = timer
            low[node] = timer
            timer++
            stack.addLast(Frame(node, 0))
        }
    }
}
