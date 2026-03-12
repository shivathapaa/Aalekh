package com.aalekh.aalekh.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests that the model data classes serialize and deserialize correctly.
 * This is critical for configuration-cache compatibility and the intermediate graph.json file.
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

    @Test
    fun `ModuleDependencyGraph round-trips through JSON`() {
        val graph = sampleGraph()
        val serialized = json.encodeToString(graph)
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(serialized)

        assertEquals(graph.projectName, deserialized.projectName)
        assertEquals(graph.modules.size, deserialized.modules.size)
        assertEquals(graph.edges.size, deserialized.edges.size)
        assertEquals(graph.metadata, deserialized.metadata)
    }

    @Test
    fun `ModuleNode fields are preserved through serialization`() {
        val graph = sampleGraph()
        val serialized = json.encodeToString(graph)
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(serialized)

        val appNode = deserialized.modules.first { it.path == ":app" }
        assertEquals(ModuleType.ANDROID_APP, appNode.type)
        assertEquals("app", appNode.name)
    }

    @Test
    fun `DependencyEdge sourceSet is preserved through serialization`() {
        val graph = sampleGraph()
        val serialized = json.encodeToString(graph)
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(serialized)

        val edge = deserialized.edges.first()
        assertEquals("commonMain", edge.sourceSet)
    }

    @Test
    fun `ModuleType enum values serialize as their string names`() {
        val graph = sampleGraph()
        val serialized = json.encodeToString(graph)

        // The JSON should contain the enum name as a string, not an ordinal
        assert(serialized.contains("\"ANDROID_APP\"")) {
            "Expected ANDROID_APP in JSON but got: ${serialized.take(500)}"
        }
        assert(serialized.contains("\"KMP\"")) {
            "Expected KMP in JSON but got: ${serialized.take(500)}"
        }
    }

    @Test
    fun `deserialized graph supports moduleByPath lookup`() {
        val graph = sampleGraph()
        val serialized = json.encodeToString(graph)
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(serialized)

        assertNotNull(deserialized.moduleByPath(":app"))
        assertNotNull(deserialized.moduleByPath(":core:domain"))
    }

    @Test
    fun `empty graph serializes without errors`() {
        val empty = ModuleDependencyGraph(
            projectName = "empty",
            modules = emptyList(),
            edges = emptyList(),
        )
        val serialized = json.encodeToString(empty)
        val deserialized = json.decodeFromString<ModuleDependencyGraph>(serialized)
        assertEquals(0, deserialized.modules.size)
        assertEquals(0, deserialized.edges.size)
    }

    @Test
    fun `all ModuleType values can be serialized and deserialized`() {
        ModuleType.entries.forEach { type ->
            val node = ModuleNode(path = ":m", name = "m", type = type)
            val graph = ModuleDependencyGraph("test", listOf(node), emptyList())
            val round = json.decodeFromString<ModuleDependencyGraph>(json.encodeToString(graph))
            assertEquals(type, round.modules.first().type, "Failed round-trip for type $type")
        }
    }
}
