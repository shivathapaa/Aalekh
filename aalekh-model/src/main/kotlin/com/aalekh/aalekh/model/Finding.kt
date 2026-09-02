package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * What a [Finding] is about - used to group the narrative into readable sections.
 */
@Serializable
public enum class FindingCategory {
    /** How the project is shaped: size, layering, entry points, foundation, regions. */
    STRUCTURE,

    /** Something that makes change expensive or dangerous: cycles, hubs, fragility. */
    RISK,

    /** Who owns what, and where ownership is missing or split. */
    OWNERSHIP,

    /** How the project has changed: growth, churn, drift, co-change. */
    EVOLUTION,

    /** The build itself: plugins, versions, toolchains, configuration consistency. */
    BUILD,

    /** Dependency relationships, internal and external. */
    DEPENDENCY;

    /** Readable section heading. */
    public val label: String
        get() = when (this) {
            STRUCTURE -> "Structure"
            RISK -> "Risk"
            OWNERSHIP -> "Ownership"
            EVOLUTION -> "Evolution"
            BUILD -> "Build"
            DEPENDENCY -> "Dependencies"
        }
}

/**
 * How much Aalekh trusts an [Provenance.INFERRED] or [Provenance.SUGGESTED] finding.
 *
 * Never attached to an observed fact or a computed metric - those are certain by construction, and
 * labelling them with a confidence would imply a doubt that does not exist.
 */
@Serializable
public enum class Confidence {
    /** The signal is strong and the heuristic rarely misfires on it. */
    HIGH,

    /** Plausible, but a reasonable project could look like this on purpose. */
    MEDIUM,

    /** A weak hint worth a look, not a conclusion. */
    LOW,
}

/**
 * One piece of the reasoning behind a [Finding] - the "how do you know?" a reader can check.
 *
 * @param label What was measured, e.g. `"Dependents"`.
 * @param value The measurement, already formatted for display, e.g. `"24 modules (55% of the project)"`.
 * @param howObtained Where the number came from. An evidence list mixing tiers is normal - a finding
 *   often combines an observed fact with a computed one - which is exactly why each item carries its
 *   own tier rather than the finding carrying one for all of them.
 */
@Serializable
public data class Evidence(
    val label: String,
    val value: String,
    val howObtained: Provenance,
)

/**
 * One human-readable statement about the project - the unit every narrative, recommendation, and
 * generated document is rendered from.
 *
 * Findings are assembled from templates, so the same graph always produces byte-identical text, which
 * is what makes them safe to commit, diff, and assert on in CI. A finding states one thing, names the
 * modules it concerns so the reader can navigate to them, and carries its evidence.
 *
 * @param id Stable kebab-case identifier, e.g. `"central-dependency"`. A public contract like
 *   `ArchRule.id`: it appears in exports and can be suppressed, so it must not change after release.
 * @param category Section this finding belongs to.
 * @param severity How much attention it deserves. `INFO` findings are descriptive - most of the
 *   narrative is `INFO` - while `WARNING` and `ERROR` mark things worth acting on. A finding never
 *   fails a build; that is what `ArchRule` is for.
 * @param title One short line, suitable as a heading.
 * @param detail One or two full sentences explaining the finding in plain language.
 * @param evidence The measurements behind it, in the order they support the claim.
 * @param subjects Module paths this finding concerns, most relevant first, for navigation.
 * @param provenance The weakest tier among the finding's inputs - a claim is only as certain as its
 *   least certain ingredient.
 * @param confidence Set only when [provenance] is [Provenance.INFERRED] or [Provenance.SUGGESTED].
 * @param action What to do about it, when there is a defensible answer. Null for purely descriptive
 *   findings - inventing an action for every observation is how advice becomes noise.
 */
@Serializable
public data class Finding(
    val id: String,
    val category: FindingCategory,
    val severity: Severity,
    val title: String,
    val detail: String,
    val evidence: List<Evidence> = emptyList(),
    val subjects: List<String> = emptyList(),
    val provenance: Provenance = Provenance.COMPUTED,
    val confidence: Confidence? = null,
    val action: String? = null,
)

/**
 * The full set of findings for one project, in reading order.
 *
 * @param findings Every finding produced, ordered most-important-first within each category.
 * @param readingOrder The modules to understand first, in the order to read them, with a sentence
 *   each. Empty for a project too small for the order to mean anything.
 * @param summary A short paragraph describing the project as a whole - the first thing a reader who
 *   has never seen the codebase should read.
 */
@Serializable
public data class NarrativeReport(
    val findings: List<Finding> = emptyList(),
    val readingOrder: List<ReadingStep> = emptyList(),
    val summary: String = "",
) {
    public companion object {
        /** An empty narrative - an empty graph has nothing to say about itself. */
        public val EMPTY: NarrativeReport = NarrativeReport()
    }
}

/**
 * One step of the suggested reading order for a project a developer has never seen.
 *
 * @param module Module path to read.
 * @param reason Why this module, at this point in the order.
 * @param position 1-based step number.
 */
@Serializable
public data class ReadingStep(
    val position: Int,
    val module: String,
    val reason: String,
)
