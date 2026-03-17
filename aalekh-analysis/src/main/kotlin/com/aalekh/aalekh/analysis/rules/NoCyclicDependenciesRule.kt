package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Fails if the module graph contains any dependency cycle in **main** (non-test) code.
 *
 * Test dependencies (testImplementation, androidTestImplementation, etc.) are excluded
 * from cycle detection because test code legitimately references modules in ways that
 * would form cycles if counted - e.g. module A's test code depending on module B is
 * not a real circular dependency.
 *
 * Test-only cycles are reported as INFO-level (visible in the report, do not fail the build).
 */
internal class NoCyclicDependenciesRule : ArchRule {
    override val id = "no-cyclic-dependencies"
    override val description = "The module dependency graph must be acyclic (a DAG) in production code."
    override val defaultSeverity = Severity.ERROR

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        val violations = mutableListOf<Violation>()

        // Find main-code cycles (ERROR)
        val mainCycles = findCycles(graph, includeTest = false)
        val mainCycleNodeSets = mainCycles.map { it.toSet() }

        mainCycles.forEach { cycle ->
            violations += Violation(
                ruleId = id,
                severity = Severity.ERROR,
                message = "Cyclic dependency detected in main code: ${cycle.joinToString(" → ")}. " +
                        "Extract the shared logic into a new module that all cycle participants depend on. " +
                        "Circular dependencies make code impossible to understand and refactor independently.",
                source = cycle.firstOrNull() ?: "unknown",
            )
        }

        // Find test-only cycles (INFO) - cycles that ONLY exist because of test edges.
        // These are cycles in the all-edges graph whose node-sets don't appear in main cycles.
        val allCycles = findCycles(graph, includeTest = true)
        val testOnlyCycles = allCycles.filter { cycle ->
            val nodeSet = cycle.toSet()
            // Exclude if any main cycle has the same set of nodes
            mainCycleNodeSets.none { mainSet -> mainSet == nodeSet }
        }

        testOnlyCycles.forEach { cycle ->
            violations += Violation(
                ruleId = "test-cyclic-dependency",
                severity = Severity.INFO,
                message = "Cyclic dependency via test code: ${cycle.joinToString(" → ")}. " +
                        "This is common when test code in module A depends on module B. " +
                        "Test-only cycles do not fail the build.",
                source = cycle.firstOrNull() ?: "unknown",
            )
        }

        return violations
    }

    private fun findCycles(
        graph: ModuleDependencyGraph,
        includeTest: Boolean,
    ): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        val onPath = mutableSetOf<String>()
        // Track seen cycle node-sets to avoid reporting the same cycle from different starting nodes
        val seenCycleSets = mutableSetOf<Set<String>>()

        fun dfs(node: String) {
            if (node in onPath) {
                val cycleStart = path.indexOf(node)
                if (cycleStart >= 0) {
                    val cycle = path.subList(cycleStart, path.size).toList()
                    if (cycle.size >= 2) {
                        val nodeSet = cycle.toSet()
                        if (seenCycleSets.add(nodeSet)) {
                            cycles += cycle
                        }
                    }
                }
                return
            }
            if (node in visited) return
            visited += node; onPath += node; path += node
            graph.edgesFrom(node)
                .filter { it.to != node }
                .filter { includeTest || !it.isTest }
                .forEach { dfs(it.to) }
            path.removeAt(path.size - 1); onPath -= node
        }

        graph.modules.forEach { if (it.path !in visited) dfs(it.path) }
        return cycles
    }
}