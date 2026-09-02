package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphMetricsTest {

    private fun node(path: String) = ModuleNode(path, path.substringAfterLast(":"), ModuleType.JVM_LIBRARY)

    private fun graph(vararg edges: Pair<String, String>, extraModules: List<String> = emptyList()):
            ModuleDependencyGraph {
        val paths = (edges.flatMap { listOf(it.first, it.second) } + extraModules).distinct()
        return ModuleDependencyGraph(
            projectName = "test",
            modules = paths.map { node(it) },
            edges = edges.map { DependencyEdge(it.first, it.second, "implementation") },
        )
    }

    // A chain :app -> :feature -> :core -> :model
    private fun chain() = graph(
        ":app" to ":feature",
        ":feature" to ":core",
        ":core" to ":model",
    )

    @Test
    fun `empty graph yields empty metrics`() {
        val metrics = GraphMetrics.compute(ModuleDependencyGraph("test", emptyList(), emptyList()))

        assertEquals(0, metrics.project.moduleCount)
        assertTrue(metrics.modules.isEmpty())
    }

    // Reachability

    @Test
    fun `transitive dependencies count everything downstream`() {
        val m = GraphMetrics.compute(chain()).modules

        assertEquals(3, m.getValue(":app").transitiveDependencies)
        assertEquals(2, m.getValue(":feature").transitiveDependencies)
        assertEquals(1, m.getValue(":core").transitiveDependencies)
        assertEquals(0, m.getValue(":model").transitiveDependencies)
    }

    @Test
    fun `blast radius counts everything upstream`() {
        val m = GraphMetrics.compute(chain()).modules

        assertEquals(0, m.getValue(":app").blastRadius)
        assertEquals(1, m.getValue(":feature").blastRadius)
        assertEquals(2, m.getValue(":core").blastRadius)
        assertEquals(3, m.getValue(":model").blastRadius)
    }

    @Test
    fun `blast radius percent is a share of the whole project`() {
        val m = GraphMetrics.compute(chain()).modules

        // 3 of 4 modules depend on :model.
        assertEquals(75.0, m.getValue(":model").blastRadiusPercent)
    }

    @Test
    fun `a module in a cycle is not a dependency of itself`() {
        val m = GraphMetrics.compute(graph(":a" to ":b", ":b" to ":a")).modules

        assertEquals(1, m.getValue(":a").transitiveDependencies)
        assertEquals(1, m.getValue(":a").blastRadius)
    }

    @Test
    fun `test edges are excluded from every metric`() {
        val g = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(DependencyEdge(":a", ":b", "testImplementation")),
        )
        val m = GraphMetrics.compute(g).modules

        assertEquals(0, m.getValue(":a").fanOut)
        assertEquals(0, m.getValue(":b").fanIn)
        assertEquals(0, m.getValue(":b").blastRadius)
    }

    // Entry points, foundation, depth

    @Test
    fun `entry points and foundation are the ends of the graph`() {
        val p = GraphMetrics.compute(chain()).project

        assertEquals(listOf(":app"), p.entryPoints)
        assertEquals(listOf(":model"), p.foundation)
    }

    @Test
    fun `a module wired to nothing is neither an entry point nor a foundation`() {
        // Gradle creates an empty parent project for every nested path, so ":core" exists next to
        // ":core:domain" with no dependencies either way. Calling it an entry point would tell a
        // reader execution starts in an empty directory.
        val metrics = GraphMetrics.compute(graph(":app" to ":core:domain", extraModules = listOf(":core")))

        assertEquals(listOf(":app"), metrics.project.entryPoints)
        assertEquals(listOf(":core:domain"), metrics.project.foundation)
        assertFalse(metrics.modules.getValue(":core").isEntryPoint)
        assertFalse(metrics.modules.getValue(":core").isFoundation)
    }

    @Test
    fun `depth counts hops from the nearest entry point`() {
        val m = GraphMetrics.compute(chain()).modules

        assertEquals(0, m.getValue(":app").depthFromEntry)
        assertEquals(1, m.getValue(":feature").depthFromEntry)
        assertEquals(3, m.getValue(":model").depthFromEntry)
        assertEquals(3, GraphMetrics.compute(chain()).project.maxDepth)
    }

    @Test
    fun `a module unreachable from any entry point has depth -1`() {
        // Two modules in a cycle, with nothing outside pointing in - no entry point reaches them.
        val m = GraphMetrics.compute(graph(":a" to ":b", ":b" to ":a")).modules

        assertEquals(-1, m.getValue(":a").depthFromEntry)
    }

    // Influence

    @Test
    fun `influence accumulates in the modules the project rests on`() {
        val m = GraphMetrics.compute(chain()).modules

        assertTrue(
            m.getValue(":model").influence > m.getValue(":app").influence,
            "the foundation must out-rank the entry point"
        )
    }

    @Test
    fun `influence weighs who depends on you, not just how many`() {
        // :hub has two dependents, but both are obscure leaves.
        // :core has one dependent, and it is the module everything else routes through.
        val g = graph(
            ":leaf1" to ":hub",
            ":leaf2" to ":hub",
            ":app" to ":mid",
            ":mid" to ":core",
            ":leaf1" to ":app",
            ":leaf2" to ":app",
        )
        val m = GraphMetrics.compute(g).modules

        assertEquals(m.getValue(":hub").fanIn, m.getValue(":app").fanIn)
        assertTrue(
            m.getValue(":core").influence > m.getValue(":hub").influence,
            "a module depended on by an important module must out-rank one depended on by leaves"
        )
    }

    @Test
    fun `influence is normalised so the mean module scores one`() {
        val m = GraphMetrics.compute(chain()).modules
        val mean = m.values.sumOf { it.influence } / m.size

        assertTrue(kotlin.math.abs(mean - 1.0) < 1e-6, "mean influence was $mean")
    }

    // Betweenness

    @Test
    fun `betweenness finds the choke point every path runs through`() {
        // Two sources and two sinks, all traffic routed through :bridge.
        val g = graph(
            ":a" to ":bridge",
            ":b" to ":bridge",
            ":bridge" to ":x",
            ":bridge" to ":y",
        )
        val m = GraphMetrics.compute(g).modules

        assertTrue(m.getValue(":bridge").betweenness > 0.0)
        assertEquals(0.0, m.getValue(":x").betweenness)
        assertEquals(0.0, m.getValue(":a").betweenness)
    }

    // Articulation points

    @Test
    fun `an articulation point is a module whose removal splits the project`() {
        // :left - :cut - :right : removing :cut disconnects the two halves.
        val g = graph(":left" to ":cut", ":cut" to ":right")
        val p = GraphMetrics.compute(g).project

        assertEquals(listOf(":cut"), p.articulationPoints)
    }

    @Test
    fun `a module in a ring is never an articulation point`() {
        val g = graph(":a" to ":b", ":b" to ":c", ":c" to ":a")

        assertTrue(GraphMetrics.compute(g).project.articulationPoints.isEmpty())
    }

    @Test
    fun `a busy module is not automatically an articulation point`() {
        // :hub has high fan-in, but every dependent also reaches :shared, so removing :hub
        // leaves the graph connected. Busy is not the same as load-bearing.
        val g = graph(
            ":a" to ":hub", ":b" to ":hub", ":c" to ":hub",
            ":a" to ":shared", ":b" to ":shared", ":c" to ":shared",
        )

        assertFalse(":hub" in GraphMetrics.compute(g).project.articulationPoints)
    }

    // API surface

    @Test
    fun `api surface ratio measures what a module re-exports`() {
        val g = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":a"), node(":b"), node(":c")),
            edges = listOf(
                DependencyEdge(":a", ":b", "api"),
                DependencyEdge(":a", ":c", "implementation"),
            ),
        )
        val m = GraphMetrics.compute(g).modules

        assertEquals(0.5, m.getValue(":a").apiSurfaceRatio)
        assertEquals(0.0, m.getValue(":b").apiSurfaceRatio)
    }

    // Stability

    @Test
    fun `a stable module depending on an unstable one is a stability violation`() {
        // :core is stable (3 dependents, 1 dependency); :util is unstable (0 dependents).
        val g = graph(
            ":a" to ":core", ":b" to ":core", ":c" to ":core",
            ":core" to ":util",
            ":util" to ":x",
        )
        val violations = GraphMetrics.compute(g).project.stabilityViolations

        assertTrue(
            violations.any { it.from == ":core" && it.to == ":util" },
            "depending from stable toward unstable must be reported: $violations"
        )
    }

    @Test
    fun `a well-directed graph reports no stability violations`() {
        assertTrue(GraphMetrics.compute(chain()).project.stabilityViolations.isEmpty())
    }

    // Concentration

    @Test
    fun `gini is zero when every module is depended on equally`() {
        assertEquals(0.0, GraphMetrics.gini(listOf(2.0, 2.0, 2.0, 2.0)))
    }

    @Test
    fun `gini approaches one when a single module absorbs all dependency`() {
        val concentrated = GraphMetrics.gini(List(99) { 0.0 } + 100.0)

        assertTrue(concentrated > 0.9, "expected high concentration, got $concentrated")
    }

    @Test
    fun `fan-in gini rises when dependency concentrates on one module`() {
        val spread = graph(":a" to ":x", ":b" to ":y", ":c" to ":z")
        val concentrated = graph(":a" to ":hub", ":b" to ":hub", ":c" to ":hub")

        assertTrue(
            GraphMetrics.compute(concentrated).project.fanInGini >
                    GraphMetrics.compute(spread).project.fanInGini
        )
    }

    // Percentiles

    @Test
    fun `percentile rank places a module against the rest of its own project`() {
        val g = graph(":a" to ":hub", ":b" to ":hub", ":c" to ":hub", ":hub" to ":leaf")
        val metrics = GraphMetrics.compute(g)

        assertEquals(100.0, metrics.percentileOf(":hub") { it.fanIn.toDouble() })
    }

    // Determinism

    @Test
    fun `metrics are identical across runs on the same graph`() {
        val g = graph(
            ":app" to ":feature", ":app" to ":core", ":feature" to ":core",
            ":core" to ":model", ":feature" to ":model",
        )

        assertEquals(GraphMetrics.compute(g), GraphMetrics.compute(g))
    }

    @Test
    fun `metrics do not depend on module declaration order`() {
        val forward = graph(":app" to ":core", ":core" to ":model")
        val reversed = ModuleDependencyGraph(
            projectName = "test",
            modules = forward.modules.reversed(),
            edges = forward.edges.reversed(),
        )

        assertEquals(GraphMetrics.compute(forward).modules, GraphMetrics.compute(reversed).modules)
    }
}
