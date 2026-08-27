package com.aalekh.aalekh.analysis.spi

import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomMetricEngineTest {

    private fun node(path: String) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.JVM_LIBRARY,
    )

    private fun edge(from: String, to: String, config: String = "implementation") =
        DependencyEdge(from = from, to = to, configuration = config)

    private val graph = ModuleDependencyGraph(
        projectName = "m",
        modules = listOf(node(":a"), node(":b")),
        edges = listOf(edge(":a", ":b")),
    )

    private fun provider(
        metricId: String,
        contribution: MetricContribution = MetricContribution(systemValue = 1.0),
    ) = object : MetricProvider {
        override val id: String = metricId
        override val displayName: String = "Display $metricId"
        override val description: String = "desc $metricId"
        override val unit: String = "u"
        override fun compute(graph: ModuleDependencyGraph): MetricContribution = contribution
    }

    @Test
    fun `a well-behaved provider becomes a metric`() {
        val report = CustomMetricEngine.compute(
            graph,
            listOf(provider("leaf-ratio", MetricContribution(systemValue = 42.0, moduleValues = mapOf(":a" to 3.0)))),
        )
        assertEquals(1, report.metrics.size)
        val metric = report.metrics.single()
        assertEquals("leaf-ratio", metric.providerId)
        assertEquals("Display leaf-ratio", metric.displayName)
        assertEquals("desc leaf-ratio", metric.description)
        assertEquals("u", metric.unit)
        assertEquals(42.0, metric.systemValue)
        assertEquals(mapOf(":a" to 3.0), metric.moduleValues)
        assertTrue(report.providerFailures.isEmpty())
    }

    @Test
    fun `a provider that throws is skipped and reported`() {
        val throwing = object : MetricProvider {
            override val id: String = "boom"
            override val displayName: String = "Boom"
            override fun compute(graph: ModuleDependencyGraph): MetricContribution =
                error("provider blew up")
        }
        val report = CustomMetricEngine.compute(graph, listOf(throwing, provider("ok")))
        assertEquals(listOf("ok"), report.metrics.map { it.providerId })
        assertEquals(1, report.providerFailures.size)
        assertTrue(report.providerFailures.single().startsWith("boom:"))
    }

    @Test
    fun `a blank id is rejected`() {
        val report = CustomMetricEngine.compute(graph, listOf(provider("   ")))
        assertTrue(report.metrics.isEmpty())
        assertEquals(1, report.providerFailures.size)
        assertTrue(report.providerFailures.single().contains("blank metric id"))
    }

    @Test
    fun `a duplicate id keeps the first and reports the rest`() {
        val report = CustomMetricEngine.compute(
            graph,
            listOf(
                provider("dupe", MetricContribution(systemValue = 1.0)),
                provider("dupe", MetricContribution(systemValue = 2.0)),
            ),
        )
        assertEquals(1, report.metrics.size)
        assertEquals(1.0, report.metrics.single().systemValue)
        assertTrue(report.providerFailures.single().contains("duplicate metric id"))
    }

    @Test
    fun `ServiceLoader discovers a registered provider`() {
        val providers = CustomMetricEngine.load(javaClass.classLoader)
        assertTrue(
            providers.any { it.id == "test-edge-count" },
            "expected the META-INF/services-registered provider to be discovered",
        )
        val report = CustomMetricEngine.loadAndCompute(graph, javaClass.classLoader)
        val edgeCount = report.metrics.single { it.providerId == "test-edge-count" }
        assertEquals(graph.edges.size.toDouble(), edgeCount.systemValue)
    }
}

/**
 * Test-only provider registered through
 * `src/test/resources/META-INF/services/com.aalekh.aalekh.analysis.spi.MetricProvider`, exercising
 * real `ServiceLoader` discovery. Reports the number of edges in the graph.
 */
class EdgeCountMetricProvider : MetricProvider {
    override val id: String = "test-edge-count"
    override val displayName: String = "Edge count"
    override fun compute(graph: ModuleDependencyGraph): MetricContribution =
        MetricContribution(systemValue = graph.edges.size.toDouble())
}
