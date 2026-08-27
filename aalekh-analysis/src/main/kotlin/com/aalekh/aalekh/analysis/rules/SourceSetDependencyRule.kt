package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Forbids dependencies owned by a specific **source set** from targeting a selected set of modules -
 * the general, configurable form of [KmpCommonMainRule].
 *
 * Every edge in the graph records the source set that declared it (from the Kotlin Multiplatform
 * configuration name - `commonMain`, `androidMain`, `iosMain`, ...). This rule flags any production
 * edge owned by [sourceSet] whose target the [to] matcher selects. It expresses per-source-set
 * direction constraints the module graph can already answer, for example:
 *
 * - `iosMain` must not depend on an `ANDROID_LIBRARY` module,
 * - `androidMain` must not depend on `:platform:desktop:**`.
 *
 * All instances share the stable id `source-set-dependency`; the [reason] and [sourceSet]
 * distinguish them in messages and the report. Only production edges are considered; a module never
 * trips the rule on its own self-loop.
 *
 * @param sourceSet The owning source-set name an offending edge must belong to.
 * @param to Selects the forbidden dependency targets (by path glob or module type).
 * @param reason Why the dependency is disallowed - surfaced in the violation message.
 * @param defaultSeverity Severity for matches; `ERROR` fails the build.
 */
internal class SourceSetDependencyRule(
    private val sourceSet: String,
    private val to: ModuleMatcher,
    private val reason: String,
    override val defaultSeverity: Severity,
) : ArchRule {

    override val id: String = RULE_ID
    override val description: String =
        "Source set '$sourceSet' must not depend on ${to.describe()}."
    override val plainLanguageExplanation: String = reason.ifBlank {
        "Dependencies declared in the '$sourceSet' source set are constrained by a project-defined rule. " +
            "A source set only compiles for the targets it belongs to, so an out-of-bounds dependency " +
            "either fails to compile for some targets or quietly narrows the module to fewer platforms."
    }

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
        graph.edges
            .asSequence()
            .filter { !it.isTest && it.sourceSet == sourceSet && it.from != it.to }
            .filter { to.matches(it.to, graph) }
            .map { edge -> toViolation(edge.from, edge.to, graph) }
            .toList()

    private fun toViolation(fromPath: String, toPath: String, graph: ModuleDependencyGraph): Violation {
        val why = reason.ifBlank { "it is disallowed by a project-defined source-set rule" }
        val targetType = graph.moduleByPath(toPath)?.type?.name?.let { " ($it)" } ?: ""
        return Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "$fromPath depends on $toPath$targetType from its '$sourceSet' source set: $why.",
            source = "$fromPath → $toPath",
            moduleHint = fromPath,
            plainLanguageExplanation = plainLanguageExplanation,
        )
    }

    companion object {
        /** Stable rule id shared by every source-set dependency rule. A public contract. */
        const val RULE_ID: String = "source-set-dependency"
    }
}
