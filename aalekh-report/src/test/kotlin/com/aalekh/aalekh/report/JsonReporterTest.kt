package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import com.aalekh.aalekh.report.json.AalekhJsonReport
import com.aalekh.aalekh.report.json.JsonReporter
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that [JsonReporter] produces the full [AalekhJsonReport] envelope
 * rather than just the raw graph.
 *
 * These tests exist specifically to prevent the regression where `generate()`
 * silently discarded `summary` and `violations` and returned `json.encodeToString(graph)`.
 */
class JsonReporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleGraph() = ModuleDependencyGraph(
        projectName = "json-test",
        modules = listOf(
            ModuleNode(":app", "app", ModuleType.ANDROID_APP),
            ModuleNode(":core:domain", "domain", ModuleType.JVM_LIBRARY),
        ),
        edges = listOf(
            DependencyEdge(":app", ":core:domain", "implementation"),
        ),
        metadata = mapOf("gradleVersion" to "9.4"),
    )

    private fun sampleViolations() = listOf(
        Violation(
            ruleId = "test-rule",
            severity = Severity.ERROR,
            message = "Test violation",
            source = ":app",
        )
    )

    // Envelope structure

    @Test
    fun `output is valid JSON`() {
        val graph = sampleGraph()
        val output = JsonReporter.generate(graph, GraphAnalyzer.summary(graph))
        // Must not throw
        val parsed = json.decodeFromString<AalekhJsonReport>(output)
        assertNotNull(parsed)
    }

    @Test
    fun `output contains graph field`() {
        val graph = sampleGraph()
        val output = JsonReporter.generate(graph, GraphAnalyzer.summary(graph))
        val report = json.decodeFromString<AalekhJsonReport>(output)
        assertEquals("json-test", report.graph.projectName)
        assertEquals(2, report.graph.modules.size)
        assertEquals(1, report.graph.edges.size)
    }

    @Test
    fun `output contains summary field with correct counts`() {
        val graph = sampleGraph()
        val summary = GraphAnalyzer.summary(graph)
        val report = json.decodeFromString<AalekhJsonReport>(
            JsonReporter.generate(graph, summary)
        )
        assertEquals(summary.totalModules, report.summary.totalModules)
        assertEquals(summary.totalEdges, report.summary.totalEdges)
        assertFalse(report.summary.hasCycles)
    }

    @Test
    fun `output contains violations field`() {
        val graph = sampleGraph()
        val violations = sampleViolations()
        val report = json.decodeFromString<AalekhJsonReport>(
            JsonReporter.generate(graph, GraphAnalyzer.summary(graph), violations)
        )
        assertEquals(1, report.violations.size)
        assertEquals("test-rule", report.violations[0].ruleId)
        assertEquals(Severity.ERROR, report.violations[0].severity)
    }

    @Test
    fun `violations default to empty list when not provided`() {
        val graph = sampleGraph()
        val report = json.decodeFromString<AalekhJsonReport>(
            JsonReporter.generate(graph, GraphAnalyzer.summary(graph))
        )
        assertTrue(report.violations.isEmpty())
    }

    @Test
    fun `output contains generatedAt timestamp`() {
        val graph = sampleGraph()
        val output = JsonReporter.generate(graph, GraphAnalyzer.summary(graph))
        val report = json.decodeFromString<AalekhJsonReport>(output)
        // ISO-8601 format: starts with a 4-digit year
        assertTrue(
            report.generatedAt.matches(Regex("\\d{4}-.*")),
            "generatedAt should be an ISO-8601 timestamp, got: ${report.generatedAt}"
        )
    }

    @Test
    fun `output contains aalekhVersion`() {
        val graph = sampleGraph()
        val output = JsonReporter.generate(graph, GraphAnalyzer.summary(graph))
        val report = json.decodeFromString<AalekhJsonReport>(output)
        assertTrue(
            report.aalekhVersion.isNotBlank(),
            "aalekhVersion must not be blank"
        )
    }

    // ── Regression guard ──────────────────────────────────────────────────

    @Test
    fun `output is NOT just the raw graph (regression guard)`() {
        // Before the fix, generate() returned json.encodeToString(graph) directly.
        // That output has "projectName" at the root level.
        // The envelope has "graph", "summary", "violations" at root level - NOT "projectName".
        val graph = sampleGraph()
        val output = JsonReporter.generate(graph, GraphAnalyzer.summary(graph))

        // The root object must have "graph" key, not "projectName"
        assertTrue(
            output.trimStart().contains("\"graph\""),
            "Root of JSON output must be an envelope with a 'graph' key, not the raw graph. " +
                    "Output starts with: ${output.take(120)}"
        )
        // "projectName" must be nested, not at root
        val rootKeys = output.substringAfter("{").substringBefore("\"graph\"")
        assertFalse(
            rootKeys.contains("\"projectName\""),
            "'projectName' must be inside the 'graph' object, not at the JSON root"
        )
    }

    @Test
    fun `output is pretty-printed`() {
        val graph = sampleGraph()
        val output = JsonReporter.generate(graph, GraphAnalyzer.summary(graph))
        // Pretty-printed JSON has newlines
        assertTrue(output.contains('\n'), "JSON output should be pretty-printed")
    }

    // Multiple violations

    @Test
    fun `all violations are present in output`() {
        val graph = sampleGraph()
        val violations = listOf(
            Violation("rule-a", Severity.ERROR, "Error message", ":app"),
            Violation("rule-b", Severity.WARNING, "Warning message", ":core:domain"),
            Violation("rule-c", Severity.INFO, "Info message", ":app"),
        )
        val report = json.decodeFromString<AalekhJsonReport>(
            JsonReporter.generate(graph, GraphAnalyzer.summary(graph), violations)
        )
        assertEquals(3, report.violations.size)
        assertEquals("rule-a", report.violations[0].ruleId)
        assertEquals("rule-b", report.violations[1].ruleId)
        assertEquals("rule-c", report.violations[2].ruleId)
    }
}