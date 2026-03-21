package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A directed dependency from one module to another.
 *
 * @param from Source module path.
 * @param to Target module path.
 * @param configuration Gradle configuration name: `"implementation"`, `"api"`, etc.
 * @param sourceSet For KMP modules: the source set that owns this dependency.
 * @param declarationLine Approximate 1-based line number of this dependency declaration
 *   in the source module's build file. Null when the build file was not scanned or the
 *   declaration could not be located. Used in violation messages to point developers
 *   directly to the line that needs to be removed.
 */
@Serializable
public data class DependencyEdge(
    val from: String,
    val to: String,
    val configuration: String,
    val sourceSet: String? = null,
    val declarationLine: Int? = null,
) {
    val isApi: Boolean get() = configuration == "api"

    val isTest: Boolean get() = configuration.contains("test", ignoreCase = true)

    val isCompileOnly: Boolean get() = configuration.contains("compileOnly", ignoreCase = true)
}