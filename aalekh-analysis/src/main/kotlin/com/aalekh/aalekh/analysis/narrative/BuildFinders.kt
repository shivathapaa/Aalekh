package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.ModuleBuildInfo
import com.aalekh.aalekh.model.Provenance
import com.aalekh.aalekh.model.Severity

/**
 * Findings about **how the project is built** rather than how it is wired together.
 *
 * The dependency graph says nothing about whether the build is configured consistently, yet that is
 * where a surprising amount of a team's time goes: a module on the wrong toolchain, a plugin applied
 * at two versions, a convention plugin everyone uses except three modules that were copy-pasted
 * before it existed. Every finding here is [Provenance.OBSERVED] - read from the build, not inferred.
 *
 * All of it is silent when the inventory is empty, so a graph extracted by an older plugin version
 * simply produces no build findings rather than misleading ones.
 */
internal object BuildFinders {

    private const val MAX_NAMED = 4

    /** A plugin on fewer than this share of a type's modules is unusual enough to point out. */
    private const val UNUSUAL_PLUGIN_SHARE = 0.15

    /** Below this many modules of a type, "most of them do X" is not a meaningful claim. */
    private const val MIN_GROUP_FOR_DRIFT = 4

    fun findAll(context: NarrativeContext): List<Finding> {
        if (context.inventory.isEmpty) return emptyList()
        return listOfNotNull(
            toolchainSplit(context),
            pluginVersionSplit(context),
            conventionPluginAdoption(context),
            unusualPlugins(context),
            untestedModules(context),
            multiplatformTargets(context),
        )
    }

    /** Modules compiling against different Java language versions. */
    private fun toolchainSplit(context: NarrativeContext): Finding? {
        val byVersion = context.inventory.modules
            .mapNotNull { info -> info.javaToolchain?.let { it to info.path } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
        if (byVersion.size < MIN_DISTINCT) return null

        return Finding(
            id = "toolchain-split",
            category = FindingCategory.BUILD,
            severity = Severity.WARNING,
            title = "Modules are built against ${Phrasing.count(byVersion.size, "Java version")}",
            detail = "The project requests " +
                    "${Phrasing.list(byVersion.keys.map { "Java $it" }, MAX_NAMED)} across different " +
                    "modules. Mixed toolchains compile fine and then fail at runtime, because a " +
                    "module built for a newer JVM can emit bytecode an older consumer cannot load.",
            evidence = byVersion.entries.take(MAX_NAMED).map { (version, paths) ->
                Phrasing.observed("Java $version", Phrasing.list(paths, MAX_NAMED))
            },
            subjects = byVersion.values.flatten(),
            provenance = Provenance.OBSERVED,
            action = "Set one toolchain in a convention plugin and let every module inherit it.",
        )
    }

    /** The same plugin applied at more than one version. */
    private fun pluginVersionSplit(context: NarrativeContext): Finding? {
        val byPlugin = context.inventory.modules
            .flatMap { info -> info.plugins.mapNotNull { p -> p.version?.let { p.id to it } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, versions) -> versions.distinct().sorted() }
            .filterValues { it.size > 1 }
            .toSortedMap()
        if (byPlugin.isEmpty()) return null

        return Finding(
            id = "plugin-version-split",
            category = FindingCategory.BUILD,
            severity = Severity.WARNING,
            title = "${Phrasing.count(byPlugin.size, "plugin")} applied at more than one version",
            detail = "${Phrasing.list(byPlugin.keys.toList(), MAX_NAMED)} " +
                    "${Phrasing.verb(byPlugin.size)} declared at different versions in different " +
                    "build scripts. Gradle resolves one version for the build, so at least one module " +
                    "is being configured by a plugin version it did not ask for.",
            evidence = byPlugin.entries.take(MAX_NAMED).map { (id, versions) ->
                Phrasing.observed(id, versions.joinToString(", "))
            },
            provenance = Provenance.OBSERVED,
            action = "Declare the version once in a version catalog and apply it by alias everywhere.",
        )
    }

    /**
     * How widely local convention plugins are used.
     *
     * A convention plugin is the mechanism that keeps a large build consistent, so the modules *not*
     * using one are where configuration drifts. Convention plugin ids have no dot - they are compiled
     * in `build-logic` rather than published - which is what distinguishes them here.
     */
    private fun conventionPluginAdoption(context: NarrativeContext): Finding? {
        val modules = context.inventory.modules.takeIf { it.size >= MIN_GROUP_FOR_DRIFT }.orEmpty()
        val users = modules.filter { info -> info.plugins.any { isConvention(it.id) } }
        if (users.isEmpty() || users.size == modules.size) return null

        val holdouts = (modules - users.toSet()).map { it.path }.sorted()
        val conventions = users.flatMap { info -> info.plugins.map { it.id }.filter(::isConvention) }
            .distinct()
            .sorted()

        return Finding(
            id = "convention-plugin-adoption",
            category = FindingCategory.BUILD,
            severity = Severity.INFO,
            title = "${Phrasing.share(users.size, modules.size)} of modules use a convention plugin",
            detail = "${users.size} of ${modules.size} modules apply one of " +
                    "${Phrasing.list(conventions, MAX_NAMED)}. The ${holdouts.size} that do not - " +
                    "${Phrasing.list(holdouts, MAX_NAMED)} - configure themselves, which is where a " +
                    "build's settings drift apart over time.",
            evidence = listOf(
                Phrasing.observed("Convention plugins", Phrasing.list(conventions, MAX_NAMED)),
                Phrasing.observed("Not using one", Phrasing.list(holdouts, MAX_NAMED)),
            ),
            subjects = holdouts,
            provenance = Provenance.OBSERVED,
            action = "Move the holdouts onto a convention plugin so build settings live in one place.",
        )
    }

    /** Plugins applied to only a handful of modules - deliberate, or left over. */
    private fun unusualPlugins(context: NarrativeContext): Finding? {
        val modules = context.inventory.modules.takeIf { it.size >= MIN_GROUP_FOR_DRIFT }.orEmpty()
        val usage = modules
            .flatMap { info -> info.plugins.map { it.id to info.path } }
            .groupBy({ it.first }, { it.second })
        val rare = usage
            .filterValues { it.size == 1 }
            .filterKeys { !isConvention(it) && usage.size > 1 }
            .toSortedMap()
        if (rare.isEmpty() || rare.size.toDouble() / usage.size > UNUSUAL_PLUGIN_SHARE) return null

        return Finding(
            id = "single-use-plugins",
            category = FindingCategory.BUILD,
            severity = Severity.INFO,
            title = "${Phrasing.count(rare.size, "plugin")} used by exactly one module",
            detail = "${Phrasing.list(rare.keys.toList(), MAX_NAMED)} " +
                    "${Phrasing.verb(rare.size)} applied in a single module. That is often exactly " +
                    "right - one module needs one capability - but it is also what an abandoned " +
                    "experiment looks like from the build's point of view.",
            evidence = rare.entries.take(MAX_NAMED).map { (id, paths) ->
                Phrasing.observed(id, paths.single())
            },
            subjects = rare.values.flatten().distinct(),
            provenance = Provenance.OBSERVED,
        )
    }

    /** Modules with no test source directory at all. */
    private fun untestedModules(context: NarrativeContext): Finding? {
        val modules = context.inventory.modules.takeIf { it.size >= MIN_GROUP_FOR_DRIFT }.orEmpty()
        val untested = modules.filter { it.testSourceSets.isEmpty() }.map { it.path }.sorted()
        if (untested.isEmpty() || untested.size == modules.size) return null

        // Rank by what a change to each would cost, so the sentence names the ones that matter.
        val byReach = untested.sortedByDescending { context.metrics.of(it)?.blastRadius ?: 0 }
        val worst = byReach.first()
        val worstReach = context.metrics.of(worst)?.blastRadius ?: 0

        return Finding(
            id = "untested-modules",
            category = FindingCategory.BUILD,
            severity = if (worstReach > 0) Severity.WARNING else Severity.INFO,
            title = "${Phrasing.count(untested.size, "module")} " +
                    "${Phrasing.verb(untested.size)} without a test source set",
            detail = "${Phrasing.list(byReach, MAX_NAMED)} have no test directory on disk. " +
                    (if (worstReach > 0) {
                        "$worst is the one to start with: ${Phrasing.count(worstReach, "module")} " +
                                "depend on it, so it is untested code the rest of the project trusts."
                    } else {
                        "None of them are depended on by anything, so the risk is contained."
                    }),
            evidence = byReach.take(MAX_NAMED).map { path ->
                Phrasing.computed(path, "${context.metrics.of(path)?.blastRadius ?: 0} dependents")
            },
            subjects = byReach,
            provenance = Provenance.OBSERVED,
            action = "Add tests to the untested modules with the largest blast radius first.",
        )
    }

    /** Which platforms a multiplatform project actually targets. */
    private fun multiplatformTargets(context: NarrativeContext): Finding? {
        val targeted = context.inventory.modules.filter { it.kmpTargets.isNotEmpty() }
        if (targeted.isEmpty()) return null

        val allTargets = targeted.flatMap { it.kmpTargets }.distinct().sorted()
        val fullCoverage = targeted.filter { it.kmpTargets.size == allTargets.size }

        return Finding(
            id = "multiplatform-targets",
            category = FindingCategory.BUILD,
            severity = Severity.INFO,
            title = "Multiplatform across ${Phrasing.count(allTargets.size, "target")}",
            detail = "${Phrasing.count(targeted.size, "module")} " +
                    "${Phrasing.verb(targeted.size)} multiplatform, together targeting " +
                    "${Phrasing.list(allTargets, MAX_NAMED)}. " +
                    "${fullCoverage.size} of them support every target; the rest support a subset, " +
                    "which bounds where they can be used.",
            evidence = listOf(
                Phrasing.observed("Targets", allTargets.joinToString(", ")),
                Phrasing.observed("Multiplatform modules", targeted.size.toString()),
                Phrasing.observed(
                    "Narrowest",
                    narrowest(targeted)?.let { "${it.path} (${it.kmpTargets.joinToString(", ")})" }
                        ?: "none",
                ),
            ),
            subjects = targeted.map { it.path },
            provenance = Provenance.OBSERVED,
        )
    }

    private fun narrowest(modules: List<ModuleBuildInfo>): ModuleBuildInfo? =
        modules.minWithOrNull(compareBy({ it.kmpTargets.size }, { it.path }))

    /**
     * True when a plugin id looks like a local convention plugin.
     *
     * Convention plugins are compiled in `build-logic` and applied by a bare id with no namespace,
     * where a published plugin is always reverse-DNS. It is a convention rather than a rule, so this
     * feeds only descriptive findings, never an error.
     */
    private fun isConvention(id: String): Boolean =
        '.' in id && id.substringBefore('.').length <= SHORT_NAMESPACE && !id.startsWith("org.") &&
                !id.startsWith("com.") && !id.startsWith("io.") && !id.startsWith("dev.")

    private const val MIN_DISTINCT = 2

    /** A namespace this short is a project prefix (`myapp.`), not a reverse-DNS domain. */
    private const val SHORT_NAMESPACE = 12
}
