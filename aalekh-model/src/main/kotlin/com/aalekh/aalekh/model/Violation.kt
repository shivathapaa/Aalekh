package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A single architecture rule violation found by the rule engine.
 *
 * @param ruleId    Identifier of the violated rule, e.g. `"layer-dependency"`
 * @param severity  Whether to fail the build
 * @param message   Explanation. Must include a concrete fix suggestion.
 * @param source    The module path or edge description that caused the violation
 */
@Serializable
public data class Violation(
    val ruleId: String,
    val severity: Severity,
    val message: String,
    val source: String,
)