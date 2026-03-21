package com.aalekh.aalekh.gradle.dsl

import com.aalekh.aalekh.model.Severity
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import javax.inject.Inject

/**
 * Configures per-rule severity overrides and suppressions inside the `rules { }` block.
 *
 * ```kotlin
 * aalekh {
 *     rules {
 *         rule("layer-dependency") {
 *             severity = Severity.WARNING   // downgrade while migrating
 *             suppressFor(":legacy:**")     // exclude legacy modules entirely
 *         }
 *         rule("no-feature-to-feature") {
 *             severity = Severity.ERROR
 *         }
 *     }
 * }
 * ```
 */
public abstract class RulesConfig @Inject constructor(objects: ObjectFactory) {

    /** Serialized rule overrides: `"ruleId:SEVERITY"` or `"ruleId:suppress:pattern"`. */
    internal val entries: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /**
     * Configures a specific rule by its stable [id].
     *
     * Unknown rule IDs are silently ignored so adding overrides for a rule
     * before it is enabled does not cause a build error.
     */
    public fun rule(id: String, configure: RuleOverride.() -> Unit) {
        val override = RuleOverride(id)
        override.configure()
        override.serialize().forEach { entries.add(it) }
    }
}

/**
 * Mutable builder for a single rule override. Not part of the public API —
 * consumers interact through the lambda in [RulesConfig.rule].
 */
public class RuleOverride(private val id: String) {

    /** Override the default severity for this rule in this project. */
    public var severity: Severity? = null

    private val suppressPatterns = mutableListOf<String>()

    /**
     * Suppresses violations from modules matching [pattern] for this rule.
     * Supports `*` (one segment) and `**` (any segments) glob wildcards.
     *
     * Use this to exempt a known legacy subtree rather than disabling the rule entirely.
     */
    public fun suppressFor(pattern: String) {
        suppressPatterns += pattern
    }

    internal fun serialize(): List<String> {
        val result = mutableListOf<String>()
        severity?.let { result += "$id:severity:${it.name}" }
        suppressPatterns.forEach { result += "$id:suppress:$it" }
        return result
    }
}