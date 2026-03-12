package com.aalekh.aalekh.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyEdgeTest {

    @Test
    fun `isApi is true only for api configuration`() {
        assertTrue(DependencyEdge(":a", ":b", "api").isApi)
        assertFalse(DependencyEdge(":a", ":b", "implementation").isApi)
        assertFalse(DependencyEdge(":a", ":b", "testApi").isApi)
    }

    @Test
    fun `isTest detects all test configuration variants`() {
        assertTrue(DependencyEdge(":a", ":b", "testImplementation").isTest)
        assertTrue(DependencyEdge(":a", ":b", "androidTestImplementation").isTest)
        assertTrue(DependencyEdge(":a", ":b", "testRuntimeOnly").isTest)
        assertFalse(DependencyEdge(":a", ":b", "implementation").isTest)
        assertFalse(DependencyEdge(":a", ":b", "api").isTest)
    }

    @Test
    fun `isCompileOnly detects compileOnly configuration`() {
        assertTrue(DependencyEdge(":a", ":b", "compileOnly").isCompileOnly)
        assertFalse(DependencyEdge(":a", ":b", "implementation").isCompileOnly)
    }

    @Test
    fun `sourceSet is null by default`() {
        val edge = DependencyEdge(":a", ":b", "implementation")
        assertEquals(null, edge.sourceSet)
    }

    @Test
    fun `sourceSet captures KMP source set name`() {
        val edge = DependencyEdge(":a", ":b", "commonMainImplementation", sourceSet = "commonMain")
        assertEquals("commonMain", edge.sourceSet)
    }
}

class ModuleNodeTest {

    @Test
    fun `shortName returns last path segment`() {
        val node = ModuleNode(path = ":feature:login:ui", name = "ui", type = ModuleType.ANDROID_LIBRARY)
        assertEquals("ui", node.shortName)
    }

    @Test
    fun `shortName for root-level module`() {
        val node = ModuleNode(path = ":core", name = "core", type = ModuleType.JVM_LIBRARY)
        assertEquals("core", node.shortName)
    }

    @Test
    fun `sourceSets defaults to empty for non-KMP modules`() {
        val node = ModuleNode(path = ":lib", name = "lib", type = ModuleType.ANDROID_LIBRARY)
        assertTrue(node.sourceSets.isEmpty())
    }

    @Test
    fun `tags are stored and accessible`() {
        val node = ModuleNode(
            path = ":feature:login",
            name = "login",
            type = ModuleType.ANDROID_LIBRARY,
            tags = setOf("feature"),
        )
        assertTrue(node.tags.contains("feature"))
    }
}
