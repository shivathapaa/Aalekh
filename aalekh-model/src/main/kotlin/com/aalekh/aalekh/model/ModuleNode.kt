package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A single Gradle subproject in the dependency graph.
 *
 * Module type is inferred from applied plugin IDs in priority order:
 * `org.jetbrains.kotlin.multiplatform` → KMP,
 * `com.android.kotlin.multiplatform.library` → KMP_ANDROID_LIBRARY,
 * `com.android.application` → ANDROID_APP,
 * `com.android.library` → ANDROID_LIBRARY,
 * `org.jetbrains.kotlin.jvm` → JVM_LIBRARY.
 * Modules that match none of the above receive type UNKNOWN.
 *
 * @param path Fully-qualified Gradle project path, e.g. `":feature:login:ui"`.
 * @param name Short module name - the last path segment, e.g. `"ui"`.
 * @param type Module type inferred from applied plugin IDs.
 * @param plugins All plugin class names applied to this module. Used for type detection.
 * @param tags Organisational tags inferred from the path segments, e.g.
 *   `setOf("feature", "login")` for `":feature:login:ui"`.
 * @param sourceSets KMP source set names when the module applies the multiplatform plugin,
 *   e.g. `setOf("commonMain", "androidMain", "iosMain")`. Empty for non-KMP modules.
 * @param buildFilePath Relative path to this module's build file from the project root,
 *   e.g. `"feature/login/ui/build.gradle.kts"`. Used in violation messages so developers
 *   know exactly which file to edit. Null when the path could not be resolved.
 * @param healthScore Architecture health score in [0, 100]. Computed by
 *   [HealthScoreCalculator] during extraction. Higher is healthier. Null for nodes
 *   deserialized from graph files produced by older plugin versions.
 */
@Serializable
public data class ModuleNode(
    val path: String,
    val name: String,
    val type: ModuleType,
    val plugins: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val sourceSets: Set<String> = emptySet(),
    val buildFilePath: String? = null,
    val healthScore: Int? = null,
) {
    /** The last path segment - short display name used in reports and violation messages. */
    val shortName: String get() = path.substringAfterLast(":")
}