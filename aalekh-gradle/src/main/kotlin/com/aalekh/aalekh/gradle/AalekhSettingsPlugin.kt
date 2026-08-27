package com.aalekh.aalekh.gradle

import com.aalekh.aalekh.gradle.extractor.ConfigurationClassifier
import com.aalekh.aalekh.gradle.task.AalekhAffectedTask
import com.aalekh.aalekh.gradle.task.AalekhMainSequenceTask
import com.aalekh.aalekh.gradle.task.AalekhBaselineTask
import com.aalekh.aalekh.gradle.task.AalekhCheckTask
import com.aalekh.aalekh.gradle.task.AalekhExtractTask
import com.aalekh.aalekh.gradle.task.AalekhMermaidTask
import com.aalekh.aalekh.gradle.task.AalekhReportTask
import com.aalekh.aalekh.gradle.task.AalekhTemporalTask
import org.gradle.api.Plugin
import org.gradle.api.file.RegularFile
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Provider

/**
 * Settings-scoped Aalekh plugin - the **primary entry point**.
 *
 * Applied in `settings.gradle.kts`, this plugin is loaded in the `settings`
 * classloader scope which is stable across configuration cache (CC) entries.
 * This is the correct scope for a plugin used with `includeBuild` - project
 * plugins loaded in the `root-project(export)` scope are NOT preserved across
 * CC entries when the plugin comes from a composite build.
 *
 * ### Consumer usage
 * ```kotlin
 * // settings.gradle.kts
 * plugins {
 *     id("io.github.shivathapaa.aalekh") version "<latest>"
 * }
 * ```
 * See the README for the current published version.
 *
 * Configure the extension on the **root project**, not on settings:
 * ```kotlin
 * // build.gradle.kts (root project)
 * aalekh {
 *     openBrowserAfterReport.set(false)
 *     includeTestDependencies.set(false)   // exclude test deps from graph
 *     includeCompileOnlyDependencies.set(true) // include compileOnly
 * }
 * ```
 *
 * ### Architecture note - settings plugin vs project plugin
 * `AalekhPlugin` (the project plugin) is kept for backwards compatibility but
 * is deprecated. `AalekhSettingsPlugin` is the canonical implementation.
 * All logic lives here; `AalekhPlugin` delegates to the same task types.
 */
public class AalekhSettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        // gradle.rootProject { } fires after settings are processed but before
        // any project is configured - the correct hook for registering tasks
        // that need to observe all subprojects.
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
                // provider { } lambdas evaluate lazily when the task input is first
                // fingerprinted - after all subprojects are configured.
                // No live Project/Configuration objects survive into the task: CC-safe.
                task.subprojectData.set(rootProject.provider {
                    buildSubprojectData(rootProject)
                })
                task.subprojectPlugins.set(rootProject.provider {
                    buildPluginData(rootProject)
                })

                task.includeTestDependencies.set(extension.includeTestDependencies)
                task.includeCompileOnlyDependencies.set(extension.includeCompileOnlyDependencies)
                task.rootProjectDir.set(rootProject.rootDir.absolutePath)
                task.outputFile.set(graphJsonFile)
            }

            rootProject.tasks.register(
                "aalekhReport",
                AalekhReportTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.openBrowser.set(extension.openBrowserAfterReport)
                task.exportMetrics.set(extension.exportMetrics)
                task.outputFile.set(
                    rootProject.layout.buildDirectory
                        .dir(extension.outputDir)
                        .map { it.file("index.html") }
                )
                task.layerEntries.set(rootProject.provider {
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
                task.teamEntries.set(rootProject.provider {
                    extension.teamOwnership.toInputString()
                })
                task.trendFile.set(rootProject.layout.buildDirectory.file("aalekh/trend.json"))
                task.dependsOn(extractTask)
            }

            val checkTask = rootProject.tasks.register(
                "aalekhCheck",
                AalekhCheckTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.outputDir.set(rootProject.layout.buildDirectory.dir(extension.outputDir))

                // Serialize layer config to CC-safe strings at configuration time.
                // Format: "name|pat1,pat2|allowed1,allowed2|hasRestriction"
                task.layerEntries.set(rootProject.provider {
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
                    extension.baselineFile.map { rootProject.layout.projectDirectory.file(it) }
                )
                task.qualityGateMetrics.set(extension.qualityGatesConfig.metrics)
                task.qualityGateSeverity.set(extension.qualityGatesConfig.severity.map { it.name })

                task.dependsOn(extractTask)
            }

            rootProject.tasks.register(
                "aalekhMermaid",
                AalekhMermaidTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                val reportsDir = rootProject.layout.buildDirectory.dir(extension.outputDir)
                task.mermaidFile.set(reportsDir.map { it.file("aalekh-graph.mmd") })
                task.markdownFile.set(reportsDir.map { it.file("aalekh-graph.md") })
                task.dependsOn(extractTask)
            }

            rootProject.tasks.register(
                "aalekhBaseline",
                AalekhBaselineTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.layerEntries.set(rootProject.provider {
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
                    extension.baselineFile.map { rootProject.layout.projectDirectory.file(it) }
                )
                task.dependsOn(extractTask)
            }

            rootProject.tasks.register(
                "aalekhTemporal",
                AalekhTemporalTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.rootDir.set(rootProject.rootDir.absolutePath)
                task.commitWindow.set(extension.temporalCouplingConfig.commitWindow)
                task.minSharedCommits.set(extension.temporalCouplingConfig.minSharedCommits)
                task.hiddenCouplingThreshold.set(extension.temporalCouplingConfig.hiddenCouplingThreshold)
                val reportsDir = rootProject.layout.buildDirectory.dir(extension.outputDir)
                task.jsonFile.set(reportsDir.map { it.file("aalekh-temporal.json") })
                task.markdownFile.set(reportsDir.map { it.file("aalekh-temporal.md") })
                task.dependsOn(extractTask)
            }

            rootProject.tasks.register(
                "aalekhAffected",
                AalekhAffectedTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.rootDir.set(rootProject.rootDir.absolutePath)
                task.baseRef.set(extension.affectedGraphConfig.baseRef)
                task.headRef.set(extension.affectedGraphConfig.headRef)
                val reportsDir = rootProject.layout.buildDirectory.dir(extension.outputDir)
                task.jsonFile.set(reportsDir.map { it.file("aalekh-affected.json") })
                task.markdownFile.set(reportsDir.map { it.file("aalekh-affected.md") })
                task.dependsOn(extractTask)
            }

            rootProject.tasks.register(
                "aalekhMainSequence",
                AalekhMainSequenceTask::class.java,
            ) { task ->
                task.graphJsonFile.set(graphJsonFile)
                task.projectName.set(rootProject.name)
                task.rootDir.set(rootProject.rootDir.absolutePath)
                val reportsDir = rootProject.layout.buildDirectory.dir(extension.outputDir)
                task.jsonFile.set(reportsDir.map { it.file("aalekh-main-sequence.json") })
                task.markdownFile.set(reportsDir.map { it.file("aalekh-main-sequence.md") })
                task.dependsOn(extractTask)
            }

            rootProject.pluginManager.withPlugin("base") {
                rootProject.tasks.named("check").configure { it.dependsOn(checkTask) }
            }
        }
    }

    // Data collection
    //
    // Collects ALL configurations Aalekh considers architecturally significant
    // ([ConfigurationClassifier.isCaptured]). Filtering by
    // includeTestDependencies / includeCompileOnlyDependencies happens
    // inside AalekhExtractTask, not here. This separation means:
    //   1. The task input is stable (changing a flag doesn't change the raw
    //      data passed to the task, only how the task processes it).
    //   2. The CC cache is correctly invalidated via the task's @Input flags,
    //      not via the provider lambda re-running.
    //
    // Note: compileOnly IS included in the raw collection here even though
    // the default extension flag excludes it from the final graph. The task
    // strips it if includeCompileOnlyDependencies = false.

    private fun buildSubprojectData(
        rootProject: org.gradle.api.Project,
    ): Map<String, List<String>> =
        rootProject.subprojects.associate { subproject ->
            val deps = mutableListOf<String>()
            subproject.configurations
                .filter { cfg -> ConfigurationClassifier.isCaptured(cfg.name) }
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

    private fun buildPluginData(
        rootProject: org.gradle.api.Project,
    ): Map<String, List<String>> =
        rootProject.subprojects.associate { subproject ->
            subproject.path to subproject.plugins.map { it.javaClass.name }
        }
}
