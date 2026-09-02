package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.graph.PresentationProfile
import com.aalekh.aalekh.analysis.graph.RegionAnalyzer
import com.aalekh.aalekh.analysis.metrics.GraphMetrics
import com.aalekh.aalekh.analysis.narrative.NarrativeContext
import com.aalekh.aalekh.analysis.narrative.NarrativeEngine
import com.aalekh.aalekh.analysis.rules.LayerSpec
import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.analysis.spi.ExtensionEngine
import com.aalekh.aalekh.analysis.spi.ExtensionResult
import com.aalekh.aalekh.analysis.temporal.CommitChange
import com.aalekh.aalekh.analysis.temporal.TemporalCouplingAnalyzer
import com.aalekh.aalekh.model.CoChange
import com.aalekh.aalekh.model.ModuleChurn
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleMainSequence
import com.aalekh.aalekh.model.NarrativeReport
import com.aalekh.aalekh.model.TemporalCouplingReport
import com.aalekh.aalekh.report.docs.DocsGenerator
import com.aalekh.aalekh.report.docs.DocsInput
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
// One function per output format is the whole point of a facade; the count tracks how many formats
// Aalekh writes, not how many jobs this class does. Splitting it would only move the same call sites
// somewhere else and force every task to know which sub-facade to reach for.
@Suppress("TooManyFunctions")
public class ReportCoordinator(
    private val graph: ModuleDependencyGraph,
    private val ruleResult: RuleEngineResult,
    private val projectName: String,
    /**
     * Contributions from third-party `FindingProvider`s and `ModuleClassifier`s on the plugin
     * classpath. Defaults to none, so a consumer with no extension jar is unaffected.
     */
    private val extensions: ExtensionResult = ExtensionResult.EMPTY,
) {
    private val summary = GraphAnalyzer.summary(graph)

    /**
     * Structural metrics for the whole graph, computed once and shared by every output.
     *
     * Blast radius, influence, betweenness, and the rest are expensive enough that recomputing them
     * per report format would show on a large project, and they must agree across formats anyway.
     */
    private val metrics = GraphMetrics.compute(graph)

    /**
     * Generates the self-contained HTML report. Returns the complete HTML string.
     *
     * @param trendJson JSON array string of historical trend entries to embed in the report.
     *   Defaults to `"[]"` when no trend history is available.
     * @param teamOwners Map of team name to the module path glob patterns it owns, from the
     *   `teams { }` DSL. Drives the ownership colour overlay; empty (the default) disables it.
     * @param layers The layers declared in the `layers { }` DSL, in declaration order. Groups the
     *   Architecture swimlane and the layer purity table by what the build enforces; empty (the
     *   default) makes the report fall back to inferring layers from module paths and say so.
     */
    // The parameters are the report's independent data channels, each supplied by a different task
    // (trend history, teams, main sequence, temporal, layers). Bundling them into a holder would add
    // indirection at every call site for no gain - the same trade-off HtmlReportGenerator.generate
    // documents, and this facade must mirror its signature.
    @Suppress("LongParameterList")
    public fun generateHtml(
        trendJson: String = "[]",
        teamOwners: Map<String, List<String>> = emptyMap(),
        mainSequence: List<ModuleMainSequence> = emptyList(),
        hiddenCoupling: List<CoChange> = emptyList(),
        churn: List<ModuleChurn> = emptyList(),
        layers: List<LayerSpec> = emptyList(),
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
            layers = layers,
            metrics = metrics,
            narrative = narrative(teamOwners, hiddenCoupling, churn, layers),
            regions = RegionAnalyzer.analyze(graph, layers, teamOwners),
            profile = PresentationProfile.of(graph),
        )

    /**
     * Builds the plain-language narrative for this graph.
     *
     * Everything it reads is already available here, so the narrative is never a separate analysis
     * pass - it is the same numbers the rest of the report shows, phrased as sentences. Findings that
     * need a task the consumer has not run (churn, hidden coupling) simply do not appear.
     */
    public fun narrative(
        teamOwners: Map<String, List<String>> = emptyMap(),
        hiddenCoupling: List<CoChange> = emptyList(),
        churn: List<ModuleChurn> = emptyList(),
        layers: List<LayerSpec> = emptyList(),
    ): NarrativeReport = NarrativeEngine.analyze(
        NarrativeContext(
            graph = graph,
            metrics = metrics,
            summary = summary,
            violations = ruleResult.violations,
            layers = layers,
            teams = teamOwners,
            churn = churn,
            hiddenCoupling = hiddenCoupling,
            inventory = graph.buildInventory,
            extensions = extensions,
        )
    )

    /** Failures from third-party extensions, for the task to log. Empty when everything ran. */
    public val extensionFailures: List<String> get() = extensions.failures

    public companion object {
        /**
         * Builds a coordinator with third-party extensions discovered from [classLoader].
         *
         * Discovery is fail-silent: a broken extension is reported in [extensionFailures] and the
         * report is generated without it.
         */
        public fun withExtensions(
            graph: ModuleDependencyGraph,
            ruleResult: RuleEngineResult,
            projectName: String,
            classLoader: ClassLoader,
        ): ReportCoordinator = ReportCoordinator(
            graph = graph,
            ruleResult = ruleResult,
            projectName = projectName,
            extensions = ExtensionEngine.loadAndRun(graph, classLoader),
        )
    }

    /**
     * Generates the Markdown documentation set, keyed by file name.
     *
     * The same analysis the HTML report renders, written for reading and reviewing instead of
     * exploring: it renders on GitHub with no build step and diffs line by line in a pull request.
     * Deterministic, so re-running on an unchanged project produces identical bytes and a diff always
     * means the architecture moved.
     */
    public fun generateDocs(
        teamOwners: Map<String, List<String>> = emptyMap(),
        hiddenCoupling: List<CoChange> = emptyList(),
        churn: List<ModuleChurn> = emptyList(),
        layers: List<LayerSpec> = emptyList(),
    ): Map<String, String> = DocsGenerator.generate(
        DocsInput(
            graph = graph,
            summary = summary,
            metrics = metrics,
            narrative = narrative(teamOwners, hiddenCoupling, churn, layers),
            regions = RegionAnalyzer.analyze(graph, layers, teamOwners),
            inventory = graph.buildInventory,
        )
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