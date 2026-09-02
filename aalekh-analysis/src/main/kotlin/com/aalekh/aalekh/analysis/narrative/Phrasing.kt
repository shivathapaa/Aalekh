package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.model.Evidence
import com.aalekh.aalekh.model.Provenance
import kotlin.math.roundToInt

/**
 * Shared sentence-building helpers for the narrative finders.
 *
 * Every finding is assembled from these rather than written ad hoc, so the whole narrative reads in
 * one voice and, more importantly, stays **deterministic**: the same graph always produces the same
 * bytes. Number formatting is pinned to [java.util.Locale.ROOT] because a decimal comma on one
 * developer's machine and a decimal point on another's would make identical projects produce
 * different reports.
 */
// One small function per phrasing concern - agreement, formatting, evidence tiers. The count is what
// keeps each finder's text short and consistent; folding them together would only make the call
// sites longer.
@Suppress("TooManyFunctions")
internal object Phrasing {

    private val locale = java.util.Locale.ROOT

    /**
     * `1 module` / `3 modules` - the plural agreement every finding needs. The default handles the
     * regular `-s` and the `consonant + y -> -ies` case (`library -> libraries`), so a caller only
     * passes an explicit plural for a genuine irregular.
     */
    fun count(n: Int, singular: String, plural: String = defaultPlural(singular)): String =
        "$n ${if (n == 1) singular else plural}"

    private fun defaultPlural(singular: String): String =
        if (singular.length > 1 && singular.endsWith("y") && singular[singular.length - 2] !in "aeiou") {
            singular.dropLast(1) + "ies"
        } else {
            singular + "s"
        }

    /** `is` / `are`, agreeing with [n]. */
    fun verb(n: Int): String = if (n == 1) "is" else "are"

    /**
     * A lexical verb agreeing with [n] - `agree(1, "depends", "depend")` is `"depends"`.
     *
     * Separate from [verb] because English inflects the two in opposite directions: one module *is*
     * and *depends*, several *are* and *depend*. Using [verb] for a lexical verb produces
     * "3 modules are depend on it", which is exactly the kind of error that makes generated prose
     * read as generated.
     */
    fun agree(n: Int, singular: String, plural: String): String = if (n == 1) singular else plural

    /** A percentage rounded to a whole number, e.g. `"37%"`. */
    fun percent(value: Double): String = "${value.roundToInt()}%"

    /** A share of [total] as a whole percentage, e.g. `share(24, 44)` is `"55%"`. */
    fun share(part: Int, total: Int): String =
        if (total == 0) "0%" else percent(part * PERCENT / total)

    /** A ratio to two decimals, e.g. `"0.83"`. */
    fun ratio(value: Double): String = String.format(locale, "%.2f", value)

    /** A multiplier to one decimal, e.g. `"3.4x"` rendered as `"3.4×"`. */
    fun multiplier(value: Double): String = String.format(locale, "%.1f×", value)

    /**
     * A readable list: `":a"`, `":a and :b"`, `":a, :b and :c"`, `":a, :b, :c and 4 others"`.
     * Capped so a finding about 200 modules stays one sentence rather than a wall of paths.
     */
    fun list(items: List<String>, max: Int = MAX_LISTED): String = when {
        items.isEmpty() -> "none"
        items.size == 1 -> items[0]
        items.size <= max -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        else -> items.take(max).joinToString(", ") + " and ${items.size - max} others"
    }

    /** An observed fact - read straight from the build, a file, or git. */
    fun observed(label: String, value: String): Evidence = Evidence(label, value, Provenance.OBSERVED)

    /** A computed measurement - a deterministic function of the graph. */
    fun computed(label: String, value: String): Evidence = Evidence(label, value, Provenance.COMPUTED)

    /** An inferred classification - a heuristic that could be wrong. */
    fun inferred(label: String, value: String): Evidence = Evidence(label, value, Provenance.INFERRED)

    private const val PERCENT = 100.0
    private const val MAX_LISTED = 3
}
