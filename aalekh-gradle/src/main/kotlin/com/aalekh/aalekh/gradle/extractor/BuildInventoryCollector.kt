package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.model.CatalogEntry
import com.aalekh.aalekh.model.VersionCatalog
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import java.io.File

/**
 * Reads the build's own configuration - catalogs, toolchains, targets, test layout, tool versions.
 *
 * Everything here answers "what is this project made of?" rather than "what depends on what?". It is
 * collected at configuration time inside `provider { }` lambdas and flattened to plain values, so no
 * live Gradle object reaches a task input.
 *
 * Every reader is fail-silent per module: a build whose Kotlin extension cannot be read contributes
 * an empty list and a log line, never an exception. An incomplete inventory is a far better outcome
 * than a failed build, and the report distinguishes "none" from "not detected" in its wording.
 */
internal object BuildInventoryCollector {

    /** Conventional test source directories, checked for existence rather than assumed. */
    private val TEST_SOURCE_DIRS = listOf(
        "test", "androidTest", "commonTest", "jvmTest", "iosTest", "androidUnitTest", "androidHostTest",
    )

    /**
     * Every version catalog declared for the build.
     *
     * Read through Gradle's `VersionCatalogsExtension` rather than by parsing `libs.versions.toml`:
     * the extension is what the build actually resolves, so it covers catalogs under a non-default
     * name, catalogs assembled in `settings.gradle.kts`, and any future catalog source, with no TOML
     * parser to keep correct.
     */
    fun catalogs(rootProject: Project): List<VersionCatalog> = runCatching {
        val extension = rootProject.extensions.findByType(VersionCatalogsExtension::class.java)
            ?: return emptyList()
        extension.catalogNames.sorted().mapNotNull { name ->
            runCatching { readCatalog(extension, name) }.getOrNull()
        }
    }.getOrElse { ex ->
        rootProject.logger.info("Aalekh: could not read version catalogs - ${ex.message}")
        emptyList()
    }

    private fun readCatalog(extension: VersionCatalogsExtension, name: String): VersionCatalog {
        val catalog = extension.named(name)
        val plugins = catalog.pluginAliases.sorted().mapNotNull { alias ->
            catalog.findPlugin(alias).orElse(null)?.get()?.let { plugin ->
                CatalogEntry(alias, plugin.pluginId, plugin.version.toString().ifBlank { null })
            }
        }
        val libraries = catalog.libraryAliases.sorted().mapNotNull { alias ->
            catalog.findLibrary(alias).orElse(null)?.get()?.let { library ->
                CatalogEntry(
                    alias = alias,
                    coordinates = "${library.module.group}:${library.module.name}",
                    version = library.versionConstraint.toString().ifBlank { null },
                )
            }
        }
        return VersionCatalog(name, plugins, libraries)
    }

    /** Java toolchain language version per subproject, for the modules that request one. */
    fun toolchains(rootProject: Project): Map<String, String> =
        rootProject.subprojects.mapNotNull { sub ->
            val version = runCatching {
                sub.extensions.findByType(JavaPluginExtension::class.java)
                    ?.toolchain?.languageVersion?.orNull?.toString()
            }.getOrNull()
            version?.let { sub.path to it }
        }.toMap()

    /**
     * Kotlin Multiplatform target names per subproject.
     *
     * The Kotlin Gradle plugin is never a compile dependency, so the extension is read reflectively
     * and a failure yields no targets rather than an error.
     */
    fun kmpTargets(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub -> sub.path to targetsOf(sub) }
            .filterValues { it.isNotEmpty() }

    private fun targetsOf(project: Project): List<String> = runCatching {
        val kotlin = project.extensions.findByName("kotlin") ?: return emptyList()
        val getTargets = kotlin.javaClass.getMethod("getTargets").apply { isAccessible = true }
        val container = getTargets.invoke(kotlin) as? NamedDomainObjectCollection<*>
        // "metadata" is the synthetic common target every KMP module has; it is an implementation
        // detail of the plugin rather than a platform anyone chose to support.
        container?.names?.filterNot { it == "metadata" }?.sorted().orEmpty()
    }.getOrElse { emptyList() }

    /** Test source directories that exist on disk, per subproject. */
    fun testSourceSets(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub ->
            val src = sub.projectDir.resolve("src")
            val present = if (!src.isDirectory) {
                emptyList()
            } else {
                TEST_SOURCE_DIRS.filter { src.resolve(it).isDirectory }
            }
            sub.path to present
        }.filterValues { it.isNotEmpty() }

    /**
     * Build-tool versions for the whole build.
     *
     * AGP and the Kotlin Gradle plugin are detected reflectively against their own version constants,
     * never via a compile dependency. They are not on Aalekh's own classpath - a settings plugin
     * loads in a different scope from the build scripts that apply them - so the buildscript
     * classloaders are searched first. A tool that is absent simply has no entry; the report says
     * "not detected" rather than inventing a version.
     */
    fun toolVersions(rootProject: Project): Map<String, String> = buildMap {
        put("gradle", rootProject.gradle.gradleVersion)
        val loaders = buildscriptClassLoaders(rootProject)
        detectVersion(loaders, "com.android.builder.model.Version", "ANDROID_GRADLE_PLUGIN_VERSION")
            ?.let { put("agp", it) }
        detectVersion(loaders, "org.jetbrains.kotlin.config.KotlinCompilerVersion", "VERSION")
            ?.let { put("kotlin", it) }
    }

    /**
     * Classloaders that may hold a build plugin, most likely first.
     *
     * The root buildscript covers the usual `alias(...) apply false` declaration; a subproject's
     * covers a build that only applies AGP or Kotlin further down, including via a convention plugin
     * from an included build. Aalekh's own loader is the last resort.
     */
    private fun buildscriptClassLoaders(rootProject: Project): List<ClassLoader> =
        (listOf(rootProject) + rootProject.subprojects)
            .mapNotNull { runCatching { it.buildscript.classLoader }.getOrNull() }
            .plus(BuildInventoryCollector::class.java.classLoader)
            .distinct()

    private fun detectVersion(loaders: List<ClassLoader>, className: String, fieldName: String): String? =
        loaders.firstNotNullOfOrNull { loader ->
            runCatching {
                Class.forName(className, false, loader).getField(fieldName).get(null) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    /**
     * Owners assigned by a `CODEOWNERS` file, resolved to module paths.
     *
     * Reads the conventional locations in the order the hosts themselves check. A team declared in
     * `teams { }` always wins over this - configuration beats a file convention - but for the many
     * projects that have CODEOWNERS and no Aalekh team block, this is ownership for free.
     */
    fun codeowners(rootDir: File, moduleDirectories: Map<String, String>): Map<String, List<String>> {
        val file = listOf(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS", ".gitlab/CODEOWNERS")
            .map { rootDir.resolve(it) }
            .firstOrNull { it.isFile }

        val rules = file
            ?.let { runCatching { CodeownersParser.parse(it.readLines()) }.getOrNull() }
            .orEmpty()
        if (rules.isEmpty()) return emptyMap()

        return moduleDirectories
            .mapValues { (_, directory) -> CodeownersParser.ownersFor(directory, rules) }
            .filterValues { it.isNotEmpty() }
    }
}
