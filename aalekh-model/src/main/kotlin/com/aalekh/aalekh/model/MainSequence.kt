package com.aalekh.aalekh.model

import kotlinx.serialization.Serializable

/**
 * Where a module sits relative to Robert Martin's *main sequence* - the line `A + I = 1` that
 * balances abstractness against instability.
 */
public enum class MainSequenceZone {
    /** Close to the main sequence: a healthy balance of abstractness and instability. */
    MAIN_SEQUENCE,

    /** Concrete **and** stable (below the line): rigid, hard to change, heavily depended upon. */
    ZONE_OF_PAIN,

    /** Abstract **and** unstable (above the line): abstractions almost nothing depends on. */
    ZONE_OF_USELESSNESS,
}

/**
 * The main-sequence position of one module.
 *
 * @param path Module Gradle path.
 * @param instability Efferent coupling ratio `Ce / (Ca + Ce)` - 0 stable, 1 unstable. From the graph.
 * @param abstractness Ratio of abstract to total types `Na / Nc` - 0 all concrete, 1 all abstract.
 *   From a coarse source scan.
 * @param distance Distance from the main sequence `|A + I - 1|` - 0 is ideal, 1 is worst.
 * @param zone Which region the module falls in.
 * @param abstractTypes Abstract type declarations counted (interfaces, abstract/sealed classes).
 * @param concreteTypes Concrete type declarations counted (classes, objects, enums).
 */
@Serializable
public data class ModuleMainSequence(
    val path: String,
    val instability: Double,
    val abstractness: Double,
    val distance: Double,
    val zone: MainSequenceZone,
    val abstractTypes: Int,
    val concreteTypes: Int,
)

/**
 * The main-sequence report for a whole project.
 *
 * @param modules One entry per analysed module (those with at least one counted type), worst
 *   distance first.
 * @param averageDistance Mean distance across [modules] - a single "how far off the main sequence is
 *   this codebase?" number. 0 when there are no analysed modules.
 */
@Serializable
public data class MainSequenceReport(
    val modules: List<ModuleMainSequence>,
    val averageDistance: Double,
) {
    public companion object {
        /** An empty report - no module had any countable types (or the scan found nothing). */
        public val EMPTY: MainSequenceReport = MainSequenceReport(emptyList(), 0.0)
    }
}
