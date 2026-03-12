package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * The type of Gradle module, inferred from its applied plugin IDs.
 *
 * Detection priority (first match wins - order matters for KMP vs plain Android):
 * 1. [KMP] - `org.jetbrains.kotlin.multiplatform`
 * 2. [KMP_ANDROID_LIBRARY] - `com.android.kotlin.multiplatform.library` (AGP 9.x)
 * 3. [ANDROID_APP] - `com.android.application`
 * 4. [ANDROID_LIBRARY] - `com.android.library`
 * 5. [JVM_LIBRARY] - `org.jetbrains.kotlin.jvm`
 * 6. [UNKNOWN] - fallback when no known plugin is applied
 */
@Serializable
public enum class ModuleType {

    /** Full Kotlin Multiplatform module - has commonMain + platform source sets. */
    KMP,

    /**
     * AGP 9.x hybrid - applies both the Android library and KMP plugins.
     * Plugin ID: `com.android.kotlin.multiplatform.library`
     */
    KMP_ANDROID_LIBRARY,

    /**
     * Android application module.
     * AGP 9.x has built-in Kotlin support - no separate Kotlin plugin needed.
     */
    ANDROID_APP,

    /** Standard Android library module. */
    ANDROID_LIBRARY,

    /** Pure JVM / Kotlin library. No Android framework dependency. */
    JVM_LIBRARY,

    /** Module type could not be inferred from applied plugins. */
    UNKNOWN;

    /**
     * Readable label shown in the HTML report legend.
     */
    public val label: String
        get() = when (this) {
            KMP -> "KMP"
            KMP_ANDROID_LIBRARY -> "KMP Android Lib"
            ANDROID_APP -> "Android App"
            ANDROID_LIBRARY -> "Android Lib"
            JVM_LIBRARY -> "JVM Lib"
            UNKNOWN -> "Unknown"
        }

    /**
     * Hex color used in the D3.js force-directed graph visualization.
     */
    public val color: String
        get() = when (this) {
            KMP -> "#9B59B6"  // Purple
            KMP_ANDROID_LIBRARY -> "#1ABC9C"  // Teal
            ANDROID_APP -> "#3498DB"  // Blue
            ANDROID_LIBRARY -> "#2ECC71"  // Green
            JVM_LIBRARY -> "#27AE60"  // Dark green
            UNKNOWN -> "#95A5A6"  // Gray
        }
}