package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionAnalyzerTest {

    private fun graphOf(edges: List<Pair<String, String>>, extra: List<String> = emptyList()):
            ModuleDependencyGraph {
        val paths = (edges.flatMap { listOf(it.first, it.second) } + extra).distinct()
        return ModuleDependencyGraph(
            projectName = "test",
            modules = paths.map { ModuleNode(it, it.substringAfterLast(":"), ModuleType.JVM_LIBRARY) },
            edges = edges.map { DependencyEdge(it.first, it.second, "implementation") },
        )
    }

    /** Thirteen modules in four obvious areas - above the threshold where regions are worth drawing. */
    private fun largeProject() = graphOf(
        listOf(
            ":app" to ":feature:login", ":app" to ":feature:profile", ":app" to ":feature:feed",
            ":app" to ":feature:settings",
            ":feature:login" to ":core:ui", ":feature:login" to ":core:data",
            ":feature:profile" to ":core:ui", ":feature:profile" to ":core:data",
            ":feature:feed" to ":core:ui", ":feature:feed" to ":core:network",
            ":feature:settings" to ":core:ui",
            ":core:ui" to ":core:model", ":core:data" to ":core:model",
            ":core:network" to ":core:model", ":core:data" to ":core:network",
            ":lib:logging" to ":core:model", ":lib:analytics" to ":lib:logging",
            ":app" to ":lib:analytics",
        ),
        extra = listOf(":lib:testing"),
    )

    @Test
    fun `a small project gets no regions`() {
        val small = graphOf(listOf(":a" to ":b", ":b" to ":c"))

        assertTrue(RegionAnalyzer.analyze(small).isEmpty)
    }

    @Test
    fun `declared layers win over every other grouping`() {
        val layers = listOf(
            LayerSpec("app", listOf(":app"), emptyList(), hasRestriction = false),
            LayerSpec("feature", listOf(":feature:**"), emptyList(), hasRestriction = false),
            LayerSpec("core", listOf(":core:**", ":lib:**"), emptyList(), hasRestriction = false),
        )
        val map = RegionAnalyzer.analyze(largeProject(), layers = layers)

        assertEquals(RegionSource.DECLARED_LAYER, map.source)
        assertEquals(Provenance.OBSERVED, map.source.provenance)
        assertEquals(setOf("app", "feature", "core"), map.regions.map { it.id }.toSet())
    }

    @Test
    fun `declared teams are used when no layers are declared`() {
        val map = RegionAnalyzer.analyze(
            largeProject(),
            teams = mapOf(
                "app-team" to listOf(":app", ":feature:**"),
                "core-team" to listOf(":core:**", ":lib:**"),
            ),
        )

        assertEquals(RegionSource.DECLARED_TEAM, map.source)
        assertEquals(setOf("app-team", "core-team"), map.regions.map { it.id }.toSet())
    }

    @Test
    fun `path prefixes are used when nothing is declared, and marked as inferred`() {
        val map = RegionAnalyzer.analyze(largeProject())

        assertEquals(RegionSource.PATH_PREFIX, map.source)
        assertEquals(Provenance.INFERRED, map.source.provenance)
        assertEquals(setOf(":app", ":feature", ":core", ":lib"), map.regions.map { it.id }.toSet())
    }

    @Test
    fun `a lopsided declared partition is kept, because it is what the build says`() {
        // A project that really is mostly feature modules has a lopsided layers { } block because
        // that is its architecture. Substituting a guess for a declaration would be worse than a
        // large region, which subdivision makes readable anyway.
        val map = RegionAnalyzer.analyze(
            largeProject(),
            layers = listOf(
                LayerSpec("most", listOf(":feature:**", ":core:**"), emptyList(), hasRestriction = false),
                LayerSpec("rest", listOf(":app", ":lib:**"), emptyList(), hasRestriction = false),
            ),
        )

        assertEquals(RegionSource.DECLARED_LAYER, map.source)
    }

    @Test
    fun `an inferred grouping that produces one blob is rejected`() {
        // Every module under a single prefix: path grouping has explained nothing, so the cascade
        // must fall through to community detection.
        val flat = graphOf(
            (0 until 15).map { ":app:m$it" to ":app:base" } + listOf(":app:base" to ":app:util"),
        )
        val map = RegionAnalyzer.analyze(flat)

        // No source produces a usable split here, and reporting one region is worse than reporting
        // none: the reader would be told the project has structure it does not have.
        assertTrue(map.isEmpty, "a single-prefix project must not be 'grouped' into one region")
    }

    @Test
    fun `modules matched by no declared pattern land in an ungrouped region`() {
        val map = RegionAnalyzer.analyze(
            largeProject(),
            layers = listOf(
                LayerSpec("core", listOf(":core:**"), emptyList(), hasRestriction = false),
                LayerSpec("feature", listOf(":feature:**"), emptyList(), hasRestriction = false),
            ),
        )
        val ungrouped = map.regions.single { it.name == "Ungrouped" }

        assertTrue(":app" in ungrouped.modules)
        assertTrue(":lib:logging" in ungrouped.modules)
    }

    @Test
    fun `region edges aggregate the module edges that cross a boundary`() {
        val map = RegionAnalyzer.analyze(largeProject())
        val featureToCore = map.edges.single { it.from == ":feature" && it.to == ":core" }

        // login->ui, login->data, profile->ui, profile->data, feed->ui, feed->network, settings->ui
        assertEquals(7, featureToCore.weight)
    }

    @Test
    fun `edges are ordered heaviest first`() {
        val weights = RegionAnalyzer.analyze(largeProject()).edges.map { it.weight }

        assertEquals(weights.sortedDescending(), weights)
    }

    @Test
    fun `cohesion reports how much of a region's coupling stays inside it`() {
        val core = RegionAnalyzer.analyze(largeProject()).regions.single { it.id == ":core" }

        // :core has internal edges (ui->model, data->model, network->model, data->network) and
        // external ones (everything the features and lib pull from it).
        assertTrue(core.internalEdges > 0)
        assertTrue(core.externalEdges > 0)
        assertEquals(
            core.internalEdges.toDouble() / (core.internalEdges + core.externalEdges),
            core.cohesion,
        )
    }

    @Test
    fun `modularity is higher for a grouping that follows the dependency clusters`() {
        // A layered project is designed so most edges cross a boundary, so its layer partition
        // legitimately scores low. Q answers "are these groups also dependency clusters?", which is
        // a different question from "are these groups right?" - the test pins that distinction.
        val clustered = graphOf(
            listOf(
                ":north:a" to ":north:b", ":north:b" to ":north:c", ":north:c" to ":north:d",
                ":north:d" to ":north:a", ":north:a" to ":north:c",
                ":south:a" to ":south:b", ":south:b" to ":south:c", ":south:c" to ":south:d",
                ":south:d" to ":south:a", ":south:a" to ":south:c",
                ":north:a" to ":south:a",
            ),
            extra = listOf(":north:e", ":south:e", ":north:f", ":south:f"),
        )

        val clusteredQ = RegionAnalyzer.analyze(clustered).modularity
        val layeredQ = RegionAnalyzer.analyze(largeProject()).modularity

        assertTrue(clusteredQ > 0.3, "clustered grouping should score well, got $clusteredQ")
        assertTrue(
            clusteredQ > layeredQ,
            "a grouping that follows the edges must out-score one that deliberately crosses them",
        )
    }

    @Test
    fun `regions are ordered largest first`() {
        val sizes = RegionAnalyzer.analyze(largeProject()).regions.map { it.modules.size }

        assertEquals(sizes.sortedDescending(), sizes)
    }

    @Test
    fun `every module lands in exactly one region`() {
        val graph = largeProject()
        val map = RegionAnalyzer.analyze(graph)
        val assigned = map.regions.flatMap { it.modules }

        assertEquals(graph.modules.size, assigned.size)
        assertEquals(assigned.distinct().size, assigned.size)
    }

    @Test
    fun `an oversized region is subdivided so its card stays readable`() {
        // A :feature:** layer over many features is a correct region and a useless card: "60
        // modules" tells the reader nothing. It must break down into the features it contains.
        val features = (0 until 30).flatMap { i ->
            listOf(":feature:f$i:ui" to ":feature:f$i:domain", ":feature:f$i:ui" to ":core:model")
        }
        val graph = graphOf(features + listOf(":app" to ":core:model"), extra = listOf(":feature", ":core"))
        val map = RegionAnalyzer.analyze(
            graph,
            layers = listOf(
                LayerSpec("feature", listOf(":feature:**"), emptyList(), hasRestriction = false),
                LayerSpec("core", listOf(":core:**", ":app"), emptyList(), hasRestriction = false),
            ),
        )
        val feature = map.regions.single { it.id == "feature" }

        assertTrue(feature.modules.size > 25, "the fixture must produce an oversized region")
        assertTrue(feature.subRegions.isNotEmpty(), "an oversized region must be subdivided")
        assertTrue(
            feature.subRegions.any { it.id == ":feature:f0" },
            "subdivision must split on the feature name: ${feature.subRegions.map { it.id }}",
        )
        assertEquals(
            feature.modules.size,
            feature.subRegions.sumOf { it.modules.size },
            "every module in the region must land in exactly one sub-region",
        )
    }

    @Test
    fun `a small region is not subdivided`() {
        val core = RegionAnalyzer.analyze(largeProject()).regions.single { it.id == ":core" }

        assertTrue(core.subRegions.isEmpty(), "a region small enough to list flat needs no drill-down")
    }

    @Test
    fun `the partition is identical across runs`() {
        val graph = largeProject()

        assertEquals(RegionAnalyzer.analyze(graph), RegionAnalyzer.analyze(graph))
    }

    @Test
    fun `the partition does not depend on module declaration order`() {
        val forward = largeProject()
        val reversed = forward.copy(modules = forward.modules.reversed(), edges = forward.edges.reversed())

        assertEquals(
            RegionAnalyzer.analyze(forward).regions.map { it.id to it.modules },
            RegionAnalyzer.analyze(reversed).regions.map { it.id to it.modules },
        )
    }

    @Test
    fun `community detection groups a project whose paths say nothing`() {
        // Flat names carrying no prefix information, but two clearly separate clusters of edges.
        val clustered = graphOf(
            listOf(
                ":a1" to ":a2", ":a2" to ":a3", ":a3" to ":a1", ":a1" to ":a4", ":a4" to ":a2",
                ":b1" to ":b2", ":b2" to ":b3", ":b3" to ":b1", ":b1" to ":b4", ":b4" to ":b2",
                ":a1" to ":b1",
            ),
            extra = listOf(":c1", ":c2", ":c3", ":c4"),
        )
        val map = RegionAnalyzer.analyze(clustered)

        assertEquals(RegionSource.DETECTED, map.source)
        assertTrue(map.regions.size >= 2)
    }
}
