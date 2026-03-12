package com.aalekh.aalekh.gradle

import com.aalekh.aalekh.gradle.task.AalekhCheckTask
import com.aalekh.aalekh.gradle.task.AalekhExtractTask
import com.aalekh.aalekh.gradle.task.AalekhReportTask
import org.gradle.api.Plugin
import org.gradle.api.file.RegularFile
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider

/**
 * Settings-scoped Aalekh plugin
 *
 * With `includeBuild`, Gradle's configuration cache stores task class references
 * using the classloader scope where the plugin was loaded. Project plugins are
 * loaded in the `root-project(export)` scope which is NOT preserved across CC
 * entries when the plugin comes from a composite build.
 *
 * Settings plugins are loaded in the `settings` classloader scope, which IS
 * stable across CC entries. This makes the CC second-run work correctly.
 *
 * ### Consumer usage (settings.gradle.kts)
 * ```kotlin
 * plugins {
 *     id("io.github.shivathapaa.aalekh") version "0.1.0"
 * }
 * ```
 *
 * The extension is configured on the root project, not on settings:
 * ```kotlin
 * // build.gradle.kts (root project)
 * aalekh {
 *     openBrowserAfterReport.set(false)
 * }
 * ```
 */
public class AalekhSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        // Wait for the root project to be available, then register everything on it.
        // gradle.rootProject { } is the correct hook - it fires after settings are
        // processed but before any project is configured.
        settings.gradle.rootProject { rootProject ->
            val extension = rootProject.extensions.create(
                AalekhExtension.NAME,
                AalekhExtension::class.java,
            )

            val graphJsonFile: Provider<RegularFile> =
                rootProject.layout.buildDirectory.file("tmp/aalekh/graph.json")

            val extractTask = rootProject.tasks.register(
                "aalekhExtract",
                AalekhExtractTask::class.java,
            ) { task ->
                task.projectName.set(rootProject.name)
                task.gradleVersion.set(rootProject.gradle.gradleVersion)
                // Capture dependency + plugin data as plain Strings inside providers.
                // provider { } lambdas run at configuration time but only after all
                // subprojects are configured (they are evaluated lazily when the task
                // input is first read). No live Project/Configuration objects survive
                // into the task - fully CC-safe.
                task.subprojectData.set(rootProject.provider {
                    buildSubprojectData(rootProject)
                })
                task.subprojectPlugins.set(rootProject.provider {
                    buildPluginData(rootProject)
                })
                task.outputFile.set(graphJsonFile)
            }

            rootProject.tasks.register(
                "aalekhReport",
                AalekhReportTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.openBrowser.set(extension.openBrowserAfterReport)
                task.outputFile.set(
                    rootProject.layout.buildDirectory
                        .dir(extension.outputDir)
                        .map { it.file("index.html") }
                )
                task.dependsOn(extractTask)
            }

            val checkTask = rootProject.tasks.register(
                "aalekhCheck",
                AalekhCheckTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.outputDir.set(rootProject.layout.buildDirectory.dir(extension.outputDir))
                task.dependsOn(extractTask)
            }

            rootProject.pluginManager.withPlugin("base") {
                rootProject.tasks.named("check").configure { it.dependsOn(checkTask) }
            }
        }
    }

    private fun buildSubprojectData(rootProject: org.gradle.api.Project): Map<String, List<String>> {
        val productionConfigs = setOf("implementation", "api", "compileOnly", "runtimeOnly")
        val testConfigs = setOf(
            "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly",
            "androidTestImplementation", "androidTestRuntimeOnly",
            "debugImplementation", "releaseImplementation",
        )
        val allConfigs = productionConfigs + testConfigs

        return rootProject.subprojects.associate { subproject ->
            val deps = mutableListOf<String>()
            subproject.configurations
                .filter { cfg -> allConfigs.contains(cfg.name) || isKmpConfig(cfg.name) }
                .forEach { cfg ->
                    cfg.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                        .forEach { dep ->
                            val to = dep.path
                            if (to != subproject.path) deps += "${cfg.name}:$to"
                        }
                }
            subproject.path to deps
        }
    }

    private fun buildPluginData(rootProject: org.gradle.api.Project): Map<String, List<String>> =
        rootProject.subprojects.associate { subproject ->
            subproject.path to subproject.plugins.map { it.javaClass.name }
        }

    private fun isKmpConfig(name: String): Boolean {
        val kmpSuffixes = listOf("Implementation", "Api", "CompileOnly", "RuntimeOnly")
        val standardConfigs = setOf(
            "implementation", "api", "compileOnly", "runtimeOnly",
            "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly",
        )
        return kmpSuffixes.any { name.endsWith(it) } && name !in standardConfigs
    }
}
