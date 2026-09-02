package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.metrics.ModuleGraphMetrics
import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.Severity

/**
 * Findings about **what makes change expensive or dangerous** here: cycles, choke points, modules
 * with an outsized blast radius, and areas that churn while everything depends on them.
 *
 * Unlike [StructureFinders], these carry real severity - they name things worth acting on.
 */
internal object RiskFinders {

    /** Blast radius above this share of the project makes almost every change a project-wide change. */
    private const val WIDE_BLAST_RADIUS = 40.0

    /** Betweenness above this marks a module the project's structure genuinely routes through. */
    private const val HIGH_BETWEENNESS = 0.15

    /** Modules named individually before a finding starts counting instead. */
    private const val MAX_NAMED = 4

    fun findAll(context: NarrativeContext): List<Finding> = listOfNotNull(
        cycles(context),
        chokePoints(context),
        wideBlastRadius(context),
        godModules(context),
        isolatedModules(context),
        churnAndReach(context),
        hiddenCoupling(context),
    )

    /** Circular dependencies: the modules that can only be built and understood together. */
    private fun cycles(context: NarrativeContext): Finding? {
        val cycles = GraphAnalyzer.findMainOnlyCycles(context.graph)
        if (cycles.isEmpty()) return null

        val tangled = cycles.flatten().distinct().sorted()
        return Finding(
            id = "dependency-cycles",
            category = FindingCategory.RISK,
            severity = Severity.ERROR,
            title = "${Phrasing.count(cycles.size, "dependency cycle")} in production code",
            detail = "${Phrasing.count(tangled.size, "module")} " +
                    "${Phrasing.verb(tangled.size)} caught in a cycle " +
                    "(${Phrasing.share(tangled.size, context.graph.modules.size)} of the project). " +
                    "Modules in a cycle cannot be built, tested, or reasoned about independently - " +
                    "changing one means changing all of them.",
            evidence = listOf(
                Phrasing.computed("Cycles", cycles.size.toString()),
                Phrasing.computed("Modules tangled", Phrasing.list(tangled, MAX_NAMED)),
                Phrasing.computed("Tangled share", Phrasing.percent(context.summary.tanglePercent)),
            ),
            subjects = tangled,
            action = "Run aalekhCheck - it names the exact dependency declaration to remove to break " +
                    "each cycle.",
        )
    }

    /** Modules whose removal would split the project, or that most paths route through. */
    private fun chokePoints(context: NarrativeContext): Finding? {
        val cuts = context.metrics.project.articulationPoints
        val busy = context.metrics.modules.values
            .filter { it.betweenness >= HIGH_BETWEENNESS }
            .sortedWith(compareByDescending<ModuleGraphMetrics> { it.betweenness }.thenBy { it.path })
            .map { it.path }
        val chokePoints = (cuts + busy).distinct()
        if (chokePoints.isEmpty()) return null

        val detail = buildString {
            if (cuts.isNotEmpty()) {
                append("Removing ")
                append(Phrasing.list(cuts, MAX_NAMED))
                append(if (cuts.size == 1) " would split" else " would each split")
                append(" the project into disconnected pieces. ")
            }
            if (busy.isNotEmpty()) {
                append(Phrasing.list(busy, MAX_NAMED))
                append(if (busy.size == 1) " sits" else " sit")
                append(" on a large share of the shortest paths between otherwise separate parts of ")
                append("the project, so changes there tend to ripple in both directions. ")
            }
            append("These are structural bottlenecks, not merely busy modules.")
        }

        return Finding(
            id = "choke-points",
            category = FindingCategory.RISK,
            severity = Severity.WARNING,
            title = "${Phrasing.count(chokePoints.size, "structural choke point")}",
            detail = detail,
            evidence = listOfNotNull(
                cuts.takeIf { it.isNotEmpty() }
                    ?.let { Phrasing.computed("Removal would disconnect the graph", Phrasing.list(it, MAX_NAMED)) },
                busy.takeIf { it.isNotEmpty() }
                    ?.let { Phrasing.computed("High betweenness", Phrasing.list(it, MAX_NAMED)) },
            ),
            subjects = chokePoints,
            action = "Before refactoring a choke point, add tests on both sides of it - it is holding " +
                    "two parts of the project together.",
        )
    }

    /** Modules a breaking change to which forces most of the project to rebuild. */
    private fun wideBlastRadius(context: NarrativeContext): Finding? {
        val wide = if (!context.isMeaningfullySized) {
            emptyList()
        } else {
            context.metrics.modules.values
                .filter { it.blastRadiusPercent >= WIDE_BLAST_RADIUS }
                .sortedWith(compareByDescending<ModuleGraphMetrics> { it.blastRadius }.thenBy { it.path })
        }
        if (wide.isEmpty()) return null

        val worst = wide.first()
        return Finding(
            id = "wide-blast-radius",
            category = FindingCategory.RISK,
            severity = Severity.WARNING,
            title = "${Phrasing.count(wide.size, "module")} with a project-wide blast radius",
            detail = "A breaking change in ${worst.path} forces " +
                    "${Phrasing.count(worst.blastRadius, "module")} to rebuild and retest - " +
                    "${Phrasing.percent(worst.blastRadiusPercent)} of the project. " +
                    (if (wide.size > 1) {
                        "${Phrasing.list(wide.drop(1).map { it.path }, MAX_NAMED)} " +
                                "reach almost as far. "
                    } else "") +
                    "Changes to these modules are never local, whatever the diff looks like.",
            evidence = wide.take(MAX_NAMED).map {
                Phrasing.computed(it.path, "${it.blastRadius} modules (${Phrasing.percent(it.blastRadiusPercent)})")
            },
            subjects = wide.map { it.path },
            action = "Treat these as public API: cover them with tests and change them deliberately.",
        )
    }

    /** High fan-in and high fan-out at once - hard to change and hard to test. */
    private fun godModules(context: NarrativeContext): Finding? {
        val gods = GraphAnalyzer.godModules(context.graph).map { it.path }.sorted()
        if (gods.isEmpty()) return null

        return Finding(
            id = "god-modules",
            category = FindingCategory.RISK,
            severity = Severity.WARNING,
            title = "${Phrasing.count(gods.size, "module")} both depended on and depending heavily",
            detail = "${Phrasing.list(gods, MAX_NAMED)} " +
                    "${if (gods.size == 1) "has" else "have"} many dependents *and* many " +
                    "dependencies. That combination is the hardest to work with: the module cannot be " +
                    "changed without affecting its consumers, and cannot be tested without standing " +
                    "up most of its dependencies.",
            evidence = gods.take(MAX_NAMED).mapNotNull { path ->
                context.metrics.of(path)?.let {
                    Phrasing.computed(path, "${it.fanIn} dependents, ${it.fanOut} dependencies")
                }
            },
            subjects = gods,
            action = "Split by responsibility: most consumers usually need only one part of what these " +
                    "modules provide.",
        )
    }

    /** Modules connected to nothing - dead weight, or a wiring mistake. */
    private fun isolatedModules(context: NarrativeContext): Finding? {
        val isolated = GraphAnalyzer.isolatedModules(context.graph).map { it.path }.sorted()
        if (isolated.isEmpty()) return null

        return Finding(
            id = "isolated-modules",
            category = FindingCategory.RISK,
            severity = Severity.INFO,
            title = "${Phrasing.count(isolated.size, "module")} connected to nothing",
            detail = "${Phrasing.list(isolated, MAX_NAMED)} neither " +
                    "depend on another module nor are depended on by one. Some are legitimate - an " +
                    "empty structural parent that only groups nested modules, a standalone tool - but " +
                    "an unwired feature module looks exactly the same from the graph.",
            evidence = listOf(Phrasing.computed("Isolated", Phrasing.list(isolated, MAX_NAMED))),
            subjects = isolated,
            action = "Confirm each is intentional; delete the ones that are left over.",
        )
    }

    /**
     * The riskiest combination in the project: a module that changes constantly *and* that everything
     * depends on. Needs git history, so it appears only after `aalekhTemporal` has run.
     */
    private fun churnAndReach(context: NarrativeContext): Finding? {
        val risky = context.churnByModule.entries
            .mapNotNull { (path, commits) -> context.metrics.of(path)?.let { it to commits } }
            .filter { (metrics, _) -> metrics.blastRadiusPercent >= WIDE_BLAST_RADIUS }
            .sortedWith(
                compareByDescending<Pair<ModuleGraphMetrics, Int>> { it.second }.thenBy { it.first.path }
            )
        if (risky.isEmpty()) return null

        val (worst, commits) = risky.first()
        return Finding(
            id = "volatile-foundation",
            category = FindingCategory.RISK,
            severity = Severity.WARNING,
            title = "${worst.path} changes often and everything depends on it",
            detail = "${worst.path} was touched by ${Phrasing.count(commits, "commit")} in the " +
                    "analysed window, and ${Phrasing.percent(worst.blastRadiusPercent)} of the project " +
                    "depends on it. High churn is fine in a leaf and expensive in a foundation: every " +
                    "change here is a change everywhere.",
            evidence = listOf(
                Phrasing.observed("Commits in window", commits.toString()),
                Phrasing.computed("Blast radius", "${worst.blastRadius} modules " +
                        "(${Phrasing.percent(worst.blastRadiusPercent)})"),
                Phrasing.computed("Instability", Phrasing.ratio(worst.instability)),
            ),
            subjects = risky.map { it.first.path },
            action = "Stabilise the API here first - it is where testing effort pays back most.",
        )
    }

    /** Modules that change together in git but declare no dependency on each other. */
    private fun hiddenCoupling(context: NarrativeContext): Finding? {
        if (context.hiddenCoupling.isEmpty()) return null
        val pairs = context.hiddenCoupling.map { "${it.moduleA} ↔ ${it.moduleB}" }

        return Finding(
            id = "hidden-coupling",
            category = FindingCategory.RISK,
            severity = Severity.WARNING,
            title = "${Phrasing.count(context.hiddenCoupling.size, "module pair")} change together " +
                    "without declaring a dependency",
            detail = "${Phrasing.list(pairs, MAX_NAMED)} keep appearing in the same commits, yet " +
                    "neither declares a dependency on the other. Something couples them that the build " +
                    "graph cannot see - a shared format, a duplicated constant, an implicit contract - " +
                    "so nothing stops the two drifting apart.",
            evidence = context.hiddenCoupling.take(MAX_NAMED).map {
                Phrasing.observed(
                    "${it.moduleA} ↔ ${it.moduleB}",
                    "${it.sharedCommits} shared commits, coupling ${Phrasing.ratio(it.degree)}",
                )
            },
            subjects = context.hiddenCoupling.flatMap { listOf(it.moduleA, it.moduleB) }.distinct(),
            action = "Make the coupling explicit - extract the shared concept into a module both can " +
                    "depend on - or find out why it exists and remove it.",
        )
    }
}
