package com.aalekh.aalekh.analysis.snapshot

import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.model.ArchitectureSnapshot
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotAnalyzerTest {

    private fun graphOf(vararg edges: Pair<String, String>, extra: List<String> = emptyList()):
            ModuleDependencyGraph {
        val paths = (edges.flatMap { listOf(it.first, it.second) } + extra).distinct()
        return ModuleDependencyGraph(
            projectName = "test",
            modules = paths.map { ModuleNode(it, it.substringAfterLast(":"), ModuleType.JVM_LIBRARY) },
            edges = edges.map { DependencyEdge(it.first, it.second, "implementation") },
        )
    }

    private val base = graphOf(":app" to ":core", ":core" to ":model")

    @Test
    fun `a snapshot records the shape, sorted so it diffs line by line`() {
        val snapshot = SnapshotAnalyzer.capture(base)

        assertEquals(listOf(":app", ":core", ":model"), snapshot.modules)
        assertEquals(listOf(":app>:core", ":core>:model"), snapshot.edges)
        assertEquals(listOf(":app"), snapshot.entryPoints)
        assertTrue(snapshot.cycles.isEmpty())
    }

    @Test
    fun `test edges are not recorded - they are not part of the architecture`() {
        val withTest = ModuleDependencyGraph(
            projectName = "test",
            modules = base.modules,
            edges = base.edges + DependencyEdge(":model", ":app", "testImplementation"),
        )

        assertEquals(SnapshotAnalyzer.capture(base).edges, SnapshotAnalyzer.capture(withTest).edges)
    }

    @Test
    fun `a snapshot remembers which layer claimed each module`() {
        val layers = listOf(
            LayerSpec("app", listOf(":app"), emptyList(), hasRestriction = false),
            LayerSpec("core", listOf(":core", ":model"), emptyList(), hasRestriction = false),
        )
        val snapshot = SnapshotAnalyzer.capture(base, layers)

        assertEquals(mapOf(":app" to "app", ":core" to "core", ":model" to "core"), snapshot.layers)
    }

    @Test
    fun `capturing the same graph twice produces the same snapshot`() {
        assertEquals(SnapshotAnalyzer.capture(base), SnapshotAnalyzer.capture(base))
    }

    @Test
    fun `capture does not depend on declaration order`() {
        val reversed = base.copy(modules = base.modules.reversed(), edges = base.edges.reversed())

        assertEquals(SnapshotAnalyzer.capture(base), SnapshotAnalyzer.capture(reversed))
    }

    // Diffing

    @Test
    fun `an unchanged architecture produces an empty diff`() {
        val snapshot = SnapshotAnalyzer.capture(base)

        assertTrue(SnapshotAnalyzer.diff(snapshot, snapshot).isEmpty)
    }

    @Test
    fun `an absent baseline reports nothing rather than everything`() {
        // Reporting every module as "added" on the very first run would bury the real signal the
        // first time anyone looks at the report.
        val diff = SnapshotAnalyzer.diff(ArchitectureSnapshot.EMPTY, SnapshotAnalyzer.capture(base))

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `an added dependency is reported`() {
        val after = graphOf(":app" to ":core", ":core" to ":model", ":app" to ":model")
        val diff = SnapshotAnalyzer.diff(SnapshotAnalyzer.capture(base), SnapshotAnalyzer.capture(after))

        assertEquals(listOf(":app>:model"), diff.addedEdges)
        assertTrue(diff.removedEdges.isEmpty())
        assertFalse(diff.hasRegression, "adding a dependency is not by itself a regression")
    }

    @Test
    fun `a removed dependency is reported`() {
        val after = graphOf(":app" to ":core", extra = listOf(":model"))
        val diff = SnapshotAnalyzer.diff(SnapshotAnalyzer.capture(base), SnapshotAnalyzer.capture(after))

        assertEquals(listOf(":core>:model"), diff.removedEdges)
    }

    @Test
    fun `added and removed modules are reported`() {
        val after = graphOf(":app" to ":core", ":core" to ":model", ":app" to ":feature")
        val diff = SnapshotAnalyzer.diff(SnapshotAnalyzer.capture(base), SnapshotAnalyzer.capture(after))

        assertEquals(listOf(":feature"), diff.addedModules)
        assertTrue(diff.removedModules.isEmpty())
    }

    @Test
    fun `a newly introduced cycle is a regression`() {
        val after = graphOf(":app" to ":core", ":core" to ":model", ":model" to ":core")
        val diff = SnapshotAnalyzer.diff(SnapshotAnalyzer.capture(base), SnapshotAnalyzer.capture(after))

        assertEquals(listOf(":core", ":model"), diff.newCycles)
        assertTrue(diff.hasRegression, "a new cycle must count as a regression")
    }

    @Test
    fun `a resolved cycle is reported and is not a regression`() {
        val cyclic = graphOf(":app" to ":core", ":core" to ":model", ":model" to ":core")
        val diff = SnapshotAnalyzer.diff(SnapshotAnalyzer.capture(cyclic), SnapshotAnalyzer.capture(base))

        assertEquals(listOf(":core", ":model"), diff.resolvedCycles)
        assertTrue(diff.newCycles.isEmpty())
        assertFalse(diff.hasRegression)
    }

    @Test
    fun `a module moving between layers is reported`() {
        val before = SnapshotAnalyzer.capture(
            base,
            listOf(
                LayerSpec("app", listOf(":app", ":core"), emptyList(), hasRestriction = false),
                LayerSpec("core", listOf(":model"), emptyList(), hasRestriction = false),
            ),
        )
        val after = SnapshotAnalyzer.capture(
            base,
            listOf(
                LayerSpec("app", listOf(":app"), emptyList(), hasRestriction = false),
                LayerSpec("core", listOf(":core", ":model"), emptyList(), hasRestriction = false),
            ),
        )
        val change = SnapshotAnalyzer.diff(before, after).layerChanges.getValue(":core")

        assertEquals("app", change.before)
        assertEquals("core", change.after)
    }

    @Test
    fun `a module leaving layer coverage is reported`() {
        val before = SnapshotAnalyzer.capture(
            base,
            listOf(LayerSpec("core", listOf(":core"), emptyList(), hasRestriction = false)),
        )
        val after = SnapshotAnalyzer.capture(base, emptyList())
        val change = SnapshotAnalyzer.diff(before, after).layerChanges.getValue(":core")

        assertEquals("core", change.before)
        assertEquals(null, change.after)
    }

    @Test
    fun `an added module is not also reported as a layer change`() {
        // It is already reported as added; calling it a layer change too would double-count it.
        val layers = listOf(LayerSpec("all", listOf(":**"), emptyList(), hasRestriction = false))
        val before = SnapshotAnalyzer.capture(base, layers)
        val after = SnapshotAnalyzer.capture(
            graphOf(":app" to ":core", ":core" to ":model", ":app" to ":new"), layers
        )
        val diff = SnapshotAnalyzer.diff(before, after)

        assertEquals(listOf(":new"), diff.addedModules)
        assertFalse(":new" in diff.layerChanges)
    }

    @Test
    fun `metric movement is reported with its direction`() {
        val after = graphOf(":app" to ":core", ":core" to ":model", ":model" to ":core")
        val diff = SnapshotAnalyzer.diff(SnapshotAnalyzer.capture(base), SnapshotAnalyzer.capture(after))
        val cycles = diff.metricDeltas.getValue("cycles")

        assertEquals(0.0, cycles.before)
        assertTrue(cycles.after > 0.0)
        assertTrue(cycles.isWorse, "more cycles must read as worse")
    }

    @Test
    fun `a metric that improved is not marked worse`() {
        val cyclic = graphOf(":app" to ":core", ":core" to ":model", ":model" to ":core")
        val diff = SnapshotAnalyzer.diff(SnapshotAnalyzer.capture(cyclic), SnapshotAnalyzer.capture(base))

        assertFalse(diff.metricDeltas.getValue("cycles").isWorse)
        assertFalse(diff.hasRegression)
    }

    @Test
    fun `diffing is deterministic`() {
        val after = graphOf(":app" to ":core", ":core" to ":model", ":app" to ":model")
        val before = SnapshotAnalyzer.capture(base)
        val now = SnapshotAnalyzer.capture(after)

        assertEquals(SnapshotAnalyzer.diff(before, now), SnapshotAnalyzer.diff(before, now))
    }
}
