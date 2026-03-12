package com.aalekh.aalekh.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Functional tests using [GradleRunner].
 *
 * These tests spin up a real Gradle build in a temp directory and verify
 * end-to-end behavior. They are slow (~5-30s each) but catch issues that
 * unit tests with [org.gradle.testfixtures.ProjectBuilder] cannot - configuration cache, task wiring,
 * output file generation, etc.
 *
 * Each test builds its own minimal multi-module project via string templates.
 * This avoids fragile file-system fixtures and keeps each test self-contained.
 */
class AalekhPluginFunctionalTest {
    @field:TempDir
    lateinit var projectDir: File

    private fun gradleRunner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()          // Adds the plugin under test to the classpath
        .withArguments(*args, "--stacktrace")
        .forwardOutput()

    private fun setupSingleModuleProject() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins {
                id("io.github.shivathapaa.aalekh")
            }
            rootProject.name = "test-project"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            aalekh { openBrowserAfterReport.set(false) }
            """.trimIndent()
        )
    }

    private fun setupMultiModuleProject() {
        listOf("core/domain", "core/data", "feature/login")
            .forEach { projectDir.resolve(it).mkdirs() }

        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins {
                id("io.github.shivathapaa.aalekh")
            }
            rootProject.name = "multi-module-test"
            include(":core:domain", ":core:data", ":feature:login")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh { openBrowserAfterReport.set(false) }
            """.trimIndent()
        )
        projectDir.resolve("core/domain/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }""".trimIndent()
        )
        projectDir.resolve("core/data/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core:domain")) }
            """.trimIndent()
        )
        projectDir.resolve("feature/login/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies {
                implementation(project(":core:domain"))
                implementation(project(":core:data"))
            }
            """.trimIndent()
        )
    }

    private fun setupCyclicProject() {
        listOf("module-a", "module-b").forEach { projectDir.resolve(it).mkdirs() }

        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "cyclic-test"
            include(":module-a", ":module-b")
            plugins {
                id("io.github.shivathapaa.aalekh")
            }
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """aalekh { openBrowserAfterReport.set(false) }""".trimIndent()
        )
        projectDir.resolve("module-a/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":module-b")) }
            """.trimIndent()
        )
        projectDir.resolve("module-b/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":module-a")) }
            """.trimIndent()
        )
    }

    @Test
    fun `aalekhReport task is registered and runs successfully`() {
        setupSingleModuleProject()

        val result = gradleRunner("aalekhReport", "--no-configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhReport")?.outcome)
    }

    @Test
    fun `aalekhExtract task runs before aalekhReport`() {
        setupSingleModuleProject()

        val result = gradleRunner("aalekhReport", "--no-configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhExtract")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhReport")?.outcome)
    }

    @Test
    fun `aalekhCheck task is registered and passes on a clean project`() {
        setupSingleModuleProject()

        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhCheck")?.outcome)
    }

    @Test
    fun `aalekhReport is configuration cache compatible on second run`() {
        setupSingleModuleProject()

        // First run - stores CC entry
        val first = gradleRunner("aalekhReport").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":aalekhReport")?.outcome)

        // Second run - must reuse CC without failing
        val second = gradleRunner("aalekhReport").build()
        // Task should be UP-TO-DATE or FROM-CACHE since nothing changed
        val outcome = second.task(":aalekhReport")?.outcome
        assertTrue(
            outcome == TaskOutcome.SUCCESS || outcome == TaskOutcome.UP_TO_DATE,
            "Second run should succeed with CC reuse, got: $outcome\n${second.output}"
        )
        assertFalse(
            second.output.contains("Class") && second.output.contains("not found in class loader"),
            "Second run must not produce classloader errors:\n${second.output}"
        )
    }

    @Test
    fun `aalekhReport generates an HTML file`() {
        setupSingleModuleProject()

        gradleRunner("aalekhReport", "--no-configuration-cache").build()

        val htmlFile = projectDir.resolve("build/reports/aalekh/index.html")
        assertTrue(htmlFile.exists(), "HTML report was not generated")
        assertTrue(htmlFile.length() > 1000, "HTML report looks too small: ${htmlFile.length()} bytes")
        assertFalse(
            htmlFile.readText().contains("{{PROJECT_NAME}}"),
            "PROJECT_NAME placeholder was not replaced in the HTML report"
        )
    }

    @Test
    fun `aalekhReport HTML contains injected graph data script tag`() {
        setupSingleModuleProject()

        gradleRunner("aalekhReport", "--no-configuration-cache").build()

        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertTrue(
            html.contains("""id="aalekh-graph-data""""),
            "HTML must contain injected <script id='aalekh-graph-data'> data tag"
        )
        assertTrue(
            html.contains("""id="aalekh-summary-data""""),
            "HTML must contain injected <script id='aalekh-summary-data'> data tag"
        )
        // Critical: data tags must appear BEFORE parseScriptJson reads them
        val dataTagIndex = html.indexOf("""id="aalekh-graph-data"""")
        val parseScriptIndex = html.indexOf("function parseScriptJson")
        assertTrue(
            dataTagIndex < parseScriptIndex,
            "Data script tag must appear before parseScriptJson() - otherwise getElementById returns null at runtime"
        )
    }

    @Test
    fun `aalekhReport HTML does not contain raw comment placeholders`() {
        setupSingleModuleProject()

        gradleRunner("aalekhReport", "--no-configuration-cache").build()

        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertFalse(
            html.contains("/* AALEKH_GRAPH_DATA */"),
            "Old comment placeholder must be replaced, not left as-is"
        )
    }

    @Test
    fun `graph extraction captures intermodule dependencies correctly`() {
        setupMultiModuleProject()

        gradleRunner("aalekhReport", "--no-configuration-cache").build()

        val html = projectDir.resolve("build/reports/aalekh/index.html")
        assertTrue(html.exists(), "HTML report was not generated")
        val content = html.readText()
        assertTrue(
            content.contains(":core:domain"),
            "HTML report should contain :core:domain"
        )
        assertTrue(
            content.contains(":feature:login"),
            "HTML report should contain :feature:login"
        )
        // Verify data was actually injected (not left as placeholder)
        assertTrue(
            content.contains("aalekh-graph-data"),
            "HTML report should have graph data script tag injected"
        )
    }

    @Test
    fun `graph extraction uses full gradle paths not short names`() {
        setupMultiModuleProject()

        gradleRunner("aalekhReport", "--no-configuration-cache").build()

        val graphJson = projectDir.resolve("build/tmp/aalekh/graph.json")
        assertTrue(graphJson.exists(), "Intermediate graph.json was not written")
        val json = graphJson.readText()

        // Must use full paths like ":core:domain", not just "domain"
        assertTrue(json.contains(":core:domain"), "graph.json must use full Gradle project paths")
        assertTrue(json.contains(":feature:login"), "graph.json must use full Gradle project paths")
        assertFalse(
            // With the old bug (":${dep.name}"), a module at ":core:domain" would produce ":domain"
            json.contains("\"to\":\":domain\""),
            "Edges must use full project paths like ':core:domain', not bare names like ':domain'"
        )
    }

    @Test
    fun `graph json is written to tmp directory`() {
        setupSingleModuleProject()

        gradleRunner("aalekhReport", "--no-configuration-cache").build()

        val graphJson = projectDir.resolve("build/tmp/aalekh/graph.json")
        assertTrue(graphJson.exists(), "Intermediate graph.json was not found at expected path")
        assertTrue(graphJson.length() > 0, "graph.json is empty")
    }

    @Test
    fun `aalekhCheck is wired into the check lifecycle`() {
        setupSingleModuleProject()

        val result = gradleRunner("check", "--no-configuration-cache").build()

        // aalekhCheck should have run as part of check
        assertTrue(
            result.tasks.any { it.path == ":aalekhCheck" },
            "aalekhCheck was not executed as part of :check"
        )
    }

    @Test
    fun `aalekhCheck generates junit xml output`() {
        setupSingleModuleProject()

        gradleRunner("aalekhCheck", "--no-configuration-cache").build()

        val xmlFile = projectDir.resolve("build/reports/aalekh/aalekh-results.xml")
        assertTrue(xmlFile.exists(), "JUnit XML was not generated by aalekhCheck")
        val xml = xmlFile.readText()
        assertTrue(xml.contains("<testsuites"), "Output must be valid JUnit XML")
    }

    @Test
    fun `aalekhCheck generates json output`() {
        setupSingleModuleProject()

        gradleRunner("aalekhCheck", "--no-configuration-cache").build()

        val jsonFile = projectDir.resolve("build/reports/aalekh/aalekh-results.json")
        assertTrue(jsonFile.exists(), "JSON results were not generated by aalekhCheck")
        assertTrue(jsonFile.length() > 0, "aalekh-results.json is empty")
    }

    @Test
    fun `aalekhCheck fails build when cycle is detected`() {
        setupCyclicProject()

        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("no-cyclic-dependencies") ||
                    result.output.contains("Cyclic") ||
                    result.output.contains("cycle"),
            "Build failure output should mention cycle detection"
        )
    }

    @Test
    fun `plugin cannot be applied to non root project`() {
        projectDir.resolve("submodule").mkdirs()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "guard-test"
            include(":submodule")
            """.trimIndent()
        )
        // Apply Aalekh only to the submodule's build file, not root - should fail
        projectDir.resolve("build.gradle.kts").writeText("")
        projectDir.resolve("submodule/build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.shivathapaa.aalekh.project")
            }
            """.trimIndent()
        )

        val result = gradleRunner("help", "--no-configuration-cache").buildAndFail()

        assertTrue(
            result.output.contains("root project") || result.output.contains("rootProject"),
            "Plugin should reject non-root application with a clear error"
        )
    }
}