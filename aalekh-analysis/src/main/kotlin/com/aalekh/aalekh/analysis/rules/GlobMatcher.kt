package com.aalekh.aalekh.analysis.rules

/**
 * Matches Gradle module paths against glob patterns.
 *
 * Supported wildcards:
 * - `*`  matches exactly one path segment (e.g. `:feature:*:domain`)
 * - `**` matches any number of path segments (e.g. `:feature:**`)
 *
 * Examples:
 * ```
 * matches(":feature:*:domain", ":feature:login:domain")  // true
 * matches(":feature:*:domain", ":feature:login:data")    // false
 * matches(":feature:**",       ":feature:login:ui")      // true
 * matches(":feature:**",       ":feature:login:data:remote") // true
 * matches(":core:*",           ":core:domain")           // true
 * matches(":core:*",           ":core:domain:model")     // false — * is one segment only
 * ```
 */
internal object GlobMatcher {

    fun matches(pattern: String, path: String): Boolean {
        val patternSegments = pattern.split(":").filter { it.isNotBlank() }
        val pathSegments = path.split(":").filter { it.isNotBlank() }
        return matchSegments(patternSegments, 0, pathSegments, 0)
    }

    /** Returns true if any pattern in [patterns] matches [path]. */
    fun matchesAny(patterns: List<String>, path: String): Boolean =
        patterns.any { matches(it, path) }

    private fun matchSegments(
        pattern: List<String>,
        pi: Int,
        path: List<String>,
        si: Int,
    ): Boolean {
        if (pi == pattern.size && si == path.size) return true
        if (pi == pattern.size) return false

        val seg = pattern[pi]

        if (seg == "**") {
            // ** consumes zero or more path segments
            for (skip in si..path.size) {
                if (matchSegments(pattern, pi + 1, path, skip)) return true
            }
            return false
        }

        if (si == path.size) return false

        return if (seg == "*") {
            // * consumes exactly one path segment
            matchSegments(pattern, pi + 1, path, si + 1)
        } else {
            seg == path[si] && matchSegments(pattern, pi + 1, path, si + 1)
        }
    }
}