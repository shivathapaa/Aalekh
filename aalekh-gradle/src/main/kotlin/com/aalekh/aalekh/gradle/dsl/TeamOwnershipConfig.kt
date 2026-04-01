package com.aalekh.aalekh.gradle.dsl

import org.gradle.api.tasks.Input

/**
 * DSL for mapping team names to module path glob patterns.
 * Configured via:
 * ```kotlin
 * aalekh {
 *     teams {
 *         team("auth-team") { modules(":feature:login:**", ":core:auth") }
 *         team("data-team") { modules(":data:**") }
 *     }
 * }
 * ```
 * Team assignments appear in the HTML report as a color overlay and
 * cross-team dependency violations are annotated separately.
 */
class TeamOwnershipConfig {

    /** Map of team name to list of module path glob patterns. */
    @get:Input
    val teams: MutableMap<String, List<String>> = mutableMapOf()

    /**
     * Declares a team and configures which module paths belong to it.
     * Module paths support `*` (single segment) and `**` (multi-segment) globs.
     */
    fun team(name: String, configure: TeamConfig.() -> Unit) {
        val cfg = TeamConfig()
        cfg.configure()
        teams[name] = cfg.patterns
    }

    /** Serialises team map for use as a Gradle task @Input string. */
    fun toInputString(): String =
        teams.entries.joinToString(";") { (name, patterns) ->
            "${name}=${patterns.joinToString(",")}"
        }
}

/** Configuration block for a single team inside [TeamOwnershipConfig]. */
class TeamConfig {
    val patterns: MutableList<String> = mutableListOf()

    /** Registers one or more module path glob patterns for this team. */
    fun modules(vararg globs: String) {
        patterns.addAll(globs)
    }
}
