package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.model.BuildInventory
import com.aalekh.aalekh.model.ModuleBuildInfo
import com.aalekh.aalekh.model.ModulePlugin
import com.aalekh.aalekh.model.PluginSource
import com.aalekh.aalekh.model.VersionCatalog

/**
 * Merges the two halves of the build inventory into one.
 *
 * The halves are split by *when* they can be read, not by what they describe. Version catalogs,
 * toolchains, and KMP targets come from the Gradle object model at configuration time; plugin ids
 * come from the text of each build script, which is read at execution time so the files can be
 * declared as proper task inputs. This puts them back together.
 *
 * Plugin resolution runs three sources against each other, strongest evidence first:
 *
 * 1. **The build script** - what the module literally declares, including convention-plugin ids and
 *    version-catalog aliases.
 * 2. **The version catalog** - resolves an alias to the real plugin id and version.
 * 3. **Applied classes** - recovers ids for plugins a convention plugin applied indirectly, which
 *    the script never names.
 *
 * Pure functions, so the merge is unit-testable without a Gradle build.
 */
internal object BuildInventoryAssembler {

    /**
     * Builds the per-module plugin list for one module.
     *
     * @param declared Plugins parsed from the module's own `plugins { }` block.
     * @param appliedClasses Plugin class names Gradle reports as applied to the module.
     * @param catalogs Version catalogs, used to resolve `alias(libs.plugins.x)` to an id and version.
     */
    fun pluginsFor(
        declared: List<ModulePlugin>,
        appliedClasses: List<String>,
        catalogs: List<VersionCatalog>,
    ): List<ModulePlugin> {
        val aliasIndex = catalogs
            .flatMap { it.plugins }
            .associateBy({ it.alias }, { it })

        val resolved = declared.map { plugin ->
            val entry = plugin.alias?.let { aliasIndex[it] } ?: return@map plugin
            // The script said `alias(libs.plugins.foo)`; the catalog says what foo actually is.
            plugin.copy(id = entry.coordinates, version = plugin.version ?: entry.version)
        }

        // Anything applied but never named in the script - almost always a convention plugin's doing.
        val declaredIds = resolved.map { it.id }.toSet()
        val fromClasses = appliedClasses
            .mapNotNull { KnownPlugins.idFor(it) }
            .distinct()
            .filterNot { it in declaredIds }
            .map { ModulePlugin(id = it, source = PluginSource.APPLIED_CLASS) }

        return (resolved + fromClasses).sortedBy { it.id }
    }

    /**
     * Assembles the per-module build info records.
     *
     * A module contributes a record only when something is actually known about it; a module with no
     * plugins, no toolchain, no targets, and no tests would be an empty row saying nothing.
     */
    @Suppress("LongParameterList")
    fun moduleInfo(
        modulePaths: List<String>,
        declaredPlugins: Map<String, List<ModulePlugin>>,
        appliedClasses: Map<String, List<String>>,
        catalogs: List<VersionCatalog>,
        toolchains: Map<String, String>,
        kmpTargets: Map<String, List<String>>,
        testSourceSets: Map<String, List<String>>,
    ): List<ModuleBuildInfo> = modulePaths.sorted().mapNotNull { path ->
        val plugins = pluginsFor(
            declared = declaredPlugins[path].orEmpty(),
            appliedClasses = appliedClasses[path].orEmpty(),
            catalogs = catalogs,
        )
        val info = ModuleBuildInfo(
            path = path,
            plugins = plugins,
            javaToolchain = toolchains[path],
            kmpTargets = kmpTargets[path].orEmpty(),
            testSourceSets = testSourceSets[path].orEmpty(),
        )
        info.takeIf {
            it.plugins.isNotEmpty() || it.javaToolchain != null ||
                it.kmpTargets.isNotEmpty() || it.testSourceSets.isNotEmpty()
        }
    }

    /**
     * Strips the plugin classes that carry no information from a module's applied-class list.
     *
     * Gradle applies a dozen infrastructure plugins to every module of every build. Keeping them
     * made plugin class names 24% of the graph file on a 128-module project, all of it noise a reader
     * would have to skip past. Module-type detection has already run by this point and never looked
     * at them anyway.
     */
    fun meaningfulPluginClasses(classNames: Collection<String>): Set<String> =
        classNames.filterNot { KnownPlugins.isInfrastructure(it) }.toSortedSet()

    /** Combines the configuration-time inventory with the parts that could only be read later. */
    fun merge(
        base: BuildInventory,
        modules: List<ModuleBuildInfo>,
        codeowners: Map<String, List<String>>,
        declaredMetadata: Map<String, com.aalekh.aalekh.model.ModuleMetadata>,
    ): BuildInventory = base.copy(
        modules = modules,
        codeowners = codeowners.toSortedMap(),
        declaredMetadata = declaredMetadata.toSortedMap(),
    )
}
