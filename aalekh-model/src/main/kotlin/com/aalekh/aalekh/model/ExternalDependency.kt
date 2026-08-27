package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A declared external (third-party) dependency of a single module.
 *
 * Captured from Gradle configurations during the `aalekhExtract` task and stored in
 * [ModuleDependencyGraph.externalDependencies]. Each instance represents one
 * `implementation("group:name:version")` (or equivalent) declaration.
 *
 * These are the **declared** coordinates read from `configuration.dependencies` at configuration
 * time - never the resolved dependency graph. Reading resolved artifacts would trigger dependency
 * resolution and break the configuration cache, so it is deliberately avoided.
 *
 * @param module Gradle path of the module that declares this dependency, e.g. `":feature:login:ui"`.
 * @param group Maven group, e.g. `"androidx.core"`. Empty when the notation omits a group.
 * @param name Artifact name, e.g. `"core-ktx"`.
 * @param version Declared version, e.g. `"1.13.1"`. Null when the version is managed elsewhere
 *   (a BOM / platform, or a version-catalog alignment) and is not stated at the declaration site.
 * @param configuration Gradle configuration name the dependency was declared on: `"implementation"`,
 *   `"api"`, `"testImplementation"`, `"commonMainImplementation"`, etc. The declaration type is
 *   recoverable from this string via [isApi] / [isTest] / [isCompileOnly].
 * @param sourceSet For KMP modules: the source set that owns this dependency, e.g. `"commonMain"`,
 *   `"androidMain"`. Null for standard Android/JVM configurations.
 */
@Serializable
public data class ExternalDependency(
    val module: String,
    val group: String,
    val name: String,
    val version: String? = null,
    val configuration: String,
    val sourceSet: String? = null,
) {
    /**
     * Human-readable Maven coordinate `group:name:version`. The group segment is dropped when
     * [group] is blank, and an unstated [version] renders as `unspecified`.
     */
    val coordinates: String
        get() {
            val base = if (group.isBlank()) name else "$group:$name"
            return "$base:${version ?: "unspecified"}"
        }

    /**
     * True when this is a production `api` dependency - it leaks transitively onto every consumer of
     * [module]. Covers the plain `api` configuration and KMP / build-type source-set api
     * configurations (`commonMainApi`, `androidMainApi`, `debugApi`, ...). Test api configurations
     * (`testApi`) are excluded - they never affect the production surface.
     */
    val isApi: Boolean get() = !isTest && (configuration == "api" || configuration.endsWith("Api"))

    /** True when this is a test-only dependency that does not affect production builds. */
    val isTest: Boolean get() = configuration.contains("test", ignoreCase = true)

    /** True when this dependency is only available at compile time, not at runtime. */
    val isCompileOnly: Boolean get() = configuration.contains("compileOnly", ignoreCase = true)
}
