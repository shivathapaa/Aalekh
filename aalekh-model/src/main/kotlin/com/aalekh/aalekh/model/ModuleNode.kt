package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A single Gradle subproject in the dependency graph.
 *
 * @param path Fully-qualified Gradle project path, e.g. `":feature:login:ui"`.
 * @param name Short module name (last path segment).
 * @param type Module type inferred from applied plugin IDs.
 * @param plugins All plugin class names applied to this module.
 * @param tags Organizational tags inferred from the path, e.g. `setOf("feature", "login")`.
 * @param sourceSets KMP source set names when the module applies the multiplatform plugin.
 * @param buildFilePath Relative path to this module's build file from the project root.
 *   Null when the build file could not be resolved.
 * @param healthScore Architecture health score 0–100. Higher is healthier. Null when not
 *   yet computed (e.g. when the node was deserialized from an older plugin version).
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
    val shortName: String get() = path.substringAfterLast(":")
}