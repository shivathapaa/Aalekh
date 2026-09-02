package com.aalekh.aalekh.gradle.extractor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [DeclarationLineFinder].
 *
 * A wrong line number is worse than none - it sends a developer to the wrong declaration - so the
 * partial-match cases below matter as much as the positive ones.
 */
class DeclarationLineFinderTest {

    @Test
    fun `finds double-quoted project path`() {
        val lines = listOf(
            "plugins { kotlin(\"jvm\") }",
            "dependencies {",
            "    implementation(project(\":feature:login\"))",
            "}",
        )
        assertEquals(3, DeclarationLineFinder.lineOf(lines, ":feature:login"))
    }

    @Test
    fun `finds single-quoted project path`() {
        val lines = listOf(
            "dependencies {",
            "    implementation project(':feature:login')",
            "}",
        )
        assertEquals(2, DeclarationLineFinder.lineOf(lines, ":feature:login"))
    }

    @Test
    fun `returns 1 for first line`() {
        val lines = listOf("implementation(project(\":core:domain\"))")
        assertEquals(1, DeclarationLineFinder.lineOf(lines, ":core:domain"))
    }

    @Test
    fun `returns null when path not found`() {
        val lines = listOf(
            "dependencies {",
            "    implementation(project(\":core:data\"))",
            "}",
        )
        assertNull(DeclarationLineFinder.lineOf(lines, ":core:domain"))
    }

    @Test
    fun `returns null for empty file`() {
        assertNull(DeclarationLineFinder.lineOf(emptyList(), ":any:module"))
    }

    @Test
    fun `does not match a longer path with the same prefix`() {
        val lines = listOf(
            "dependencies {",
            "    implementation(project(\":core:data-test\"))",
            "}",
        )
        assertNull(DeclarationLineFinder.lineOf(lines, ":core:data"))
    }

    @Test
    fun `finds correct line when multiple dependencies declared`() {
        val lines = listOf(
            "dependencies {",
            "    implementation(project(\":core:domain\"))",
            "    implementation(project(\":core:data\"))",
            "    testImplementation(project(\":core:testing\"))",
            "}",
        )
        assertEquals(2, DeclarationLineFinder.lineOf(lines, ":core:domain"))
        assertEquals(3, DeclarationLineFinder.lineOf(lines, ":core:data"))
        assertEquals(4, DeclarationLineFinder.lineOf(lines, ":core:testing"))
    }

    @Test
    fun `handles api and testImplementation configurations`() {
        val lines = listOf(
            "dependencies {",
            "    api(project(\":core:model\"))",
            "}",
        )
        assertEquals(2, DeclarationLineFinder.lineOf(lines, ":core:model"))
    }

    @Test
    fun `handles named path argument style`() {
        val lines = listOf(
            "dependencies {",
            "    implementation(project(path = \":core:domain\"))",
            "}",
        )
        assertEquals(2, DeclarationLineFinder.lineOf(lines, ":core:domain"))
    }

    // Type-safe project accessors

    @Test
    fun `finds type-safe project accessor`() {
        val lines = listOf(
            "dependencies {",
            "    implementation(projects.core.domain)",
            "}",
        )
        assertEquals(2, DeclarationLineFinder.lineOf(lines, ":core:domain"))
    }

    @Test
    fun `accessor for a kebab-case segment is camel-cased`() {
        assertEquals("projects.core.dataTest", DeclarationLineFinder.accessorFor(":core:data-test"))
        assertEquals("projects.feature.login.ui", DeclarationLineFinder.accessorFor(":feature:login:ui"))
    }

    @Test
    fun `finds kebab-case module through its camel-cased accessor`() {
        val lines = listOf("    implementation(projects.core.dataTest)")
        assertEquals(1, DeclarationLineFinder.lineOf(lines, ":core:data-test"))
    }

    @Test
    fun `accessor match does not fire on a longer accessor with the same prefix`() {
        // projects.core.data must not match inside projects.core.dataTest - they are different
        // modules, and pointing at the wrong one is worse than reporting no line at all.
        val lines = listOf("    implementation(projects.core.dataTest)")
        assertNull(DeclarationLineFinder.lineOf(lines, ":core:data"))
    }

    @Test
    fun `accessor match fires on a nested accessor prefix followed by a delimiter`() {
        val lines = listOf("    implementation(projects.core.data)")
        assertEquals(1, DeclarationLineFinder.lineOf(lines, ":core:data"))
    }
}
