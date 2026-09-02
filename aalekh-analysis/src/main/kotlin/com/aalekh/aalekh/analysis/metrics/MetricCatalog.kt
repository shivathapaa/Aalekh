package com.aalekh.aalekh.analysis.metrics

import com.aalekh.aalekh.model.Provenance

/**
 * How to read a metric's value - the boundary between "fine", "worth a look", and "act on this".
 *
 * @param fair Value at which the metric stops being unremarkable.
 * @param poor Value at which it warrants action.
 * @param higherIsWorse True when a larger number is a worse result. Governs which side of the
 *   thresholds counts as trouble, so a caller never has to hardcode the direction per metric.
 */
public data class MetricBands(
    val fair: Double,
    val poor: Double,
    val higherIsWorse: Boolean,
) {
    /** Where [value] falls: `"good"`, `"fair"`, or `"poor"`. */
    public fun classify(value: Double): String = when {
        higherIsWorse && value >= poor -> "poor"
        higherIsWorse && value >= fair -> "fair"
        !higherIsWorse && value <= poor -> "poor"
        !higherIsWorse && value <= fair -> "fair"
        else -> "good"
    }
}

/**
 * One metric's definition, in the words a developer needs to act on it.
 *
 * Every field is mandatory for a reason. A metric that cannot say what engineering question it
 * answers, or how to read its number, is a number without a purpose - and Aalekh has no shortage of
 * places to put numbers. Requiring [question], [interpretation], and [action] up front is what keeps
 * mathematically-interesting-but-useless metrics out of the report.
 *
 * @param id Stable kebab-case identifier, used as a column key and in exports.
 * @param name Display name.
 * @param unit Unit label (`"modules"`, `"%"`, `""` for a bare number).
 * @param formula How it is calculated, in one line.
 * @param question The engineering question it answers.
 * @param interpretation How to read the number.
 * @param action What a bad value suggests doing.
 * @param bands Thresholds, or null when the metric is descriptive and has no good or bad value.
 * @param provenance Whether the metric is observed from the build or computed from the graph.
 * @param perModule True when the metric is per-module; false when it describes the whole project.
 */
public data class MetricDefinition(
    val id: String,
    val name: String,
    val unit: String,
    val formula: String,
    val question: String,
    val interpretation: String,
    val action: String,
    val bands: MetricBands?,
    val provenance: Provenance,
    val perModule: Boolean,
)

/**
 * The single definition of every metric Aalekh reports.
 *
 * The report's KPI cards, the per-module table, the generated documentation, and the CSV header all
 * read from here, so a metric's explanation cannot drift between surfaces - and adding a metric means
 * answering "what engineering question does this answer?" before it can be displayed anywhere.
 */
public object MetricCatalog {

    /** Every metric, in a stable display order. */
    public val all: List<MetricDefinition> = listOf(
        MetricDefinition(
            id = "fan-in",
            name = "Fan-in",
            unit = "modules",
            formula = "Number of modules that directly depend on this one",
            question = "How many modules would notice if this one changed?",
            interpretation = "High fan-in means many consumers, so the module's API is hard to change. " +
                    "That is fine for a stable foundation module and a warning sign for a volatile one.",
            action = "If fan-in is high and the module changes often, stabilise its API or split it.",
            bands = null,
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "fan-out",
            name = "Fan-out",
            unit = "modules",
            formula = "Number of modules this one directly depends on",
            question = "How much does this module need in order to work?",
            interpretation = "High fan-out means the module is hard to build, test, or reuse in " +
                    "isolation, because so much has to exist first.",
            action = "Reduce by depending on fewer, more cohesive modules, or by inverting dependencies.",
            bands = MetricBands(fair = 8.0, poor = 15.0, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "instability",
            name = "Instability",
            unit = "",
            formula = "fanOut / (fanIn + fanOut)",
            question = "Is this module free to change, or does the project depend on it holding still?",
            interpretation = "0 means everything depends on it and it depends on nothing - it must " +
                    "stay stable. 1 means it depends on everything and nothing depends on it - it is " +
                    "free to change. Both extremes are legitimate; the problem is a stable module " +
                    "that keeps changing.",
            action = "Neither high nor low is wrong by itself. Compare it against how often the " +
                    "module actually changes.",
            bands = null,
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "blast-radius",
            name = "Blast radius",
            unit = "modules",
            formula = "Modules that transitively depend on this one",
            question = "If I make a breaking change here, how much of the project must be rebuilt, " +
                    "retested, and reviewed?",
            interpretation = "The real cost of changing a module. A blast radius above about a " +
                    "third of the project means almost every change is a whole-project change.",
            action = "For a large blast radius, invest in tests and a stable API before refactoring, " +
                    "or split the module so consumers depend only on the part they use.",
            bands = MetricBands(fair = 25.0, poor = 50.0, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "influence",
            name = "Influence",
            unit = "×",
            formula = "PageRank over the dependency graph, normalised so the mean module is 1.0",
            question = "Which modules is this project actually built on?",
            interpretation = "Unlike fan-in, influence weighs *who* depends on you: being used by " +
                    "the app counts for more than being used by a leaf. 3.0 means three times the " +
                    "pull of an average module.",
            action = "The highest-influence modules are the ones to understand first, review most " +
                    "carefully, and test most thoroughly.",
            bands = null,
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "betweenness",
            name = "Betweenness",
            unit = "",
            formula = "Share of shortest dependency paths that pass through this module",
            question = "Is this module a choke point the project's structure routes through?",
            interpretation = "High betweenness means the module sits between otherwise separate " +
                    "parts of the project. Changes there tend to ripple in both directions.",
            action = "A choke point with high churn is a prime candidate for splitting along the " +
                    "boundary it straddles.",
            bands = null,
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "comprehension-cost",
            name = "Comprehension cost",
            unit = "modules",
            formula = "Modules reachable by following this one's dependencies",
            question = "How much of the codebase must I read to understand this module?",
            interpretation = "The number of other modules whose behaviour can affect this one. " +
                    "A module you cannot understand without reading fifty others is expensive to own.",
            action = "Reduce by depending on narrower interfaces rather than whole subsystems.",
            bands = MetricBands(fair = 20.0, poor = 50.0, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "api-surface-ratio",
            name = "API surface",
            unit = "%",
            formula = "api edges / all production out-edges",
            question = "How much of what this module depends on does it re-export to its consumers?",
            interpretation = "Every `api` dependency leaks onto everyone who uses this module, " +
                    "widening their compile classpath and their blast radius along with it.",
            action = "Convert `api` to `implementation` wherever the type does not appear in this " +
                    "module's own public signatures.",
            bands = MetricBands(fair = 50.0, poor = 80.0, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "depth-from-entry",
            name = "Depth",
            unit = "hops",
            formula = "Shortest hop count from the nearest entry point",
            question = "How far is this module from where execution starts?",
            interpretation = "Depth 0 is an entry point; deeper modules are further from user-facing " +
                    "behaviour and closer to the foundation. A natural reading order for newcomers.",
            action = "Nothing to fix - use it to navigate.",
            bands = null,
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "health-score",
            name = "Module health",
            unit = "/100",
            formula = "100 − instability(30) − god(25) − cycle(25) − transitive(20)",
            question = "Is this module in a healthy position in the graph?",
            interpretation = "A single composite for scanning a large table. Below 40 means several " +
                    "signals are bad at once.",
            action = "Read the individual metrics; the composite tells you where to look, not what to do.",
            bands = MetricBands(fair = 70.0, poor = 40.0, higherIsWorse = false),
            provenance = Provenance.COMPUTED,
            perModule = true,
        ),
        MetricDefinition(
            id = "ccd",
            name = "CCD",
            unit = "",
            formula = "Sum over every module of its dependency-set size",
            question = "How coupled is this project as a whole?",
            interpretation = "The headline Lakos coupling number. Meaningful compared against " +
                    "itself over time, not against other projects - it grows with module count.",
            action = "Track the trend. A rise faster than module count means coupling is growing.",
            bands = null,
            provenance = Provenance.COMPUTED,
            perModule = false,
        ),
        MetricDefinition(
            id = "nccd",
            name = "NCCD",
            unit = "×",
            formula = "CCD divided by the CCD of a balanced binary tree of the same size",
            question = "Is this project more tangled than a well-structured one of its size?",
            interpretation = "Around 1.0 is tree-like and healthy. Above 1.0 is more coupled than a " +
                    "balanced tree; below 1.0 means more independent modules than a tree would have.",
            action = "Above about 1.5, look for the modules with the largest dependency sets.",
            bands = MetricBands(fair = 1.3, poor = 1.8, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = false,
        ),
        MetricDefinition(
            id = "tangle",
            name = "Tangled",
            unit = "%",
            formula = "Share of modules sitting inside a dependency cycle",
            question = "How much of the project cannot be built or reasoned about independently?",
            interpretation = "0% for a clean DAG. Anything above 0 means some modules can only be " +
                    "compiled, tested, and understood together.",
            action = "Break the cycles; `aalekhCheck` names the exact edge to cut.",
            bands = MetricBands(fair = 0.1, poor = 10.0, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = false,
        ),
        MetricDefinition(
            id = "fan-in-gini",
            name = "Dependency concentration",
            unit = "",
            formula = "Gini coefficient of fan-in across all modules",
            question = "Is dependency spread across the project, or absorbed by a few modules?",
            interpretation = "0 means every module is depended on equally. Approaching 1 means a " +
                    "handful of modules absorb nearly all dependency - a small core the whole " +
                    "project routes through.",
            action = "High concentration is not wrong, but it makes those few modules the " +
                    "bottleneck for every change. Make sure they are the ones you meant.",
            bands = MetricBands(fair = 0.6, poor = 0.8, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = false,
        ),
        MetricDefinition(
            id = "critical-path",
            name = "Critical path",
            unit = "modules",
            formula = "Longest chain of production dependencies",
            question = "What limits how much of this project can build in parallel?",
            interpretation = "No amount of CPU can build this chain faster than one module at a " +
                    "time, so it sets the floor on a clean build.",
            action = "Shorten by flattening long chains - depend on the foundation directly rather " +
                    "than relaying through intermediate modules.",
            bands = MetricBands(fair = 8.0, poor = 15.0, higherIsWorse = true),
            provenance = Provenance.COMPUTED,
            perModule = false,
        ),
        MetricDefinition(
            id = "churn",
            name = "Churn",
            unit = "commits",
            formula = "Commits in the analysed window that touched this module",
            question = "Which modules keep changing?",
            interpretation = "A change hotspot. Combined with a large blast radius it marks the " +
                    "riskiest module in the project: it changes often and drags everything with it.",
            action = "Harden the tests around high-churn, high-blast-radius modules first.",
            bands = null,
            provenance = Provenance.OBSERVED,
            perModule = true,
        ),
    )

    private val byId: Map<String, MetricDefinition> = all.associateBy { it.id }

    /** The definition for a metric id, or null when the id is unknown. */
    public fun find(id: String): MetricDefinition? = byId[id]

    /** Every per-module metric, in display order. */
    public val perModule: List<MetricDefinition> = all.filter { it.perModule }

    /** Every whole-project metric, in display order. */
    public val projectWide: List<MetricDefinition> = all.filterNot { it.perModule }
}
