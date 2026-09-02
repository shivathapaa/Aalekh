package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Flags modules that belong to no declared layer - the "did you forget to classify this?" check.
 *
 * Layered architecture only holds if every module is actually placed in a layer. A module matched by
 * none of the `layers { }` patterns slips past [LayerDependencyRule] entirely: it can depend on
 * anything and nothing constrains what depends on it. This rule makes layer coverage exhaustive, so a
 * newly added module cannot quietly escape the architecture by not being classified.
 *
 * The rule draws its patterns from the same `layers { }` declarations as [LayerDependencyRule]; it
 * does nothing until at least one layer is declared. Opt in with `rules { requireLayerForAllModules() }`.
 */
internal class UncoveredModuleRule(
    private val layerPatterns: List<String>,
) : ArchRule {

    override val id: String = "uncovered-module"
    override val description: String = "Every module must belong to a declared architectural layer."
    override val defaultSeverity: Severity = Severity.WARNING
    override val plainLanguageExplanation: String =
        "A module that matches no layer pattern is outside the architecture: the layer rules never " +
                "see it, so it can depend on anything. Add it to a layer, or widen an existing " +
                "layer's patterns to include it."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        if (layerPatterns.isEmpty()) return emptyList()
        return graph.modules
            .filterNot { module -> GlobMatcher.matchesAny(layerPatterns, module.path) }
            .map { module -> violation(module) }
    }

    private fun violation(module: ModuleNode): Violation {
        val buildFileHint = module.buildFilePath?.let { " (declared in $it)" } ?: ""
        return Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "${module.path} belongs to no declared layer$buildFileHint - it is exempt from " +
                    "all layer rules. Assign it to a layer in the layers { } block.",
            source = module.path,
            moduleHint = module.path,
            plainLanguageExplanation = plainLanguageExplanation,
        )
    }

    companion object {
        /**
         * Extracts the flat list of module patterns from serialized `layers { }` entries, via the
         * shared [LayerSpecParser] so this rule and [LayerDependencyRule] can never disagree about
         * which modules a layer covers.
         */
        fun fromSerializedLayers(entries: List<String>): UncoveredModuleRule =
            UncoveredModuleRule(LayerSpecParser.parse(entries).flatMap { it.modulePatterns })
    }
}
