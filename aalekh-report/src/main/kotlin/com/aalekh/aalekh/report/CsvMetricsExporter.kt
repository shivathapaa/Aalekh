package com.aalekh.aalekh.report

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.ModuleDependencyGraph
import java.time.Instant

/**
 * Exports per-module architecture metrics as CSV.
 *
 * The output is designed to be piped into external tools - spreadsheets, Datadog,
 * Grafana, or any time-series store. Aalekh's responsibility ends at writing the file.
 *
 * Columns: timestamp, module, type, fanIn, fanOut, instability, transitiveDepCount,
 *          healthScore, isGodModule, isOnCriticalPath, hasCycle
 */
public object CsvMetricsExporter {

    private const val GOD_FAN_IN = 5
    private const val GOD_FAN_OUT = 5

    public fun export(graph: ModuleDependencyGraph): String {
        val criticalPathSet = GraphAnalyzer.criticalPath(graph).toSet()
        val cycleNodeSet = GraphAnalyzer.findMainOnlyCycles(graph).flatten().toSet()
        val godModuleSet = graph.modules
            .filter { graph.fanIn(it.path) >= GOD_FAN_IN && graph.fanOut(it.path) >= GOD_FAN_OUT }
            .map { it.path }
            .toSet()

        val timestamp = Instant.now().toString()

        val sb = StringBuilder()
        sb.appendLine("timestamp,module,type,fanIn,fanOut,instability,transitiveDepCount,healthScore,isGodModule,isOnCriticalPath,hasCycle")

        graph.modules.sortedBy { it.path }.forEach { module ->
            sb.appendLine(
                listOf(
                    timestamp,
                    module.path,
                    module.type.name,
                    graph.fanIn(module.path),
                    graph.fanOut(module.path),
                    "%.4f".format(graph.instability(module.path)),
                    graph.transitiveCount(module.path),
                    module.healthScore ?: "",
                    module.path in godModuleSet,
                    module.path in criticalPathSet,
                    module.path in cycleNodeSet,
                ).joinToString(",")
            )
        }

        return sb.toString()
    }
}