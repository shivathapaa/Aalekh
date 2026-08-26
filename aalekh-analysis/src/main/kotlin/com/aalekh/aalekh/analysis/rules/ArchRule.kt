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
         *   `"ruleId:option:preventRegression"`, `"ruleId:threshold:N"`,
         *   `"custom:class:fully.qualified.ClassName"`.
         * @param previousCycleCount Main-code cycle count from the previous run's results JSON.
         *   Null when no prior results exist - regression check is skipped in that case.
         * @param forbidEntries Serialized `forbid { }` predicate rules. Format per entry:
         *   `"<fromKind>|<fromValue>|<toKind>|<toValue>|<severity>|<reason>"` where kind is
         *   `path` or `type`.
         */
        // Parameters are one cohesive set of serialized DSL channels reconstructed at execution
        // time; bundling them into a holder adds indirection at every call site without any gain.
        @Suppress("LongParameterList")
        public fun fromConfig(
            layerEntries: List<String>,
            featurePattern: String,
            featureAllowedPairs: List<String>,
            ruleEntries: List<String>,
            previousCycleCount: Int? = null,
            forbidEntries: List<String> = emptyList(),
        ): RuleEngine {
            val parsed = parseRuleEntries(ruleEntries)
            val rules = mutableListOf<ArchRule>()

            rules += parsed.customRules
            rules += NoCyclicDependenciesRule(
                previousCycleCount = if (parsed.preventCycleRegression) previousCycleCount else null,
                preventRegression = parsed.preventCycleRegression,
            )
            if (layerEntries.isNotEmpty()) {
                rules += LayerDependencyRule.fromSerializedLayers(layerEntries)
            }
            if (featurePattern.isNotBlank()) {
                rules += NoFeatureToFeatureDependencyRule(featurePattern, featureAllowedPairs)
            }
            parsed.maxTransitive?.let { rules += MaxTransitiveDependenciesRule(it) }
            parsed.maxGraphHeight?.let { rules += MaxGraphHeightRule(it) }
            if (parsed.forbidOrphans) rules += NoOrphanModulesRule()
            rules += forbidEntries.mapNotNull { parsePredicateRule(it) }

            return RuleEngine(
                rules = rules,
                severityOverrides = parsed.severityOverrides,
                suppressions = parsed.suppressions,
            )
        }

        /**
         * Rebuilds a [PredicateRule] from one serialized `forbid { }` entry, or null if it is
         * malformed. Format: `"<fromKind>|<fromValue>|<toKind>|<toValue>|<severity>|<reason>"`; the
         * reason is the final field and may itself contain no `|` (the DSL strips it).
         */
        private const val PREDICATE_FIELDS = 6
        private const val PREDICATE_MIN_FIELDS = 5
        private const val TO_VALUE_INDEX = 3
        private const val SEVERITY_INDEX = 4
        private const val REASON_INDEX = 5

        private fun parsePredicateRule(entry: String): ArchRule? {
            val parts = entry.split("|", limit = PREDICATE_FIELDS)
            if (parts.size < PREDICATE_MIN_FIELDS) return null
            val severity = Severity.entries.firstOrNull { it.name == parts[SEVERITY_INDEX] } ?: Severity.ERROR
            return PredicateRule(
                from = ModuleMatcher.fromSerialized(parts[0], parts[1]),
                to = ModuleMatcher.fromSerialized(parts[2], parts[TO_VALUE_INDEX]),
                reason = parts.getOrElse(REASON_INDEX) { "" },
                defaultSeverity = severity,
            )
        }

        /** Accumulated result of parsing the serialized `ruleEntries` strings. */
        private class ParsedRuleEntries {
            val severityOverrides = mutableMapOf<String, Severity>()
            val suppressions = mutableMapOf<String, MutableList<String>>()
            val customRules = mutableListOf<ArchRule>()
            var preventCycleRegression = false
            var maxTransitive: Int? = null
            var maxGraphHeight: Int? = null
            var forbidOrphans = false
        }

        /** Parses the delimited `ruleId:kind:value` entries into a [ParsedRuleEntries] accumulator. */
        private fun parseRuleEntries(ruleEntries: List<String>): ParsedRuleEntries {
            val parsed = ParsedRuleEntries()
            for (entry in ruleEntries) {
                val parts = entry.split(":")
                if (parts.size < 3) continue
                val ruleId = parts[0]
                when (parts[1]) {
                    "severity" -> Severity.entries.firstOrNull { it.name == parts[2] }
                        ?.let { parsed.severityOverrides[ruleId] = it }

                    "suppress" -> parsed.suppressions.getOrPut(ruleId) { mutableListOf() }
                        .add(parts.drop(2).joinToString(":"))

                    "option" -> applyOption(parsed, ruleId, parts[2])

                    "threshold" -> applyThreshold(parsed, ruleId, parts[2])

                    "class" -> if (ruleId == "custom") {
                        parsed.customRules += loadCustomRule(parts.drop(2).joinToString(":"))
                    }
                }
            }
            return parsed
        }

        private fun applyOption(parsed: ParsedRuleEntries, ruleId: String, value: String) {
            when {
                ruleId == "no-cyclic-dependencies" && value == "preventRegression" ->
                    parsed.preventCycleRegression = true

                ruleId == "no-orphan-modules" && value == "enabled" ->
                    parsed.forbidOrphans = true
            }
        }

        private fun applyThreshold(parsed: ParsedRuleEntries, ruleId: String, value: String) {
            when (ruleId) {
                "max-transitive-dependencies" -> parsed.maxTransitive = value.toIntOrNull()
                "max-graph-height" -> parsed.maxGraphHeight = value.toIntOrNull()
            }
        }

        /** Builds an engine with no rules - useful in tests. */
        public fun empty(): RuleEngine = RuleEngine(emptyList())

        /**
         * Reflectively instantiates a user-supplied [ArchRule] by fully qualified class name.
         * Failure to load or cast becomes a single always-firing rule so the user sees the
         * problem in the normal violations pipeline rather than as an opaque build crash.
         */
        private fun loadCustomRule(className: String): ArchRule = runCatching {
            val cls = Class.forName(className)
            val instance = cls.getDeclaredConstructor().newInstance()
            instance as? ArchRule
                ?: error("Class '$className' does not implement ${ArchRule::class.qualifiedName}")
        }.getOrElse { ex -> FailedToLoadCustomRule(className, ex.message ?: ex.javaClass.simpleName) }
    }
}

/**
 * Stand-in [ArchRule] returned by [RuleEngine.fromConfig] when a user-supplied custom rule
 * class cannot be loaded. Surfaces the load failure as a normal ERROR violation so the user
 * sees the cause in the report and CI logs rather than as a crash.
 */
internal class FailedToLoadCustomRule(
    private val className: String,
    private val cause: String,
) : ArchRule {
    override val id: String = "aalekh-custom-rule"
    override val description: String = "Custom rule failed to load."
    override val defaultSeverity: Severity = Severity.ERROR
    override val plainLanguageExplanation: String =
        "Aalekh tried to load a custom ArchRule class from the rules DSL but failed. " +
                "Verify the class is on the plugin or buildscript classpath, exposes a " +
                "no-argument public constructor, and implements " +
                "com.aalekh.aalekh.analysis.rules.ArchRule."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> = listOf(
        Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "Could not load custom rule '$className': $cause",
            source = className,
            moduleHint = null,
            plainLanguageExplanation = plainLanguageExplanation,
        )
    )
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