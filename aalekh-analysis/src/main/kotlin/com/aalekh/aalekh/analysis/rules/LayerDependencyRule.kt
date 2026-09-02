package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.analysis.rules.LayerDependencyRule.Companion.fromSerializedLayers
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Enforces the dependency direction between declared architectural layers.
 *
 * A module in layer A must not depend on a module in layer B unless layer A
 * declares `canOnlyDependOn("B")` or layer B is in A's allowed list.
 *
 * This rule is constructed via [fromSerializedLayers] from the string entries
 * passed through task inputs (for CC safety). It is not instantiated directly.
 */
internal class LayerDependencyRule(
    private val layers: List<LayerSpec>,
) : ArchRule {

    override val id = "layer-dependency"
    override val description = "Modules must only depend on modules in permitted layers."
    override val defaultSeverity = Severity.ERROR
    override val plainLanguageExplanation =
        "Layered architecture keeps concerns separate — e.g. data modules should not " +
                "import from UI modules. Violations create hidden coupling that makes " +
                "code hard to test and refactor independently."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> {
        if (layers.none { it.hasRestriction }) return emptyList()

        // Pre-resolve which layer each module belongs to. First declared match wins - the same
        // resolution the HTML report uses, so the swimlane a module is drawn in is always the layer
        // whose rules apply to it.
        val moduleToLayer: Map<String, LayerSpec> = buildMap {
            for (module in graph.modules) {
                LayerSpecParser.layerOf(layers, module.path)?.let { put(module.path, it) }
            }
        }

        val violations = mutableListOf<Violation>()

        for (edge in graph.edges) {
            if (edge.isTest) continue

            val fromLayer = moduleToLayer[edge.from] ?: continue
            if (!fromLayer.hasRestriction) continue

            val toLayer = moduleToLayer[edge.to] ?: continue

            if (fromLayer.name == toLayer.name) continue // same layer, always OK
            if (fromLayer.allowedLayers.contains(toLayer.name)) continue

            val fromModule = graph.moduleByPath(edge.from)
            val buildFileHint = fromModule?.buildFilePath
                ?.let { " Edit $it and remove: implementation(project(\"${edge.to}\"))" }
                ?: ""

            violations += Violation(
                ruleId = id,
                severity = defaultSeverity,
                message = "${edge.from} (layer '${fromLayer.name}') depends on " +
                        "${edge.to} (layer '${toLayer.name}'). " +
                        "Layer '${fromLayer.name}' may only depend on: " +
                        fromLayer.allowedLayers.joinToString(", ").ifEmpty { "(nothing)" } +
                        ".$buildFileHint",
                source = "${edge.from} → ${edge.to}",
                moduleHint = edge.from,
                plainLanguageExplanation = plainLanguageExplanation,
            )
        }

        return violations
    }

    companion object {
        /**
         * Deserializes layer entries from the format used in task @Input properties, via the shared
         * [LayerSpecParser] - the same parse the report and [UncoveredModuleRule] use.
         */
        fun fromSerializedLayers(entries: List<String>): LayerDependencyRule =
            LayerDependencyRule(LayerSpecParser.parse(entries))
    }
}