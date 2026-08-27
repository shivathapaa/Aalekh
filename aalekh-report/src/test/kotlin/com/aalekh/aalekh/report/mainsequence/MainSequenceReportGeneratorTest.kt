package com.aalekh.aalekh.report.mainsequence

import com.aalekh.aalekh.model.MainSequenceReport
import com.aalekh.aalekh.model.MainSequenceZone
import com.aalekh.aalekh.model.ModuleMainSequence
import kotlin.test.Test
import kotlin.test.assertTrue

class MainSequenceReportGeneratorTest {

    private fun report() = MainSequenceReport(
        modules = listOf(
            ModuleMainSequence(
                path = ":pain",
                instability = 0.0,
                abstractness = 0.0,
                distance = 1.0,
                zone = MainSequenceZone.ZONE_OF_PAIN,
                abstractTypes = 0,
                concreteTypes = 8,
            ),
            ModuleMainSequence(
                path = ":ok",
                instability = 1.0,
                abstractness = 0.0,
                distance = 0.0,
                zone = MainSequenceZone.MAIN_SEQUENCE,
                abstractTypes = 0,
                concreteTypes = 3,
            ),
        ),
        averageDistance = 0.5,
    )

    @Test
    fun `markdown renders the summary, zones and module table`() {
        val md = MainSequenceReportGenerator.markdown(report(), "demo")
        assertTrue(md.contains("# demo - main sequence"))
        assertTrue(md.contains("Average distance from the main sequence: 0.50"))
        assertTrue(md.contains("Zone of pain"))
        assertTrue(md.contains("`:pain`"))
        assertTrue(md.contains("| Module | Abstractness | Instability | Distance | Zone |"))
    }

    @Test
    fun `an empty report explains why`() {
        val md = MainSequenceReportGenerator.markdown(MainSequenceReport.EMPTY, "demo")
        assertTrue(md.contains("No countable type declarations"))
    }

    @Test
    fun `json carries the distance and module data`() {
        val json = MainSequenceReportGenerator.json(report())
        assertTrue(json.contains("\"averageDistance\""))
        assertTrue(json.contains("\":pain\""))
        assertTrue(json.contains("ZONE_OF_PAIN"))
    }
}
