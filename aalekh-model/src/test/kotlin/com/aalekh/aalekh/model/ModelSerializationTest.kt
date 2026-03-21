package com.aalekh.aalekh.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests that all model data classes round-trip correctly through JSON.
 * Critical for configuration-cache compatibility and the graph.json intermediate file.
 */
class ModelSerializationTest {

    private val json = Json { encodeDefaults = true; prettyPrint = false }

    private fun node(path: String, type: ModuleType = ModuleType.JVM_LIBRARY) =
        ModuleNode(path = path, name = path.substringAfterLast(":"), type = type)

    private fun sampleGraph() = ModuleDependencyGraph(
        projectName = "serialization-test",
        modules = listOf(
            node(":app", ModuleType.ANDROID_APP),
            node(":core:domain", ModuleType.KMP),
        ),
        edges = listOf(
            DependencyEdge(
                from = ":app",
                to = ":core:domain",
                configuration = "implementation",
                sourceSet = "commonMain",
            )
        ),
        metadata = mapOf("gradleVersion" to "9.0"),
    )

    // ModuleDependencyGraph

    @Test
    fun `ModuleDependencyGraph round-trips through JSON`() {
        val graph = sampleGraph()
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(graph))
        assertEquals(graph.projectName, deserialized.projectName)
        assertEquals(graph.modules.size, deserialized.modules.size)
        assertEquals(graph.edges.size, deserialized.edges.size)
        assertEquals(graph.metadata, deserialized.metadata)
    }

    @Test
    fun `deserialized graph supports moduleByPath lookup`() {
        val deserialized =
            json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(sampleGraph()))
        assertNotNull(deserialized.moduleByPath(":app"))
        assertNotNull(deserialized.moduleByPath(":core:domain"))
    }

    @Test
    fun `empty graph serializes without errors`() {
        val empty =
            ModuleDependencyGraph(projectName = "empty", modules = emptyList(), edges = emptyList())
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(empty))
        assertEquals(0, deserialized.modules.size)
    }

    // ModuleNode

    @Test
    fun `ModuleNode core fields round-trip`() {
        val deserialized =
            json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(sampleGraph()))
        val app = deserialized.modules.first { it.path == ":app" }
        assertEquals(ModuleType.ANDROID_APP, app.type)
        assertEquals("app", app.name)
    }

    @Test
    fun `ModuleNode buildFilePath is preserved when set`() {
        val graph = ModuleDependencyGraph(
            projectName = "test",
            modules = listOf(
                ModuleNode(
                    path = ":feature:login",
                    name = "login",
                    type = ModuleType.ANDROID_LIBRARY,
                    buildFilePath = "feature/login/build.gradle.kts",
                )
            ),
            edges = emptyList(),
        )
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(graph))
        assertEquals("feature/login/build.gradle.kts", deserialized.modules[0].buildFilePath)
    }

    @Test
    fun `ModuleNode buildFilePath defaults to null`() {
        val deserialized =
            json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(sampleGraph()))
        assertNull(deserialized.modules[0].buildFilePath)
    }

    @Test
    fun `all ModuleType values round-trip`() {
        ModuleType.entries.forEach { type ->
            val graph = ModuleDependencyGraph("t", listOf(node(":m", type)), emptyList())
            val round = json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(graph))
            assertEquals(type, round.modules.first().type)
        }
    }

    @Test
    fun `ModuleType serializes as string name not ordinal`() {
        val serialized = json.encodeToString(sampleGraph())
        assert(serialized.contains("\"ANDROID_APP\"")) { "Expected ANDROID_APP in: $serialized" }
        assert(serialized.contains("\"KMP\"")) { "Expected KMP in: $serialized" }
    }

    // DependencyEdge

    @Test
    fun `DependencyEdge sourceSet round-trips`() {
        val deserialized =
            json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(sampleGraph()))
        assertEquals("commonMain", deserialized.edges.first().sourceSet)
    }

    // Violation

    @Test
    fun `Violation core fields round-trip`() {
        val violation = Violation(
            ruleId = "test-rule",
            severity = Severity.ERROR,
            message = "Test message",
            source = ":a → :b",
        )
        val deserialized = json.decodeFromString<Violation>(json.encodeToString(violation))
        assertEquals("test-rule", deserialized.ruleId)
        assertEquals(Severity.ERROR, deserialized.severity)
        assertEquals(":a → :b", deserialized.source)
    }

    @Test
    fun `Violation moduleHint is preserved when set`() {
        val violation = Violation(
            ruleId = "layer-dependency",
            severity = Severity.ERROR,
            message = "msg",
            source = ":a → :b",
            moduleHint = ":a",
        )
        val deserialized = json.decodeFromString<Violation>(json.encodeToString(violation))
        assertEquals(":a", deserialized.moduleHint)
    }

    @Test
    fun `Violation moduleHint defaults to null`() {
        val violation =
            Violation(ruleId = "r", severity = Severity.WARNING, message = "m", source = "s")
        val deserialized = json.decodeFromString<Violation>(json.encodeToString(violation))
        assertNull(deserialized.moduleHint)
    }

    @Test
    fun `Violation plainLanguageExplanation round-trips`() {
        val violation = Violation(
            ruleId = "r",
            severity = Severity.ERROR,
            message = "m",
            source = "s",
            plainLanguageExplanation = "This is why the rule exists.",
        )
        val deserialized = json.decodeFromString<Violation>(json.encodeToString(violation))
        assertEquals("This is why the rule exists.", deserialized.plainLanguageExplanation)
    }

    @Test
    fun `Violation with all nullable fields omitted deserializes with null defaults`() {
        // Simulates reading a violation written by an older version of the plugin
        // that did not have moduleHint or plainLanguageExplanation.
        val legacyJson = """{"ruleId":"r","severity":"ERROR","message":"m","source":"s"}"""
        val deserialized = json.decodeFromString<Violation>(legacyJson)
        assertNull(deserialized.moduleHint)
        assertNull(deserialized.plainLanguageExplanation)
    }

    @Test
    fun `all Severity values round-trip`() {
        Severity.entries.forEach { s ->
            val v = Violation(ruleId = "r", severity = s, message = "m", source = "s")
            assertEquals(s, json.decodeFromString<Violation>(json.encodeToString(v)).severity)
        }
    }
}