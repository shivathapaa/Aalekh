package com.aalekh.aalekh.report.custommetrics

import com.aalekh.aalekh.model.CustomMetric
import com.aalekh.aalekh.model.CustomMetricReport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomMetricReportGeneratorTest {

    @Test
    fun `empty report explains how to register a provider`() {
        val md = CustomMetricReportGenerator.markdown(CustomMetricReport.EMPTY, "demo")
        assertTrue(md.contains("No custom metric providers were discovered"))
        assertTrue(md.contains("META-INF/services/com.aalekh.aalekh.analysis.spi.MetricProvider"))
    }

    @Test
    fun `a system metric renders in the system table`() {
        val report = CustomMetricReport(
            metrics = listOf(
                CustomMetric(
                    providerId = "leaf-ratio",
                    displayName = "Leaf ratio",
                    description = "Share of leaf modules",
                    unit = "%",
                    systemValue = 42.5,
                ),
            ),
        )
        val md = CustomMetricReportGenerator.markdown(report, "demo")
        assertTrue(md.contains("## System metrics"))
        assertTrue(md.contains("Leaf ratio"))
        assertTrue(md.contains("42.50 %"), "expected the value formatted with its unit")
        assertFalse(md.contains("## Per-module metrics"), "no per-module data was supplied")
    }

    @Test
    fun `per-module metric ranks modules high-to-low and caps the table`() {
        val values = (1..30).associate { ":m$it" to it.toDouble() }
        val report = CustomMetricReport(
            metrics = listOf(
                CustomMetric(
                    providerId = "churn",
                    displayName = "Churn",
                    moduleValues = values,
                ),
            ),
        )
        val md = CustomMetricReportGenerator.markdown(report, "demo")
        assertTrue(md.contains("## Per-module metrics"))
        assertTrue(md.contains("### Churn"))
        val topIndex = md.indexOf(":m30")
        val lowerIndex = md.indexOf(":m29")
        assertTrue(topIndex in 0 until lowerIndex, "highest value must be listed first")
        assertTrue(md.contains("and 5 more"), "30 modules with a 25-row cap should note 5 hidden")
    }

    @Test
    fun `provider failures are surfaced as notes`() {
        val report = CustomMetricReport(
            metrics = emptyList(),
            providerFailures = listOf("boom: provider blew up"),
        )
        val md = CustomMetricReportGenerator.markdown(report, "demo")
        assertTrue(md.contains("## Provider notes"))
        assertTrue(md.contains("boom: provider blew up"))
    }

    @Test
    fun `whole numbers render without decimals`() {
        val report = CustomMetricReport(
            metrics = listOf(CustomMetric("count", "Count", systemValue = 7.0)),
        )
        val md = CustomMetricReportGenerator.markdown(report, "demo")
        assertTrue(md.contains("| Count | 7 |"), "an integral value should not show trailing decimals")
    }

    @Test
    fun `json round-trips the report`() {
        val report = CustomMetricReport(
            metrics = listOf(CustomMetric("count", "Count", systemValue = 7.0)),
        )
        val json = CustomMetricReportGenerator.json(report)
        assertTrue(json.contains("\"providerId\": \"count\""))
        assertTrue(json.contains("\"systemValue\": 7.0"))
    }
}
