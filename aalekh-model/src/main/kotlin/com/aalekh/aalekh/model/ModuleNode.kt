package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A single Gradle module (subproject) in the dependency graph.
 *
 * @param path      Fully-qualified Gradle project path, e.g. `":feature:login:ui"`
 * @param name      Short module name, e.g. `"ui"`
 * @param type      Module type inferred from applied plugin IDs
 * @param plugins   All plugin IDs applied to this module - used for type detection and future rules
 * @param tags      User-defined grouping tags, e.g. `setOf("feature", "login")`
 * @param sourceSets For KMP modules: discovered source set names (`commonMain`, `androidMain`, …)
 */
@Serializable
public data class ModuleNode(
    val path: String,
    val name: String,
    val type: ModuleType,
    val plugins: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val sourceSets: Set<String> = emptySet(),
) {
    /** The last path segment - useful as a short display name in reports. */
    val shortName: String get() = path.substringAfterLast(":")
}