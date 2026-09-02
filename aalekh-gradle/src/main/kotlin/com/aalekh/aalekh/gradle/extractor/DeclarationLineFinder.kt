package com.aalekh.aalekh.gradle.extractor

/**
 * Locates the line in a build file where a project dependency is declared.
 *
 * A violation that names a file *and a line* is a one-click fix; one that names only a module is a
 * search. This scanner supplies that line for `DependencyEdge.declarationLine`, which in turn lets
 * `CycleAdvisor` print `implementation(project(":b")) in a/build.gradle.kts:14`.
 *
 * It is deliberately a **textual scan, not a parse**. It recognises the three notations a Gradle build
 * file can name a project with:
 *
 * ```
 * implementation(project(":core:domain"))      // double-quoted, Kotlin DSL
 * implementation project(':core:domain')       // single-quoted, Groovy DSL
 * implementation(projects.core.domain)         // type-safe project accessor
 * ```
 *
 * The quoted forms match on the full quoted path, so `":core:data"` never matches
 * `":core:data-test"`. The accessor form checks the character after the match for the same reason.
 * A path the scanner cannot find yields null - an absent line number is always better than a wrong
 * one, since a wrong one sends a developer to the wrong place.
 */
internal object DeclarationLineFinder {

    /**
     * The 1-based line of the first declaration of [targetPath] in [lines], or null if absent.
     */
    fun lineOf(lines: List<String>, targetPath: String): Int? {
        val quoted = "\"$targetPath\""
        val singleQuoted = "':${targetPath.trimStart(':')}'"
        val accessor = accessorFor(targetPath)
        return lines
            .indexOfFirst { line ->
                line.contains(quoted) ||
                    line.contains(singleQuoted) ||
                    containsAccessor(line, accessor)
            }
            .takeIf { it >= 0 }
            ?.plus(1)
    }

    /**
     * The type-safe project accessor Gradle generates for a module path, e.g.
     * `":core:data-test"` → `"projects.core.dataTest"`. Each path segment is lower-camel-cased on
     * `-`, `_`, and `.` boundaries, matching Gradle's own accessor naming.
     */
    fun accessorFor(targetPath: String): String =
        targetPath.split(":")
            .filter { it.isNotBlank() }
            .joinToString(separator = ".", prefix = "projects.") { camelCase(it) }

    private fun camelCase(segment: String): String {
        val parts = segment.split('-', '_', '.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return segment
        return parts.first() + parts.drop(1).joinToString("") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }
    }

    /**
     * True when [line] references [accessor] as a whole accessor rather than as the prefix of a
     * longer one - `projects.core.data` must not match inside `projects.core.dataTest`.
     */
    private fun containsAccessor(line: String, accessor: String): Boolean {
        var from = line.indexOf(accessor)
        while (from >= 0) {
            val next = line.getOrNull(from + accessor.length)
            if (next == null || !isAccessorChar(next)) return true
            from = line.indexOf(accessor, from + 1)
        }
        return false
    }

    /** A character that can continue a type-safe accessor, and so rules out a whole-accessor match. */
    private fun isAccessorChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'
}
