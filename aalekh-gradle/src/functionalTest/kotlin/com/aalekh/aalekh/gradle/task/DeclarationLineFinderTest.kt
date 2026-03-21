package com.aalekh.aalekh.gradle.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the declaration line scanning logic extracted from AalekhExtractTask.
 *
 * The scanner looks for dependency declarations of the form:
 *   implementation(project(":target:path"))
 *   api(project(":target:path"))
 *   implementation(project(path = ":target:path"))
 * and returns the 1-based line number of the first match.
 */
class DeclarationLineFinderTest {

    // Mirrors the private findDeclarationLine() logic from AalekhExtractTask
    private fun findDeclarationLine(lines: List<String>, targetPath: String): Int? {
        val needle = "\"$targetPath\""
        val altNeedle = "':${targetPath.trimStart(':')}'"
        return lines.indexOfFirst { line ->
            line.contains(needle) || line.contains(altNeedle)
        }.takeIf { it >= 0 }?.plus(1)
    }

    @Test
    fun `finds double-quoted project path`() {
        val lines = listOf(
            "plugins { kotlin(\"jvm\") }",
            "dependencies {",
            "    implementation(project(\":feature:login\"))",
            "}",
        )
        assertEquals(3, findDeclarationLine(lines, ":feature:login"))
    }

    @Test
    fun `finds single-quoted project path`() {
        val lines = listOf(
            "dependencies {",
            "    implementation project(':feature:login')",
            "}",
        )
        assertEquals(2, findDeclarationLine(lines, ":feature:login"))
    }

    @Test
    fun `returns 1 for first line`() {
        val lines = listOf("implementation(project(\":core:domain\"))")
        assertEquals(1, findDeclarationLine(lines, ":core:domain"))
    }

    @Test
    fun `returns null when path not found`() {
        val lines = listOf(
            "dependencies {",
            "    implementation(project(\":core:data\"))",
            "}",
        )
        assertNull(findDeclarationLine(lines, ":core:domain"))
    }

    @Test
    fun `returns null for empty file`() {
        assertNull(findDeclarationLine(emptyList(), ":any:module"))
    }

    @Test
    fun `does not match partial path`() {
        // :core:data-test must not match when searching for :core:data
        val lines = listOf(
            "dependencies {",
            "    implementation(project(\":core:data-test\"))",
            "}",
        )
        // :core:data-test contains ":core:data" as substring but the needle includes trailing "
        // so ":core:data\"" won't match ":core:data-test\"" - correct behaviour
        assertNull(findDeclarationLine(lines, ":core:data"))
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
        assertEquals(2, findDeclarationLine(lines, ":core:domain"))
        assertEquals(3, findDeclarationLine(lines, ":core:data"))
        assertEquals(4, findDeclarationLine(lines, ":core:testing"))
    }

    @Test
    fun `handles api and testImplementation configurations`() {
        val lines = listOf(
            "dependencies {",
            "    api(project(\":core:model\"))",
            "}",
        )
        assertEquals(2, findDeclarationLine(lines, ":core:model"))
    }

    @Test
    fun `handles named path argument style`() {
        val lines = listOf(
            "dependencies {",
            "    implementation(project(path = \":core:domain\"))",
            "}",
        )
        assertEquals(2, findDeclarationLine(lines, ":core:domain"))
    }
}