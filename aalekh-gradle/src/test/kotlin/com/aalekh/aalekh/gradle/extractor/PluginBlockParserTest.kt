package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.model.PluginSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginBlockParserTest {

    private fun parse(script: String) = PluginBlockParser.parse(script.trimIndent().lines())

    @Test
    fun `reads Kotlin DSL plugin ids`() {
        val plugins = parse(
            """
            plugins {
                id("com.android.library")
                id("org.jetbrains.kotlin.android")
            }
            """
        )

        assertEquals(listOf("com.android.library", "org.jetbrains.kotlin.android"), plugins.map { it.id })
        assertTrue(plugins.all { it.source == PluginSource.BUILD_SCRIPT })
    }

    @Test
    fun `reads Groovy DSL plugin ids`() {
        val plugins = parse(
            """
            plugins {
                id 'com.android.application'
            }
            """
        )

        assertEquals(listOf("com.android.application"), plugins.map { it.id })
    }

    @Test
    fun `captures an explicit version`() {
        val plugin = parse(
            """
            plugins {
                id("com.foo.bar") version "1.2.3"
            }
            """
        ).single()

        assertEquals("com.foo.bar", plugin.id)
        assertEquals("1.2.3", plugin.version)
    }

    @Test
    fun `expands the kotlin shorthand to a full plugin id`() {
        val plugins = parse(
            """
            plugins {
                kotlin("jvm") version "2.3.0"
                kotlin("plugin.serialization")
            }
            """
        )

        assertEquals(
            listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.plugin.serialization"),
            plugins.map { it.id },
        )
        assertEquals("2.3.0", plugins.first().version)
    }

    @Test
    fun `records a version catalog alias for later resolution`() {
        val plugin = parse(
            """
            plugins {
                alias(libs.plugins.androidApplication)
            }
            """
        ).single()

        assertEquals("androidApplication", plugin.alias)
        assertNull(plugin.version, "the version lives in the catalog, not the script")
    }

    @Test
    fun `flattens a nested catalog alias`() {
        val plugin = parse(
            """
            plugins {
                alias(libs.plugins.android.application)
            }
            """
        ).single()

        assertEquals("android.application", plugin.alias)
    }

    @Test
    fun `reads backtick-quoted and bare core plugins`() {
        val plugins = parse(
            """
            plugins {
                `java-library`
                application
            }
            """
        )

        assertEquals(listOf("java-library", "application"), plugins.map { it.id })
    }

    @Test
    fun `reads convention plugin ids`() {
        val plugins = parse(
            """
            plugins {
                id("myapp.android.library")
                id("myapp.android.hilt")
            }
            """
        )

        assertEquals(listOf("myapp.android.library", "myapp.android.hilt"), plugins.map { it.id })
    }

    @Test
    fun `handles a single-line plugins block`() {
        assertEquals(
            listOf("org.jetbrains.kotlin.jvm"),
            parse("""plugins { kotlin("jvm") version "2.3.0" }""").map { it.id },
        )
    }

    @Test
    fun `ignores commented-out declarations`() {
        val plugins = parse(
            """
            plugins {
                id("com.real.plugin")
                // id("com.commented.plugin")
            }
            """
        )

        assertEquals(listOf("com.real.plugin"), plugins.map { it.id })
    }

    @Test
    fun `stops at the end of the plugins block`() {
        // The dependencies block must not be mined for plugin ids; only the plugins block counts.
        val plugins = parse(
            """
            plugins {
                id("com.real.plugin")
            }

            dependencies {
                implementation("com.example:not-a-plugin:1.0")
            }
            """
        )

        assertEquals(listOf("com.real.plugin"), plugins.map { it.id })
    }

    @Test
    fun `a script with no plugins block yields nothing`() {
        assertTrue(parse("""dependencies { implementation(project(":core")) }""").isEmpty())
    }

    @Test
    fun `duplicate ids are collapsed`() {
        val plugins = parse(
            """
            plugins {
                id("com.foo")
                id("com.foo")
            }
            """
        )

        assertEquals(1, plugins.size)
    }
}
