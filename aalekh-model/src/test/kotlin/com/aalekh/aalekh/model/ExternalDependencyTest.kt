package com.aalekh.aalekh.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalDependencyTest {

    private fun dep(
        configuration: String,
        version: String? = "1.0.0",
        group: String = "com.example",
    ) = ExternalDependency(
        module = ":app",
        group = group,
        name = "lib",
        version = version,
        configuration = configuration,
    )

    @Test
    fun `isApi covers api and KMP source-set api configurations`() {
        assertTrue(dep("api").isApi)
        assertTrue(dep("commonMainApi").isApi)
        assertTrue(dep("androidMainApi").isApi)
        assertFalse(dep("implementation").isApi)
        assertFalse(dep("testApi").isApi)
    }

    @Test
    fun `isTest detects all test configuration variants`() {
        assertTrue(dep("testImplementation").isTest)
        assertTrue(dep("androidTestImplementation").isTest)
        assertFalse(dep("implementation").isTest)
    }

    @Test
    fun `isCompileOnly detects compileOnly configuration`() {
        assertTrue(dep("compileOnly").isCompileOnly)
        assertFalse(dep("implementation").isCompileOnly)
    }

    @Test
    fun `coordinates renders group name version`() {
        assertEquals("com.example:lib:1.0.0", dep("implementation").coordinates)
    }

    @Test
    fun `coordinates renders unspecified when version is null`() {
        assertEquals("com.example:lib:unspecified", dep("implementation", version = null).coordinates)
    }

    @Test
    fun `coordinates drops blank group`() {
        assertEquals("lib:1.0.0", dep("implementation", group = "").coordinates)
    }

    @Test
    fun `version and sourceSet default to null`() {
        val d = ExternalDependency(module = ":app", group = "g", name = "n", configuration = "implementation")
        assertNull(d.version)
        assertNull(d.sourceSet)
    }
}
