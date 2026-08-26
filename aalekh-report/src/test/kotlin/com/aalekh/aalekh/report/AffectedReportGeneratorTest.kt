package com.aalekh.aalekh.report

import com.aalekh.aalekh.model.AffectedModules
import com.aalekh.aalekh.report.affected.AffectedJsonReport
import com.aalekh.aalekh.report.affected.AffectedReportGenerator
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AffectedReportGeneratorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val affected = AffectedModules(
        totalModules = 5,
        changed = listOf(":core:domain"),
        affected = listOf(":app", ":core:data", ":core:domain"),
    )

    @Test
    fun `markdown reports the counts and percentage`() {
        val md = AffectedReportGenerator.markdown(affected, "origin/main", "HEAD", "demo")
        assertTrue(md.contains("## Aalekh - affected modules"))
        assertTrue(md.contains("`origin/main...HEAD`"))
        assertTrue(md.contains("3 of 5 modules affected"))
        assertTrue(md.contains("60%"))
        assertTrue(md.contains("1 changed, 2 downstream"))
    }

    @Test
    fun `markdown lists changed and affected modules`() {
        val md = AffectedReportGenerator.markdown(affected, "origin/main", "HEAD", "demo")
        assertTrue(md.contains("### Changed"))
        assertTrue(md.contains("### Affected"))
        assertTrue(md.contains("`:core:domain`"))
        assertTrue(md.contains("`:app`"))
    }

    @Test
    fun `an empty diff renders a clear note`() {
        val md = AffectedReportGenerator.markdown(AffectedModules.none(5), "HEAD~1", "", "demo")
        assertTrue(md.contains("No module sources changed"))
        assertTrue(!md.contains("### Changed"))
    }

    @Test
    fun `json round-trips with the diff range`() {
        val encoded = AffectedReportGenerator.json(affected, "origin/main", "HEAD")
        val decoded = json.decodeFromString(AffectedJsonReport.serializer(), encoded)
        assertEquals("origin/main", decoded.baseRef)
        assertEquals("HEAD", decoded.headRef)
        assertEquals(affected, decoded.affected)
    }
}
