package com.aalekh.aalekh.report

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvMetricsExporterTest {

    private fun node(path: String, healthScore: Int? = null) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
        healthScore = healthScore,
    )

    private fun edge(from: String, to: String) =
        DependencyEdge(from = from, to = to, configuration = "implementation")

    private fun sampleGraph() = ModuleDependencyGraph(
        projectName = "csv-test",
        modules = listOf(
            node(":core:domain", healthScore = 95),
            node(":core:data", healthScore = 72),
            node(":feature:login", healthScore = 61),
        ),
        edges = listOf(
            edge(":core:data", ":core:domain"),
            edge(":feature:login", ":core:domain"),
            edge(":feature:login", ":core:data"),
        ),
    )

    @Test
    fun `output starts with correct header row`() {
        val csv = CsvMetricsExporter.export(sampleGraph())
        val header = csv.lines().first()
        assertEquals(
            "timestamp,module,type,fanIn,fanOut,instability,transitiveDepCount,healthScore,isGodModule,isOnCriticalPath,hasCycle",
            header,
        )
    }

    @Test
    fun `produces one data row per module`() {
        val csv = CsvMetricsExporter.export(sampleGraph())
        val dataRows = csv.lines().drop(1).filter { it.isNotBlank() }
        assertEquals(3, dataRows.size)
    }

    @Test
    fun `rows are sorted by module path`() {
        val csv = CsvMetricsExporter.export(sampleGraph())
        val paths = csv.lines().drop(1).filter { it.isNotBlank() }
            .map { it.split(",")[1] }
        assertEquals(paths.sorted(), paths)
    }

    @Test
    fun `health score is included when present`() {
        val csv = CsvMetricsExporter.export(sampleGraph())
        assertTrue(csv.contains("95"), "Health score 95 missing from CSV")
        assertTrue(csv.contains("72"), "Health score 72 missing from CSV")
    }

    @Test
    fun `health score column is empty string when null`() {
        val graph = ModuleDependencyGraph(
            "test",
            listOf(node(":a", healthScore = null)),
            emptyList(),
        )
        val csv = CsvMetricsExporter.export(graph)
        val dataRow = csv.lines().drop(1).first { it.isNotBlank() }
        val columns = dataRow.split(",")
        assertEquals(11, columns.size)
        // healthScore column (index 7) should be empty
        assertEquals("", columns[7])
    }

    @Test
    fun `god module is flagged correctly`() {
        // Build a god module: fanIn>=5 AND fanOut>=5
        val sources = (1..5).map { node(":src$it") }
        val targets = (1..5).map { node(":tgt$it") }
        val god = node(":god")
        val edges = sources.map { edge(it.path, ":god") } + targets.map { edge(":god", it.path) }
        val graph = ModuleDependencyGraph("test", sources + targets + god, edges)

        val csv = CsvMetricsExporter.export(graph)
        val godRow = csv.lines().drop(1).first { it.contains(":god") }
        val columns = godRow.split(",")
        assertEquals("true", columns[8], "isGodModule column should be true")
    }

    @Test
    fun `cycle participation is flagged correctly`() {
        val graph = ModuleDependencyGraph(
            "test",
            listOf(node(":a"), node(":b")),
            listOf(edge(":a", ":b"), edge(":b", ":a")),
        )
        val csv = CsvMetricsExporter.export(graph)
        val rowA = csv.lines().drop(1).first { it.contains(",:a,") }
        assertEquals("true", rowA.split(",")[10], "hasCycle column should be true for :a")
    }

    @Test
    fun `no cycle flag on clean graph`() {
        val csv = CsvMetricsExporter.export(sampleGraph())
        csv.lines().drop(1).filter { it.isNotBlank() }.forEach { row ->
            assertEquals("false", row.split(",")[10], "No cycles in clean graph: $row")
        }
    }

    @Test
    fun `empty graph produces header only`() {
        val graph = ModuleDependencyGraph("empty", emptyList(), emptyList())
        val csv = CsvMetricsExporter.export(graph)
        val nonBlankLines = csv.lines().filter { it.isNotBlank() }
        assertEquals(1, nonBlankLines.size, "Empty graph should produce only the header row")
    }

    @Test
    fun `output is valid CSV - no unquoted commas in module paths`() {
        // Module paths contain colons but not commas, so splitting on comma should always
        // yield exactly 11 columns per data row
        val csv = CsvMetricsExporter.export(sampleGraph())
        csv.lines().drop(1).filter { it.isNotBlank() }.forEach { row ->
            assertEquals(11, row.split(",").size, "Row has wrong column count: $row")
        }
    }
}