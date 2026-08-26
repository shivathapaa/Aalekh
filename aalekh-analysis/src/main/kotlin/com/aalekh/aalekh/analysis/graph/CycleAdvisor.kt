package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.CycleBreakSuggestion
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Turns detected dependency cycles into actionable break-up advice: for each cycle, the specific
 * edge(s) to remove to make it acyclic.
 *
 * The approach, per strongly connected component (over **main** edges only):
 * 1. Take the SCC's induced subgraph.
 * 2. Compute a **feedback arc set** - a set of edges whose removal breaks every cycle - with the
 *    Eades-Lin-Smyth greedy heuristic (deterministic, near-linear).
 * 3. Map each arc back to the declared [DependencyEdge] so the suggestion can name the build file and
 *    the exact `configuration(project(...))` line to delete.
 *
 * Minimum feedback arc set is NP-hard, so the result is a good greedy approximation - honest as a
 * *suggestion*. Removing all suggested edges for a component is still guaranteed to break its cycles.
 * Pure functions - no I/O, no mutation of the input.
 */
public object CycleAdvisor {

    private const val MIN_CYCLE_SIZE = 2

    /**
     * Suggests edges to cut to break every cycle in [graph], strongest (largest cycle) first.
     * Returns an empty list for an acyclic graph.
     */
    public fun suggestBreaks(graph: ModuleDependencyGraph): List<CycleBreakSuggestion> {
        val components = CouplingAnalyzer.stronglyConnectedComponents(graph)
            .filter { it.size >= MIN_CYCLE_SIZE }
        if (components.isEmpty()) return emptyList()

        return components
            .flatMap { component -> suggestionsForComponent(graph, component) }
            .sortedWith(
                compareByDescending<CycleBreakSuggestion> { it.cycleSize }
                    .thenBy { it.from }
                    .thenBy { it.to }
            )
    }

    private fun suggestionsForComponent(
        graph: ModuleDependencyGraph,
        component: List<String>,
    ): List<CycleBreakSuggestion> {
        val nodes = component.toSet()
        val edges = graph.edges.filter {
            !it.isTest && it.from != it.to && it.from in nodes && it.to in nodes
        }
        val pairs = edges.map { DirectedPair(it.from, it.to) }.toSet()
        val feedbackArcs = FeedbackArcSolver(nodes, pairs).solve()

        return feedbackArcs
            .sortedWith(compareBy<DirectedPair> { it.from }.thenBy { it.to })
            .mapNotNull { arc -> representativeEdge(edges, arc)?.let { toSuggestion(graph, it, nodes.size) } }
    }

    /** The declaration used to break a `from -> to` link: the first edge for that pair by config name. */
    private fun representativeEdge(edges: List<DependencyEdge>, arc: DirectedPair): DependencyEdge? =
        edges.filter { it.from == arc.from && it.to == arc.to }.minByOrNull { it.configuration }

    private fun toSuggestion(
        graph: ModuleDependencyGraph,
        edge: DependencyEdge,
        cycleSize: Int,
    ): CycleBreakSuggestion = CycleBreakSuggestion(
        from = edge.from,
        to = edge.to,
        configuration = edge.configuration,
        buildFilePath = graph.moduleByPath(edge.from)?.buildFilePath,
        declarationLine = edge.declarationLine,
        cycleSize = cycleSize,
    )
}

/** A directed edge between two module paths, used for feedback-arc-set computation. */
internal data class DirectedPair(val from: String, val to: String)

/**
 * Computes a feedback arc set for a directed graph using the Eades-Lin-Smyth greedy heuristic.
 *
 * The heuristic builds a linear vertex ordering by repeatedly stripping sinks (to the right), sources
 * (to the left), and otherwise the vertex with the greatest out-degree minus in-degree. Every edge
 * that points *backwards* in the final ordering is a feedback arc; removing them yields a DAG. Ties
 * are broken by module name so the output is deterministic.
 */
internal class FeedbackArcSolver(
    nodes: Set<String>,
    private val edges: Set<DirectedPair>,
) {
    private val out = HashMap<String, MutableSet<String>>()
    private val incoming = HashMap<String, MutableSet<String>>()
    private val remaining = LinkedHashSet(nodes.sorted())

    init {
        remaining.forEach { out[it] = sortedSetOf(); incoming[it] = sortedSetOf() }
        edges.forEach { (from, to) ->
            out.getValue(from).add(to)
            incoming.getValue(to).add(from)
        }
    }

    fun solve(): Set<DirectedPair> {
        val position = order().withIndex().associate { (index, node) -> node to index }
        return edges.filterTo(mutableSetOf()) { position.getValue(it.from) > position.getValue(it.to) }
    }

    private fun order(): List<String> {
        val left = mutableListOf<String>()
        val right = ArrayDeque<String>()
        while (remaining.isNotEmpty()) {
            drainSinks(right)
            drainSources(left)
            if (remaining.isEmpty()) break
            val pivot = remaining.maxByOrNull { out.getValue(it).size - incoming.getValue(it).size }!!
            left += pivot
            detach(pivot)
        }
        return left + right
    }

    private fun drainSinks(right: ArrayDeque<String>) {
        var sink = remaining.firstOrNull { out.getValue(it).isEmpty() }
        while (sink != null) {
            right.addFirst(sink)
            detach(sink)
            sink = remaining.firstOrNull { out.getValue(it).isEmpty() }
        }
    }

    private fun drainSources(left: MutableList<String>) {
        var source = remaining.firstOrNull { incoming.getValue(it).isEmpty() }
        while (source != null) {
            left += source
            detach(source)
            source = remaining.firstOrNull { incoming.getValue(it).isEmpty() }
        }
    }

    private fun detach(node: String) {
        out.getValue(node).forEach { incoming.getValue(it).remove(node) }
        incoming.getValue(node).forEach { out.getValue(it).remove(node) }
        out.getValue(node).clear()
        incoming.getValue(node).clear()
        remaining.remove(node)
    }
}
