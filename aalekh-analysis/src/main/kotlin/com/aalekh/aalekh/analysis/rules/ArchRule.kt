package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * A single architecture rule that can be evaluated against a [ModuleDependencyGraph].
 *
 * ## Implementing a custom rule
 * ```kotlin
 * class NoAndroidInDomainRule : ArchRule {
 *     override val id = "no-android-in-domain"
 *     override val description = "Domain modules must not depend on Android libraries"
 *     override val defaultSeverity = Severity.ERROR
 *
 *     override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
 *         graph.edges
 *             .filter { it.from.contains(":domain") }
 *             .filter { graph.moduleByPath(it.to)?.type == ModuleType.ANDROID_LIBRARY }
 *             .map { edge ->
 *                 Violation(
 *                     ruleId   = id,
 *                     severity = defaultSeverity,
 *                     message  = "${edge.from} depends on Android library ${edge.to}. " +
 *                                "Move Android-specific code to the data or presentation layer.",
 *                     source   = "${edge.from} -> ${edge.to}",
 *                 )
 *             }
 * }
 * ```
 */
public interface ArchRule {
    /** Stable identifier used in reports, baselines, and DSL references.*/
    public val id: String

    /** Description of what this rule enforces.*/
    public val description: String

    /** Default severity - can be overridden in the DSL.*/
    public val defaultSeverity: Severity

    /**
     * Evaluates this rule against the given graph.
     * Returns an empty list if no violations are found.
     * Must be a pure function - no I/O, no mutation, no Gradle API.
     */
    public fun evaluate(graph: ModuleDependencyGraph): List<Violation>
}

/**
 * Evaluates a set of [ArchRule]s against a graph and collects all violations.
 */
public class RuleEngine(private val rules: List<ArchRule>) {
    /** Runs all registered rules and returns every violation found.*/
    public fun evaluate(graph: ModuleDependencyGraph): RuleEngineResult {
        val violations = rules.flatMap { rule ->
            runCatching { rule.evaluate(graph) }
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
        }
        return RuleEngineResult(violations = violations, rulesEvaluated = rules.size)
    }

    public companion object {
        /** Builds a [RuleEngine] with all built-in rules enabled.*/
        public fun withBuiltinRules(): RuleEngine = RuleEngine(
            rules = listOf(
                NoCyclicDependenciesRule()
            )
        )

        /** Builds an empty [RuleEngine] - for testing or purely visualization use cases.*/
        public fun empty(): RuleEngine = RuleEngine(emptyList())
    }
}

/** The result of a full rule engine evaluation pass.*/
public data class RuleEngineResult(
    val violations: List<Violation>,
    val rulesEvaluated: Int,
) {
    val errorCount: Int get() = violations.count { it.severity == Severity.ERROR }
    val warningCount: Int get() = violations.count { it.severity == Severity.WARNING }
    val hasBuildFailure: Boolean get() = errorCount > 0
}
