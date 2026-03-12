package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A directed dependency from one module to another.
 *
 * @param from          Source module path
 * @param to            Target module path
 * @param configuration Gradle configuration name: `"implementation"`, `"api"`, etc.
 * @param sourceSet     For KMP modules: the source set that owns this dep (`"commonMain"`, …)
 */
@Serializable
public data class DependencyEdge(
    val from: String,
    val to: String,
    val configuration: String,
    val sourceSet: String? = null,
) {
    /** Whether this dependency is part of the public API (leaks to consumers). */
    val isApi: Boolean get() = configuration == "api"

    /** Whether this is a test-only dependency (does not affect production builds). */
    val isTest: Boolean
        get() = configuration.contains("test", ignoreCase = true)

    /** Whether this is a compile-only dependency (not present at runtime). */
    val isCompileOnly: Boolean get() = configuration.contains("compileOnly", ignoreCase = true)
}
