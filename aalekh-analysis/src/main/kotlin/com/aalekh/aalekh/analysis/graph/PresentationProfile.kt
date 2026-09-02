package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.ModuleDependencyGraph
import kotlinx.serialization.Serializable

/**
 * How big a project is, and therefore how it should be presented.
 *
 * A force-directed graph is the clearest possible picture of thirty modules and the least useful
 * picture of five hundred. The failure is not the layout algorithm - it is drawing five hundred of
 * anything at once. So the report changes *what it draws* with size rather than trying to make one
 * view survive every scale.
 *
 * The governing principle: **at every size, the landing view must be readable without interaction.**
 * A diagram that only becomes useful after zooming has already failed the reader who opened it to
 * find out what the project is.
 */
@Serializable
public enum class PresentationProfile {

    /** Small enough to draw every module and every edge at once. */
    DETAIL,

    /** Regions first, with a module-level graph one click away. */
    GROUPED,

    /** Regions and flows only; module-level views exist but are reached by search, not by scrolling. */
    SURVEY,

    /**
     * Too large for any global node-link view. Orientation comes from regions and search; the graph
     * is only ever drawn around a module the reader asked for.
     */
    ATLAS;

    /** The view the report should open on. */
    public val landingView: String
        get() = when (this) {
            DETAIL -> "graph"
            GROUPED, SURVEY, ATLAS -> "regions"
        }

    /** Whether a global module-level force graph is worth offering at all. */
    public val allowsGlobalGraph: Boolean get() = this != ATLAS

    /** One line explaining to the reader why they are seeing this view first. */
    public val rationale: String
        get() = when (this) {
            DETAIL -> "Small enough to show every module at once."
            GROUPED -> "Grouped into regions so the shape is readable; open a region for its modules."
            SURVEY -> "Too large to draw module by module - start from regions, then search for what " +
                    "you need."
            ATLAS -> "Far too large for a whole-project diagram. Start from regions or search; the " +
                    "graph is drawn around one module at a time."
        }

    public companion object {
        /**
         * The profile for a graph, from its module count.
         *
         * The boundaries are where each view stops being readable in practice rather than where it
         * stops rendering: a force layout is legible to roughly 60 modules, region maps hold up to a
         * few hundred, and past about 600 even an aggregated global view is too dense to land on.
         */
        public fun of(graph: ModuleDependencyGraph): PresentationProfile = when {
            graph.modules.size <= DETAIL_MAX -> DETAIL
            graph.modules.size <= GROUPED_MAX -> GROUPED
            graph.modules.size <= SURVEY_MAX -> SURVEY
            else -> ATLAS
        }

        private const val DETAIL_MAX = 60
        private const val GROUPED_MAX = 200
        private const val SURVEY_MAX = 600
    }
}
