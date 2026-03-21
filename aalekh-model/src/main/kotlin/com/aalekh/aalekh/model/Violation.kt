package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * A single architecture rule violation produced by the rule engine.
 *
 * @param ruleId The stable rule identifier, e.g. `"layer-dependency"`.
 * @param severity Whether this violation fails the build or is advisory.
 * @param message The primary violation message. Must name the offending modules
 *   and include a concrete fix suggestion.
 * @param source Human-readable description of the edge or module that caused
 *   the violation, e.g. `":feature:login:data → :feature:login:ui"`.
 * @param moduleHint The primary module path to navigate to in the HTML graph
 *   view. Defaults to null; the report falls back to parsing [source] when absent.
 *   Rules should set this to the `from` module of the offending dependency.
 * @param plainLanguageExplanation Optional plain-language explanation shown
 *   in the violations panel below the technical message. Intended for
 *   developers unfamiliar with the rule. Provided by the rule implementation.
 */
@Serializable
public data class Violation(
    val ruleId: String,
    val severity: Severity,
    val message: String,
    val source: String,
    val moduleHint: String? = null,
    val plainLanguageExplanation: String? = null,
)