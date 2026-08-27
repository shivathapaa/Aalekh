package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.model.MainSequenceReport
import com.aalekh.aalekh.model.MainSequenceZone
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleMainSequence

/**
 * Computes each module's position relative to Robert Martin's *main sequence* - the line `A + I = 1`.
 *
 * - **Instability (I)** = `Ce / (Ca + Ce)`, taken from the dependency graph (production edges only).
 * - **Abstractness (A)** = `Na / (Na + Nc)`, the ratio of abstract to total type declarations, supplied
 *   by a coarse source scan (the graph alone cannot know it).
 * - **Distance (D)** = `|A + I - 1|`, how far the module sits from the ideal balance. `0` is on the
 *   line; `1` is a far corner.
 *
 * A concrete-and-stable module (low A, low I) is in the **zone of pain** - rigid and hard to change
 * yet widely depended upon. An abstract-and-unstable module (high A, high I) is in the **zone of
 * uselessness** - abstractions almost nothing uses. This is a pure function; the scan that produces
 * [TypeAbstractness] lives in the Gradle module.
 */
public object MainSequenceAnalyzer {

    /** A module is treated as on the main sequence when its distance is within this tolerance. */
    private const val MAIN_SEQUENCE_TOLERANCE = 0.25

    /** Coarse count of abstract vs concrete type declarations in one module. */
    public data class TypeAbstractness(val abstractTypes: Int, val concreteTypes: Int) {
        val totalTypes: Int get() = abstractTypes + concreteTypes

        /** Merges two counts - lets a scanner accumulate over many source files. */
        public operator fun plus(other: TypeAbstractness): TypeAbstractness =
            TypeAbstractness(abstractTypes + other.abstractTypes, concreteTypes + other.concreteTypes)

        public companion object {
            public val ZERO: TypeAbstractness = TypeAbstractness(0, 0)
        }
    }

    private val commentPattern = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    // A type declaration head: optional Kotlin/Java modifiers followed by the declaring keyword. The
    // lookbehind rejects `::class` / `.class` references; the lookahead requires a name or opener next.
    private val declarationPattern = Regex(
        """(?<![A-Za-z0-9_.:])((?:sealed\s+|abstract\s+|data\s+|enum\s+|value\s+|annotation\s+|fun\s+)*)""" +
            """(class|interface|object|enum)(?=[\s(:<{])""",
    )

    /**
     * Counts abstract vs concrete type declarations in one file's [source] text with a deliberately
     * **coarse** lexical scan - it strips comments and matches declaration keywords, not a full parse.
     * An interface is abstract; a `class` is abstract when `abstract` or `sealed`; everything else
     * (concrete/`data`/`enum`/`value` classes, `object`s, Java `enum`s) is concrete. Pure, so the
     * regex edge cases are unit-tested here rather than through file I/O.
     */
    public fun countTypes(source: String): TypeAbstractness {
        var abstractTypes = 0
        var concreteTypes = 0
        val cleaned = commentPattern.replace(source, " ")
        declarationPattern.findAll(cleaned).forEach { match ->
            if (isAbstract(match.groupValues[1], match.groupValues[2])) abstractTypes++ else concreteTypes++
        }
        return TypeAbstractness(abstractTypes, concreteTypes)
    }

    private fun isAbstract(modifiers: String, keyword: String): Boolean = when (keyword) {
        "interface" -> true
        "class" -> "abstract" in modifiers || "sealed" in modifiers
        else -> false
    }

    /**
     * Builds the [MainSequenceReport] for [graph] given per-module type counts. Modules with no
     * counted types (missing from [abstractness] or with zero total) are skipped - abstractness is
     * undefined for them. Results are sorted worst-distance first.
     */
    public fun analyze(
        graph: ModuleDependencyGraph,
        abstractness: Map<String, TypeAbstractness>,
    ): MainSequenceReport {
        val positions = graph.modules.mapNotNull { module ->
            val counts = abstractness[module.path]?.takeIf { it.totalTypes > 0 } ?: return@mapNotNull null
            position(module.path, graph.instability(module.path), counts)
        }.sortedByDescending { it.distance }

        if (positions.isEmpty()) return MainSequenceReport.EMPTY
        return MainSequenceReport(
            modules = positions,
            averageDistance = positions.map { it.distance }.average(),
        )
    }

    private fun position(path: String, instability: Double, counts: TypeAbstractness): ModuleMainSequence {
        val abstractness = counts.abstractTypes.toDouble() / counts.totalTypes
        val distance = kotlin.math.abs(abstractness + instability - 1.0)
        return ModuleMainSequence(
            path = path,
            instability = instability,
            abstractness = abstractness,
            distance = distance,
            zone = zoneOf(abstractness, instability, distance),
            abstractTypes = counts.abstractTypes,
            concreteTypes = counts.concreteTypes,
        )
    }

    private fun zoneOf(abstractness: Double, instability: Double, distance: Double): MainSequenceZone = when {
        distance <= MAIN_SEQUENCE_TOLERANCE -> MainSequenceZone.MAIN_SEQUENCE
        // Below the line (A + I < 1): concrete and stable -> pain. Above it: abstract and unstable.
        abstractness + instability < 1.0 -> MainSequenceZone.ZONE_OF_PAIN
        else -> MainSequenceZone.ZONE_OF_USELESSNESS
    }
}
