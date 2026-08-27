package com.aalekh.aalekh.gradle.dsl

import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity

/**
 * Builds one predicate rule inside a `forbid { }` block: "modules matching *from* must not depend on
 * modules matching *to*".
 *
 * ```kotlin
 * aalekh {
 *     forbid {
 *         from(":core:domain")
 *         toModuleType(ModuleType.ANDROID_LIBRARY)
 *         because("the domain layer stays platform-agnostic")
 *         severity(Severity.ERROR)   // default ERROR
 *     }
 *     forbid {
 *         from(":feature:**")
 *         to(":feature:**")
 *         because("features must not depend on each other")
 *     }
 * }
 * ```
 *
 * Each side is selected either by path glob (`from` / `to`) or by module type
 * (`fromModuleType` / `toModuleType`). The spec serializes to a plain, configuration-cache-safe
 * string; predicates are never captured as lambdas.
 */
public class ForbiddenDependencySpec {

    private var fromSelector: String? = null
    private var toSelector: String? = null
    private var reason: String = ""
    private var severity: Severity = Severity.ERROR
    private var apiOnly: Boolean = false

    /** Selects depending modules by Gradle path glob, e.g. `":feature:**"`. */
    public fun from(pattern: String) {
        fromSelector = "path|$pattern"
    }

    /** Selects depending modules by [ModuleType]. */
    public fun fromModuleType(type: ModuleType) {
        fromSelector = "type|${type.name}"
    }

    /** Selects forbidden dependency targets by Gradle path glob. */
    public fun to(pattern: String) {
        toSelector = "path|$pattern"
    }

    /** Selects forbidden dependency targets by [ModuleType]. */
    public fun toModuleType(type: ModuleType) {
        toSelector = "type|${type.name}"
    }

    /** Explains why the dependency is disallowed; shown in the violation message. */
    public fun because(text: String) {
        reason = text
    }

    /** Severity for a match. `ERROR` (default) fails the build; `WARNING` only reports. */
    public fun severity(level: Severity) {
        severity = level
    }

    /**
     * Restricts the rule to `api` dependencies only - an "API-leak" rule. The *from* module may still
     * depend on *to* via `implementation`; it just may not re-export it onto its own consumers with
     * `api`. Use it to stop a module widening its public surface, e.g.
     * `forbid { from(":core:public"); to(":core:internal"); apiOnly() }`.
     */
    public fun apiOnly() {
        apiOnly = true
    }

    /**
     * Serializes to `"<fromKind>|<fromValue>|<toKind>|<toValue>|<severity>|<reason>|<apiOnly>"`. The
     * reason is flattened to a single line with no `|` so it stays parseable; `apiOnly` is the final
     * boolean field.
     */
    internal fun serialize(): String {
        val from = requireNotNull(fromSelector) {
            "forbid { } requires a from(...) or fromModuleType(...) selector"
        }
        val to = requireNotNull(toSelector) {
            "forbid { } requires a to(...) or toModuleType(...) selector"
        }
        val safeReason = reason.replace('|', '/').replace('\n', ' ').trim()
        return "$from|$to|${severity.name}|$safeReason|$apiOnly"
    }
}
