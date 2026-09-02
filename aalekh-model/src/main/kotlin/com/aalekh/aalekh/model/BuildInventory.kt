package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * One Gradle plugin as applied to one module.
 *
 * @param id Plugin id, e.g. `"com.android.library"`. For a convention plugin from `build-logic` this
 *   is the id the build script used, e.g. `"myapp.android.library"`.
 * @param version Declared version, or null when the version is set elsewhere - by a version catalog
 *   entry Aalekh could not resolve, by `pluginManagement`, or because the plugin is a local
 *   convention plugin that has no version of its own.
 * @param alias Version-catalog alias the plugin was applied through, e.g. `"androidApplication"` for
 *   `alias(libs.plugins.androidApplication)`. Null when applied by literal id.
 * @param source Where Aalekh learned about this plugin.
 */
@Serializable
public data class ModulePlugin(
    val id: String,
    val version: String? = null,
    val alias: String? = null,
    val source: PluginSource = PluginSource.BUILD_SCRIPT,
)

/**
 * How a plugin application was discovered.
 *
 * The distinction matters for accuracy: a plugin read out of a build script is a fact about what the
 * project declares, while one recognised from an applied class is Aalekh matching against a table it
 * maintains, and that table can be incomplete.
 */
@Serializable
public enum class PluginSource {
    /** Read from a `plugins { }` block in the module's build script. */
    BUILD_SCRIPT,

    /** Recognised from an applied plugin class against Aalekh's known-plugin table. */
    APPLIED_CLASS,
}

/** A version-catalog entry: an alias, what it points at, and the version it resolves to. */
@Serializable
public data class CatalogEntry(
    val alias: String,
    val coordinates: String,
    val version: String? = null,
)

/**
 * The version catalogs declared for the build.
 *
 * Read through Gradle's own `VersionCatalogsExtension` rather than by parsing `libs.versions.toml`,
 * so the values are exactly what the build resolves - including catalogs under a non-default name or
 * assembled programmatically.
 *
 * @param name Catalog name, usually `"libs"`.
 * @param plugins Plugin aliases and the plugin ids they resolve to.
 * @param libraries Library aliases and the coordinates they resolve to.
 */
@Serializable
public data class VersionCatalog(
    val name: String,
    val plugins: List<CatalogEntry> = emptyList(),
    val libraries: List<CatalogEntry> = emptyList(),
)

/**
 * Per-module build configuration that is not a dependency.
 *
 * @param path Module Gradle path.
 * @param plugins Plugins applied to this module, sorted by id.
 * @param javaToolchain Java language version requested via the toolchain, e.g. `"17"`. Null when the
 *   module sets none and inherits the daemon's JDK.
 * @param kmpTargets Kotlin Multiplatform target names, e.g. `["android", "iosArm64", "jvm"]`. Empty
 *   for non-multiplatform modules.
 * @param testSourceSets Test source directories that exist on disk, e.g. `["test", "androidTest"]`.
 *   A module with none has no tests of its own.
 */
@Serializable
public data class ModuleBuildInfo(
    val path: String,
    val plugins: List<ModulePlugin> = emptyList(),
    val javaToolchain: String? = null,
    val kmpTargets: List<String> = emptyList(),
    val testSourceSets: List<String> = emptyList(),
)

/**
 * Metadata a team declares about a module, in a committed `.aalekh/modules.json`.
 *
 * This is the escape hatch from inference. Anything stated here is [Provenance.OBSERVED] and
 * overrides whatever Aalekh would otherwise guess - a module's purpose, its owner, its layer. Nothing
 * is required; a file that names only the ten modules a newcomer keeps asking about is a perfectly
 * good use of it.
 *
 * @param path Module Gradle path this describes.
 * @param purpose One or two sentences on what the module is for, in the team's own words.
 * @param owner Team or person responsible, overriding both `teams { }` and CODEOWNERS.
 * @param layer Architectural layer, overriding both `layers { }` matching and path inference.
 * @param status Lifecycle marker, e.g. `"experimental"`, `"deprecated"`, `"frozen"`.
 * @param links Named URLs - design docs, dashboards, ADRs.
 */
@Serializable
public data class ModuleMetadata(
    val path: String,
    val purpose: String? = null,
    val owner: String? = null,
    val layer: String? = null,
    val status: String? = null,
    val links: Map<String, String> = emptyMap(),
)

/**
 * Everything Aalekh knows about how the project is **built**, as opposed to how it is wired together.
 *
 * The dependency graph answers "what depends on what"; this answers "what is this project made of" -
 * which plugins, at which versions, on which toolchains, with which targets, owned by whom. It is
 * the difference between a dependency-graph tool and a project inventory.
 *
 * Every field is defaulted so a graph file written by an older plugin version still deserializes, and
 * so a project that declares none of this still produces a valid, if empty, inventory.
 *
 * @param modules Per-module build configuration, sorted by path.
 * @param catalogs Version catalogs declared for the build.
 * @param toolVersions Build-tool versions detected for the whole build, keyed by tool name
 *   (`"gradle"`, `"agp"`, `"kotlin"`). Absent keys mean the tool was not detected, not that it is
 *   not present.
 * @param codeowners Module path to the owners CODEOWNERS assigns it, when such a file exists.
 * @param declaredMetadata Module metadata read from `.aalekh/modules.json`, keyed by module path.
 */
@Serializable
public data class BuildInventory(
    val modules: List<ModuleBuildInfo> = emptyList(),
    val catalogs: List<VersionCatalog> = emptyList(),
    val toolVersions: Map<String, String> = emptyMap(),
    val codeowners: Map<String, List<String>> = emptyMap(),
    val declaredMetadata: Map<String, ModuleMetadata> = emptyMap(),
) {
    /** Build info for one module, or null when it has none recorded. */
    public fun of(path: String): ModuleBuildInfo? = modules.firstOrNull { it.path == path }

    /** True when nothing at all was captured - the inventory panels stay hidden. */
    public val isEmpty: Boolean
        get() = modules.isEmpty() && catalogs.isEmpty() && toolVersions.isEmpty()

    public companion object {
        /** An empty inventory, for graphs extracted before this data existed. */
        public val EMPTY: BuildInventory = BuildInventory()
    }
}
