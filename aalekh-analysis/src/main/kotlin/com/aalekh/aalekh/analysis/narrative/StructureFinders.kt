package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.analysis.metrics.ModuleGraphMetrics
import com.aalekh.aalekh.model.Confidence
import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.Provenance
import com.aalekh.aalekh.model.Severity

/**
 * Findings that describe **how the project is put together** - its size, shape, where execution
 * starts, and what it rests on.
 *
 * These are the first things a reader who has never seen the codebase needs, and almost all of them
 * are `INFO`: describing a project is not the same as criticising it.
 */
internal object StructureFinders {

    /** Fewer modules than this and the shape classification says nothing useful. */
    private const val MIN_MODULES_FOR_SHAPE = 5

    /** Above this share of modules in one path prefix, the project is dominated by that area. */
    private const val DOMINANT_PREFIX_SHARE = 50.0

    /** Fan-in concentration above this means a small core absorbs most of the project's dependency. */
    private const val CONCENTRATED_GINI = 0.6

    /** Entry points listed before the finding switches to counting instead of naming. */
    private const val MAX_NAMED_ENTRY_POINTS = 5

    fun findAll(context: NarrativeContext): List<Finding> = listOfNotNull(
        projectShape(context),
        entryPoints(context),
        foundation(context),
        concentration(context),
        depth(context),
    )

    /**
     * Classifies the overall shape from the module-path vocabulary and the graph's proportions.
     *
     * Deliberately marked `INFERRED`: "this looks like a layered project" is a reading of naming
     * conventions, not a fact the build states. Declared layers upgrade the same claim to observed.
     */
    private fun projectShape(context: NarrativeContext): Finding? {
        val total = context.graph.modules.size
        if (total < MIN_MODULES_FOR_SHAPE) return null

        val prefixes = context.graph.modules
            .mapNotNull { it.path.split(":").filter(String::isNotBlank).firstOrNull() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })

        val declared = context.layers.isNotEmpty()
        val topPrefix = prefixes.firstOrNull()
        val dominated = topPrefix != null && topPrefix.value * PERCENT / total >= DOMINANT_PREFIX_SHARE

        val shape = when {
            declared -> "layered"
            prefixes.size >= MIN_GROUPS_FOR_MODULAR && !dominated -> "feature-modular"
            dominated -> "dominated by one area"
            else -> "flat"
        }

        val detail = buildString {
            append("The project has ")
            append(Phrasing.count(total, "module"))
            append(" grouped under ")
            append(Phrasing.count(prefixes.size, "top-level path", "top-level paths"))
            append(" (")
            append(Phrasing.list(prefixes.take(MAX_NAMED_PREFIXES).map { ":${it.key} (${it.value})" }))
            append("). ")
            append(
                if (declared) {
                    "It declares ${Phrasing.count(context.layers.size, "architectural layer")}, " +
                            "so the intended structure is enforced rather than implied."
                } else {
                    "No layers are declared, so the structure below is read from module paths " +
                            "rather than from configuration."
                }
            )
        }

        return Finding(
            id = "project-shape",
            category = FindingCategory.STRUCTURE,
            severity = Severity.INFO,
            title = "This is a $shape project of ${Phrasing.count(total, "module")}",
            detail = detail,
            evidence = listOf(
                Phrasing.computed("Modules", total.toString()),
                Phrasing.computed("Dependencies", context.graph.edges.count { !it.isTest }.toString()),
                if (declared) {
                    Phrasing.observed("Declared layers", context.layers.joinToString(" → ") { it.name })
                } else {
                    Phrasing.inferred(
                        "Top-level paths",
                        prefixes.take(MAX_NAMED_PREFIXES).joinToString(", ") { ":${it.key}" },
                    )
                },
            ),
            provenance = if (declared) Provenance.COMPUTED else Provenance.INFERRED,
            confidence = if (declared) null else Confidence.MEDIUM,
        )
    }

    /** Where execution starts: modules nothing else depends on. */
    private fun entryPoints(context: NarrativeContext): Finding? {
        val entries = context.metrics.project.entryPoints
        if (entries.isEmpty()) return null

        val detail = when {
            entries.size == 1 ->
                "${entries.single()} is the only module nothing else depends on, so it is where " +
                        "this project starts. Read it first to see what the project actually does."
            else ->
                "${Phrasing.count(entries.size, "module")} ${Phrasing.verb(entries.size)} depended on " +
                        "by nothing else, so each is a place execution can start - typically an " +
                        "application, a sample, or a test harness."
        }

        return Finding(
            id = "entry-points",
            category = FindingCategory.STRUCTURE,
            severity = Severity.INFO,
            title = if (entries.size == 1) "Entry point: ${entries.single()}" else "${entries.size} entry points",
            detail = detail,
            evidence = listOf(
                Phrasing.computed("Entry points", Phrasing.list(entries, MAX_NAMED_ENTRY_POINTS)),
            ),
            subjects = entries,
        )
    }

    /** What the project rests on: the modules with the most pull. */
    private fun foundation(context: NarrativeContext): Finding? {
        val top = if (!context.isMeaningfullySized) {
            emptyList()
        } else {
            context.byInfluence.take(MAX_FOUNDATION)
                .mapNotNull { context.metrics.of(it) }
                .filter { it.fanIn > 0 }
        }
        if (top.isEmpty()) return null

        val lead = top.first()
        return Finding(
            id = "foundation",
            category = FindingCategory.STRUCTURE,
            severity = Severity.INFO,
            title = "The project rests on ${lead.path}",
            detail = "${lead.path} carries the most weight in the graph: " +
                    "${Phrasing.count(lead.fanIn, "module")} " +
                    "${Phrasing.agree(lead.fanIn, "depends", "depend")} on it " +
                    "directly and ${Phrasing.share(lead.blastRadius, context.graph.modules.size)} of the " +
                    "project depends on it in the end. Influence weighs those dependents by their own " +
                    "importance, so this is what the project is genuinely built on, not just what is " +
                    "referenced most often.",
            evidence = listOf(
                Phrasing.computed("Influence", Phrasing.multiplier(lead.influence) + " the average module"),
                Phrasing.computed("Direct dependents", lead.fanIn.toString()),
                Phrasing.computed("Blast radius", "${lead.blastRadius} modules " +
                        "(${Phrasing.share(lead.blastRadius, context.graph.modules.size)} of the project)"),
                Phrasing.computed("Next most influential", Phrasing.list(top.drop(1).map { it.path })),
            ),
            subjects = top.map { it.path },
        )
    }

    /** Whether dependency is spread across the project or absorbed by a few modules. */
    private fun concentration(context: NarrativeContext): Finding? {
        val gini = context.metrics.project.fanInGini
        if (!context.isMeaningfullySized || gini < CONCENTRATED_GINI) return null

        val core = context.metrics.modules.values
            .sortedWith(compareByDescending<ModuleGraphMetrics> { it.fanIn }.thenBy { it.path })
            .take(MAX_CORE)
            .filter { it.fanIn > 0 }

        val coreShare = Phrasing.share(core.sumOf { it.fanIn }, context.graph.edges.count { !it.isTest })

        return Finding(
            id = "dependency-concentration",
            category = FindingCategory.STRUCTURE,
            severity = Severity.INFO,
            title = "Most dependencies converge on a small core",
            detail = "Dependency is unevenly spread: ${Phrasing.list(core.map { it.path })} " +
                    "absorb $coreShare of all production dependencies between them. That is not wrong " +
                    "in itself - most projects have a core - but it does make those modules the " +
                    "bottleneck for almost every change.",
            evidence = listOf(
                Phrasing.computed("Fan-in concentration (Gini)", Phrasing.ratio(gini)),
                Phrasing.computed("Core modules", Phrasing.list(core.map { "${it.path} (${it.fanIn})" })),
            ),
            subjects = core.map { it.path },
        )
    }

    /** How many layers deep the project goes, and what that costs a clean build. */
    private fun depth(context: NarrativeContext): Finding? {
        val depth = context.metrics.project.maxDepth
        if (depth < MIN_INTERESTING_DEPTH) return null

        return Finding(
            id = "graph-depth",
            category = FindingCategory.STRUCTURE,
            severity = Severity.INFO,
            title = "The dependency graph is $depth levels deep",
            detail = "From an entry point, the furthest module is $depth hops away. The longest " +
                    "chain of production dependencies is " +
                    "${Phrasing.count(context.summary.criticalPathLength, "module")} long, and no " +
                    "amount of parallelism can build that chain faster than one module at a time.",
            evidence = listOf(
                Phrasing.computed("Depth from entry point", depth.toString()),
                Phrasing.computed("Critical path", "${context.summary.criticalPathLength} modules"),
            ),
        )
    }

    private const val PERCENT = 100.0
    private const val MIN_GROUPS_FOR_MODULAR = 3
    private const val MAX_NAMED_PREFIXES = 4
    private const val MAX_FOUNDATION = 3
    private const val MAX_CORE = 3
    private const val MIN_INTERESTING_DEPTH = 3
}
