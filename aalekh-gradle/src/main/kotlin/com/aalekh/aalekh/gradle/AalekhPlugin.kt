package com.aalekh.aalekh.gradle

import com.aalekh.aalekh.gradle.task.AalekhCheckTask
import com.aalekh.aalekh.gradle.task.AalekhExtractTask
import com.aalekh.aalekh.gradle.task.AalekhReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Project-scoped Aalekh plugin - **deprecated, use the settings plugin instead**.
 *
 * This plugin is kept for backwards compatibility with users who applied Aalekh
 * via `build.gradle.kts` before the settings plugin was introduced. It produces
 * a deprecation warning on first use and registers the same tasks as
 * [AalekhSettingsPlugin].
 *
 * ## Why deprecated?
 * Project plugins applied via `includeBuild` are loaded in the
 * `root-project(export)` classloader scope, which is NOT preserved across
 * configuration cache entries. This causes a CC miss on every second run.
 * The settings plugin is loaded in the `settings` scope, which IS stable.
 *
 * ## Migration (takes ~30 seconds)
 * ```kotlin
 * // settings.gradle.kts - REPLACE the build.gradle.kts plugin block with this:
 * plugins {
 *     id("io.github.shivathapaa.aalekh") version "0.1.1"
 * }
 * ```
 * Then remove the plugin from `build.gradle.kts`. The `aalekh { }` extension
 * block in `build.gradle.kts` stays exactly as-is.
 */
@Deprecated(
    message = "Use the settings plugin instead: " +
            "id(\"io.github.shivathapaa.aalekh\") in settings.gradle.kts. " +
            "The project plugin will be removed in a future release.",
    replaceWith = ReplaceWith("AalekhSettingsPlugin"),
)
public class AalekhPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        require(project == project.rootProject) {
            """
            Aalekh must be applied to the root project only.
            It was applied to '${project.path}'.

            Recommended: use the settings plugin in settings.gradle.kts instead:
                plugins { id("io.github.shivathapaa.aalekh") version "0.1.1" }
            """.trimIndent()
        }

        project.logger.warn(
            "\n⚠ Aalekh: the project plugin (io.github.shivathapaa.aalekh.project) is deprecated.\n" +
                    "  Migrate to the settings plugin for configuration cache stability:\n" +
                    "  In settings.gradle.kts: plugins { id(\"io.github.shivathapaa.aalekh\") version \"0.1.1\" }\n" +
                    "  Then remove Aalekh from build.gradle.kts. The aalekh { } block stays as-is.\n"
        )

        val extension = project.extensions.create(
            AalekhExtension.NAME,
            AalekhExtension::class.java,
        )

        val graphJsonFile: Provider<RegularFile> =
            project.layout.buildDirectory.file("tmp/aalekh/graph.json")

        val extractTask = project.tasks.register(
            "aalekhExtract", AalekhExtractTask::class.java,
        ) { task ->
            task.projectName.set(project.name)
            task.gradleVersion.set(project.gradle.gradleVersion)
            task.subprojectData.set(project.provider { buildSubprojectData(project) })
            task.subprojectPlugins.set(project.provider { buildPluginData(project) })
            // Wire extension flags - same as settings plugin
            task.includeTestDependencies.set(extension.includeTestDependencies)
            task.includeCompileOnlyDependencies.set(extension.includeCompileOnlyDependencies)
            task.rootProjectDir.set(project.rootDir.absolutePath)
            task.outputFile.set(graphJsonFile)
        }

        project.tasks.register("aalekhReport", AalekhReportTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            task.openBrowser.set(extension.openBrowserAfterReport)
            task.exportMetrics.set(extension.exportMetrics)
            task.outputFile.set(
                project.layout.buildDirectory
                    .dir(extension.outputDir)
                    .map { it.file("index.html") }
            )
            task.layerEntries.set(project.provider {
                extension.layerContainer.map { layer ->
                    val patterns = layer.modulePatterns.get().joinToString(",")
                    val allowed = layer.allowedDependencyLayers.get().joinToString(",")
                    val restricted = layer.hasRestriction.get()
                    "${layer.name}|$patterns|$allowed|$restricted"
                }
            })
            task.featurePattern.set(extension.featureIsolationConfig.featurePattern)
            task.featureAllowedPairs.set(extension.featureIsolationConfig.allowedPairs)
            task.ruleEntries.set(extension.rulesConfig.entries)
            task.trendFile.set(project.layout.buildDirectory.file("aalekh/trend.json"))
            task.dependsOn(extractTask)
        }

        val checkTask = project.tasks.register("aalekhCheck", AalekhCheckTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            task.outputDir.set(project.layout.buildDirectory.dir(extension.outputDir))

            task.layerEntries.set(project.provider {
                extension.layerContainer.map { layer ->
                    val patterns = layer.modulePatterns.get().joinToString(",")
                    val allowed = layer.allowedDependencyLayers.get().joinToString(",")
                    val restricted = layer.hasRestriction.get()
                    "${layer.name}|$patterns|$allowed|$restricted"
                }
            })
            task.featurePattern.set(extension.featureIsolationConfig.featurePattern)
            task.featureAllowedPairs.set(extension.featureIsolationConfig.allowedPairs)
            task.ruleEntries.set(extension.rulesConfig.entries)

            task.dependsOn(extractTask)
        }

        project.pluginManager.withPlugin("base") {
            project.tasks.named("check").configure { it.dependsOn(checkTask) }
        }
    }

    private fun buildSubprojectData(rootProject: Project): Map<String, List<String>> {
        val allConfigs = setOf(
            "implementation", "api", "compileOnly", "runtimeOnly",
            "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly",
            "androidTestImplementation", "androidTestRuntimeOnly",
            "debugImplementation", "releaseImplementation",
        )
        return rootProject.subprojects.associate { sub ->
            val deps = mutableListOf<String>()
            sub.configurations
                .filter { cfg -> allConfigs.contains(cfg.name) || isKmpConfig(cfg.name) }
                .forEach { cfg ->
                    cfg.dependencies.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                        .forEach { dep ->
                            val to = dep.path
                            if (to != sub.path) deps += "${cfg.name}:$to"
                        }
                }
            sub.path to deps
        }
    }

    private fun buildPluginData(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub ->
            sub.path to sub.plugins.map { it.javaClass.name }
        }

    private fun isKmpConfig(name: String): Boolean {
        val kmpSuffixes = listOf("Implementation", "Api", "CompileOnly", "RuntimeOnly")
        val standardConfigs = setOf(
            "implementation", "api", "compileOnly", "runtimeOnly",
            "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly"
        )
        return kmpSuffixes.any { name.endsWith(it) } && name !in standardConfigs
    }
}