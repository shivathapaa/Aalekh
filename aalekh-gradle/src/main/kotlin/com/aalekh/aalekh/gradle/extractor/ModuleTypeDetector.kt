package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.gradle.task.AalekhExtractTask
import com.aalekh.aalekh.model.ModuleType

/**
 * Infers [ModuleType] from the plugin class names applied to a Gradle subproject.
 *
 * Pulled out of the task so detection logic can grow as the AGP/KGP ecosystem
 * evolves without touching the task itself, and so it is unit-testable in
 * isolation from Gradle.
 *
 * ### Detection priority
 * Order matters. KMP must be checked before plain Android because a KMP module
 * often also has the Android library plugin applied. The first match wins.
 *
 * ### Adding new module types
 * When AGP or KGP introduces new plugin IDs, add them here. The [ModuleType] enum
 * is the contract; this object is the detection implementation.
 */
public object ModuleTypeDetector {

    /**
     * Detects the module type from a list of plugin class names.
     *
     * [AalekhExtractTask] receives plugin class names as plain `String`s — no
     * live `Project` reference — so the detection survives configuration cache
     * serialisation. Plugin class names look like
     * `"org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper"`, so the match
     * is on known substrings rather than exact equality.
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
}
