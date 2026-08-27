package com.aalekh.aalekh.report.codeclimate

import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeClimateReporterTest {

    private val graph = ModuleDependencyGraph(
        projectName = "cc",
        modules = listOf(
            ModuleNode(":a", "a", ModuleType.JVM_LIBRARY, buildFilePath = "a/build.gradle.kts"),
            ModuleNode(":b", "b", ModuleType.JVM_LIBRARY),
        ),
        edges = listOf(DependencyEdge(":a", ":b", "implementation")),
    )

    private fun result(vararg violations: Violation) = RuleEngineResult(violations.toList(), rulesEvaluated = 1)

    @Test
    fun `emits a code climate issue with the build file path and mapped severity`() {
        val json = CodeClimateReporter.generate(
            graph,
            result(
                Violation(
                    ruleId = "layer-dependency",
                    severity = Severity.ERROR,
                    message = ":a must not depend on :b",
                    source = ":a → :b",
                    moduleHint = ":a",
                ),
            ),
        )
        assertTrue(json.contains("\"check_name\": \"layer-dependency\""))
        assertTrue(json.contains("\"severity\": \"critical\""))
        assertTrue(json.contains("\"path\": \"a/build.gradle.kts\""))
        assertTrue(json.contains("\"begin\": 1"))
        assertTrue(json.contains("\"fingerprint\""))
    }

    @Test
    fun `maps warning and info severities`() {
        val json = CodeClimateReporter.generate(
            graph,
            result(
                Violation("r1", Severity.WARNING, "w", ":a", ":a"),
                Violation("r2", Severity.INFO, "i", ":a", ":a"),
            ),
        )
        assertTrue(json.contains("\"severity\": \"minor\""))
        assertTrue(json.contains("\"severity\": \"info\""))
    }

    @Test
    fun `fingerprints are stable and distinct per violation`() {
        val v1 = Violation("r", Severity.ERROR, "message one", ":a → :b", ":a")
        val v2 = Violation("r", Severity.ERROR, "message two", ":a → :b", ":a")
        val first = CodeClimateReporter.generate(graph, result(v1))
        val firstAgain = CodeClimateReporter.generate(graph, result(v1))
        assertEquals(first, firstAgain, "same violation must yield the same fingerprint")
        val two = CodeClimateReporter.generate(graph, result(v1, v2))
        // two distinct messages -> two distinct fingerprints
        val fingerprints = Regex("\"fingerprint\": \"([0-9a-f]+)\"").findAll(two).map { it.groupValues[1] }.toList()
        assertEquals(2, fingerprints.toSet().size)
    }

    @Test
    fun `no violations yields an empty json array`() {
        assertEquals("[]", CodeClimateReporter.generate(graph, result()).trim())
    }
}
