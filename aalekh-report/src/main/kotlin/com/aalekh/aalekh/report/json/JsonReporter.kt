package com.aalekh.aalekh.report.json

import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.model.AalekhBuildConfig
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Violation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Generates the machine-readable JSON report written to
 * `build/reports/aalekh/aalekh-results.json`.
 *
 * ### Output format
 * A single [AalekhJsonReport] envelope containing the full graph, summary
 * statistics, and all violations. This allows CI tooling, dashboards, and
 * downstream scripts to consume a single file rather than parsing multiple
 * outputs.
 *
 * ### Why not just encode the graph directly?
 * The original implementation encoded only [ModuleDependencyGraph], silently
 * discarding `summary` and `violations` which are passed as parameters.
 * That made the JSON output incomplete and misleading for any tool that relied
 * on it to check for violations programmatically.
 */
public object JsonReporter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    public fun generate(
        graph: ModuleDependencyGraph,
        summary: GraphSummary,
        violations: List<Violation> = emptyList(),
    ): String = json.encodeToString(
        AalekhJsonReport(
            graph = graph,
            summary = summary,
            violations = violations,
            generatedAt = Instant.now().toString(),
            aalekhVersion = AalekhBuildConfig.VERSION,
        )
    )
}

/**
 * The top-level envelope for `aalekh-results.json`.
 *
 * All fields are serializable so the file is self-contained - a consumers
 * does not need to cross-reference the HTML report or JUnit XML to get
 * violation details, summary counts, or the full graph topology.
 */
@Serializable
public data class AalekhJsonReport(
    /** Complete module dependency graph - all nodes and edges. */
    val graph: ModuleDependencyGraph,

    /** Pre-computed graph statistics (cycles, fan-in/out, instability, etc.). */
    val summary: GraphSummary,

    /**
     * All violations found by the rule engine, ordered by severity
     * (ERROR first, then WARNING, then INFO).
     */
    val violations: List<Violation>,

    /** ISO-8601 timestamp of when this report was generated. */
    val generatedAt: String,

    /** Aalekh plugin version that produced this report. */
    val aalekhVersion: String,
)