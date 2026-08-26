package com.aalekh.aalekh.analysis.baseline

import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViolationBaselineTest {

    private fun violation(
        ruleId: String,
        source: String,
        severity: Severity = Severity.ERROR,
    ) = Violation(ruleId = ruleId, severity = severity, message = "msg", source = source)

    @Test
    fun `fingerprint combines ruleId and source`() {
        assertEquals(
            "no-cyclic-dependencies|:a → :b",
            ViolationBaseline.fingerprint(violation("no-cyclic-dependencies", ":a → :b")),
        )
    }

    @Test
    fun `toFingerprints excludes INFO and is sorted and de-duplicated`() {
        val violations = listOf(
            violation("layer-dependency", ":b → :c"),
            violation("layer-dependency", ":a → :b"),
            violation("layer-dependency", ":a → :b"),                       // duplicate
            violation("test-cyclic-dependency", ":x → :y", Severity.INFO),  // excluded
        )
        val fingerprints = ViolationBaseline.toFingerprints(violations)
        assertEquals(
            listOf("layer-dependency|:a → :b", "layer-dependency|:b → :c"),
            fingerprints,
        )
    }

    @Test
    fun `apply suppresses known violations and keeps new ones`() {
        val known = violation("layer-dependency", ":a → :b")
        val fresh = violation("layer-dependency", ":c → :d")
        val result = ViolationBaseline.apply(
            listOf(known, fresh),
            setOf(ViolationBaseline.fingerprint(known)),
        )
        assertEquals(1, result.baselinedCount)
        assertEquals(listOf(fresh), result.newViolations)
    }

    @Test
    fun `apply never suppresses INFO violations even if fingerprint matches`() {
        val info = violation("test-cyclic-dependency", ":a → :b", Severity.INFO)
        val result = ViolationBaseline.apply(
            listOf(info),
            setOf(ViolationBaseline.fingerprint(info)),
        )
        assertEquals(0, result.baselinedCount)
        assertTrue(result.newViolations.contains(info))
    }

    @Test
    fun `empty baseline passes everything through`() {
        val violations = listOf(violation("a", ":x"), violation("b", ":y"))
        val result = ViolationBaseline.apply(violations, emptySet())
        assertEquals(0, result.baselinedCount)
        assertEquals(violations, result.newViolations)
    }

    @Test
    fun `a brand-new violation is not baselined`() {
        val result = ViolationBaseline.apply(
            listOf(violation("layer-dependency", ":new → :edge")),
            setOf("layer-dependency|:old → :edge"),
        )
        assertEquals(0, result.baselinedCount)
        assertFalse(result.newViolations.isEmpty())
    }
}
