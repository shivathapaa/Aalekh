package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.analysis.metrics.ModuleGraphMetrics
import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.Severity

/**
 * Findings about **dependencies** - both the third-party libraries the project pulls in and the
 * shape of what modules expose to each other.
 */
internal object DependencyFinders {

    /** API surface above this share means a module re-exports most of what it depends on. */
    private const val LEAKY_API_RATIO = 0.8

    /** A library used by at least this share of modules is effectively part of the platform. */
    private const val UBIQUITOUS_SHARE = 50.0

    private const val MAX_NAMED = 4

    fun findAll(context: NarrativeContext): List<Finding> = listOfNotNull(
        versionFragmentation(context),
        ubiquitousLibraries(context),
        leakyApiSurface(context),
        layerBoundaryLeaks(context),
    )

    /** The same library declared at more than one version across the project. */
    private fun versionFragmentation(context: NarrativeContext): Finding? {
        val conflicts = context.graph.externalDependencies
            .filter { it.version != null }
            .groupBy { "${it.group}:${it.name}" }
            .mapValues { (_, deps) -> deps.mapNotNull { it.version }.distinct().sorted() }
            .filterValues { it.size > 1 }
            .toSortedMap()
        if (conflicts.isEmpty()) return null

        return Finding(
            id = "version-fragmentation",
            category = FindingCategory.DEPENDENCY,
            severity = Severity.WARNING,
            title = "${Phrasing.count(conflicts.size, "library")} declared at more than one version",
            detail = "${Phrasing.list(conflicts.keys.toList(), MAX_NAMED)} " +
                    "${Phrasing.verb(conflicts.size)} declared at different versions in different " +
                    "modules. Gradle will resolve a single version anyway, so at least one module is " +
                    "compiling against a version it did not ask for.",
            evidence = conflicts.entries.take(MAX_NAMED).map { (coordinate, versions) ->
                Phrasing.observed(coordinate, versions.joinToString(", "))
            },
            action = "Align them through a version catalog or a platform/BOM so the declared version " +
                    "matches the resolved one.",
        )
    }

    /** Third-party libraries so widely used they are effectively part of this project's platform. */
    private fun ubiquitousLibraries(context: NarrativeContext): Finding? {
        val total = context.graph.modules.size
        val usage = if (total == 0) {
            emptyList()
        } else {
            context.graph.externalDependencies
                .filterNot { it.isTest }
                .groupBy { "${it.group}:${it.name}" }
                .mapValues { (_, deps) -> deps.map { it.module }.distinct().size }
                .entries
                .filter { it.value * PERCENT / total >= UBIQUITOUS_SHARE }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        }
        if (usage.isEmpty()) return null

        return Finding(
            id = "ubiquitous-libraries",
            category = FindingCategory.DEPENDENCY,
            severity = Severity.INFO,
            title = "${Phrasing.count(usage.size, "library")} used across most of the project",
            detail = "${Phrasing.list(usage.take(MAX_NAMED).map { it.key })} " +
                    "${Phrasing.verb(usage.size)} declared by at least half the modules. Libraries " +
                    "this pervasive are part of the project's platform in practice: changing or " +
                    "removing one is an architectural decision, not a dependency bump.",
            evidence = usage.take(MAX_NAMED).map { (coordinate, modules) ->
                Phrasing.observed(coordinate, "$modules of $total modules (${Phrasing.share(modules, total)})")
            },
        )
    }

    /** Modules that re-export nearly everything they depend on. */
    private fun leakyApiSurface(context: NarrativeContext): Finding? {
        val leaky = context.metrics.modules.values
            .filter { it.apiSurfaceRatio >= LEAKY_API_RATIO && it.fanOut >= MIN_FANOUT_FOR_LEAK && it.fanIn > 0 }
            .sortedWith(compareByDescending<ModuleGraphMetrics> { it.fanOut }.thenBy { it.path })
        if (leaky.isEmpty()) return null

        return Finding(
            id = "leaky-api-surface",
            category = FindingCategory.DEPENDENCY,
            severity = Severity.INFO,
            title = "${Phrasing.count(leaky.size, "module")} re-export almost everything they depend on",
            detail = "${Phrasing.list(leaky.map { it.path }, MAX_NAMED)} declare most of their " +
                    "dependencies as `api`, which pushes them onto every consumer's compile classpath. " +
                    "That widens each consumer's blast radius too: a change deep in the graph now " +
                    "reaches everything downstream of these modules.",
            evidence = leaky.take(MAX_NAMED).map {
                Phrasing.computed(
                    it.path,
                    "${Phrasing.percent(it.apiSurfaceRatio * PERCENT)} of ${it.fanOut} dependencies are api",
                )
            },
            subjects = leaky.map { it.path },
            action = "Convert `api` to `implementation` for any dependency whose types do not appear " +
                    "in the module's own public signatures.",
        )
    }

    /**
     * Layer violations grouped by the boundary they cross, so a hundred violations read as the two
     * or three architectural decisions that actually caused them.
     */
    private fun layerBoundaryLeaks(context: NarrativeContext): Finding? {
        val leaks = if (context.layers.isEmpty()) emptyList() else context.violations
            .filter { it.ruleId == "layer-dependency" }
            .mapNotNull { violation ->
                val from = violation.moduleHint ?: return@mapNotNull null
                val to = violation.source.substringAfter("→", "").trim().ifEmpty { return@mapNotNull null }
                val fromLayer = context.layerOf(from) ?: return@mapNotNull null
                val toLayer = context.layerOf(to) ?: return@mapNotNull null
                "$fromLayer → $toLayer"
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        if (leaks.isEmpty()) return null

        val worst = leaks.first()
        return Finding(
            id = "layer-boundary-leaks",
            category = FindingCategory.DEPENDENCY,
            severity = Severity.ERROR,
            title = "Layer violations concentrate on ${Phrasing.count(leaks.size, "boundary", "boundaries")}",
            detail = "The ${worst.key} boundary accounts for " +
                    "${Phrasing.count(worst.value, "violation")}, the largest single group. " +
                    "Violations cluster on a boundary when one decision was made repeatedly, so " +
                    "fixing the decision usually fixes them all at once.",
            evidence = leaks.take(MAX_NAMED).map { (boundary, count) ->
                Phrasing.computed(boundary, Phrasing.count(count, "violation"))
            },
            action = "Address the worst boundary first rather than the violations one by one.",
        )
    }

    private const val PERCENT = 100.0
    private const val MIN_FANOUT_FOR_LEAK = 3
}
