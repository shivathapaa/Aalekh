package com.aalekh.aalekh.report.json

import com.aalekh.aalekh.analysis.graph.GraphSummary
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Violation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public object JsonReporter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    public fun generate(
        graph: ModuleDependencyGraph,
        summary: GraphSummary,
        violations: List<Violation> = emptyList(),
    ): String = json.encodeToString(graph)
}