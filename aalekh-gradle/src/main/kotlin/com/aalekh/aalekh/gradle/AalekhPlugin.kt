package com.aalekh.aalekh.gradle

import com.aalekh.aalekh.gradle.extractor.ConfigurationClassifier
import com.aalekh.aalekh.gradle.task.AalekhAffectedTask
import com.aalekh.aalekh.gradle.task.AalekhBaselineTask
import com.aalekh.aalekh.gradle.task.AalekhCheckTask
import com.aalekh.aalekh.gradle.task.AalekhExtractTask
import com.aalekh.aalekh.gradle.task.AalekhMainSequenceTask
import com.aalekh.aalekh.gradle.task.AalekhMetricsTask
import com.aalekh.aalekh.gradle.task.AalekhMermaidTask
import com.aalekh.aalekh.gradle.task.AalekhReportTask
import com.aalekh.aalekh.gradle.task.AalekhTemporalTask
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
 *     id("io.github.shivathapaa.aalekh") version "<latest>"
 * }
 * ```
 * Then remove the plugin from `build.gradle.kts`. The `aalekh { }` extension
 * block in `build.gradle.kts` stays exactly as-is. See the README for the
 * current published version.
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
                plugins { id("io.github.shivathapaa.aalekh") version "<version>" }
            """.trimIndent()
        }

        project.logger.warn(
            "\n⚠ Aalekh: the project plugin (io.github.shivathapaa.aalekh.project) is deprecated.\n" +
                    "  Migrate to the settings plugin for configuration cache stability:\n" +
                    "  In settings.gradle.kts: plugins { id(\"io.github.shivathapaa.aalekh\") }\n" +
                    "  Then remove Aalekh from build.gradle.kts. The aalekh { } block stays as-is.\n" +
                    "  See https://github.com/shivathapaa/aalekh for the latest version.\n"
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
            task.forbidEntries.set(extension.forbiddenEntries)
            task.reachabilityEntries.set(extension.rulesConfig.reachabilityEntries)
            task.teamEntries.set(project.provider { extension.teamOwnership.toInputString() })
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
            task.forbidEntries.set(extension.forbiddenEntries)
            task.reachabilityEntries.set(extension.rulesConfig.reachabilityEntries)
            task.baselineFile.set(
                extension.baselineFile.map { project.layout.projectDirectory.file(it) }
            )
            task.qualityGateMetrics.set(extension.qualityGatesConfig.metrics)
            task.qualityGateSeverity.set(extension.qualityGatesConfig.severity.map { it.name })

            task.dependsOn(extractTask)
        }

        project.tasks.register("aalekhMermaid", AalekhMermaidTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            val reportsDir = project.layout.buildDirectory.dir(extension.outputDir)
            task.mermaidFile.set(reportsDir.map { it.file("aalekh-graph.mmd") })
            task.markdownFile.set(reportsDir.map { it.file("aalekh-graph.md") })
            task.dotFile.set(reportsDir.map { it.file("aalekh-graph.dot") })
            task.dependsOn(extractTask)
        }

        project.tasks.register("aalekhBaseline", AalekhBaselineTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
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
            task.forbidEntries.set(extension.forbiddenEntries)
            task.reachabilityEntries.set(extension.rulesConfig.reachabilityEntries)
            task.baselineOutputFile.set(
                extension.baselineFile.map { project.layout.projectDirectory.file(it) }
            )
            task.dependsOn(extractTask)
        }

        project.tasks.register("aalekhTemporal", AalekhTemporalTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            task.rootDir.set(project.rootDir.absolutePath)
            task.commitWindow.set(extension.temporalCouplingConfig.commitWindow)
            task.minSharedCommits.set(extension.temporalCouplingConfig.minSharedCommits)
            task.hiddenCouplingThreshold.set(extension.temporalCouplingConfig.hiddenCouplingThreshold)
            val reportsDir = project.layout.buildDirectory.dir(extension.outputDir)
            task.jsonFile.set(reportsDir.map { it.file("aalekh-temporal.json") })
            task.markdownFile.set(reportsDir.map { it.file("aalekh-temporal.md") })
            task.dependsOn(extractTask)
        }

        project.tasks.register("aalekhAffected", AalekhAffectedTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            task.rootDir.set(project.rootDir.absolutePath)
            task.baseRef.set(extension.affectedGraphConfig.baseRef)
            task.headRef.set(extension.affectedGraphConfig.headRef)
            val reportsDir = project.layout.buildDirectory.dir(extension.outputDir)
            task.jsonFile.set(reportsDir.map { it.file("aalekh-affected.json") })
            task.markdownFile.set(reportsDir.map { it.file("aalekh-affected.md") })
            task.dependsOn(extractTask)
        }

        project.tasks.register("aalekhMainSequence", AalekhMainSequenceTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            task.rootDir.set(project.rootDir.absolutePath)
            val reportsDir = project.layout.buildDirectory.dir(extension.outputDir)
            task.jsonFile.set(reportsDir.map { it.file("aalekh-main-sequence.json") })
            task.markdownFile.set(reportsDir.map { it.file("aalekh-main-sequence.md") })
            task.dependsOn(extractTask)
        }

        project.tasks.register("aalekhMetrics", AalekhMetricsTask::class.java) { task ->
            task.graphJsonFile.set(graphJsonFile)
            task.projectName.set(project.name)
            val reportsDir = project.layout.buildDirectory.dir(extension.outputDir)
            task.jsonFile.set(reportsDir.map { it.file("aalekh-custom-metrics.json") })
            task.markdownFile.set(reportsDir.map { it.file("aalekh-custom-metrics.md") })
            task.dependsOn(extractTask)
        }

        project.pluginManager.withPlugin("base") {
            project.tasks.named("check").configure { it.dependsOn(checkTask) }
        }
    }

    private fun buildSubprojectData(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub ->
            val deps = mutableListOf<String>()
            sub.configurations
                .filter { cfg -> ConfigurationClassifier.isCaptured(cfg.name) }
                .forEach { cfg ->
                    cfg.dependencies.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                        .forEach { dep ->
                            val to = dep.path
                            if (to != sub.path) deps += "${cfg.name}:$to"
                        }
                }
            sub.path to deps
        }

    private fun buildPluginData(rootProject: Project): Map<String, List<String>> =
        rootProject.subprojects.associate { sub ->
            sub.path to sub.plugins.map { it.javaClass.name }
        }
}
