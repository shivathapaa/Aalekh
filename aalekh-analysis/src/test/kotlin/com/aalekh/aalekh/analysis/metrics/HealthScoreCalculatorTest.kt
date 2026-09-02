package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthScoreCalculatorTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
    )

    private fun edge(from: String, to: String) =
        DependencyEdge(from = from, to = to, configuration = "implementation")

    @Test
    fun `isolated module with no deps scores 100`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":core:domain")),
            edges = emptyList(),
        )
        val score = HealthScoreCalculator.score(":core:domain", graph, emptySet())
        assertEquals(100, score)
    }

    @Test
    fun `score is always in range 0 to 100`() {
        // God module in a cycle with many transitive deps - worst case
        val modules = (1..10).map { node(":m$it") }
        val edges = modules.zipWithNext { a, b -> edge(a.path, b.path) } +
                listOf(edge(modules.last().path, modules.first().path)) // cycle
        val graph = ModuleDependencyGraph("test", modules, edges)
        val cycleNodes = modules.map { it.path }.toSet()

        modules.forEach { m ->
            val s = HealthScoreCalculator.score(m.path, graph, cycleNodes)
            assertTrue(s in 0..100, "Score $s out of range for ${m.path}")
        }
    }

    @Test
    fun `cycle participation reduces score`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":a"), node(":b")),
            edges = listOf(edge(":a", ":b"), edge(":b", ":a")),
        )
        val withCycle = HealthScoreCalculator.score(":a", graph, setOf(":a", ":b"))
        val withoutCycle = HealthScoreCalculator.score(":a", graph, emptySet())
        assertTrue(withCycle < withoutCycle, "Cycle participation must reduce score")
    }

    @Test
    fun `high instability reduces score`() {
        // :leaf depends on many others but nothing depends on it - high instability
        val deps = (1..6).map { node(":dep$it") }
        val leaf = node(":leaf")
        val edges = deps.map { edge(":leaf", it.path) }
        val graph = ModuleDependencyGraph("test", deps + leaf, edges)

        val leafScore = HealthScoreCalculator.score(":leaf", graph, emptySet())
        val stableScore = HealthScoreCalculator.score(":dep1", graph, emptySet())
        assertTrue(
            leafScore < stableScore,
            "High instability module must score lower than stable module"
        )
    }

    @Test
    fun `god module status reduces score`() {
        // Module with fanIn>=5 AND fanOut>=5
        val sources = (1..5).map { node(":src$it") }
        val targets = (1..5).map { node(":tgt$it") }
        val god = node(":god")
        val inEdges = sources.map { edge(it.path, ":god") }
        val outEdges = targets.map { edge(":god", it.path) }
        val graph = ModuleDependencyGraph("test", sources + targets + god, inEdges + outEdges)

        val godScore = HealthScoreCalculator.score(":god", graph, emptySet())
        val leafScore = HealthScoreCalculator.score(":tgt1", graph, emptySet())
        assertTrue(godScore < leafScore, "God module must score lower than a simple leaf")
    }

    @Test
    fun `score thresholds are meaningful`() {
        // A perfectly stable module (fanIn > 0, fanOut = 0, no cycle, few transitives)
        val dependents = (1..3).map { node(":dep$it") }
        val stable = node(":stable")
        val graph = ModuleDependencyGraph(
            "test",
            dependents + stable,
            dependents.map { edge(it.path, ":stable") },
        )
        val score = HealthScoreCalculator.score(":stable", graph, emptySet())
        assertTrue(score >= 70, "Stable module with no penalty should score >= 70, got $score")
    }

    // Project score - a different measure from the per-module score above.

    private fun summary(
        cycleCount: Int = 0,
        godModuleCount: Int = 0,
        averageInstability: Double = 0.0,
    ) = GraphSummary(
        totalModules = 10,
        totalEdges = 12,
        modulesByType = emptyMap(),
        hasCycles = cycleCount > 0,
        cycleCount = cycleCount,
        maxFanOut = 3,
        maxFanIn = 3,
        averageInstability = averageInstability,
        criticalPathLength = 3,
        godModuleCount = godModuleCount,
        isolatedModuleCount = 0,
    )

    @Test
    fun `clean project scores 100 and is healthy`() {
        val health = HealthScoreCalculator.projectScore(summary(), errorCount = 0, warningCount = 0)

        assertEquals(100, health.score)
        assertEquals("Healthy", health.band)
        assertTrue(health.components.all { it.penalty == 0 })
    }

    @Test
    fun `every signal is reported even when it deducts nothing`() {
        val health = HealthScoreCalculator.projectScore(summary(), errorCount = 0, warningCount = 0)

        assertEquals(
            listOf("Cycles", "Blocking violations", "Advisories", "Coupling hubs", "Average instability"),
            health.components.map { it.label },
        )
    }

    @Test
    fun `penalties sum to the deduction from 100`() {
        val health = HealthScoreCalculator.projectScore(
            summary(cycleCount = 2, godModuleCount = 1, averageInstability = 0.7),
            errorCount = 3,
            warningCount = 2,
        )

        assertEquals(100 - health.components.sumOf { it.penalty }, health.score)
    }

    @Test
    fun `each signal is capped so one problem cannot zero the score`() {
        val health = HealthScoreCalculator.projectScore(
            summary(cycleCount = 500),
            errorCount = 0,
            warningCount = 0,
        )

        val cycles = health.components.single { it.label == "Cycles" }
        assertEquals(cycles.maxPenalty, cycles.penalty)
        assertTrue(health.score > 0, "A capped signal must leave the score above zero, got ${health.score}")
    }

    @Test
    fun `average instability at or below the floor is not penalised`() {
        val atFloor = HealthScoreCalculator.projectScore(
            summary(averageInstability = 0.5), errorCount = 0, warningCount = 0
        )
        val aboveFloor = HealthScoreCalculator.projectScore(
            summary(averageInstability = 0.9), errorCount = 0, warningCount = 0
        )

        assertEquals(0, atFloor.components.single { it.label == "Average instability" }.penalty)
        assertTrue(aboveFloor.components.single { it.label == "Average instability" }.penalty > 0)
    }

    @Test
    fun `band tracks the score`() {
        val healthy = HealthScoreCalculator.projectScore(summary(), 0, 0)
        val fair = HealthScoreCalculator.projectScore(summary(cycleCount = 2), errorCount = 1, warningCount = 0)
        val atRisk = HealthScoreCalculator.projectScore(
            summary(cycleCount = 5, godModuleCount = 5, averageInstability = 1.0),
            errorCount = 10,
            warningCount = 10,
        )

        assertEquals("Healthy", healthy.band)
        assertEquals("Fair", fair.band)
        assertEquals("At risk", atRisk.band)
    }

    @Test
    fun `project score is always in range 0 to 100`() {
        val worst = HealthScoreCalculator.projectScore(
            summary(cycleCount = 999, godModuleCount = 999, averageInstability = 1.0),
            errorCount = 999,
            warningCount = 999,
        )

        assertTrue(worst.score in 0..100, "Score ${worst.score} out of range")
    }
}