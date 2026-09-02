package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.NarrativeReport
import com.aalekh.aalekh.model.ReadingStep
import com.aalekh.aalekh.model.Severity

/**
 * Turns analysis into English.
 *
 * Everything here is available as a number elsewhere in the report; the narrative states what those
 * numbers mean. `fan-in = 24` becomes "24 modules depend on this, more than half the project, so
 * changes here are never local".
 *
 * Sentences are assembled from computed values by a finder, so the same graph always produces
 * byte-identical text - which is what makes the output safe to commit, diff, and assert on in CI.
 *
 * Findings are ordered by severity first and category second, so anything actionable is read before
 * the descriptive material regardless of which finder produced it.
 */
public object NarrativeEngine {

    /** Modules in the suggested reading order for someone new to the project. */
    private const val READING_STEPS = 7

    /** Below this module count a reading order is noise - just read all of them. */
    private const val MIN_MODULES_FOR_READING_ORDER = 8

    /**
     * Share of the project a module must be depended on by to earn a place in the reading order.
     *
     * Without a floor the order pads itself out to [READING_STEPS] with whatever ranks next, which
     * on a real project means test-support and tooling modules that explain nothing. A short list of
     * modules that matter is more useful than a full one that does not.
     */
    private const val MIN_FOUNDATION_SHARE = 0.05

    /** Builds the full narrative for [context]. Returns [NarrativeReport.EMPTY] for an empty graph. */
    public fun analyze(context: NarrativeContext): NarrativeReport {
        if (context.graph.modules.isEmpty()) return NarrativeReport.EMPTY

        val findings = (
                StructureFinders.findAll(context) +
                        RiskFinders.findAll(context) +
                        DependencyFinders.findAll(context) +
                        OwnershipFinders.findAll(context) +
                        BuildFinders.findAll(context) +
                        RecommendationFinders.findAll(context) +
                        // Third-party findings are ordered with the built-in ones rather than
                        // appended, so a team's own finding about their own project can lead.
                        context.extensions.findings
                ).sortedWith(
                compareBy<Finding> { it.severity.ordinal }
                    .thenBy { it.category.ordinal }
                    .thenBy { it.id }
                    .thenBy { it.title }
            )

        return NarrativeReport(
            findings = findings,
            readingOrder = readingOrder(context),
            summary = summary(context, findings),
        )
    }

    /**
     * A short paragraph describing the project - the first thing a reader who has never opened the
     * codebase should see.
     */
    private fun summary(context: NarrativeContext, findings: List<Finding>): String {
        val modules = context.graph.modules.size
        val edges = context.graph.edges.count { !it.isTest }
        val blocking = findings.count { it.severity == Severity.ERROR }
        val entries = context.metrics.project.entryPoints

        return buildString {
            append("${context.graph.projectName} is a Gradle project of ")
            append(Phrasing.count(modules, "module"))
            append(" wired together by ")
            append(Phrasing.count(edges, "production dependency", "production dependencies"))
            append(". ")

            if (entries.isNotEmpty()) {
                append(
                    if (entries.size == 1) "Execution starts at ${entries.single()}. "
                    else "It has ${Phrasing.count(entries.size, "entry point")}: " +
                            "${Phrasing.list(entries)}. "
                )
            }

            context.byInfluence.firstOrNull()?.let { lead ->
                context.metrics.of(lead)?.let { metrics ->
                    if (metrics.fanIn > 0) {
                        append("The module carrying the most weight is $lead, which ")
                        append(Phrasing.share(metrics.blastRadius, modules))
                        append(" of the project depends on. ")
                    }
                }
            }

            append(
                when {
                    blocking > 0 -> "There ${Phrasing.verb(blocking)} " +
                            "${Phrasing.count(blocking, "issue")} that ${
                                if (blocking == 1) "needs" else "need"
                            } attention before the architecture can be described as sound."
                    context.summary.cycleCount > 0 -> "The graph contains " +
                            "${Phrasing.count(context.summary.cycleCount, "dependency cycle")}."
                    else -> "The dependency graph is acyclic, so every module can be built and " +
                            "understood on its own terms."
                }
            )
        }
    }

    /**
     * The order to read the project in: start where execution starts, then the modules the most
     * depends on, spreading across different areas rather than walking one branch to the bottom.
     */
    private fun readingOrder(context: NarrativeContext): List<ReadingStep> {
        if (context.graph.modules.size < MIN_MODULES_FOR_READING_ORDER) return emptyList()

        val chosen = LinkedHashMap<String, String>()
        val areasSeen = mutableSetOf<String>()

        val applications = context.graph.modules
            .filter { it.type == ModuleType.ANDROID_APP }
            .map { it.path }
            .toSet()
        context.metrics.project.entryPoints.take(MAX_ENTRY_STEPS).forEach { path ->
            // Only an application module is somewhere execution actually starts. The rest are
            // entry points in the graph sense - nothing depends on them - which is a weaker claim
            // and should read as one.
            chosen[path] = if (path in applications) {
                "Execution starts here - it shows what the project actually does."
            } else {
                "Nothing depends on it, so it is a place to start rather than a building block."
            }
            areasSeen += areaOf(path)
        }

        // The foundation, most influential first, at most one per area so the order spans the
        // project instead of walking a single branch to the bottom.
        addFoundation(context, chosen, areasSeen, oneAreaEach = true)
        // If one-per-area left the order short, fill from the remaining influential modules.
        addFoundation(context, chosen, areasSeen, oneAreaEach = false)

        return chosen.entries.mapIndexed { index, (path, reason) ->
            ReadingStep(position = index + 1, module = path, reason = reason)
        }
    }

    private fun addFoundation(
        context: NarrativeContext,
        chosen: MutableMap<String, String>,
        areasSeen: MutableSet<String>,
        oneAreaEach: Boolean,
    ) {
        context.byInfluence
            .asSequence()
            .filter { it !in chosen }
            .mapNotNull { path -> context.metrics.of(path) }
            .filter { it.fanIn > 0 && it.blastRadiusPercent >= MIN_FOUNDATION_SHARE * PERCENT }
            .filterNot { oneAreaEach && areaOf(it.path) in areasSeen && chosen.size > MAX_ENTRY_STEPS }
            .take(READING_STEPS)
            .forEach { metrics ->
                if (chosen.size >= READING_STEPS) return
                areasSeen += areaOf(metrics.path)
                chosen[metrics.path] =
                    "${Phrasing.share(metrics.blastRadius, context.graph.modules.size)} of the " +
                            "project depends on it; understanding it explains a lot of the rest."
            }
    }

    /** The top-level path segment a module sits under - a crude but stable notion of "area". */
    private fun areaOf(path: String): String =
        path.split(":").firstOrNull { it.isNotBlank() } ?: path

    /** Findings for one module, for its detail page. */
    public fun findingsFor(report: NarrativeReport, modulePath: String): List<Finding> =
        report.findings.filter { modulePath in it.subjects }

    /** Findings grouped by category, in category order, skipping empty categories. */
    public fun byCategory(report: NarrativeReport): Map<FindingCategory, List<Finding>> =
        FindingCategory.entries
            .associateWith { category -> report.findings.filter { it.category == category } }
            .filterValues { it.isNotEmpty() }

    private const val MAX_ENTRY_STEPS = 2

    private const val PERCENT = 100.0
}
