package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.analysis.rules.GlobMatcher
import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.analysis.rules.LayerSpecParser
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Provenance
import kotlinx.serialization.Serializable

/**
 * Where a region partition came from - and therefore how much to trust it.
 */
@Serializable
public enum class RegionSource {
    /** The `layers { }` block. The architecture the build enforces. */
    DECLARED_LAYER,

    /** The `teams { }` block or CODEOWNERS. The architecture the organisation enforces. */
    DECLARED_TEAM,

    /** Common module-path prefixes. A convention, and usually a deliberate one. */
    PATH_PREFIX,

    /** Community detection over the dependency graph. What the edges say, absent any declaration. */
    DETECTED;

    /** The provenance tier a partition from this source carries. */
    public val provenance: Provenance
        get() = when (this) {
            DECLARED_LAYER, DECLARED_TEAM -> Provenance.OBSERVED
            PATH_PREFIX, DETECTED -> Provenance.INFERRED
        }

    /** How the report should describe the grouping. */
    public val label: String
        get() = when (this) {
            DECLARED_LAYER -> "declared layers"
            DECLARED_TEAM -> "declared teams"
            PATH_PREFIX -> "module path prefixes"
            DETECTED -> "detected communities"
        }
}

/**
 * One region: a group of modules treated as a unit when the project is too large to draw module
 * by module.
 *
 * @param id Stable identifier, used as a node id in the aggregated graph.
 * @param name Display name.
 * @param modules Module paths in this region, sorted.
 * @param internalEdges Production edges with both ends inside the region.
 * @param externalEdges Production edges with exactly one end inside it.
 * @param cohesion `internal / (internal + external)`, in `[0, 1]`. High cohesion means the region is
 *   a real boundary; low cohesion means the grouping cuts across how the code actually depends.
 * @param subRegions Sub-groups, present only when the region is too large to read as one list.
 *   A declared layer can legitimately hold hundreds of modules - a `:feature:**` layer over 120
 *   features - and collapsing that to a single card says nothing. Splitting it one level deeper turns
 *   the region map into a drill-down: project → region → sub-region → module.
 */
@Serializable
public data class Region(
    val id: String,
    val name: String,
    val modules: List<String>,
    val internalEdges: Int,
    val externalEdges: Int,
    val cohesion: Double,
    val subRegions: List<Region> = emptyList(),
)

/** An aggregated dependency between two regions, weighted by how many module edges it stands for. */
@Serializable
public data class RegionEdge(
    val from: String,
    val to: String,
    val weight: Int,
)

/**
 * The project collapsed to regions and the flows between them.
 *
 * @param regions Regions, largest first.
 * @param edges Aggregated inter-region dependencies, heaviest first.
 * @param source Where the grouping came from.
 * @param modularity Newman modularity `Q` of the partition, roughly `[-0.5, 1]`. Above about 0.3
 *   means the groups genuinely correspond to how the modules depend on each other.
 *
 *   Near or below zero is **not** automatically a problem: a layered architecture is *designed* so
 *   that most edges cross a boundary - features depend on core, core depends on model - so a layer
 *   partition legitimately scores low. Read it as "are these groups also dependency clusters?", which
 *   is a different question from "are these groups correct?".
 */
@Serializable
public data class RegionMap(
    val regions: List<Region> = emptyList(),
    val edges: List<RegionEdge> = emptyList(),
    val source: RegionSource = RegionSource.PATH_PREFIX,
    val modularity: Double = 0.0,
) {
    /** True when there is no useful partition - the report then falls back to module-level views. */
    public val isEmpty: Boolean get() = regions.size < 2

    public companion object {
        /** No usable partition. */
        public val EMPTY: RegionMap = RegionMap()
    }
}

/**
 * Groups modules into regions, so a project too large to read module-by-module can be read
 * region-by-region.
 *
 * Beyond a couple of hundred modules a node-link diagram stops being a picture of an architecture and
 * becomes a picture of a hairball. The fix is not a better layout - it is to draw fewer things:
 * fifteen regions and the flows between them, with the modules one click away.
 *
 * Grouping follows a **declared-first cascade**, taking the first source that yields a usable
 * partition:
 *
 * 1. `layers { }` - the architecture the build enforces
 * 2. `teams { }` - the architecture the organisation enforces
 * 3. module path prefixes - the architecture the directory tree implies
 * 4. community detection - what the dependency edges themselves say
 *
 * The cascade matters for honesty as much as quality: a partition from a declared source is an
 * observed fact, while one from prefixes or detection is an inference, and the report says which.
 * Pure functions - no I/O, deterministic tie-breaks throughout.
 */
// One private function per step of the cascade and the aggregation that follows it; they share the
// graph and the assignment map, so splitting them across objects would mean threading both through
// every call for no gain in clarity.
@Suppress("TooManyFunctions")
public object RegionAnalyzer {

    /** A partition where one region holds more than this share of modules has not partitioned anything. */
    private const val MAX_DOMINANT_SHARE = 0.8

    /** Fewer modules than this and regions are just a longer way of listing them. */
    private const val MIN_MODULES = 12

    /** Label propagation rounds. Converges in far fewer on dependency graphs; this is the ceiling. */
    private const val PROPAGATION_ROUNDS = 20

    /**
     * Groups [graph] into regions.
     *
     * @param layers Declared layers, in declaration order. Tried first.
     * @param teams Declared team name to module glob patterns. Tried second.
     * @return The best available partition, or [RegionMap.EMPTY] for a project small enough to read
     *   directly.
     */
    public fun analyze(
        graph: ModuleDependencyGraph,
        layers: List<LayerSpec> = emptyList(),
        teams: Map<String, List<String>> = emptyMap(),
    ): RegionMap {
        val candidates = if (graph.modules.size < MIN_MODULES) {
            emptyList()
        } else {
            listOfNotNull(
                byLayers(graph, layers)?.let { it to RegionSource.DECLARED_LAYER },
                byTeams(graph, teams)?.let { it to RegionSource.DECLARED_TEAM },
                byPathPrefix(graph)?.let { it to RegionSource.PATH_PREFIX },
                byCommunity(graph).let { it to RegionSource.DETECTED },
            )
        }

        val chosen = candidates.firstOrNull { (groups, source) -> isUsable(groups, graph, source) }
            ?: return RegionMap.EMPTY
        return build(graph, chosen.first, chosen.second)
    }

    /**
     * True when a grouping actually divides the project rather than relabelling it.
     *
     * Two ways a partition fails to be one, and both happen in practice: one region per module, and a
     * single region swallowing almost everything. The first is how path-prefix grouping fails on flat
     * module names - `:auth`, `:billing`, `:search` each become their own "region", which is the
     * module list with extra steps.
     *
     * The dominant-region check applies only to **inferred** sources. A project that really is 90%
     * feature modules has a lopsided `layers { }` block because that is the architecture, and
     * rejecting it would substitute a guess for a fact; an oversized declared region is made readable
     * by subdivision instead. For an inferred source, a blob means the heuristic did not work, and
     * the next one in the cascade deserves a turn.
     */
    private fun isUsable(
        groups: Map<String, String>,
        graph: ModuleDependencyGraph,
        source: RegionSource,
    ): Boolean {
        val sizes = groups.values.groupingBy { it }.eachCount()
        val total = graph.modules.size
        val wellSized = sizes.size >= MIN_REGIONS && sizes.size <= total / MIN_AVERAGE_REGION_SIZE
        val notABlob = source.provenance == Provenance.OBSERVED ||
                sizes.values.max().toDouble() / total <= MAX_DOMINANT_SHARE
        return wellSized && notABlob
    }

    private fun byLayers(graph: ModuleDependencyGraph, layers: List<LayerSpec>): Map<String, String>? {
        if (layers.isEmpty()) return null
        return graph.modules.associate { module ->
            module.path to (LayerSpecParser.layerOf(layers, module.path)?.name ?: UNGROUPED)
        }
    }

    private fun byTeams(graph: ModuleDependencyGraph, teams: Map<String, List<String>>): Map<String, String>? {
        if (teams.isEmpty()) return null
        return graph.modules.associate { module ->
            val team = teams.entries
                .firstOrNull { (_, patterns) -> GlobMatcher.matchesAny(patterns, module.path) }
                ?.key
            module.path to (team ?: UNGROUPED)
        }
    }

    /**
     * Groups by module path prefix, deepening until the groups are a useful size.
     *
     * `:feature:login:ui` and `:feature:login:data` belong together under `:feature:login` on a large
     * project and under `:feature` on a smaller one; which reads better depends on how many groups
     * each depth produces, so both are tried and the better one kept.
     */
    private fun byPathPrefix(graph: ModuleDependencyGraph): Map<String, String>? {
        val paths = graph.modules.map { it.path }
        return listOf(1, 2)
            .map { depth -> paths.associateWith { prefixOf(it, depth) } }
            .firstOrNull { isUsable(it, graph, RegionSource.PATH_PREFIX) }
    }

    private fun prefixOf(path: String, depth: Int): String {
        val segments = path.split(":").filter { it.isNotBlank() }
        if (segments.isEmpty()) return UNGROUPED
        // A module shallower than the requested depth is its own group rather than being folded
        // into a deeper one it does not belong to.
        return ":" + segments.take(minOf(depth, segments.size)).joinToString(":")
    }

    /**
     * Label propagation over the undirected projection of the graph.
     *
     * Each module repeatedly adopts the label most common among its neighbours, ties broken by the
     * smallest label so the result is reproducible - the usual randomised tie-break would make the
     * report differ between runs on the same graph, which is exactly what Aalekh must not do.
     */
    private fun byCommunity(graph: ModuleDependencyGraph): Map<String, String> {
        val paths = graph.modules.map { it.path }.sorted()
        val neighbours = paths.associateWith { mutableSetOf<String>() }
        graph.edges.asSequence()
            .filter { !it.isTest && it.from != it.to }
            .forEach { edge ->
                neighbours[edge.from]?.add(edge.to)
                neighbours[edge.to]?.add(edge.from)
            }

        val labels = paths.associateWith { it }.toMutableMap()
        repeat(PROPAGATION_ROUNDS) {
            var changed = false
            paths.forEach { path ->
                val counts = neighbours.getValue(path)
                    .mapNotNull { labels[it] }
                    .groupingBy { it }
                    .eachCount()
                if (counts.isEmpty()) return@forEach
                val best = counts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key
                if (labels[path] != best) {
                    labels[path] = best
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        return labels
    }

    /** Turns a module→group assignment into regions, aggregated edges, and a modularity score. */
    private fun build(
        graph: ModuleDependencyGraph,
        assignment: Map<String, String>,
        source: RegionSource,
    ): RegionMap {
        val mainEdges = graph.edges.filter { !it.isTest && it.from != it.to }
        val grouped = assignment.entries.groupBy({ it.value }, { it.key })

        val internal = mutableMapOf<String, Int>()
        val external = mutableMapOf<String, Int>()
        val crossings = mutableMapOf<Pair<String, String>, Int>()

        mainEdges.forEach { edge ->
            val from = assignment[edge.from] ?: return@forEach
            val to = assignment[edge.to] ?: return@forEach
            if (from == to) {
                internal.merge(from, 1, Int::plus)
            } else {
                external.merge(from, 1, Int::plus)
                external.merge(to, 1, Int::plus)
                crossings.merge(from to to, 1, Int::plus)
            }
        }

        val regions = grouped.entries
            .map { (name, modules) ->
                val inside = internal[name] ?: 0
                val outside = external[name] ?: 0
                val sorted = modules.sorted()
                Region(
                    id = name,
                    name = if (name == UNGROUPED) "Ungrouped" else name,
                    modules = sorted,
                    internalEdges = inside,
                    externalEdges = outside,
                    cohesion = if (inside + outside == 0) 0.0 else inside.toDouble() / (inside + outside),
                    subRegions = subdivide(sorted, mainEdges),
                )
            }
            .sortedWith(compareByDescending<Region> { it.modules.size }.thenBy { it.id })

        val edges = crossings.entries
            .map { (pair, weight) -> RegionEdge(pair.first, pair.second, weight) }
            .sortedWith(compareByDescending<RegionEdge> { it.weight }.thenBy { it.from }.thenBy { it.to })

        return RegionMap(
            regions = regions,
            edges = edges,
            source = source,
            modularity = modularity(mainEdges.size, internal, external),
        )
    }

    /**
     * Splits an oversized region one level deeper, by the first path segment that distinguishes its
     * modules from each other.
     *
     * A `:feature:**` layer over 120 features is a correct region and a useless card: "361 modules"
     * tells the reader nothing they can act on. Subdividing on the segment after the shared prefix
     * recovers the structure that was there all along - `:feature:login`, `:feature:profile` - and
     * turns one card into a drill-down.
     *
     * Only the modules' own paths are used, so a sub-region is exactly as trustworthy as the naming
     * convention behind it, and no deeper analysis is implied than there is.
     */
    private fun subdivide(modules: List<String>, mainEdges: List<com.aalekh.aalekh.model.DependencyEdge>):
            List<Region> {
        // Deepen until the paths actually separate. A fixed "common prefix + 1" is defeated by the
        // empty parent project Gradle creates for every nested path: `:feature` sits in the same
        // region as `:feature:login:ui`, dragging the shared depth to zero and putting everything
        // back into one bucket.
        val groups = if (modules.size <= MAX_FLAT_REGION) {
            emptyMap()
        } else {
            (1..maxSegments(modules)).asSequence()
                .map { depth -> modules.groupBy { prefixOf(it, depth) } }
                .firstOrNull { it.size >= MIN_REGIONS && it.size < modules.size }
                .orEmpty()
        }
        if (groups.isEmpty()) return emptyList()

        val membership = modules.associateWith { path ->
            groups.entries.first { (_, members) -> path in members }.key
        }
        val internal = mutableMapOf<String, Int>()
        val external = mutableMapOf<String, Int>()
        mainEdges.forEach { edge ->
            val from = membership[edge.from]
            val to = membership[edge.to]
            when {
                from == null && to == null -> Unit
                from == to -> internal.merge(from!!, 1, Int::plus)
                else -> {
                    from?.let { external.merge(it, 1, Int::plus) }
                    to?.let { external.merge(it, 1, Int::plus) }
                }
            }
        }

        return groups.entries
            .map { (id, members) ->
                val inside = internal[id] ?: 0
                val outside = external[id] ?: 0
                Region(
                    id = id,
                    name = id,
                    modules = members.sorted(),
                    internalEdges = inside,
                    externalEdges = outside,
                    cohesion = if (inside + outside == 0) 0.0 else inside.toDouble() / (inside + outside),
                )
            }
            .sortedWith(compareByDescending<Region> { it.modules.size }.thenBy { it.id })
    }

    /** The deepest module path in a group, in segments - the ceiling for subdivision. */
    private fun maxSegments(modules: List<String>): Int =
        modules.maxOf { path -> path.split(":").count { it.isNotBlank() } }

    /**
     * Newman modularity `Q` for the partition.
     *
     * `Q = Σ (intra / m − (degree / 2m)²)`. It answers a question the region list alone cannot: do
     * these groups correspond to how the modules actually depend on each other, or is the grouping
     * orthogonal to the dependency structure? A team partition with `Q` near zero is a sign that the
     * teams and the code disagree about where the boundaries are.
     */
    private fun modularity(
        edgeCount: Int,
        internal: Map<String, Int>,
        external: Map<String, Int>,
    ): Double {
        if (edgeCount == 0) return 0.0
        val m = edgeCount.toDouble()
        val names = internal.keys + external.keys
        return names.sumOf { name ->
            val inside = (internal[name] ?: 0).toDouble()
            val degree = 2 * inside + (external[name] ?: 0).toDouble()
            inside / m - (degree / (2 * m)) * (degree / (2 * m))
        }
    }

    /** Label for modules no declared source claims. */
    private const val UNGROUPED = "(ungrouped)"

    /** A partition needs at least this many groups to be worth drawing instead of the modules. */
    private const val MIN_REGIONS = 2

    /**
     * Average modules per region below which the partition is not summarising anything.
     *
     * Drawing one region per module is the module graph again, with an extra layer of indirection
     * between the reader and the names they were looking for.
     */
    private const val MIN_AVERAGE_REGION_SIZE = 2

    /** Above this many modules, a region is drilled into rather than listed flat. */
    private const val MAX_FLAT_REGION = 25
}
