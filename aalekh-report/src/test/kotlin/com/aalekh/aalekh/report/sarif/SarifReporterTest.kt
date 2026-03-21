package com.aalekh.aalekh.report.sarif

import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.DependencyEdge
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleNode
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SarifReporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun node(path: String, buildFile: String? = null) = ModuleNode(
        path = path,
        name = path.substringAfterLast(":"),
        type = ModuleType.ANDROID_LIBRARY,
        buildFilePath = buildFile,
    )

    private fun sampleGraph() = ModuleDependencyGraph(
        projectName = "sarif-test",
        modules = listOf(
            node(":feature:login", "feature/login/build.gradle.kts"),
            node(":core:domain"),
        ),
        edges = listOf(DependencyEdge(":feature:login", ":core:domain", "implementation")),
        metadata = mapOf("aalekhVersion" to "0.2.0"),
    )

    private fun violation(
        ruleId: String = "layer-dependency",
        severity: Severity = Severity.ERROR,
        moduleHint: String? = ":feature:login",
    ) = Violation(
        ruleId = ruleId,
        severity = severity,
        message = "Test violation message",
        source = ":feature:login → :core:domain",
        moduleHint = moduleHint,
        plainLanguageExplanation = "Plain explanation.",
    )

    private fun resultWith(vararg violations: Violation) =
        RuleEngineResult(violations = violations.toList(), rulesEvaluated = 1)

    private fun parse(sarif: String): JsonObject = json.parseToJsonElement(sarif).jsonObject

    // Schema structure

    @Test
    fun `output is valid JSON`() {
        val sarif = SarifReporter.generate(sampleGraph(), resultWith())
        parse(sarif) // must not throw
    }

    @Test
    fun `schema version is 2_1_0`() {
        val root = parse(SarifReporter.generate(sampleGraph(), resultWith()))
        assertEquals("2.1.0", root["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `has runs array with one entry`() {
        val runs = parse(SarifReporter.generate(sampleGraph(), resultWith()))["runs"]?.jsonArray
        assertEquals(1, runs?.size)
    }

    @Test
    fun `tool driver name is Aalekh`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith()))
            .run()
        val name =
            run["tool"]?.jsonObject?.get("driver")?.jsonObject?.get("name")?.jsonPrimitive?.content
        assertEquals("Aalekh", name)
    }

    @Test
    fun `tool driver version matches graph metadata`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith())).run()
        val version = run["tool"]?.jsonObject?.get("driver")?.jsonObject
            ?.get("version")?.jsonPrimitive?.content
        assertEquals("0.2.0", version)
    }

    // Empty violations

    @Test
    fun `empty violations produces empty results array`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith())).run()
        val results = run["results"]?.jsonArray
        assertTrue(results?.isEmpty() ?: true, "Results should be empty when no violations")
    }

    // Violation mapping

    @Test
    fun `one violation produces one result`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith(violation()))).run()
        assertEquals(1, run["results"]?.jsonArray?.size)
    }

    @Test
    fun `result ruleId matches violation ruleId`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith(violation()))).run()
        val ruleId =
            run["results"]?.jsonArray?.get(0)?.jsonObject?.get("ruleId")?.jsonPrimitive?.content
        assertEquals("layer-dependency", ruleId)
    }

    @Test
    fun `ERROR severity maps to SARIF error level`() {
        val run = parse(
            SarifReporter.generate(
                sampleGraph(), resultWith(violation(severity = Severity.ERROR))
            )
        ).run()
        val level =
            run["results"]?.jsonArray?.get(0)?.jsonObject?.get("level")?.jsonPrimitive?.content
        assertEquals("error", level)
    }

    @Test
    fun `WARNING severity maps to SARIF warning level`() {
        val run = parse(
            SarifReporter.generate(
                sampleGraph(), resultWith(violation(severity = Severity.WARNING))
            )
        ).run()
        val level =
            run["results"]?.jsonArray?.get(0)?.jsonObject?.get("level")?.jsonPrimitive?.content
        assertEquals("warning", level)
    }

    @Test
    fun `INFO severity maps to SARIF note level`() {
        val run = parse(
            SarifReporter.generate(
                sampleGraph(), resultWith(violation(severity = Severity.INFO))
            )
        ).run()
        val level =
            run["results"]?.jsonArray?.get(0)?.jsonObject?.get("level")?.jsonPrimitive?.content
        assertEquals("note", level)
    }

    @Test
    fun `result message text matches violation message`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith(violation()))).run()
        val message = run["results"]?.jsonArray?.get(0)?.jsonObject
            ?.get("message")?.jsonObject?.get("text")?.jsonPrimitive?.content
        assertEquals("Test violation message", message)
    }

    // Location resolution

    @Test
    fun `location uses buildFilePath from graph when moduleHint resolves`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith(violation()))).run()
        val uri = run["results"]?.jsonArray?.get(0)?.jsonObject
            ?.get("locations")?.jsonArray?.get(0)?.jsonObject
            ?.get("physicalLocation")?.jsonObject
            ?.get("artifactLocation")?.jsonObject
            ?.get("uri")?.jsonPrimitive?.content
        assertEquals("feature/login/build.gradle.kts", uri)
    }

    @Test
    fun `location falls back to conventional path when buildFilePath is null`() {
        val graphWithoutBuildFile = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(node(":feature:checkout")), // no buildFilePath
            edges = emptyList(),
        )
        val v = violation(moduleHint = ":feature:checkout")
        val run = parse(SarifReporter.generate(graphWithoutBuildFile, resultWith(v))).run()
        val uri = run["results"]?.jsonArray?.get(0)?.jsonObject
            ?.get("locations")?.jsonArray?.get(0)?.jsonObject
            ?.get("physicalLocation")?.jsonObject
            ?.get("artifactLocation")?.jsonObject
            ?.get("uri")?.jsonPrimitive?.content
        // Conventional fallback: :feature:checkout → feature/checkout/build.gradle.kts
        assertEquals("feature/checkout/build.gradle.kts", uri)
    }

    @Test
    fun `uriBaseId is SRCROOT`() {
        val run = parse(SarifReporter.generate(sampleGraph(), resultWith(violation()))).run()
        val uriBaseId = run["results"]?.jsonArray?.get(0)?.jsonObject
            ?.get("locations")?.jsonArray?.get(0)?.jsonObject
            ?.get("physicalLocation")?.jsonObject
            ?.get("artifactLocation")?.jsonObject
            ?.get("uriBaseId")?.jsonPrimitive?.content
        assertEquals("%SRCROOT%", uriBaseId)
    }

    // Rules section

    @Test
    fun `rules section contains entry for each unique ruleId`() {
        val result = resultWith(
            violation(ruleId = "layer-dependency"),
            violation(ruleId = "no-feature-to-feature"),
        )
        val run = parse(SarifReporter.generate(sampleGraph(), result)).run()
        val rules = run["tool"]?.jsonObject?.get("driver")?.jsonObject?.get("rules")?.jsonArray
        assertEquals(2, rules?.size)
    }

    @Test
    fun `duplicate ruleIds produce one rules entry not two`() {
        val result = resultWith(
            violation(ruleId = "layer-dependency"),
            violation(ruleId = "layer-dependency"),
        )
        val run = parse(SarifReporter.generate(sampleGraph(), result)).run()
        val rules = run["tool"]?.jsonObject?.get("driver")?.jsonObject?.get("rules")?.jsonArray
        assertEquals(1, rules?.size)
    }

    // Special characters

    @Test
    fun `violation message with quotes is properly escaped`() {
        val v = violation().copy(message = """He said "fix this" immediately""")
        val sarif = SarifReporter.generate(sampleGraph(), resultWith(v))
        assertFalse(
            sarif.contains("""He said "fix this""""),
            "Raw unescaped quotes must not appear"
        )
        parse(sarif) // must remain valid JSON after escaping
    }

    // Helper

    private fun JsonObject.run(): JsonObject =
        this["runs"]?.jsonArray?.get(0)?.jsonObject
            ?: error("No runs array in SARIF output")
}