package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A single Gradle subproject in the dependency graph.
 *
 * @param path Fully-qualified Gradle project path, e.g. `":feature:login:ui"`.
 * @param name Short module name (last path segment), e.g. `"ui"`.
 * @param type Module type inferred from applied plugin IDs.
 * @param plugins All plugin class names applied to this module.
 * @param tags Organizational tags inferred from the path, e.g. `setOf("feature", "login")`.
 * @param sourceSets KMP source set names when the module applies the multiplatform plugin.
 * @param buildFilePath Relative path to this module's build file from the project root,
 *   e.g. `"feature/login/ui/build.gradle.kts"`. Used in violation messages to tell
 *   developers exactly which file to edit. Null for modules where the path could not
 *   be resolved (e.g. modules discovered from serialized data without filesystem access).
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
) {
    /** The last path segment - short display name in reports. */
    val shortName: String get() = path.substringAfterLast(":")
}