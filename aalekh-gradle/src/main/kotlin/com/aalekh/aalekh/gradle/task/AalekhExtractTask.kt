package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.metrics.HealthScoreCalculator
import com.aalekh.aalekh.gradle.extractor.BuildInventoryAssembler
import com.aalekh.aalekh.gradle.extractor.BuildInventoryCollector
import com.aalekh.aalekh.gradle.extractor.ConfigurationClassifier
import com.aalekh.aalekh.gradle.extractor.DeclarationLineFinder
import com.aalekh.aalekh.gradle.extractor.ModuleMetadataReader
import com.aalekh.aalekh.gradle.extractor.ModuleTypeDetector
import com.aalekh.aalekh.gradle.extractor.PluginBlockParser
import com.aalekh.aalekh.model.AalekhBuildConfig
import com.aalekh.aalekh.model.BuildInventory
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ExternalDependency
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.time.Instant

/**
 * Extracts the module dependency graph and writes it as JSON.
 *
 * ### Why a dedicated task instead of projectsEvaluated?
 * Writing files at configuration time (inside `projectsEvaluated`) makes Gradle
 * treat every build as having changed inputs, breaking the configuration cache.
 * This task moves all I/O into a proper task action, so Gradle can:
 * - Fingerprint the inputs (project structure, dependency data)
 * - Skip the task on subsequent runs if nothing changed (UP-TO-DATE)
 * - Store/restore the output correctly across CC entries
 *
 * ### Configuration Cache safety
 * All inputs are plain @Input Strings/Maps/Booleans - no live Project, Configuration,
 * or Dependency objects are captured. The plugin serializes everything to
 * primitive types in provider lambdas at configuration time, then passes
 * only those primitives to this task.
 *
 * ### Filtering
 * [includeTestDependencies] and [includeCompileOnlyDependencies] mirror the
 * flags on [com.aalekh.aalekh.gradle.AalekhExtension]. They are task inputs
 * (not just extension properties) so Gradle can correctly invalidate the
 * UP-TO-DATE cache when a user toggles them.
 *
 * ### Caching
 * [CacheableTask]: output is fully determined by inputs. Caching avoids
 * re-extracting the graph on CI when nothing has changed.
 */
@CacheableTask
public abstract class AalekhExtractTask : DefaultTask() {

    /** Root project name - written into graph metadata. */
    @get:Input
    public abstract val projectName: Property<String>

    /** Gradle version - written into graph metadata. */
    @get:Input
    public abstract val gradleVersion: Property<String>

    /**
     * Inter-module dependency data captured at configuration time.
     *
     * Format: `Map<subprojectPath, List<"configurationName:targetProjectPath">>`
     *
     * Example:
     * ```
     * { ":feature:login" -> ["implementation::core:domain", "testImplementation::core:test-fixtures"] }
     * ```
     *
     * All configurations (production + test + compileOnly) are collected here
     * unconditionally. Filtering by [includeTestDependencies] and
     * [includeCompileOnlyDependencies] happens in [extract] so the task can
     * be correctly UP-TO-DATE cached when only the filter flags change.
     */
    @get:Input
    public abstract val subprojectData: MapProperty<String, List<String>>

    /**
     * External (third-party) dependency data captured at configuration time.
     *
     * Format: `Map<subprojectPath, List<"configurationName|group|name|version">>`
     *
     * The value is pipe-delimited (not colon-delimited like [subprojectData]) because Maven
     * coordinates themselves contain colons. The `group` and `version` segments may be empty.
     *
     * Example:
     * ```
     * { ":app" -> ["implementation|androidx.core|core-ktx|1.13.1", "api|com.squareup.okhttp3|okhttp|4.12.0"] }
     * ```
     *
     * Collected unconditionally, like [subprojectData]. Filtering by [includeExternalDependencies],
     * [includeTestDependencies], and [includeCompileOnlyDependencies] happens in [extract] so the
     * task is correctly UP-TO-DATE cached when only the flags change.
     */
    @get:Input
    public abstract val subprojectExternalData: MapProperty<String, List<String>>

    /**
     * Applied plugin class names per subproject - used for module type detection.
     *
     * Format: `Map<subprojectPath, List<pluginClassName>>`
     */
    @get:Input
    public abstract val subprojectPlugins: MapProperty<String, List<String>>

    /**
     * Kotlin Multiplatform source-set names per subproject, empty for non-KMP modules.
     *
     * Format: `Map<subprojectPath, List<sourceSetName>>`, e.g.
     * `{ ":core:domain" -> ["commonMain", "androidMain", "iosMain"] }`.
     *
     * Populates [ModuleNode.sourceSets]. Read reflectively from the Kotlin extension by the plugins,
     * so a module whose extension cannot be read simply contributes an empty list.
     */
    @get:Input
    public abstract val subprojectSourceSets: MapProperty<String, List<String>>

    /**
     * Repo-relative build file path per subproject, forward-slash separated.
     *
     * Format: `Map<subprojectPath, "feature/login/build.gradle.kts">`. Read from the real
     * `Project.getBuildFile()` rather than derived from the module path, so modules that do not follow
     * the directory convention still resolve. Modules absent from this map fall back to the
     * conventional location.
     */
    @get:Input
    public abstract val subprojectBuildFilePaths: MapProperty<String, String>

    /**
     * The subproject build files themselves, scanned to locate the line each project dependency is
     * declared on ([DependencyEdge.declarationLine]).
     *
     * Declared as an input because their **contents** change the output: reordering two dependency
     * declarations changes the recorded lines without changing any other input, and a cached run
     * would otherwise report stale line numbers. `RELATIVE` path sensitivity keeps the task
     * relocatable across checkout directories.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val buildFiles: ConfigurableFileCollection

    /**
     * The configuration-time half of the build inventory, as JSON.
     *
     * Version catalogs, toolchains, KMP targets, test source sets, and tool versions are read from
     * the Gradle object model while it is still available, then flattened to one string rather than a
     * dozen map properties. The execution-time half - plugin ids from build scripts, CODEOWNERS,
     * declared module metadata - is merged in by [extract], because those come from files that must
     * be declared as task inputs to be tracked correctly.
     */
    @get:Input
    public abstract val buildInventoryJson: Property<String>

    /**
     * Optional project-level files that describe the build rather than the code: `CODEOWNERS` and
     * `.aalekh/modules.json`.
     *
     * Declared as inputs so editing either correctly re-runs extraction. Both are optional; a project
     * with neither simply has no ownership map and no declared metadata.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val projectMetadataFiles: ConfigurableFileCollection

    /**
     * Whether to include test configurations (testImplementation,
     * androidTestImplementation, etc.) in the extracted graph.
     *
     * Default: `true` - mirrors [com.aalekh.aalekh.gradle.AalekhExtension.includeTestDependencies].
     *
     * When `false`, test edges are stripped before the graph is written to JSON.
     * The HTML report and rule engine will not see any test dependencies.
     */
    @get:Input
    public abstract val includeTestDependencies: Property<Boolean>

    /**
     * Whether to include compileOnly configurations in the extracted graph.
     *
     * Default: `false` - mirrors [com.aalekh.aalekh.gradle.AalekhExtension.includeCompileOnlyDependencies].
     *
     * compileOnly dependencies are not present at runtime and are rarely
     * architecturally significant, so they are excluded by default to reduce
     * graph noise. Enable if your project uses compileOnly for architectural
     * separation that you want to visualize or enforce.
     */
    @get:Input
    public abstract val includeCompileOnlyDependencies: Property<Boolean>

    /**
     * Whether to capture external (third-party) dependencies in the extracted graph.
     *
     * Default: `true` - mirrors [com.aalekh.aalekh.gradle.AalekhExtension.includeExternalDependencies].
     *
     * When `false`, [ModuleDependencyGraph.externalDependencies] is left empty and the HTML report
     * shows no external-dependency section.
     */
    @get:Input
    public abstract val includeExternalDependencies: Property<Boolean>

    /**
     * Root project directory path. Used to resolve conventional build file paths
     * for violation messages. Stored as a plain string rather than a Directory
     * property so it doesn't skew UP-TO-DATE checks on the output file.
     */
    @get:Input
    public abstract val rootProjectDir: Property<String>

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Extracts the module dependency graph to JSON. " +
                "Runs automatically before aalekhReport and aalekhCheck."
    }

    @TaskAction
    public fun extract() {
        val depsData = subprojectData.get()
        val externalData = subprojectExternalData.get()
        val pluginsData = subprojectPlugins.get()
        val sourceSetData = subprojectSourceSets.getOrElse(emptyMap())
        val includeTest = includeTestDependencies.getOrElse(true)
        val includeCompileOnly = includeCompileOnlyDependencies.getOrElse(false)
        val includeExternal = includeExternalDependencies.getOrElse(true)

        val buildFilePaths = depsData.keys.associateWith { path -> resolveBuildFilePath(path) }
        val buildFileLines = readBuildFiles(buildFilePaths)

        val nodes = depsData.keys.sorted().map { path ->
            val plugins = pluginsData[path] ?: emptyList()
            // Type detection runs against the full class list, including Gradle's own; only the
            // recorded set is trimmed, so detection is unaffected by the noise filter.
            val type = ModuleTypeDetector.detectFromPluginNames(plugins)
            ModuleNode(
                path = path,
                name = path.substringAfterLast(":"),
                type = type,
                plugins = BuildInventoryAssembler.meaningfulPluginClasses(plugins),
                tags = inferTags(path),
                sourceSets = sourceSetData[path]?.toSet() ?: emptySet(),
                buildFilePath = buildFilePaths[path],
            )
        }

        val edges = buildEdges(depsData, includeTest, includeCompileOnly, buildFileLines)

        val externalDependencies =
            if (includeExternal) buildExternalDependencies(externalData, includeTest, includeCompileOnly)
            else emptyList()

        val graph = scoreHealth(
            ModuleDependencyGraph(
                projectName = projectName.get(),
                modules = nodes,
                edges = edges,
                externalDependencies = externalDependencies,
                buildInventory = buildInventory(depsData.keys.toList(), pluginsData, buildFileLines, buildFilePaths),
                metadata = mapOf(
                    "gradleVersion" to gradleVersion.get(),
                    "extractedAt" to Instant.now().toString(),
                    "moduleCount" to nodes.size.toString(),
                    "edgeCount" to edges.size.toString(),
                    "externalDepCount" to externalDependencies.size.toString(),
                    "aalekhVersion" to AalekhBuildConfig.VERSION,
                    "includeTestDependencies" to includeTest.toString(),
                    "includeCompileOnlyDependencies" to includeCompileOnly.toString(),
                    "includeExternalDependencies" to includeExternal.toString(),
                ),
            )
        )

        val json = Json { encodeDefaults = true }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(graph))

        logger.lifecycle(
            "Aalekh extracted ${nodes.size} modules, ${edges.size} edges" +
                    (if (includeExternal) ", ${externalDependencies.size} external deps" else "") +
                    (if (!includeTest) " (test deps excluded)" else "") +
                    (if (includeCompileOnly) " (compileOnly included)" else "")
        )
    }

    // Parses the "configurationName:targetProjectPath" strings collected by the plugins into edges,
    // applying the test / compileOnly scope filters - the single place the extension flags take effect.
    private fun buildEdges(
        depsData: Map<String, List<String>>,
        includeTest: Boolean,
        includeCompileOnly: Boolean,
        buildFileLines: Map<String, List<String>>,
    ): List<DependencyEdge> =
        depsData.flatMap { (fromPath, depStrings) ->
            val lines = buildFileLines[fromPath].orEmpty()
            depStrings.mapNotNull { depString ->
                val colonIdx = depString.indexOf(':')
                if (colonIdx < 0) return@mapNotNull null
                val config = depString.substring(0, colonIdx)
                val toPath = depString.substring(colonIdx + 1)
                if (!includeTest && ConfigurationClassifier.isTestConfig(config)) return@mapNotNull null
                if (!includeCompileOnly && ConfigurationClassifier.isCompileOnlyConfig(config)) {
                    return@mapNotNull null
                }
                DependencyEdge(
                    from = fromPath,
                    to = toPath,
                    configuration = config,
                    sourceSet = ConfigurationClassifier.kmpSourceSetName(config),
                    declarationLine = DeclarationLineFinder.lineOf(lines, toPath),
                )
            }
        }.distinctBy { Triple(it.from, it.to, it.configuration) }

    /**
     * Assembles the build inventory: the configuration-time half from [buildInventoryJson], merged
     * with the parts that can only be read from files at execution time.
     *
     * Plugin ids come from each module's own `plugins { }` block - the build files are already in
     * memory for declaration-line scanning, so this costs nothing extra - resolved against the
     * version catalogs and topped up from applied classes for whatever a convention plugin applied
     * without the script naming it.
     */
    private fun buildInventory(
        modulePaths: List<String>,
        pluginsData: Map<String, List<String>>,
        buildFileLines: Map<String, List<String>>,
        buildFilePaths: Map<String, String?>,
    ): BuildInventory {
        val base = runCatching {
            inventoryJson.decodeFromString(BuildInventory.serializer(), buildInventoryJson.getOrElse(""))
        }.getOrElse { BuildInventory.EMPTY }

        val declaredPlugins = buildFileLines.mapValues { (_, lines) -> PluginBlockParser.parse(lines) }
        val moduleInfo = BuildInventoryAssembler.moduleInfo(
            modulePaths = modulePaths,
            declaredPlugins = declaredPlugins,
            appliedClasses = pluginsData,
            catalogs = base.catalogs,
            toolchains = base.modules.associate { it.path to it.javaToolchain.orEmpty() }
                .filterValues { it.isNotEmpty() },
            kmpTargets = base.modules.associate { it.path to it.kmpTargets }.filterValues { it.isNotEmpty() },
            testSourceSets = base.modules.associate { it.path to it.testSourceSets }
                .filterValues { it.isNotEmpty() },
        )

        val rootDir = java.io.File(rootProjectDir.get())
        val moduleDirectories = buildFilePaths
            .mapNotNull { (path, file) -> file?.substringBeforeLast('/', "")?.let { path to it } }
            .filter { it.second.isNotEmpty() }
            .toMap()

        return BuildInventoryAssembler.merge(
            base = base,
            modules = moduleInfo,
            codeowners = BuildInventoryCollector.codeowners(rootDir, moduleDirectories),
            declaredMetadata = ModuleMetadataReader.read(rootDir, logger),
        )
    }

    /**
     * Reads each module's build file once, so [DeclarationLineFinder] can scan it for every edge
     * leaving that module. A file that cannot be read contributes no lines - an edge then simply has
     * no declaration line, which is the same as before this data existed.
     */
    private fun readBuildFiles(buildFilePaths: Map<String, String?>): Map<String, List<String>> {
        val rootDir = java.io.File(rootProjectDir.get())
        return buildFilePaths.mapNotNull { (modulePath, relativePath) ->
            if (relativePath == null) return@mapNotNull null
            val lines = runCatching { rootDir.resolve(relativePath).readLines() }.getOrElse { ex ->
                logger.info("Aalekh: could not read $relativePath for $modulePath - ${ex.message}")
                return@mapNotNull null
            }
            modulePath to lines
        }.toMap()
    }

    /**
     * Fills in each module's [ModuleNode.healthScore] from the assembled graph.
     *
     * A second pass because the score needs the finished graph: it reads fan-in, fan-out, transitive
     * reach, and cycle membership. Computing it here rather than in each consumer means the CSV
     * export, the report table, and any downstream tool all read one number produced by one formula.
     */
    private fun scoreHealth(graph: ModuleDependencyGraph): ModuleDependencyGraph {
        val cycleNodes = GraphAnalyzer.findMainOnlyCycles(graph).flatten().toSet()
        return graph.copy(
            modules = graph.modules.map { module ->
                module.copy(healthScore = HealthScoreCalculator.score(module.path, graph, cycleNodes))
            }
        )
    }

    // Parses the pipe-delimited external-dependency strings collected by the plugins into model
    // objects, applying the same test / compileOnly scope filters used for inter-module edges.
    private fun buildExternalDependencies(
        externalData: Map<String, List<String>>,
        includeTest: Boolean,
        includeCompileOnly: Boolean,
    ): List<ExternalDependency> =
        externalData.flatMap { (modulePath, depStrings) ->
            depStrings.mapNotNull { depString ->
                // "configurationName|group|name|version" - group/version may be empty.
                val parts = depString.split('|', limit = EXTERNAL_DEP_FIELD_COUNT)
                if (parts.size < EXTERNAL_DEP_FIELD_COUNT) return@mapNotNull null
                val config = parts[0]
                val name = parts[2]
                if (config.isEmpty() || name.isEmpty()) return@mapNotNull null
                if (!includeTest && ConfigurationClassifier.isTestConfig(config)) return@mapNotNull null
                if (!includeCompileOnly && ConfigurationClassifier.isCompileOnlyConfig(config)) {
                    return@mapNotNull null
                }
                ExternalDependency(
                    module = modulePath,
                    group = parts[1],
                    name = name,
                    version = parts[3].ifEmpty { null },
                    configuration = config,
                    sourceSet = ConfigurationClassifier.kmpSourceSetName(config),
                )
            }
        }.distinctBy { listOf(it.module, it.group, it.name, it.version, it.configuration) }

    private fun inferTags(path: String): Set<String> {
        val segments = path.split(":").filter { it.isNotBlank() }
        return if (segments.size > 1) segments.dropLast(1).toSet() else emptySet()
    }

    /**
     * The repo-relative build file path for a module.
     *
     * Prefers the real location reported by the Gradle model ([subprojectBuildFilePaths]), which is
     * correct even when a module does not follow the directory convention. Falls back to the
     * conventional `:feature:login:data` → `feature/login/data/build.gradle.kts` derivation for graphs
     * produced without that data, checking `.kts` before `.gradle`. Null when nothing resolves - the
     * module then simply has no build file hint in violation messages.
     */
    private fun resolveBuildFilePath(modulePath: String): String? {
        subprojectBuildFilePaths.getOrElse(emptyMap())[modulePath]?.let { return it }

        val relativeDirPath = modulePath.trimStart(':').replace(':', '/')
        val rootDir = java.io.File(rootProjectDir.get())
        val kts = rootDir.resolve("$relativeDirPath/build.gradle.kts")
        val groovy = rootDir.resolve("$relativeDirPath/build.gradle")
        return when {
            kts.exists() -> "$relativeDirPath/build.gradle.kts"
            groovy.exists() -> "$relativeDirPath/build.gradle"
            else -> null
        }
    }

    private companion object {
        // "configurationName|group|name|version" - four pipe-delimited fields.
        const val EXTERNAL_DEP_FIELD_COUNT = 4

        // Tolerant on read: an inventory written by a newer plugin version must not fail an older one.
        val inventoryJson = Json { ignoreUnknownKeys = true }
    }

}