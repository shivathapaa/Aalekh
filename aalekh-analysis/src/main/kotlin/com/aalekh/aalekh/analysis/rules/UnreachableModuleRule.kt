package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Requires that every module matching a pattern is reachable from a declared root - `mustBeReachableFrom`.
 *
 * Use it to prove a module is actually wired into the build: "every `:feature:*` must be reachable
 * from `:app`". A feature that no production path from `:app` reaches is dead in that entry point -
 * it compiles but ships to nobody, the classic "we forgot to add it to the app" bug. This is the
 * targeted, root-relative counterpart to the graph-wide [NoOrphanModulesRule].
 *
 * Reconstructed from a serialized `rules { mustBeReachableFrom(...) }` entry for configuration-cache
 * safety.
 */
internal class UnreachableModuleRule(
    private val modulePattern: String,
    private val fromPattern: String,
    private val reason: String,
    override val defaultSeverity: Severity,
) : ArchRule {

    override val id: String = "unreachable-module"
    override val description: String =
        "Modules matching a pattern must be reachable from a declared root module."
    override val plainLanguageExplanation: String =
        "A module that no production path from the declared root reaches is not actually wired into " +
                "that entry point - it builds but nothing ships it. Wire it into the graph, or drop " +
                "the requirement if the module is intentionally standalone."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        if (modulePattern.isBlank() || fromPattern.isBlank()) return emptyList()

        // Union of everything reachable from any root matching fromPattern.
        val reachable = graph.modules.asSequence()
            .filter { GlobMatcher.matches(fromPattern, it.path) }
            .flatMap { GraphReachability.productionReachable(graph, it.path).asSequence() }
            .toSet()

        return graph.modules
            .filter { GlobMatcher.matches(modulePattern, it.path) && it.path !in reachable }
            .map { module -> violation(module.path) }
    }

    private fun violation(module: String): Violation {
        val why = if (reason.isBlank()) "" else " $reason"
        return Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "$module is not reachable from any module matching '$fromPattern' over " +
                    "production dependencies - it appears unused from that root.$why",
            source = module,
            moduleHint = module,
            plainLanguageExplanation = plainLanguageExplanation,
        )
    }
}
