package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * A single architecture rule evaluated against a [ModuleDependencyGraph].
 *
 * Implement this interface to create project-specific rules:
 * ```kotlin
 * class NoAndroidInDomainRule : ArchRule {
 *     override val id = "no-android-in-domain"
 *     override val description = "Domain modules must not depend on Android libraries"
 *     override val defaultSeverity = Severity.ERROR
 *     override val plainLanguageExplanation =
 *         "The domain layer must stay platform-agnostic so it can be shared via KMP."
 *
 *     override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
 *         graph.edges
 *             .filter { it.from.contains(":domain") }
 *             .filter { graph.moduleByPath(it.to)?.type == ModuleType.ANDROID_LIBRARY }
 *             .map { edge ->
 *                 Violation(
 *                     ruleId = id,
 *                     severity = defaultSeverity,
 *                     message = "${edge.from} depends on Android module ${edge.to}.",
 *                     source = "${edge.from} → ${edge.to}",
 *                     moduleHint = edge.from,
 *                     plainLanguageExplanation = plainLanguageExplanation,
 *                 )
 *             }
 * }
 * ```
 */
public interface ArchRule {
    public val id: String
    public val description: String
    public val defaultSeverity: Severity
    public val plainLanguageExplanation: String
    public fun evaluate(graph: ModuleDependencyGraph): List<Violation>
}

/**
 * Evaluates a set of [ArchRule]s and applies severity overrides and suppressions.
 *
 * @param rules The rules to run.
 * @param severityOverrides Per-rule severity replacements. Overrides never affect INFO violations.
 * @param suppressions Per-rule module glob patterns. Violations whose [Violation.moduleHint]
 *   matches any pattern are dropped.
 */
public class RuleEngine(
    private val rules: List<ArchRule>,
    private val severityOverrides: Map<String, Severity> = emptyMap(),
    private val suppressions: Map<String, List<String>> = emptyMap(),
) {
    public fun evaluate(graph: ModuleDependencyGraph): RuleEngineResult {
        val violations = rules.flatMap { rule ->
            val raw = runCatching { rule.evaluate(graph) }
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

            raw
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

        /** Builds an engine with only the built-in cycle detection rule. */
        public fun withBuiltinRules(): RuleEngine = RuleEngine(
            rules = listOf(NoCyclicDependenciesRule())
        )

        /**
         * Builds a fully configured [RuleEngine] from the serialized DSL inputs passed
         * through Gradle task properties. All parameters are plain strings for
         * configuration-cache safety - no live Gradle objects.
         *
         * @param layerEntries Serialized layer declarations. Format per entry:
         *   `"layerName|pat1,pat2|allowedLayer1,allowedLayer2|hasRestriction"`.
         * @param featurePattern Glob pattern identifying feature modules, e.g. `":feature:**"`.
         *   Empty string disables the feature isolation rule.
         * @param featureAllowedPairs Serialized allow-pairs. Format: `"fromPattern->toPattern"`.
         * @param ruleEntries Serialized rule overrides. Formats:
         *   `"ruleId:severity:LEVEL"`, `"ruleId:suppress:pattern"`,
         *   `"ruleId:option:preventRegression"`, `"ruleId:threshold:N"`.
         * @param previousCycleCount Main-code cycle count from the previous run's results JSON.
         *   Null when no prior results exist - regression check is skipped in that case.
         */
        public fun fromConfig(
            layerEntries: List<String>,
            featurePattern: String,
            featureAllowedPairs: List<String>,
            ruleEntries: List<String>,
            previousCycleCount: Int? = null,
        ): RuleEngine {
            val rules = mutableListOf<ArchRule>()

            val severityOverrides = mutableMapOf<String, Severity>()
            val suppressions = mutableMapOf<String, MutableList<String>>()
            var preventCycleRegression = false
            var maxTransitive: Int? = null

            for (entry in ruleEntries) {
                val parts = entry.split(":")
                if (parts.size < 3) continue
                val ruleId = parts[0]
                when (parts[1]) {
                    "severity" -> severityOverrides[ruleId] =
                        Severity.entries.firstOrNull { it.name == parts[2] } ?: continue

                    "suppress" -> suppressions.getOrPut(ruleId) { mutableListOf() }
                        .add(parts.drop(2).joinToString(":"))

                    "option" -> if (ruleId == "no-cyclic-dependencies" && parts[2] == "preventRegression") {
                        preventCycleRegression = true
                    }

                    "threshold" -> if (ruleId == "max-transitive-dependencies") {
                        maxTransitive = parts[2].toIntOrNull()
                    }
                }
            }

            rules += NoCyclicDependenciesRule(
                previousCycleCount = if (preventCycleRegression) previousCycleCount else null,
                preventRegression = preventCycleRegression,
            )

            if (layerEntries.isNotEmpty()) {
                rules += LayerDependencyRule.fromSerializedLayers(layerEntries)
            }

            if (featurePattern.isNotBlank()) {
                rules += NoFeatureToFeatureDependencyRule(
                    featurePattern = featurePattern,
                    allowedPairs = featureAllowedPairs,
                )
            }

            if (maxTransitive != null) {
                rules += MaxTransitiveDependenciesRule(maxTransitive)
            }

            return RuleEngine(
                rules = rules,
                severityOverrides = severityOverrides,
                suppressions = suppressions,
            )
        }

        /** Builds an engine with no rules - useful in tests. */
        public fun empty(): RuleEngine = RuleEngine(emptyList())
    }
}

/**
 * The result of a [RuleEngine.evaluate] call.
 *
 * @param violations All violations found, across all rules.
 * @param rulesEvaluated The number of rules that were run.
 */
public data class RuleEngineResult(
    val violations: List<Violation>,
    val rulesEvaluated: Int,
) {
    /** Number of ERROR-severity violations. */
    val errorCount: Int get() = violations.count { it.severity == Severity.ERROR }

    /** Number of WARNING-severity violations. */
    val warningCount: Int get() = violations.count { it.severity == Severity.WARNING }

    /** True when the build should fail - i.e. at least one ERROR-severity violation exists. */
    val hasBuildFailure: Boolean get() = errorCount > 0
}