package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.analysis.temporal.CommitChange
import com.aalekh.aalekh.analysis.temporal.TemporalCouplingAnalyzer
import com.aalekh.aalekh.model.CoChange
import com.aalekh.aalekh.model.ModuleChurn
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleMainSequence
import com.aalekh.aalekh.model.TemporalCouplingReport
import com.aalekh.aalekh.report.html.HtmlReportGenerator
import com.aalekh.aalekh.report.json.JsonReporter
import com.aalekh.aalekh.report.junit.JUnitXmlWriter
import com.aalekh.aalekh.report.mermaid.MermaidGraphGenerator
import com.aalekh.aalekh.report.sarif.SarifReporter
import com.aalekh.aalekh.report.temporal.TemporalReportGenerator

/**
 * Facade that drives all report generation from a single call site.
 *
 * Tasks never instantiate individual report generators directly - this keeps
 * task code thin and report logic testable without Gradle on the classpath.
 */
public class ReportCoordinator(
    private val graph: ModuleDependencyGraph,
    private val ruleResult: RuleEngineResult,
    private val projectName: String,
) {
    private val summary = GraphAnalyzer.summary(graph)

    /**
     * Generates the self-contained HTML report. Returns the complete HTML string.
     *
     * @param trendJson JSON array string of historical trend entries to embed in the report.
     *   Defaults to `"[]"` when no trend history is available.
     * @param teamOwners Map of team name to the module path glob patterns it owns, from the
     *   `teams { }` DSL. Drives the ownership colour overlay; empty (the default) disables it.
     */
    public fun generateHtml(
        trendJson: String = "[]",
        teamOwners: Map<String, List<String>> = emptyMap(),
        mainSequence: List<ModuleMainSequence> = emptyList(),
        hiddenCoupling: List<CoChange> = emptyList(),
        churn: List<ModuleChurn> = emptyList(),
    ): String =
        HtmlReportGenerator.generate(
            projectName = projectName,
            graph = graph,
            summary = summary,
            violations = ruleResult.violations,
            appliedRules = ruleResult.appliedRules,
            trendJson = trendJson,
            teamOwners = teamOwners,
            mainSequence = mainSequence,
            hiddenCoupling = hiddenCoupling,
            churn = churn,
        )

    /** Generates JUnit XML output for CI test reporting systems. */
    public fun generateJUnitXml(): String =
        JUnitXmlWriter.generate(
            projectName = projectName,
            result = ruleResult,
        )

    /**
     * Generates the machine-readable JSON report envelope:
     * `{ graph, summary, violations, generatedAt, aalekhVersion }`.
     */
    public fun generateJson(): String =
        JsonReporter.generate(
            graph = graph,
            summary = summary,
            violations = ruleResult.violations,
        )

    /** Generates SARIF 2.1 output for GitHub code scanning PR annotations. */
    public fun generateSarif(): String =
        SarifReporter.generate(
            graph = graph,
            result = ruleResult,
        )

    /**
     * Generates a CSV of per-module metrics for import into external tools.
     * One timestamped row per module with fan-in, fan-out, instability,
     * transitive dep count, health score, and boolean flags.
     */
    public fun generateCsv(): String = CsvMetricsExporter.export(graph)

    /**
     * Generates the module graph as a raw Mermaid `graph` definition (no code fence).
     * Diffable plain text that renders inline on GitHub and in most IDEs.
     */
    public fun generateMermaid(): String = MermaidGraphGenerator.generate(graph)

    /**
     * Generates a Markdown document embedding the graph in a ` ```mermaid ` fenced block,
     * ready to commit as a rendered diagram.
     */
    public fun generateMermaidMarkdown(): String =
        MermaidGraphGenerator.generateMarkdown(graph, projectName)

    /**
     * Computes temporal (change) coupling for this graph from a window of recent commits.
     *
     * The [commits] come from `git log`, read by the Gradle plugin at execution time; this facade
     * keeps all git I/O out of the report module. Returns [TemporalCouplingReport.EMPTY] when there
     * is no history to analyse. Format the result with [temporalMarkdown] / [temporalJson].
     *
     * @param minSharedCommits Pairs sharing fewer commits than this are dropped as noise.
     * @param hiddenCouplingThreshold Undeclared pairs at or above this coupling degree are flagged
     *   as hidden coupling.
     */
    public fun analyzeTemporal(
        commits: List<CommitChange>,
        minSharedCommits: Int = TemporalCouplingAnalyzer.DEFAULT_MIN_SHARED_COMMITS,
        hiddenCouplingThreshold: Double = TemporalCouplingAnalyzer.DEFAULT_HIDDEN_COUPLING_THRESHOLD,
    ): TemporalCouplingReport =
        TemporalCouplingAnalyzer.analyze(graph, commits, minSharedCommits, hiddenCouplingThreshold)

    /** Renders a [TemporalCouplingReport] as a reviewable Markdown document. */
    public fun temporalMarkdown(report: TemporalCouplingReport): String =
        TemporalReportGenerator.markdown(report, projectName)

    /** Renders a [TemporalCouplingReport] as machine-readable JSON. */
    public fun temporalJson(report: TemporalCouplingReport): String =
        TemporalReportGenerator.json(report)
}