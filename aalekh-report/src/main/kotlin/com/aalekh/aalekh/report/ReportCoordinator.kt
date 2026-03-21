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
 * task code thin and the report logic testable without Gradle on the classpath.
 *
 * @param graph         The extracted module dependency graph
 * @param ruleResult    Results from the rule engine
 * @param projectName   Root project name, used in report titles
 */
public class ReportCoordinator(
    private val graph: ModuleDependencyGraph,
    private val ruleResult: RuleEngineResult,
    private val projectName: String,
) {
    private val summary = GraphAnalyzer.summary(graph)

    /** Generates the self-contained HTML report. Returns the full HTML string. */
    public fun generateHtml(): String =
        HtmlReportGenerator.generate(
            projectName = projectName,
            graph = graph,
            summary = summary,
            violations = ruleResult.violations,
        )

    /** Generates the JUnit XML report for CI systems. */
    public fun generateJUnitXml(): String =
        JUnitXmlWriter.generate(
            projectName = projectName,
            result = ruleResult,
        )

    /** Generates the machine-readable JSON report envelope. */
    public fun generateJson(): String =
        JsonReporter.generate(
            graph = graph,
            summary = summary,
            violations = ruleResult.violations,
        )

    /** Generates the SARIF report for GitHub code scanning PR annotations. */
    public fun generateSarif(): String =
        SarifReporter.generate(
            graph = graph,
            result = ruleResult,
        )
}