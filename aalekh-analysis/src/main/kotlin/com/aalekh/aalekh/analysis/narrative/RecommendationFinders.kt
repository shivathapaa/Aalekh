package com.aalekh.aalekh.analysis.narrative

import com.aalekh.aalekh.analysis.metrics.StabilityViolation
import com.aalekh.aalekh.model.Confidence
import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.Provenance
import com.aalekh.aalekh.model.Severity

/**
 * **Recommendations** - the only findings that suggest changing the architecture rather than
 * describing it.
 *
 * Every one is `SUGGESTED` provenance with a stated confidence, because each rests on a heuristic
 * that a deliberate design decision can legitimately contradict. They name the evidence that produced
 * them so a reader can dismiss one in seconds rather than having to reverse-engineer why the tool
 * said it.
 */
internal object RecommendationFinders {

    /** A module needs at least this many consumers before splitting it could pay for itself. */
    private const val MIN_CONSUMERS_TO_SPLIT = 4

    /** Each side of a proposed split needs at least this many consumers to be worth separating. */
    private const val MIN_GROUP_SIZE = 2

    /** Modules above this fan-out are excluded from merge suggestions - merging them makes a bigger hub. */
    private const val MAX_FANOUT_TO_MERGE = 8

    private const val MAX_SUGGESTIONS = 3
    private const val MAX_NAMED = 4

    fun findAll(context: NarrativeContext): List<Finding> =
        splitCandidates(context) + mergeCandidates(context) + listOfNotNull(dependencyInversion(context))

    /**
     * Modules whose consumers fall into groups with nothing else in common - a sign the module is
     * really several modules that happen to share a directory.
     */
    private fun splitCandidates(context: NarrativeContext): List<Finding> = context.metrics.modules.values
        .filter { it.fanIn >= MIN_CONSUMERS_TO_SPLIT }
        .sortedWith(compareByDescending<com.aalekh.aalekh.analysis.metrics.ModuleGraphMetrics> { it.fanIn }
            .thenBy { it.path })
        .take(MAX_CANDIDATES_EXAMINED)
        .mapNotNull { metrics ->
            val groups = ConsumerClustering.groupConsumers(context.graph, metrics.path)
                .filter { it.size >= MIN_GROUP_SIZE }
            if (groups.size < MIN_DISJOINT_GROUPS) return@mapNotNull null

            Finding(
                id = "split-candidate",
                category = FindingCategory.STRUCTURE,
                severity = Severity.INFO,
                title = "${metrics.path} may be serving unrelated consumers",
                detail = "${Phrasing.count(metrics.fanIn, "module")} depend on ${metrics.path}, but " +
                        "they fall into ${Phrasing.count(groups.size, "group")} that share no other " +
                        "dependency with each other. Groups like that usually each use a different " +
                        "part of the module and drag in the rest for nothing - which is what makes its " +
                        "blast radius (${Phrasing.percent(metrics.blastRadiusPercent)} of the project) " +
                        "larger than it needs to be.",
                evidence = groups.take(MAX_NAMED).mapIndexed { index, group ->
                    Phrasing.computed("Consumer group ${index + 1}", Phrasing.list(group, MAX_NAMED))
                } + Phrasing.computed("Blast radius", "${metrics.blastRadius} modules"),
                subjects = listOf(metrics.path) + groups.flatten(),
                provenance = Provenance.SUGGESTED,
                confidence = if (groups.size > MIN_DISJOINT_GROUPS) Confidence.MEDIUM else Confidence.LOW,
                action = "Check whether each group uses a distinct part of ${metrics.path}. If so, " +
                        "splitting it lets each consumer depend only on what it uses.",
            )
        }
        .take(MAX_SUGGESTIONS)

    /**
     * Pairs of modules with the same consumers and the same dependencies - separate in the build, but
     * indistinguishable from the graph's point of view.
     */
    private fun mergeCandidates(context: NarrativeContext): List<Finding> {
        val profiles = mergeProfiles(context)
        return indistinguishablePairs(profiles)
            .take(MAX_SUGGESTIONS)
            .map { (a, b) -> mergeFinding(context, profiles.getValue(a), a, b) }
    }

    /** Both sides of each mergeable module's neighbourhood, for modules small enough to merge. */
    private fun mergeProfiles(context: NarrativeContext): Map<String, GraphNeighbourhood> =
        context.graph.modules
            .map { it.path }
            .filter { path ->
                val metrics = context.metrics.of(path)
                metrics != null && metrics.fanIn > 0 && metrics.fanOut <= MAX_FANOUT_TO_MERGE
            }
            .associateWith { path ->
                GraphNeighbourhood(
                    dependents = context.graph.edgesTo(path).filter { !it.isTest }.map { it.from }.toSortedSet(),
                    dependencies = context.graph.edgesFrom(path).filter { !it.isTest }.map { it.to }.toSortedSet(),
                )
            }

    /** Module pairs the dependency graph cannot tell apart. */
    private fun indistinguishablePairs(profiles: Map<String, GraphNeighbourhood>): List<Pair<String, String>> {
        val paths = profiles.keys.sorted()
        val pairs = mutableListOf<Pair<String, String>>()
        for (i in paths.indices) {
            for (j in i + 1 until paths.size) {
                if (areIndistinguishable(paths[i], paths[j], profiles)) pairs += paths[i] to paths[j]
            }
        }
        return pairs
    }

    /**
     * True when two modules have the same dependents and the same dependencies, and neither depends
     * on the other - so no consumer could take one without effectively taking the other too.
     */
    private fun areIndistinguishable(
        a: String,
        b: String,
        profiles: Map<String, GraphNeighbourhood>,
    ): Boolean {
        val first = profiles.getValue(a)
        val second = profiles.getValue(b)
        return first.dependents == second.dependents &&
                first.dependencies == second.dependencies &&
                b !in first.dependencies &&
                a !in second.dependencies
    }

    private fun mergeFinding(
        context: NarrativeContext,
        shared: GraphNeighbourhood,
        a: String,
        b: String,
    ): Finding {
        val coChange = context.hiddenCoupling.firstOrNull {
            (it.moduleA == a && it.moduleB == b) || (it.moduleA == b && it.moduleB == a)
        }
        return Finding(
            id = "merge-candidate",
            category = FindingCategory.STRUCTURE,
            severity = Severity.INFO,
            title = "$a and $b are indistinguishable in the graph",
            detail = "$a and $b have exactly the same dependents and the same dependencies, so " +
                    "no consumer can take one without effectively taking the other. " +
                    (coChange?.let {
                        "They also changed together in ${Phrasing.count(it.sharedCommits, "commit")}. "
                    } ?: "") +
                    "Two modules carry two build files, two sets of configuration, and an extra " +
                    "edge in every diagram; the separation is only worth that if it will be used.",
            evidence = listOfNotNull(
                Phrasing.computed("Shared dependents", Phrasing.list(shared.dependents.toList(), MAX_NAMED)),
                Phrasing.computed("Shared dependencies", Phrasing.list(shared.dependencies.toList(), MAX_NAMED)),
                coChange?.let { Phrasing.observed("Co-changed", "${it.sharedCommits} commits") },
            ),
            subjects = listOf(a, b),
            provenance = Provenance.SUGGESTED,
            confidence = if (coChange != null) Confidence.MEDIUM else Confidence.LOW,
            action = "Merge them, or give one a consumer the other does not have.",
        )
    }

    /** What sits on either side of a module in the graph. */
    private data class GraphNeighbourhood(
        val dependents: Set<String>,
        val dependencies: Set<String>,
    )

    /** Dependencies pointing from a stable module toward a volatile one. */
    private fun dependencyInversion(context: NarrativeContext): Finding? {
        val violations = context.metrics.project.stabilityViolations
            .filter { it.toInstability - it.fromInstability >= MIN_STABILITY_GAP }
        if (violations.isEmpty()) return null

        val worst = violations.first()
        return Finding(
            id = "dependency-inversion-candidate",
            category = FindingCategory.STRUCTURE,
            severity = Severity.INFO,
            title = "${Phrasing.count(violations.size, "dependency", "dependencies")} " +
                    "point from stable code toward volatile code",
            detail = "${worst.from} is stable (instability ${Phrasing.ratio(worst.fromInstability)}) " +
                    "but depends on ${worst.to}, which is volatile " +
                    "(${Phrasing.ratio(worst.toInstability)}). Every change to the volatile module " +
                    "ripples into something the rest of the project relies on holding still.",
            evidence = violations.take(MAX_NAMED).map(::stabilityEvidence),
            subjects = violations.flatMap { listOf(it.from, it.to) }.distinct(),
            provenance = Provenance.SUGGESTED,
            confidence = Confidence.MEDIUM,
            action = "Invert the dependency: declare the interface in the stable module and implement " +
                    "it in the volatile one, so the arrow points the other way.",
        )
    }

    private fun stabilityEvidence(violation: StabilityViolation) = Phrasing.computed(
        "${violation.from} → ${violation.to}",
        "instability ${Phrasing.ratio(violation.fromInstability)} → " +
                Phrasing.ratio(violation.toInstability),
    )

    /** How many high-fan-in modules to run consumer clustering on; it is quadratic in consumers. */
    private const val MAX_CANDIDATES_EXAMINED = 12
    private const val MIN_DISJOINT_GROUPS = 2

    /** Below this instability gap the direction is a wash, not a violation worth reporting. */
    private const val MIN_STABILITY_GAP = 0.4
}
