package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Forbids one set of modules from *transitively* depending on another - `forbidReachable`.
 *
 * Where the `forbid { }` predicate ([PredicateRule]) checks direct edges only, this rule checks the
 * whole production reachability closure: a module matching [fromPattern] must not be able to reach
 * any module matching [toPattern] through any chain of production dependencies, however long. It
 * catches the indirect leak a direct-edge rule misses - `:core:domain` pulling in an Android module
 * three hops away via an innocent-looking utility dependency.
 *
 * Reconstructed from a serialized `rules { forbidReachable(...) }` entry for configuration-cache
 * safety; never instantiated with a live lambda.
 */
internal class ForbiddenTransitiveDependencyRule(
    private val fromPattern: String,
    private val toPattern: String,
    private val reason: String,
    override val defaultSeverity: Severity,
) : ArchRule {

    override val id: String = "forbidden-transitive-dependency"
    override val description: String =
        "Modules matching one pattern must not transitively depend on modules matching another."
    override val plainLanguageExplanation: String =
        "A forbidden dependency can hide several hops away - a module you think is clean pulls in " +
                "a banned module through a chain of intermediaries. This rule follows the full " +
                "production dependency closure, not just direct edges, so the leak cannot hide."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        if (fromPattern.isBlank() || toPattern.isBlank()) return emptyList()
        return graph.modules
            .filter { GlobMatcher.matches(fromPattern, it.path) }
            .flatMap { source ->
                GraphReachability.productionReachable(graph, source.path)
                    .filter { GlobMatcher.matches(toPattern, it) }
                    .map { reached -> violation(source.path, reached) }
            }
    }

    private fun violation(from: String, to: String): Violation {
        val why = if (reason.isBlank()) "" else " $reason"
        return Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "$from transitively depends on $to (matched by forbidReachable " +
                    "'$fromPattern' -> '$toPattern').$why",
            source = "$from ⇒ $to",
            moduleHint = from,
            plainLanguageExplanation = plainLanguageExplanation,
        )
    }
}
