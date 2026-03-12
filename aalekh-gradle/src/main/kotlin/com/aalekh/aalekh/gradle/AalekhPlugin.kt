package com.aalekh.aalekh.gradle

import com.aalekh.aalekh.gradle.task.AalekhCheckTask
import com.aalekh.aalekh.gradle.task.AalekhExtractTask
import com.aalekh.aalekh.gradle.task.AalekhReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Project-scoped Aalekh plugin - legacy / fallback entry point.
 *
 * With `includeBuild`, use the settings plugin (`AalekhSettingsPlugin`) applied
 * in `settings.gradle.kts`. It is loaded in the `settings` classloader scope
 * which is stable across configuration cache entries.
 *
 * This project plugin still works correctly when Aalekh is consumed from
 * the Gradle Plugin Portal (not via `includeBuild`), because in that case
 * the plugin JAR is on the root classloader from the start.
 *
 * ## Usage (build.gradle.kts of root project)
 * ```kotlin
 * plugins {
 *     id("io.github.shivathapaa.aalekh.project") version "0.1.0"
 * }
 * ```
 */
public class AalekhPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) {
            """
            Aalekh must be applied to the root project only.
            It was applied to '${project.path}'.

            Recommended: use the settings plugin in settings.gradle.kts instead:
                plugins { id("io.github.shivathapaa.aalekh") version "0.1.0" }
            """.trimIndent()
        }

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
            task.outputFile.set(graphJsonFile)
        }

        project.tasks.register("aalekhReport", AalekhReportTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            task.openBrowser.set(extension.openBrowserAfterReport)
            task.outputFile.set(
                project.layout.buildDirectory
                    .dir(extension.outputDir)
                    .map { it.file("index.html") }
            )
            task.dependsOn(extractTask)
        }

        val checkTask = project.tasks.register("aalekhCheck", AalekhCheckTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            task.outputDir.set(project.layout.buildDirectory.dir(extension.outputDir))
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
