package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A directed dependency from one module to another.
 *
 * Edges are extracted from Gradle configurations during the `aalekhExtract` task
 * and stored in [ModuleDependencyGraph.edges]. Each edge represents one
 * `implementation(project(...))` or equivalent declaration.
 *
 * @param from Source module Gradle path, e.g. `":feature:login:data"`.
 * @param to Target module Gradle path, e.g. `":core:domain"`.
 * @param configuration Gradle configuration name: `"implementation"`, `"api"`,
 *   `"testImplementation"`, `"commonMainImplementation"`, etc.
 * @param sourceSet For KMP modules: the source set that owns this dependency,
 *   e.g. `"commonMain"`, `"androidMain"`. Null for standard Android/JVM configurations.
 * @param declarationLine Approximate 1-based line number of this dependency declaration
 *   in the source module's build file. Populated by scanning the build file during
 *   extraction. Null when the file was not scanned or the line could not be located.
 *   Used in violation messages to point developers directly to the line to remove.
 */
@Serializable
public data class DependencyEdge(
    val from: String,
    val to: String,
    val configuration: String,
    val sourceSet: String? = null,
    val declarationLine: Int? = null,
) {
    /** True when this dependency is declared as `api` - it leaks to consumers of [from]. */
    val isApi: Boolean get() = configuration == "api"

    /** True when this is a test-only dependency that does not affect production builds. */
    val isTest: Boolean get() = configuration.contains("test", ignoreCase = true)

    /** True when this dependency is only available at compile time, not at runtime. */
    val isCompileOnly: Boolean get() = configuration.contains("compileOnly", ignoreCase = true)
}