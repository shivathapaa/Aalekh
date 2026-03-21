package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.html.HtmlReportGenerator
import com.aalekh.aalekh.report.json.JsonReporter
import com.aalekh.aalekh.report.junit.JUnitXmlWriter
import com.aalekh.aalekh.report.sarif.SarifReporter

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

    /** Generates the self-contained HTML report. Returns the complete HTML string. */
    public fun generateHtml(): String =
        HtmlReportGenerator.generate(
            projectName = projectName,
            graph = graph,
            summary = summary,
            violations = ruleResult.violations,
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
}