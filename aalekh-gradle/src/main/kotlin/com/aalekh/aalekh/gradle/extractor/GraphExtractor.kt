package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.gradle.service.AalekhBuildService
import com.aalekh.aalekh.model.AalekhBuildConfig
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import java.time.Instant


/**
 * Extracts a [ModuleDependencyGraph] from a configured Gradle root project.
 *
 * ### Design principles
 * - **Read-only**: only reads from already-resolved project state. Never triggers
 *   additional dependency resolution, which would affect build performance.
 * - **CC-safe**: does NOT store any reference to [Project]. Call this during
 *   `projectsEvaluated`, store the result in [AalekhBuildService], then discard.
 * - **No external library dependencies**: uses only Gradle API + Kotlin stdlib.
 * - **Fail-silent on individual modules**: a broken module config should not
 *   crash the entire extraction. Errors are logged and the module is included
 *   with type [ModuleType.UNKNOWN].
 *
 * ### Performance
 * Reads from already-configured state - no I/O, no new resolution.
 * On a 200-module project this should complete in under 100ms.
 */
public object GraphExtractor {
    /**
     * Gradle configurations whose project-to-project deps are architecturally significant.
     * These are the exact names Gradle uses - case-sensitive.
     */
    private val PRODUCTION_CONFIGS = setOf(
        "implementation", "api", "compileOnly", "runtimeOnly",
    )

    private val TEST_CONFIGS = setOf(
        "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly",
        "androidTestImplementation", "androidTestRuntimeOnly",
        "debugImplementation", "releaseImplementation",
    )

    /**
     * Extracts the full dependency graph from [rootProject].
     * Must be called from `projectsEvaluated` or later.
     */
    public fun extract(rootProject: Project): ModuleDependencyGraph {
        val subprojects = rootProject.subprojects.sortedBy { it.path }

        val nodes = subprojects.mapNotNull { project ->
            runCatching { buildNode(project) }
                .onFailure { ex ->
                    rootProject.logger.warn(
                        "Aalekh: failed to extract node for '${project.path}': ${ex.message}"
                    )
                }
                .getOrNull()
        }

        val edges = subprojects.flatMap { project ->
            runCatching { extractEdges(project) }
                .onFailure { ex ->
                    rootProject.logger.warn(
                        "Aalekh: failed to extract edges for '${project.path}': ${ex.message}"
                    )
                }
                .getOrElse { emptyList() }
        }

        return ModuleDependencyGraph(
            projectName = rootProject.name,
            modules = nodes,
            edges = edges
                .filter { it.from != it.to }  // drop self-loops (Gradle allows project(":self") but it's not architectural)
                .distinctBy { Triple(it.from, it.to, it.configuration) },
            metadata = buildMetadata(rootProject),
        )
    }

    private fun buildNode(project: Project): ModuleNode = ModuleNode(
        path = project.path,
        name = project.name,
        type = ModuleTypeDetector.detect(project),
        plugins = project.plugins.map { it.javaClass.name }.toSet(),
        sourceSets = detectSourceSets(project),
        tags = inferTags(project),
    )

    /**
     * Infers organizational tags from the module path.
     * `:feature:login:ui` -> `setOf("feature", "login")`
     * `:core:domain`      -> `setOf("core")`
     */
    private fun inferTags(project: Project): Set<String> {
        val segments = project.path.split(":").filter { it.isNotBlank() }
        return if (segments.size > 1) segments.dropLast(1).toSet() else emptySet()
    }

    /**
     * Discovers KMP source sets via reflection to avoid a hard compile dependency on KGP.
     * Returns an empty set for non-KMP modules.
     */
    private fun detectSourceSets(project: Project): Set<String> {
        if (!project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) return emptySet()
        return runCatching {
            val kotlin = project.extensions.findByName("kotlin") ?: return emptySet()
            val sourceSets = kotlin.javaClass.getMethod("getSourceSets").invoke(kotlin)
            @Suppress("UNCHECKED_CAST")
            (sourceSets as? Iterable<Any>)
                ?.mapNotNull { it.javaClass.getMethod("getName").invoke(it) as? String }
                ?.toSet()
                ?: emptySet()
        }.getOrElse { emptySet() }
    }

    private fun extractEdges(project: Project): List<DependencyEdge> {
        val allConfigs = PRODUCTION_CONFIGS + TEST_CONFIGS

        return project.configurations
            .filter { config ->
                allConfigs.contains(config.name) || isKmpSourceSetConfig(config.name)
            }
            .flatMap { config ->
                config.dependencies
                    .filterIsInstance<ProjectDependency>()
                    .map { dep ->
                        DependencyEdge(
                            from = project.path,
                            to = dep.path,
                            configuration = config.name,
                            sourceSet = kmpSourceSetName(config.name),
                        )
                    }
            }
    }

    /**
     * KMP source set configurations follow the pattern: `<sourceSetName><ConfigSuffix>`
     * e.g. `commonMainImplementation`, `androidMainApi`, `iosMainCompileOnly`
     */
    private val KMP_CONFIG_SUFFIXES = listOf(
        "Implementation", "Api", "CompileOnly", "RuntimeOnly",
        "TestImplementation", "TestRuntimeOnly",
    )

    private fun isKmpSourceSetConfig(name: String): Boolean =
        KMP_CONFIG_SUFFIXES.any { name.endsWith(it) } &&
                !PRODUCTION_CONFIGS.contains(name) &&
                !TEST_CONFIGS.contains(name)

    /** Extracts the source set name from a KMP configuration, or null for non-KMP configs. */
    private fun kmpSourceSetName(configName: String): String? {
        val suffix = KMP_CONFIG_SUFFIXES.firstOrNull { configName.endsWith(it) } ?: return null
        val candidate = configName.removeSuffix(suffix)
        return candidate.ifEmpty { null }
    }

    private fun buildMetadata(rootProject: Project): Map<String, String> = buildMap {
        put("gradleVersion", rootProject.gradle.gradleVersion)
        put("extractedAt", Instant.now().toString())
        put("moduleCount", rootProject.subprojects.size.toString())
        put("aalekhVersion", AalekhBuildConfig.VERSION)

        // AGP version - read without a hard dependency on AGP on the classpath
        runCatching {
            val agpClass = Class.forName("com.android.Version")
            val version = agpClass.getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null)
            put("agpVersion", version.toString())
        }

        // Kotlin version
        runCatching {
            val kotlinClass = Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion")
            val version = kotlinClass.getField("VERSION").get(null)
            put("kotlinVersion", version.toString())
        }
    }
}