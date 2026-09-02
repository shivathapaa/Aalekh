package com.aalekh.aalekh.report.docs

import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.analysis.graph.RegionMap
import com.aalekh.aalekh.analysis.metrics.GraphMetricSet
import com.aalekh.aalekh.analysis.metrics.MetricCatalog
import com.aalekh.aalekh.analysis.metrics.ModuleGraphMetrics
import com.aalekh.aalekh.model.BuildInventory
import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.FindingCategory
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.NarrativeReport
import com.aalekh.aalekh.model.Provenance
import com.aalekh.aalekh.model.Severity

/**
 * Everything the generated documentation needs, in one bundle.
 *
 * Gathered by `ReportCoordinator` from the same analysis the HTML report renders, so the two can
 * never describe the project differently.
 */
public data class DocsInput(
    val graph: ModuleDependencyGraph,
    val summary: GraphSummary,
    val metrics: GraphMetricSet,
    val narrative: NarrativeReport,
    val regions: RegionMap,
    val inventory: BuildInventory,
)

/**
 * Generates human-readable Markdown documentation for a project.
 *
 * The HTML report is for exploring; this is for **reading, committing, and reviewing**. Markdown
 * renders on GitHub without a build step, diffs line by line in a pull request, and can be opened by
 * anyone without running Gradle - so an architecture change shows up in review as a text diff next to
 * the code that caused it.
 *
 * Everything is assembled from the same deterministic findings the report uses, so re-running on an
 * unchanged project produces byte-identical files. That is the property that makes committing the
 * output safe: a diff means the architecture changed, never that the tool ran again.
 */
// One private function per generated document, plus the shared formatting helpers. The count tracks
// how many documents Aalekh writes rather than how many jobs this object does; splitting it would
// mean passing the same DocsInput through another layer for no gain.
@Suppress("TooManyFunctions")
public object DocsGenerator {

    private const val MAX_TABLE_ROWS = 40
    private const val MAX_LISTED_MODULES = 8
    private const val PERCENT = 100.0

    /**
     * Generates the full document set, keyed by file name relative to the docs output directory.
     *
     * @return `README.md` plus one file per topic. Topics with nothing to say are omitted rather
     *   than emitted empty - a file that only ever says "no data" is noise in a repository.
     */
    public fun generate(input: DocsInput): Map<String, String> = buildMap {
        put("README.md", overview(input))
        put("modules.md", moduleCatalogue(input))
        put("onboarding.md", onboarding(input))
        put("health.md", health(input))
        if (!input.regions.isEmpty) put("regions.md", regions(input))
        if (!input.inventory.isEmpty) put("build.md", build(input))
        if (input.graph.externalDependencies.isNotEmpty()) put("dependencies.md", dependencies(input))
    }

    /** The landing document: what this project is, and what the analysis found. */
    private fun overview(input: DocsInput): String = buildString {
        appendLine("# ${input.graph.projectName}")
        appendLine()
        appendLine(input.narrative.summary)
        appendLine()
        appendLine(generatedNote())
        appendLine()

        appendLine("## Contents")
        appendLine()
        appendLine("| Document | What's inside |")
        appendLine("|----------|---------------|")
        appendLine(
            "| [Modules](modules.md) | Every module, what depends on it, and what a change costs |"
        )
        appendLine("| [Where to start](onboarding.md) | A reading order for someone new to the project |")
        appendLine("| [Health](health.md) | Metrics, what they mean, and how to read them |")
        if (!input.regions.isEmpty) {
            appendLine("| [Regions](regions.md) | How the project divides, and the flows between |")
        }
        if (!input.inventory.isEmpty) {
            appendLine("| [Build](build.md) | Plugins, versions, toolchains, targets |")
        }
        if (input.graph.externalDependencies.isNotEmpty()) {
            appendLine("| [Dependencies](dependencies.md) | Third-party libraries and version alignment |")
        }
        appendLine()

        appendLine("## At a glance")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Modules | ${input.graph.modules.size} |")
        appendLine("| Production dependencies | ${input.graph.edges.count { !it.isTest }} |")
        appendLine("| Dependency cycles | ${input.summary.cycleCount} |")
        appendLine("| Longest dependency chain | ${input.summary.criticalPathLength} modules |")
        appendLine("| Entry points | ${listOrNone(input.metrics.project.entryPoints)} |")
        appendLine()

        appendFindings(input.narrative.findings)
    }

    /** Findings grouped by category, actionable first. */
    private fun StringBuilder.appendFindings(findings: List<Finding>) {
        if (findings.isEmpty()) return
        appendLine("## What the analysis found")
        appendLine()

        FindingCategory.entries.forEach { category ->
            val group = findings.filter { it.category == category }
            if (group.isEmpty()) return@forEach
            appendLine("### ${category.label}")
            appendLine()
            group.forEach { appendFinding(it) }
        }
    }

    private fun StringBuilder.appendFinding(finding: Finding) {
        appendLine("#### ${severityMark(finding.severity)} ${finding.title}")
        appendLine()
        appendLine(finding.detail)
        appendLine()
        if (finding.evidence.isNotEmpty()) {
            finding.evidence.forEach { appendLine("- **${it.label}:** ${it.value}") }
            appendLine()
        }
        finding.action?.let {
            appendLine("> **What to do:** $it")
            appendLine()
        }
        appendProvenanceNote(finding)
    }

    /** Marks a heuristic as one, so a suggestion is never mistaken for a fact read from the build. */
    private fun StringBuilder.appendProvenanceNote(finding: Finding) {
        val inferred = finding.provenance == Provenance.INFERRED ||
            finding.provenance == Provenance.SUGGESTED
        if (!inferred) return
        val confidence = finding.confidence?.let { ", ${it.name.lowercase()} confidence" } ?: ""
        appendLine(
            "<sub>${finding.provenance.name.lowercase()}$confidence" +
                " - a heuristic, not a fact read from the build</sub>"
        )
        appendLine()
    }

    /** One row per module with the numbers that decide whether changing it is cheap or expensive. */
    private fun moduleCatalogue(input: DocsInput): String = buildString {
        appendLine("# Module catalogue")
        appendLine()
        appendLine(
            "Every module, ordered by how much of the project depends on it. **Blast radius** is what " +
                "a breaking change forces to rebuild and retest; **comprehension cost** is how much " +
                "you have to read to understand the module."
        )
        appendLine()
        appendLine(generatedNote())
        appendLine()

        val declared = input.inventory.declaredMetadata
        val ranked = input.metrics.modules.values
            .sortedWith(
                compareByDescending<ModuleGraphMetrics> { it.blastRadius }.thenBy { it.path }
            )

        appendLine("| Module | Dependents | Blast radius | Depends on | Comprehension cost | Owner |")
        appendLine("|--------|-----------:|-------------:|-----------:|-------------------:|-------|")
        ranked.forEach { m ->
            val owner = declared[m.path]?.owner
                ?: input.inventory.codeowners[m.path]?.joinToString(", ")
                ?: "-"
            appendLine(
                "| `${m.path}` | ${m.fanIn} | ${m.blastRadius} " +
                    "(${percent(m.blastRadiusPercent)}) | ${m.fanOut} | ${m.transitiveDependencies} | $owner |"
            )
        }
        appendLine()

        val described = declared.values.filter { it.purpose != null }.sortedBy { it.path }
        if (described.isNotEmpty()) {
            appendLine("## What these modules are for")
            appendLine()
            appendLine(
                "Declared by the team in `.aalekh/modules.json`. Aalekh can measure a module's shape " +
                    "but never its intent, so this is the only place that information can come from."
            )
            appendLine()
            described.forEach { meta ->
                appendLine("### `${meta.path}`")
                appendLine()
                meta.purpose?.let { appendLine(it); appendLine() }
                val facts = listOfNotNull(
                    meta.owner?.let { "**Owner:** $it" },
                    meta.status?.let { "**Status:** $it" },
                )
                if (facts.isNotEmpty()) {
                    appendLine(facts.joinToString(" · "))
                    appendLine()
                }
            }
        }
    }

    /** The reading order, as a numbered walkthrough. */
    private fun onboarding(input: DocsInput): String = buildString {
        appendLine("# Where to start")
        appendLine()
        appendLine(input.narrative.summary)
        appendLine()
        appendLine(generatedNote())
        appendLine()

        if (input.narrative.readingOrder.isEmpty()) {
            appendLine(
                "This project is small enough to read end to end - there is no shortcut worth " +
                    "describing. Start at " +
                    "${listOrNone(input.metrics.project.entryPoints)} and follow the dependencies."
            )
            return@buildString
        }

        appendLine("## Read these first")
        appendLine()
        input.narrative.readingOrder.forEach { step ->
            appendLine("${step.position}. **`${step.module}`** - ${step.reason}")
        }
        appendLine()

        val entryPoints = input.metrics.project.entryPoints
        if (entryPoints.isNotEmpty()) {
            appendLine("## Where execution starts")
            appendLine()
            entryPoints.forEach { path ->
                val m = input.metrics.of(path)
                appendLine("- `$path` - depends on ${m?.fanOut ?: 0} modules directly")
            }
            appendLine()
        }

        val foundation = input.metrics.project.foundation
        if (foundation.isNotEmpty()) {
            appendLine("## What the project rests on")
            appendLine()
            appendLine(
                "These depend on nothing else in the project, so they can be read on their own terms."
            )
            appendLine()
            foundation.take(MAX_LISTED_MODULES).forEach { path ->
                val m = input.metrics.of(path)
                appendLine("- `$path` - ${m?.fanIn ?: 0} modules depend on it")
            }
            appendLine()
        }
    }

    /** Metrics with their definitions, so a number never appears without its meaning. */
    private fun health(input: DocsInput): String = buildString {
        appendLine("# Health")
        appendLine()
        appendLine(generatedNote())
        appendLine()

        appendLine("## Whole-project metrics")
        appendLine()
        appendLine("| Metric | Value | What it means |")
        appendLine("|--------|------:|---------------|")
        val values = mapOf(
            "ccd" to input.summary.ccd.toString(),
            "nccd" to "%.2f".format(java.util.Locale.ROOT, input.summary.nccd),
            "tangle" to percent(input.summary.tanglePercent),
            "fan-in-gini" to "%.2f".format(java.util.Locale.ROOT, input.metrics.project.fanInGini),
            "critical-path" to "${input.summary.criticalPathLength} modules",
        )
        values.forEach { (id, value) ->
            val definition = MetricCatalog.find(id) ?: return@forEach
            appendLine("| ${definition.name} | $value | ${definition.question} |")
        }
        appendLine()

        appendLine("## How to read these")
        appendLine()
        MetricCatalog.all.forEach { definition ->
            appendLine("### ${definition.name}")
            appendLine()
            appendLine("`${definition.formula}`")
            appendLine()
            appendLine("**${definition.question}** ${definition.interpretation}")
            appendLine()
            appendLine("*${definition.action}*")
            appendLine()
        }
    }

    /** How the project divides, and what crosses the boundaries. */
    private fun regions(input: DocsInput): String = buildString {
        val map = input.regions
        appendLine("# Regions")
        appendLine()
        appendLine(
            "The project grouped into ${map.regions.size} regions from ${map.source.label}" +
                if (map.source.provenance == Provenance.OBSERVED) {
                    ", which is what the build declares."
                } else {
                    ". Nothing is declared, so this grouping is inferred - declare `layers { }` or " +
                        "`teams { }` to group by the architecture you intend."
                }
        )
        appendLine()
        appendLine(generatedNote())
        appendLine()

        appendLine("| Region | Modules | Coupling kept inside |")
        appendLine("|--------|--------:|---------------------:|")
        map.regions.forEach { region ->
            appendLine("| ${region.name} | ${region.modules.size} | ${percent(region.cohesion * PERCENT)} |")
        }
        appendLine()

        appendLine("## Flows between regions")
        appendLine()
        appendLine("Each row counts every module dependency that crosses that boundary.")
        appendLine()
        appendLine("| From | To | Dependencies |")
        appendLine("|------|----|-------------:|")
        map.edges.take(MAX_TABLE_ROWS).forEach { edge ->
            appendLine("| ${edge.from} | ${edge.to} | ${edge.weight} |")
        }
        appendLine()

        map.regions.filter { it.modules.isNotEmpty() }.forEach { region ->
            appendLine("## ${region.name}")
            appendLine()
            if (region.subRegions.isNotEmpty()) {
                appendLine("${region.modules.size} modules in ${region.subRegions.size} groups:")
                appendLine()
                region.subRegions.forEach { sub ->
                    appendLine("- **${sub.name}** (${sub.modules.size})")
                }
            } else {
                region.modules.forEach { appendLine("- `$it`") }
            }
            appendLine()
        }
    }

    /** The build inventory as a reference table. */
    private fun build(input: DocsInput): String = buildString {
        val inventory = input.inventory
        appendLine("# Build")
        appendLine()
        appendLine("What this project is made of, read from the build rather than inferred.")
        appendLine()
        appendLine(generatedNote())
        appendLine()

        if (inventory.toolVersions.isNotEmpty()) {
            appendLine("## Tool versions")
            appendLine()
            inventory.toolVersions.toSortedMap().forEach { (tool, version) ->
                appendLine("- **$tool** $version")
            }
            appendLine()
        }

        val usage = inventory.modules
            .flatMap { info -> info.plugins.map { it to info.path } }
            .groupBy({ it.first.id }, { it })
        if (usage.isNotEmpty()) {
            appendLine("## Plugins")
            appendLine()
            appendLine("| Plugin | Version | Modules |")
            appendLine("|--------|---------|--------:|")
            usage.entries
                .sortedWith(compareByDescending<Map.Entry<String, List<Pair<*, *>>>> { it.value.size }
                    .thenBy { it.key })
                .forEach { (id, uses) ->
                    val versions = uses.mapNotNull { (plugin, _) ->
                        (plugin as com.aalekh.aalekh.model.ModulePlugin).version
                    }.distinct().sorted()
                    val versionCell = when {
                        versions.isEmpty() -> "-"
                        versions.size == 1 -> versions.single()
                        else -> "**${versions.joinToString(", ")}** (conflict)"
                    }
                    appendLine("| `$id` | $versionCell | ${uses.size} |")
                }
            appendLine()
        }

        inventory.catalogs.forEach { catalog ->
            appendLine("## Version catalog: ${catalog.name}")
            appendLine()
            if (catalog.plugins.isNotEmpty()) {
                appendLine("| Plugin alias | Resolves to | Version |")
                appendLine("|--------------|-------------|---------|")
                catalog.plugins.forEach {
                    appendLine("| `${it.alias}` | `${it.coordinates}` | ${it.version ?: "-"} |")
                }
                appendLine()
            }
        }
    }

    /** Third-party libraries and where their versions disagree. */
    private fun dependencies(input: DocsInput): String = buildString {
        appendLine("# Dependencies")
        appendLine()
        appendLine(
            "Third-party libraries as **declared** across the project. These are declaration sites, " +
                "not the resolved dependency graph - reading the resolved graph would mean resolving " +
                "it, which Aalekh deliberately never does."
        )
        appendLine()
        appendLine(generatedNote())
        appendLine()

        val byCoordinate = input.graph.externalDependencies
            .groupBy { "${it.group}:${it.name}" }
            .toSortedMap()

        val conflicts = byCoordinate.filterValues { deps ->
            deps.mapNotNull { it.version }.distinct().size > 1
        }
        if (conflicts.isNotEmpty()) {
            appendLine("## Version conflicts")
            appendLine()
            appendLine(
                "Gradle resolves one version per library for the whole build, so where a project " +
                    "declares two, at least one module compiles against a version it did not ask for."
            )
            appendLine()
            appendLine("| Library | Declared versions |")
            appendLine("|---------|-------------------|")
            conflicts.forEach { (coordinate, deps) ->
                val versions = deps.mapNotNull { it.version }.distinct().sorted()
                appendLine("| `$coordinate` | ${versions.joinToString(", ")} |")
            }
            appendLine()
        }

        appendLine("## All libraries")
        appendLine()
        appendLine("| Library | Version | Modules |")
        appendLine("|---------|---------|--------:|")
        byCoordinate.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<*>>> { it.value.size }.thenBy { it.key })
            .forEach { (coordinate, deps) ->
                val versions = deps.filterIsInstance<com.aalekh.aalekh.model.ExternalDependency>()
                    .mapNotNull { it.version }.distinct().sorted()
                val modules = deps.filterIsInstance<com.aalekh.aalekh.model.ExternalDependency>()
                    .map { it.module }.distinct().size
                appendLine(
                    "| `$coordinate` | ${versions.joinToString(", ").ifEmpty { "-" }} | $modules |"
                )
            }
        appendLine()
    }

    private fun severityMark(severity: Severity): String = when (severity) {
        Severity.ERROR -> "🔴"
        Severity.WARNING -> "🟠"
        Severity.INFO -> "▪"
    }

    private fun percent(value: Double): String = "${kotlin.math.round(value).toInt()}%"

    private fun listOrNone(items: List<String>): String =
        if (items.isEmpty()) "none" else items.take(MAX_LISTED_MODULES).joinToString(", ") { "`$it`" }

    /**
     * The note every generated file carries.
     *
     * Deliberately free of a timestamp: a generated document with a "last updated" line changes on
     * every run, so committing it produces a diff whenever the tool runs rather than whenever the
     * architecture changes - which would make the diffs worthless for review.
     */
    private fun generatedNote(): String =
        "<sub>Generated by [Aalekh](https://github.com/shivathapaa/aalekh) with `aalekhDocs`. " +
            "Re-run to refresh; a diff here means the architecture changed.</sub>"
}
