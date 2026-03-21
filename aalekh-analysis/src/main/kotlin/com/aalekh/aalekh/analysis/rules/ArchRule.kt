package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * A single architecture rule evaluated against a [ModuleDependencyGraph].
 *
 * Implement this interface to create custom rules:
 * ```kotlin
 * class NoAndroidInDomainRule : ArchRule {
 *     override val id = "no-android-in-domain"
 *     override val description = "Domain modules must not depend on Android libraries"
 *     override val defaultSeverity = Severity.ERROR
 *     override val plainLanguageExplanation =
 *         "The domain layer must remain platform-agnostic so it can be shared via KMP."
 *
 *     override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
 *         graph.edges
 *             .filter { it.from.contains(":domain") }
 *             .filter { graph.moduleByPath(it.to)?.type == ModuleType.ANDROID_LIBRARY }
 *             .map { edge ->
 *                 Violation(
 *                     ruleId = id,
 *                     severity = defaultSeverity,
 *                     message = "${edge.from} depends on Android module ${edge.to}. " +
 *                               "Move Android-specific code to the data or presentation layer.",
 *                     source = "${edge.from} → ${edge.to}",
 *                     moduleHint = edge.from,
 *                     plainLanguageExplanation = plainLanguageExplanation,
 *                 )
 *             }
 * }
 * ```
 */
public interface ArchRule {
    /** Stable identifier used in reports, baselines, and DSL rule overrides. */
    public val id: String

    /** One-line description of what this rule enforces. */
    public val description: String

    /** Default severity. Can be overridden per-project via the `rules { }` DSL block. */
    public val defaultSeverity: Severity

    /**
     * Plain-language explanation shown in the violations panel below the technical message.
     * Keep it to 1–2 sentences. Focus on *why* the rule exists, not *what* it detected.
     */
    public val plainLanguageExplanation: String

    /**
     * Evaluates this rule against [graph]. Returns an empty list when no violations are found.
     * Must be a pure function — no I/O, no mutation, no Gradle API calls.
     */
    public fun evaluate(graph: ModuleDependencyGraph): List<Violation>
}

/**
 * Evaluates a set of [ArchRule]s against a graph and collects all violations.
 *
 * @param rules The rules to evaluate.
 * @param severityOverrides Per-rule severity overrides. Key is the rule [ArchRule.id],
 *   value is the overriding [Severity]. Overrides replace [ArchRule.defaultSeverity].
 * @param suppressions Per-rule module suppression patterns. Key is the rule id,
 *   value is a list of glob patterns. Violations whose [Violation.moduleHint] or
 *   [Violation.source] matches any pattern are dropped silently.
 */
public class RuleEngine(
    private val rules: List<ArchRule>,
    private val severityOverrides: Map<String, Severity> = emptyMap(),
    private val suppressions: Map<String, List<String>> = emptyMap(),
) {
    /** Runs all registered rules and returns every violation after applying overrides and suppressions. */
    public fun evaluate(graph: ModuleDependencyGraph): RuleEngineResult {
        val violations = rules.flatMap { rule ->
            val rawViolations = runCatching { rule.evaluate(graph) }
                .getOrElse { ex ->
                    listOf(
                        Violation(
                            ruleId = rule.id,
                            severity = Severity.ERROR,
                            message = "Rule '${rule.id}' threw an exception: ${ex.message}. " +
                                    "This is a bug in the rule implementation.",
                            source = "RuleEngine",
                        )
                    )
                }

            val effectiveSeverity = severityOverrides[rule.id]
            val suppressPatterns = suppressions[rule.id] ?: emptyList()

            rawViolations
                .filterNot { v -> isSuppressed(v, suppressPatterns) }
                .map { v ->
                    if (effectiveSeverity != null && v.severity != Severity.INFO) {
                        v.copy(severity = effectiveSeverity)
                    } else v
                }
        }

        return RuleEngineResult(violations = violations, rulesEvaluated = rules.size)
    }

    private fun isSuppressed(violation: Violation, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        val candidate = violation.moduleHint ?: violation.source.split(" ").first()
        return GlobMatcher.matchesAny(patterns, candidate)
    }

    public companion object {
        /** Builds a [RuleEngine] with all built-in rules and no overrides. */
        public fun withBuiltinRules(): RuleEngine = RuleEngine(
            rules = listOf(NoCyclicDependenciesRule())
        )

        /**
         * Builds a fully configured [RuleEngine] from the serialized DSL inputs
         * passed through task properties. Called by [AalekhCheckTask].
         *
         * @param layerEntries Serialized layer configs: `"name|pat1,pat2|allowed1,allowed2|hasRestriction"`.
         * @param featurePattern Glob pattern for feature modules. Empty string disables the rule.
         * @param featureAllowedPairs Serialized allow-pairs: `"from->to"`.
         * @param ruleEntries Serialized rule overrides from [RulesConfig].
         */
        public fun fromConfig(
            layerEntries: List<String>,
            featurePattern: String,
            featureAllowedPairs: List<String>,
            ruleEntries: List<String>,
        ): RuleEngine {
            val rules = mutableListOf<ArchRule>()
            rules += NoCyclicDependenciesRule()

            if (layerEntries.isNotEmpty()) {
                rules += LayerDependencyRule.fromSerializedLayers(layerEntries)
            }

            if (featurePattern.isNotBlank()) {
                rules += NoFeatureToFeatureDependencyRule(
                    featurePattern = featurePattern,
                    allowedPairs = featureAllowedPairs,
                )
            }

            val severityOverrides = mutableMapOf<String, Severity>()
            val suppressions = mutableMapOf<String, MutableList<String>>()

            for (entry in ruleEntries) {
                val parts = entry.split(":")
                if (parts.size < 3) continue
                val ruleId = parts[0]
                when (parts[1]) {
                    "severity" -> severityOverrides[ruleId] =
                        Severity.entries.firstOrNull { it.name == parts[2] } ?: continue

                    "suppress" -> suppressions.getOrPut(ruleId) { mutableListOf() }
                        .add(parts.drop(2).joinToString(":")) // re-join in case pattern had ":"
                }
            }

            return RuleEngine(
                rules = rules,
                severityOverrides = severityOverrides,
                suppressions = suppressions,
            )
        }

        /** Builds an empty [RuleEngine] — for testing or pure visualization. */
        public fun empty(): RuleEngine = RuleEngine(emptyList())
    }
}

/** The result of a full rule engine evaluation pass. */
public data class RuleEngineResult(
    val violations: List<Violation>,
    val rulesEvaluated: Int,
) {
    val errorCount: Int get() = violations.count { it.severity == Severity.ERROR }
    val warningCount: Int get() = violations.count { it.severity == Severity.WARNING }
    val hasBuildFailure: Boolean get() = errorCount > 0
}