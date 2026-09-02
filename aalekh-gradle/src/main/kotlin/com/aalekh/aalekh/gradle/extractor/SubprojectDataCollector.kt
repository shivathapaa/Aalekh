package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.model.BuildInventory
import com.aalekh.aalekh.model.ModuleBuildInfo
import kotlinx.serialization.json.Json
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

/**
 * Reads every fact `AalekhExtractTask` needs out of the configured Gradle project model.
 *
 * Both the settings plugin and the deprecated project plugin call these from inside a
 * `project.provider { }` lambda, so everything returned here is a plain `String`, `List`, or `File` -
 * no live `Project`, `Configuration`, or `Dependency` survives into a task input, which is what keeps
 * extraction configuration-cache safe. The two plugins previously carried byte-identical copies of
 * this logic; centralising it is the same move [ConfigurationClassifier] made for configuration names.
 *
 * Every collector is **fail-silent per module**: a subproject whose model cannot be read contributes
 * an empty entry and a log line, never an exception, so one broken module never fails the run.
 */
// One function per fact the extract task needs; the count tracks how much of the Gradle model
// Aalekh reads, not how many jobs this object does. Splitting it would mean two plugins having to
// know which half to call for what.
@Suppress("TooManyFunctions")
internal object SubprojectDataCollector {

    private val json = Json { encodeDefaults = true }

    /** Plugin IDs that mark a module as Kotlin Multiplatform, and so as having KMP source sets. */
    private val KMP_PLUGIN_IDS = listOf(
        "org.jetbrains.kotlin.multiplatform",
        "com.android.kotlin.multiplatform.library",
    )

    /**
     * Inter-module dependencies as `"configurationName:targetProjectPath"` per subproject.
     *
     * Collects every configuration [ConfigurationClassifier.isCaptured] accepts, unfiltered - the
     * `includeTestDependencies` / `includeCompileOnlyDependencies` flags are applied later in the
     * task, so toggling a flag invalidates the task rather than the provider.
     */
    fun dependencies(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub ->
            val deps = mutableListOf<String>()
            capturedConfigurations(sub).forEach { cfg ->
                cfg.dependencies.filterIsInstance<ProjectDependency>().forEach { dep ->
                    // Self-loops are dropped: Gradle allows project(":self") but it is not
                    // architecturally meaningful and would create a false cycle.
                    if (dep.path != sub.path) deps += "${cfg.name}:${dep.path}"
                }
            }
            sub.path to deps
        }

    /**
     * Declared external coordinates as `"configurationName|group|name|version"` per subproject.
     *
     * Pipe-delimited rather than colon-delimited because Maven coordinates contain colons. These are
     * the **declared** coordinates only - reading the resolved graph would trigger resolution and
     * break the configuration cache.
     */
    fun externalDependencies(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub ->
            val deps = mutableListOf<String>()
            capturedConfigurations(sub).forEach { cfg ->
                cfg.dependencies.filterIsInstance<ExternalModuleDependency>().forEach { dep ->
                    deps += "${cfg.name}|${dep.group}|${dep.name}|${dep.version ?: ""}"
                }
            }
            sub.path to deps
        }

    /** Applied plugin class names per subproject - the input to [ModuleTypeDetector]. */
    fun plugins(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub ->
            sub.path to sub.plugins.map { it.javaClass.name }
        }

    /**
     * KMP source-set names per subproject (`commonMain`, `androidMain`, `iosMain`, ...), empty for
     * modules that are not multiplatform.
     *
     * The Kotlin Gradle plugin is never a compile dependency, so the extension is read reflectively
     * and any failure yields an empty list. The multiplatform check itself uses
     * `pluginManager.hasPlugin(id)`, which needs no reflection.
     */
    fun sourceSets(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub -> sub.path to kmpSourceSetNames(sub) }

    /**
     * Repo-relative build file path per subproject, forward-slash separated.
     *
     * Read from `Project.getBuildFile()` rather than derived from the module path, so a module whose
     * build file does not follow the `:a:b` → `a/b/build.gradle.kts` convention still resolves.
     * Modules with no build file on disk, or one outside the root directory, are omitted.
     */
    fun buildFilePaths(rootProject: Project): Map<String, String> =
        rootProject.subprojects.mapNotNull { sub ->
            val relative = relativeBuildFilePath(sub, rootProject.rootDir) ?: return@mapNotNull null
            sub.path to relative
        }.toMap()

    /**
     * The build files themselves, for content fingerprinting.
     *
     * `AalekhExtractTask` scans these to locate the line each dependency is declared on, so their
     * contents are a genuine task input - without them a cached run could report a stale line number
     * after a build file is reordered.
     */
    fun buildFiles(rootProject: Project): List<File> =
        rootProject.subprojects.mapNotNull { sub ->
            sub.buildFile.takeIf { it.isFile }
        }

    /**
     * The configuration-time half of the build inventory, serialized to JSON for a task `@Input`.
     *
     * Catalogs, toolchains, KMP targets, test layout, and tool versions are all read from the Gradle
     * object model, which only exists now. One string beats a dozen map properties: the task treats
     * the inventory as a unit anyway, and adding a field later needs no new task property.
     */
    fun buildInventoryJson(rootProject: Project): String {
        val toolchains = BuildInventoryCollector.toolchains(rootProject)
        val targets = BuildInventoryCollector.kmpTargets(rootProject)
        val tests = BuildInventoryCollector.testSourceSets(rootProject)

        val modules = (toolchains.keys + targets.keys + tests.keys).sorted().map { path ->
            ModuleBuildInfo(
                path = path,
                javaToolchain = toolchains[path],
                kmpTargets = targets[path].orEmpty(),
                testSourceSets = tests[path].orEmpty(),
            )
        }

        val inventory = BuildInventory(
            modules = modules,
            catalogs = BuildInventoryCollector.catalogs(rootProject),
            toolVersions = BuildInventoryCollector.toolVersions(rootProject),
        )
        return runCatching { json.encodeToString(BuildInventory.serializer(), inventory) }
            .getOrElse { ex ->
                rootProject.logger.info("Aalekh: could not serialize the build inventory - ${ex.message}")
                json.encodeToString(BuildInventory.serializer(), BuildInventory.EMPTY)
            }
    }

    /**
     * Optional project-level description files - `CODEOWNERS` and `.aalekh/modules.json` - for the
     * ones that exist. Declared as task inputs so editing either re-runs extraction.
     */
    fun projectMetadataFiles(rootProject: Project): List<File> =
        listOf(
            ".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS", ".gitlab/CODEOWNERS",
            ModuleMetadataReader.DEFAULT_PATH,
        ).map { rootProject.rootDir.resolve(it) }.filter { it.isFile }

    private fun capturedConfigurations(project: Project) =
        project.configurations.filter { cfg -> ConfigurationClassifier.isCaptured(cfg.name) }

    private fun relativeBuildFilePath(project: Project, rootDir: File): String? {
        val file = project.buildFile.takeIf { it.isFile } ?: return null
        // A build file outside the root directory (an unusual layout, or a composite) has no
        // repo-relative path; the git-driven analyses assume one, so it is better omitted than wrong.
        return file.relativeToOrNull(rootDir)
            ?.invariantSeparatorsPath
            ?.takeIf { !it.startsWith("..") }
    }

    private fun kmpSourceSetNames(project: Project): List<String> {
        if (KMP_PLUGIN_IDS.none { project.pluginManager.hasPlugin(it) }) return emptyList()
        return runCatching {
            val kotlin = project.extensions.findByName("kotlin")
            val getSourceSets = kotlin?.javaClass?.getMethod("getSourceSets")?.apply { isAccessible = true }
            val container = getSourceSets?.invoke(kotlin) as? NamedDomainObjectCollection<*>
            container?.names?.toList().orEmpty()
        }.getOrElse { ex ->
            project.logger.info(
                "Aalekh: could not read KMP source sets for ${project.path} - ${ex.message}"
            )
            emptyList()
        }
    }
}
