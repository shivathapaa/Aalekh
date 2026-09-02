package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.analysis.rules.LayerSpecParser
import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.Severity

/**
 * Findings about **who owns what** - unowned modules, modules outside the declared architecture, and
 * dependencies that cross team boundaries.
 *
 * Every finding here is silent unless the project actually declares `teams { }` or `layers { }`.
 * Inventing an ownership problem for a project that never claimed to have owners would be noise.
 */
internal object OwnershipFinders {

    private const val MAX_NAMED = 4

    fun findAll(context: NarrativeContext): List<Finding> = listOfNotNull(
        unownedModules(context),
        unclassifiedModules(context),
        crossTeamDependencies(context),
    )

    /** Modules no declared team claims. */
    private fun unownedModules(context: NarrativeContext): Finding? {
        val unowned = if (!context.hasOwnershipData) {
            emptyList()
        } else {
            context.graph.modules.map { it.path }.filter { context.teamOf(it) == null }.sorted()
        }
        if (unowned.isEmpty()) return null

        return Finding(
            id = "unowned-modules",
            category = FindingCategory.OWNERSHIP,
            severity = Severity.WARNING,
            title = "${Phrasing.count(unowned.size, "module")} " +
                    "${Phrasing.verb(unowned.size)} claimed by no team",
            detail = "${Phrasing.list(unowned, MAX_NAMED)} " +
                    "${if (unowned.size == 1) "matches" else "match"} none of the declared team " +
                    "patterns. An unowned module has no obvious reviewer, and tends to accumulate " +
                    "changes nobody is accountable for.",
            evidence = listOf(
                Phrasing.computed("Unowned", Phrasing.list(unowned, MAX_NAMED)),
                Phrasing.observed(
                    "Ownership sources",
                    listOfNotNull(
                        context.teams.keys.sorted().joinToString(", ").ifBlank { null }
                            ?.let { "teams { }: $it" },
                        "CODEOWNERS".takeIf { context.inventory.codeowners.isNotEmpty() },
                        ".aalekh/modules.json".takeIf {
                            context.inventory.declaredMetadata.values.any { m -> m.owner != null }
                        },
                    ).joinToString(" · "),
                ),
            ),
            subjects = unowned,
            action = "Widen a teams { } pattern, add a CODEOWNERS rule, or name the owner in " +
                    ".aalekh/modules.json.",
        )
    }

    /** Modules that belong to no declared layer, and so escape every layer rule. */
    private fun unclassifiedModules(context: NarrativeContext): Finding? {
        val unclassified = if (context.layers.isEmpty()) {
            emptyList()
        } else {
            context.graph.modules
                .map { it.path }
                .filter { LayerSpecParser.layerOf(context.layers, it) == null }
                .sorted()
        }
        if (unclassified.isEmpty()) return null

        return Finding(
            id = "unclassified-modules",
            category = FindingCategory.OWNERSHIP,
            severity = Severity.WARNING,
            title = "${Phrasing.count(unclassified.size, "module")} " +
                    "${Phrasing.verb(unclassified.size)} outside the declared architecture",
            detail = "${Phrasing.list(unclassified, MAX_NAMED)} " +
                    "${Phrasing.verb(unclassified.size)} matched by no layer pattern, so the layer " +
                    "rules never see ${if (unclassified.size == 1) "it" else "them"}: " +
                    "${if (unclassified.size == 1) "it" else "they"} may depend on anything, and " +
                    "nothing constrains what depends on ${if (unclassified.size == 1) "it" else "them"}.",
            evidence = listOf(
                Phrasing.computed("Unclassified", Phrasing.list(unclassified, MAX_NAMED)),
                Phrasing.observed("Declared layers", context.layers.joinToString(", ") { it.name }),
            ),
            subjects = unclassified,
            action = "Assign them to a layer, or enable requireLayerForAllModules() to fail the build " +
                    "when a new module escapes classification.",
        )
    }

    /** How much of the dependency graph crosses a team boundary. */
    private fun crossTeamDependencies(context: NarrativeContext): Finding? {
        val production = if (context.teams.size < MIN_TEAMS) {
            emptyList()
        } else {
            context.graph.edges.filter { !it.isTest && it.from != it.to }
        }
        val crossings = production
            .mapNotNull { edge ->
                val from = context.teamOf(edge.from) ?: return@mapNotNull null
                val to = context.teamOf(edge.to) ?: return@mapNotNull null
                if (from == to) null else "$from → $to"
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        if (crossings.isEmpty()) return null

        val total = crossings.sumOf { it.value }
        return Finding(
            id = "cross-team-dependencies",
            category = FindingCategory.OWNERSHIP,
            severity = Severity.INFO,
            title = "${Phrasing.share(total, production.size)} of dependencies cross a team boundary",
            detail = "${Phrasing.count(total, "dependency", "dependencies")} of " +
                    "${production.size} run between modules owned by different teams. The busiest is " +
                    "${crossings.first().key} with ${Phrasing.count(crossings.first().value, "edge")}. " +
                    "Every crossing is a coordination cost: a change on one side needs a review on the " +
                    "other.",
            evidence = crossings.take(MAX_NAMED).map { (pair, count) ->
                Phrasing.computed(pair, Phrasing.count(count, "dependency", "dependencies"))
            },
        )
    }

    private const val MIN_TEAMS = 2
}
