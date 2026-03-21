package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Fails if the module graph contains any dependency cycle in main (non-test) code.
 *
 * Test dependencies are excluded because test code legitimately creates apparent
 * cycles - e.g. module A's tests depending on module B is not a real cycle.
 * Test-only cycles are reported as INFO (visible in report, do not fail the build).
 */
internal class NoCyclicDependenciesRule : ArchRule {
    override val id = "no-cyclic-dependencies"
    override val description =
        "The module dependency graph must be acyclic (a DAG) in production code."
    override val defaultSeverity = Severity.ERROR
    override val plainLanguageExplanation =
        "Circular dependencies in main code make modules impossible to understand " +
                "and refactor independently. Extract shared logic into a new module that " +
                "all cycle participants depend on."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        val violations = mutableListOf<Violation>()

        val mainCycles = findCycles(graph, includeTest = false)
        val mainCycleNodeSets = mainCycles.map { it.toSet() }

        mainCycles.forEach { cycle ->
            violations += Violation(
                ruleId = id,
                severity = Severity.ERROR,
                message = "Cyclic dependency in main code: ${cycle.joinToString(" → ")}. " +
                        "Extract the shared logic into a new module.",
                source = cycle.joinToString(" → "),
                moduleHint = cycle.firstOrNull(),
                plainLanguageExplanation = plainLanguageExplanation,
            )
        }

        // Cycles that only exist via test edges - common and acceptable.
        val allCycles = findCycles(graph, includeTest = true)
        allCycles
            .filter { cycle -> mainCycleNodeSets.none { it == cycle.toSet() } }
            .forEach { cycle ->
                violations += Violation(
                    ruleId = "test-cyclic-dependency",
                    severity = Severity.INFO,
                    message = "Cyclic dependency via test code: ${cycle.joinToString(" → ")}. " +
                            "Test-only cycles do not fail the build.",
                    source = cycle.joinToString(" → "),
                    moduleHint = cycle.firstOrNull(),
                    plainLanguageExplanation = "This cycle only exists through test dependencies " +
                            "and is common when test code in one module references another. " +
                            "It does not affect production builds.",
                )
            }

        return violations
    }

    private fun findCycles(graph: ModuleDependencyGraph, includeTest: Boolean): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        val onPath = mutableSetOf<String>()
        val seenCycleSets = mutableSetOf<Set<String>>()

        fun dfs(node: String) {
            if (node in onPath) {
                val start = path.indexOf(node)
                if (start >= 0) {
                    val cycle = path.subList(start, path.size).toList()
                    if (cycle.size >= 2 && seenCycleSets.add(cycle.toSet())) cycles += cycle
                }
                return
            }
            if (node in visited) return
            visited += node; onPath += node; path += node
            graph.edgesFrom(node)
                .filter { it.to != node }
                .filter { includeTest || !it.isTest }
                .forEach { dfs(it.to) }
            path.removeAt(path.size - 1)
            onPath -= node
        }

        graph.modules.forEach { if (it.path !in visited) dfs(it.path) }
        return cycles
    }
}