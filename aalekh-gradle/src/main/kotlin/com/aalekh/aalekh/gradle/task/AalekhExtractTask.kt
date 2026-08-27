package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.gradle.extractor.ConfigurationClassifier
import com.aalekh.aalekh.gradle.extractor.ModuleTypeDetector
import com.aalekh.aalekh.model.AalekhBuildConfig
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ExternalDependency
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
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
        val includeTest = includeTestDependencies.getOrElse(true)
        val includeCompileOnly = includeCompileOnlyDependencies.getOrElse(false)
        val includeExternal = includeExternalDependencies.getOrElse(true)

        val nodes = depsData.keys.sorted().map { path ->
            val plugins = pluginsData[path] ?: emptyList()
            val type = ModuleTypeDetector.detectFromPluginNames(plugins)
            val tags = inferTags(path)
            ModuleNode(
                path = path,
                name = path.substringAfterLast(":"),
                type = type,
                plugins = plugins.toSet(),
                tags = tags,
                sourceSets = emptySet(),
                buildFilePath = resolveBuildFilePath(path),
            )
        }

        val edges = buildEdges(depsData, includeTest, includeCompileOnly)

        val externalDependencies =
            if (includeExternal) buildExternalDependencies(externalData, includeTest, includeCompileOnly)
            else emptyList()

        val graph = ModuleDependencyGraph(
            projectName = projectName.get(),
            modules = nodes,
            edges = edges,
            externalDependencies = externalDependencies,
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
    ): List<DependencyEdge> =
        depsData.flatMap { (fromPath, depStrings) ->
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
                )
            }
        }.distinctBy { Triple(it.from, it.to, it.configuration) }

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
     * Derives the conventional build file path from a Gradle module path.
     *
     * Gradle convention: `:feature:login:data` lives at `feature/login/data/build.gradle.kts`.
     * Check `.kts` first then fall back to `.gradle` (Groovy DSL projects).
     * Returns null if neither file exists - the module may use a non-standard location.
     */
    private fun resolveBuildFilePath(modulePath: String): String? {
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
    }

}