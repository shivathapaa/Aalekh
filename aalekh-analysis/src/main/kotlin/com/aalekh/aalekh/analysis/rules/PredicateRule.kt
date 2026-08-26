package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * A user-defined structural rule from the `forbid { }` predicate DSL: forbids any production
 * dependency from a module the [from] matcher selects to a module the [to] matcher selects.
 *
 * This gives the common "X must not depend on Y" case a one-line DSL form without writing a custom
 * [ArchRule] class and shipping it on the classpath. All predicate rules share the stable id
 * `forbidden-dependency`; the [reason] distinguishes them in messages and the report.
 *
 * @param from Selects the depending modules.
 * @param to Selects the forbidden dependency targets.
 * @param reason Why this dependency is disallowed - surfaced in the violation message.
 * @param defaultSeverity Severity for matches; `ERROR` fails the build.
 */
internal class PredicateRule(
    private val from: ModuleMatcher,
    private val to: ModuleMatcher,
    private val reason: String,
    override val defaultSeverity: Severity,
) : ArchRule {

    override val id: String = RULE_ID
    override val description: String = "Forbidden dependency: ${from.describe()} → ${to.describe()}."
    override val plainLanguageExplanation: String =
        reason.ifBlank { "This dependency direction is disallowed by a project-defined rule." }

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
        graph.edges
            .asSequence()
            .filter { !it.isTest && it.from != it.to }
            .filter { from.matches(it.from, graph) && to.matches(it.to, graph) }
            .map { edge -> toViolation(edge.from, edge.to, graph) }
            .toList()

    private fun toViolation(fromPath: String, toPath: String, graph: ModuleDependencyGraph): Violation {
        val buildFileHint = graph.moduleByPath(fromPath)?.buildFilePath
            ?.let { " Edit $it and remove: implementation(project(\"$toPath\"))." }
            ?: ""
        val why = reason.ifBlank { "it is disallowed by a project-defined rule" }
        return Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "$fromPath must not depend on $toPath: $why.$buildFileHint",
            source = "$fromPath → $toPath",
            moduleHint = fromPath,
            plainLanguageExplanation = plainLanguageExplanation,
        )
    }

    companion object {
        /** Stable rule id shared by every predicate rule. A public contract (SARIF, suppressions). */
        const val RULE_ID: String = "forbidden-dependency"
    }
}
