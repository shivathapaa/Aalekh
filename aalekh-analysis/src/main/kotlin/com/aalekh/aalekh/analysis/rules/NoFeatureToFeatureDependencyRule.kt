package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Prevents feature modules from depending on each other.
 *
 * Feature-to-feature dependencies are how Android projects accidentally become
 * monoliths. This rule catches them at the moment the dependency is added rather
 * than after the coupling has compounded over many months.
 *
 * Specific pairs can be allowed via [allowedPairs] (populated from the
 * `featureIsolation { allow(...) }` DSL block).
 *
 * @param featurePattern Glob pattern identifying feature modules, e.g. `":feature:**"`.
 * @param allowedPairs Serialized allow-pairs in `"from->to"` format.
 */
internal class NoFeatureToFeatureDependencyRule(
    private val featurePattern: String,
    private val allowedPairs: List<String>,
) : ArchRule {

    override val id = "no-feature-to-feature"
    override val description = "Feature modules must not depend on each other."
    override val defaultSeverity = Severity.ERROR
    override val plainLanguageExplanation =
        "Feature modules should be independently deliverable slices of functionality. " +
                "When features depend on each other they become tightly coupled, making it " +
                "impossible to develop, test, or ship them independently."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        val featureModules = graph.modules
            .filter { GlobMatcher.matches(featurePattern, it.path) }
            .map { it.path }
            .toSet()

        if (featureModules.size < 2) return emptyList()

        val violations = mutableListOf<Violation>()

        for (edge in graph.edges) {
            if (edge.isTest) continue
            if (edge.from !in featureModules) continue
            if (edge.to !in featureModules) continue
            if (isAllowed(edge.from, edge.to)) continue

            val fromModule = graph.moduleByPath(edge.from)
            val buildFileHint = fromModule?.buildFilePath
                ?.let { " Edit $it and remove: implementation(project(\"${edge.to}\"))" }
                ?: ""

            violations += Violation(
                ruleId = id,
                severity = defaultSeverity,
                message = "Feature module ${edge.from} depends on feature module ${edge.to}. " +
                        "Extract shared code into a :core or :shared module instead.$buildFileHint",
                source = "${edge.from} → ${edge.to}",
                moduleHint = edge.from,
                plainLanguageExplanation = plainLanguageExplanation,
            )
        }

        return violations
    }

    private fun isAllowed(from: String, to: String): Boolean =
        allowedPairs.any { pair ->
            val (fromPat, toPat) = pair.split("->", limit = 2)
                .let { it[0] to (it.getOrElse(1) { "" }) }
            GlobMatcher.matches(fromPat, from) && GlobMatcher.matches(toPat, to)
        }
}