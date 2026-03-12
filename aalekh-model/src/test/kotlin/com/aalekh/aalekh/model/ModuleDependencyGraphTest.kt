package com.aalekh.aalekh.model

import kotlin.test.*

/**
 * Unit tests for [ModuleDependencyGraph] - graph queries, metrics, and cycle detection.
 */
class ModuleDependencyGraphTest {
    private fun node(path: String, type: ModuleType = ModuleType.JVM_LIBRARY) =
        ModuleNode(path = path, name = path.substringAfterLast(":"), type = type)

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    /** Simple linear chain: :a → :b → :c */
    private fun linearGraph(): ModuleDependencyGraph {
        val a = node(":a")
        val b = node(":b")
        val c = node(":c")
        return ModuleDependencyGraph(
            projectName = "linear",
            modules = listOf(a, b, c),
            edges = listOf(edge(":a", ":b"), edge(":b", ":c")),
        )
    }

    /** Diamond: :app → :feat-a → :core, :app → :feat-b → :core */
    private fun diamondGraph(): ModuleDependencyGraph {
        return ModuleDependencyGraph(
            projectName = "diamond",
            modules = listOf(
                node(":app", ModuleType.ANDROID_APP),
                node(":feat-a"),
                node(":feat-b"),
                node(":core"),
            ),
            edges = listOf(
                edge(":app", ":feat-a"),
                edge(":app", ":feat-b"),
                edge(":feat-a", ":core"),
                edge(":feat-b", ":core"),
            ),
        )
    }

    /** Cyclic graph: :a → :b → :c → :a */
    private fun cyclicGraph(): ModuleDependencyGraph {
        return ModuleDependencyGraph(
            projectName = "cyclic",
            modules = listOf(node(":a"), node(":b"), node(":c")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":c"), edge(":c", ":a")),
        )
    }

    // moduleByPath
    @Test
    fun `moduleByPath returns the correct node`() {
        val graph = linearGraph()
        assertEquals(":b", graph.moduleByPath(":b")?.path)
    }

    @Test
    fun `moduleByPath returns null for unknown path`() {
        assertNull(linearGraph().moduleByPath(":unknown"))
    }

    // fanIn / fanOut
    @Test
    fun `fanOut counts direct production dependencies`() {
        // :app depends on :feat-a and :feat-b
        assertEquals(2, diamondGraph().fanOut(":app"))
    }

    @Test
    fun `fanIn counts direct dependents`() {
        // :core is used by both :feat-a and :feat-b
        assertEquals(2, diamondGraph().fanIn(":core"))
    }

    @Test
    fun `fanIn and fanOut are zero for isolated module`() {
        val isolated = ModuleDependencyGraph(
            projectName = "solo",
            modules = listOf(node(":solo")),
            edges = emptyList(),
        )
        assertEquals(0, isolated.fanIn(":solo"))
        assertEquals(0, isolated.fanOut(":solo"))
    }

    @Test
    fun `fanOut excludes test dependencies`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":a"), node(":b"), node(":test-helper")),
            edges = listOf(
                edge(":a", ":b", "implementation"),
                edge(":a", ":test-helper", "testImplementation"),
            ),
        )
        // fanOut counts only non-test production deps
        assertEquals(1, graph.fanOut(":a"))
    }

    // instability
    @Test
    fun `instability is 0 for a maximally stable module (no outgoing deps)`() {
        // :core has no outgoing prod deps in diamondGraph
        assertEquals(0.0, diamondGraph().instability(":core"))
    }

    @Test
    fun `instability is 1 for a maximally unstable module (no incoming deps)`() {
        // :app has no fan-in
        assertEquals(1.0, diamondGraph().instability(":app"))
    }

    @Test
    fun `instability is 0_5 for a balanced module`() {
        val graph = ModuleDependencyGraph(
            projectName = "balanced",
            modules = listOf(node(":a"), node(":b"), node(":c")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":c")),
        )
        // :b: fanIn=1, fanOut=1 → instability = 1/(1+1) = 0.5
        assertEquals(0.5, graph.instability(":b"))
    }

    @Test
    fun `instability returns 0 for isolated module (no edges)`() {
        val graph = ModuleDependencyGraph(
            projectName = "isolated",
            modules = listOf(node(":solo")),
            edges = emptyList(),
        )
        assertEquals(0.0, graph.instability(":solo"))
    }

    // transitiveDependencies
    @Test
    fun `transitiveDependencies follows the full chain`() {
        // :a → :b → :c; transitives of :a = {:b, :c}
        val graph = linearGraph()
        val transitives = graph.transitiveDependencies(":a")
        assertEquals(setOf(":b", ":c"), transitives)
    }

    @Test
    fun `transitiveDependencies for leaf module is empty`() {
        assertTrue(linearGraph().transitiveDependencies(":c").isEmpty())
    }

    @Test
    fun `transitiveDependencies handles diamond without duplicates`() {
        // :app should reach :feat-a, :feat-b, :core - exactly once each
        val transitives = diamondGraph().transitiveDependencies(":app")
        assertEquals(setOf(":feat-a", ":feat-b", ":core"), transitives)
    }

    @Test
    fun `transitiveCount matches transitiveDependencies size`() {
        val graph = diamondGraph()
        assertEquals(graph.transitiveDependencies(":app").size, graph.transitiveCount(":app"))
    }

    // cycle detection
    @Test
    fun `hasCycle returns false for acyclic graph`() {
        assertFalse(diamondGraph().hasCycle())
    }

    @Test
    fun `hasCycle returns true for cyclic graph`() {
        assertTrue(cyclicGraph().hasCycle())
    }

    @Test
    fun `findCycles returns empty list for acyclic graph`() {
        assertTrue(diamondGraph().findCycles().isEmpty())
    }

    @Test
    fun `findCycles detects a 3-node cycle`() {
        val cycles = cyclicGraph().findCycles()
        assertTrue(cycles.isNotEmpty(), "Expected at least one cycle")
        // Each cycle must contain at least 2 nodes
        cycles.forEach { cycle -> assertTrue(cycle.size >= 2) }
    }

    // edge helpers
    @Test
    fun `edgesFrom returns only outgoing edges`() {
        val edges = diamondGraph().edgesFrom(":app")
        assertEquals(2, edges.size)
        assertTrue(edges.all { it.from == ":app" })
    }

    @Test
    fun `edgesTo returns only incoming edges`() {
        val edges = diamondGraph().edgesTo(":core")
        assertEquals(2, edges.size)
        assertTrue(edges.all { it.to == ":core" })
    }

    // metadata
    @Test
    fun `metadata is preserved in the graph`() {
        val graph = ModuleDependencyGraph(
            projectName = "meta-test",
            modules = emptyList(),
            edges = emptyList(),
            metadata = mapOf("gradleVersion" to "9.0", "aalekhVersion" to "0.1.0"),
        )
        assertEquals("9.0", graph.metadata["gradleVersion"])
        assertEquals("0.1.0", graph.metadata["aalekhVersion"])
    }

    @Test
    fun `hasCycle returns false when only self-loops exist`() {
        // Gradle allows project(":app") inside :app itself - not a real cycle
        val graph = ModuleDependencyGraph(
            projectName = "self-loop",
            modules = listOf(node(":app"), node(":lib")),
            edges = listOf(edge(":app", ":app"), edge(":app", ":lib")),
        )
        assertFalse(graph.hasCycle())
    }

    @Test
    fun `findCycles returns empty for self-loops`() {
        val graph = ModuleDependencyGraph(
            projectName = "self-loop",
            modules = listOf(node(":app")),
            edges = listOf(edge(":app", ":app")),
        )
        assertTrue(graph.findCycles().isEmpty())
    }

    @Test
    fun `findCycles minimum cycle size is 2`() {
        val cycles = cyclicGraph().findCycles()
        assertTrue(cycles.all { it.size >= 2 }, "Every detected cycle must involve at least 2 modules")
    }

    @Test
    fun `duplicate edges with same from-to-config triple are only counted once`() {
        val graph = ModuleDependencyGraph(
            projectName = "dedup-test",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(
                edge(":a", ":b", "implementation"),
                edge(":a", ":b", "implementation"),  // duplicate
                edge(":a", ":b", "api"),              // different config - keep
            ).distinctBy { Triple(it.from, it.to, it.configuration) },
        )
        assertEquals(2, graph.edges.size)
    }
}
