package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.gradle.task.AalekhExtractTask
import com.aalekh.aalekh.model.ModuleType
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

/**
 * Infers [ModuleType] from the plugin IDs applied to a Gradle project.
 *
 * Deliberately separated from [GraphExtractor] because:
 * 1. It is unit-testable without a full Gradle project (using [ProjectBuilder])
 * 2. Detection logic will grow as the AGP/KGP ecosystem evolves
 * 3. Single responsibility -> extraction and detection are different concerns
 *
 * ### Detection priority
 * The order matters. KMP must be checked before plain Android because a KMP
 * module often also has the Android library plugin applied.
 *
 * ### Adding new module types
 * When AGP or KGP introduce new plugin IDs, add them here. The [ModuleType]
 * enum is the contract; this object is the detection implementation.
 */
public object ModuleTypeDetector {
    // Plugin IDs checked in priority order
    private val KMP_PLUGIN_IDS = setOf(
        "org.jetbrains.kotlin.multiplatform",
    )
    private val KMP_ANDROID_LIB_PLUGIN_IDS = setOf(
        "com.android.kotlin.multiplatform.library",  // AGP 9.x
    )
    private val ANDROID_APP_PLUGIN_IDS = setOf(
        "com.android.application",
    )
    private val ANDROID_LIB_PLUGIN_IDS = setOf(
        "com.android.library",
        "com.android.dynamic-feature",
    )
    private val JVM_PLUGIN_IDS = setOf(
        "org.jetbrains.kotlin.jvm",
        "java-library",
        "java",
    )

    /** Detects the module type for a Gradle [Project]. */
    public fun detect(project: Project): ModuleType = when {
        project.hasAnyPlugin(KMP_PLUGIN_IDS) -> ModuleType.KMP
        project.hasAnyPlugin(KMP_ANDROID_LIB_PLUGIN_IDS) -> ModuleType.KMP_ANDROID_LIBRARY
        project.hasAnyPlugin(ANDROID_APP_PLUGIN_IDS) -> ModuleType.ANDROID_APP
        project.hasAnyPlugin(ANDROID_LIB_PLUGIN_IDS) -> ModuleType.ANDROID_LIBRARY
        project.hasAnyPlugin(JVM_PLUGIN_IDS) -> ModuleType.JVM_LIBRARY
        else -> ModuleType.UNKNOWN
    }

    /**
     * Detects the module type from a list of plugin class names.
     * Used by [AalekhExtractTask] which receives plain
     * Strings (no live Project reference) for configuration cache safety.
     *
     * Plugin class names look like "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper"
     * so we match against known substrings.
     */
    public fun detectFromPluginNames(pluginClassNames: List<String>): ModuleType {
        val names = pluginClassNames.joinToString(" ")
        return when {
            names.contains("KotlinMultiplatformPluginWrapper") ||
                    names.contains("kotlin.multiplatform") -> ModuleType.KMP

            names.contains("com.android.kotlin.multiplatform") -> ModuleType.KMP_ANDROID_LIBRARY
            names.contains("AppPlugin") ||
                    names.contains("com.android.application") -> ModuleType.ANDROID_APP

            names.contains("LibraryPlugin") ||
                    names.contains("com.android.library") ||
                    names.contains("DynamicFeaturePlugin") -> ModuleType.ANDROID_LIBRARY

            names.contains("KotlinPluginWrapper") ||
                    names.contains("JavaLibraryPlugin") ||
                    names.contains("JavaPlugin") -> ModuleType.JVM_LIBRARY

            else -> ModuleType.UNKNOWN
        }
    }

    private fun Project.hasAnyPlugin(ids: Set<String>): Boolean =
        ids.any { plugins.hasPlugin(it) }
}