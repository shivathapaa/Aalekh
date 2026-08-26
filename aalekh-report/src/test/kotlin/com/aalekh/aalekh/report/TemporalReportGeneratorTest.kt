package com.aalekh.aalekh.report

import com.aalekh.aalekh.model.CoChange
import com.aalekh.aalekh.model.DeclaredEdgeRef
import com.aalekh.aalekh.model.ModuleChurn
import com.aalekh.aalekh.model.TemporalCouplingReport
import com.aalekh.aalekh.report.temporal.TemporalReportGenerator
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemporalReportGeneratorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val report = TemporalCouplingReport(
        commitsAnalyzed = 42,
        churn = listOf(ModuleChurn(":core:domain", 7), ModuleChurn(":core:data", 4)),
        coChanges = listOf(
            CoChange(":core:data", ":core:domain", sharedCommits = 3, degree = 0.75, declared = true),
            CoChange(":core:domain", ":core:util", sharedCommits = 2, degree = 1.0, declared = false),
        ),
        hiddenCoupling = listOf(
            CoChange(":core:domain", ":core:util", sharedCommits = 2, degree = 1.0, declared = false),
        ),
        deadStructure = listOf(DeclaredEdgeRef(":feature:login", ":core:data")),
    )

    @Test
    fun `markdown includes each section and the analysed commit count`() {
        val md = TemporalReportGenerator.markdown(report, "demo")
        assertTrue(md.contains("# demo - temporal coupling"))
        assertTrue(md.contains("last 42 commit"))
        assertTrue(md.contains("## Hidden coupling"))
        assertTrue(md.contains("## Dead structure"))
        assertTrue(md.contains("## Change hotspots"))
        assertTrue(md.contains("## Top co-changing pairs"))
    }

    @Test
    fun `markdown renders the hidden-coupling pair and dead edge`() {
        val md = TemporalReportGenerator.markdown(report, "demo")
        assertTrue(md.contains("`:core:domain` | `:core:util`"), "hidden coupling pair must be listed")
        assertTrue(md.contains("`:feature:login` | `:core:data`"), "dead-structure edge must be listed")
    }

    @Test
    fun `degree is formatted with two decimals`() {
        val md = TemporalReportGenerator.markdown(report, "demo")
        assertTrue(md.contains("0.75"), "degree must be shown as a fixed two-decimal value")
        assertTrue(md.contains("1.00"))
    }

    @Test
    fun `an empty report renders a clear note and no tables`() {
        val md = TemporalReportGenerator.markdown(TemporalCouplingReport.EMPTY, "demo")
        assertTrue(md.contains("No git history"))
        assertTrue(!md.contains("## Change hotspots"))
    }

    @Test
    fun `json round-trips through the model`() {
        val encoded = TemporalReportGenerator.json(report)
        val decoded = json.decodeFromString(TemporalCouplingReport.serializer(), encoded)
        assertEquals(report, decoded)
    }
}
