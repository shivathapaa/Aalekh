package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.gradle.extractor.ModuleTypeDetector
import com.aalekh.aalekh.model.AalekhBuildConfig
import com.aalekh.aalekh.model.DependencyEdge
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
        val pluginsData = subprojectPlugins.get()
        val includeTest = includeTestDependencies.getOrElse(true)
        val includeCompileOnly = includeCompileOnlyDependencies.getOrElse(false)

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
            )
        }

        val edges = depsData.flatMap { (fromPath, depStrings) ->
            depStrings.mapNotNull { depString ->
                val colonIdx = depString.indexOf(':')
                if (colonIdx < 0) return@mapNotNull null
                val config = depString.substring(0, colonIdx)
                val toPath = depString.substring(colonIdx + 1)

                // Apply filter flags before building the edge - this is the
                // single place where the extension flags take effect.
                if (!includeTest && isTestConfig(config)) return@mapNotNull null
                if (!includeCompileOnly && isCompileOnlyConfig(config)) return@mapNotNull null

                DependencyEdge(
                    from = fromPath,
                    to = toPath,
                    configuration = config,
                    sourceSet = kmpSourceSetName(config),
                )
            }
        }.distinctBy { Triple(it.from, it.to, it.configuration) }

        val graph = ModuleDependencyGraph(
            projectName = projectName.get(),
            modules = nodes,
            edges = edges,
            metadata = mapOf(
                "gradleVersion" to gradleVersion.get(),
                "extractedAt" to Instant.now().toString(),
                "moduleCount" to nodes.size.toString(),
                "edgeCount" to edges.size.toString(),
                "aalekhVersion" to AalekhBuildConfig.VERSION,
                "includeTestDependencies" to includeTest.toString(),
                "includeCompileOnlyDependencies" to includeCompileOnly.toString(),
            ),
        )

        val json = Json { encodeDefaults = true }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(graph))

        logger.lifecycle(
            "Aalekh extracted ${nodes.size} modules, ${edges.size} edges" +
                    (if (!includeTest) " (test deps excluded)" else "") +
                    (if (includeCompileOnly) " (compileOnly included)" else "")
        )
    }

    private fun inferTags(path: String): Set<String> {
        val segments = path.split(":").filter { it.isNotBlank() }
        return if (segments.size > 1) segments.dropLast(1).toSet() else emptySet()
    }

    // Config classification

    private fun isTestConfig(configName: String): Boolean =
        configName.contains("test", ignoreCase = true)

    private fun isCompileOnlyConfig(configName: String): Boolean =
        configName.contains("compileOnly", ignoreCase = true)

    private val KMP_CONFIG_SUFFIXES = listOf("Implementation", "Api", "CompileOnly", "RuntimeOnly")
    private val STANDARD_CONFIGS = setOf(
        "implementation", "api", "compileOnly", "runtimeOnly",
        "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly"
    )

    /**
     * Extracts the KMP source set name from a configuration name, or null for
     * standard (non-KMP) configurations.
     *
     * Examples:
     * - `"commonMainImplementation"` → `"commonMain"`
     * - `"androidMainApi"` → `"androidMain"`
     * - `"implementation"` → `null`
     */
    private fun kmpSourceSetName(configName: String): String? {
        if (configName in STANDARD_CONFIGS) return null
        val suffix = KMP_CONFIG_SUFFIXES.firstOrNull { configName.endsWith(it) } ?: return null
        val candidate = configName.removeSuffix(suffix)
        return candidate.ifEmpty { null }
    }
}