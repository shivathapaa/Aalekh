package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * How Aalekh came to know a value - the accuracy contract attached to everything it reports.
 *
 * Aalekh mixes hard build facts with heuristics, and a reader cannot judge a claim without knowing
 * which they are looking at. Every classification, metric, and finding therefore carries one of these
 * tiers, and the report renders it. The ordering is strongest-evidence-first: a value derived from
 * more than one source takes the *weakest* tier of its inputs.
 *
 * The governing rule across the codebase: **a declared value always overrides an inferred one, and
 * the surface says which is in effect.** When only an inference is available, the surface names the
 * declaration that would replace it - for example "declare `layers { }` to make this exact".
 */
@Serializable
public enum class Provenance {

    /**
     * Read directly from the Gradle model, a build file, a version catalog, or git. Reproducible by
     * a developer opening the same file. Examples: dependency edges, configuration names, declared
     * external coordinates, KMP source-set names.
     */
    OBSERVED,

    /**
     * A deterministic function of [OBSERVED] data. Same inputs always give the same result, and the
     * formula is documented. Examples: fan-in, cycles, CCD, blast radius, health score.
     */
    COMPUTED,

    /**
     * A heuristic classification that could be wrong, always accompanied by the evidence behind it.
     * Examples: module type from applied plugin class names, architectural layer guessed from path
     * segments, tags derived from path prefixes.
     */
    INFERRED,

    /**
     * A recommendation rather than a statement of fact - an action Aalekh believes is worth
     * considering, with its rationale. Examples: the edge to cut to break a cycle, a module split or
     * merge candidate, a suggested `layers { }` block.
     */
    SUGGESTED,
}
