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
 * Each test spins up a real Gradle build in a temp directory to verify
 * end-to-end behavior - configuration cache, task wiring, output file
 * generation, and rule enforcement.
 */
class AalekhPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private fun gradleRunner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .forwardOutput()

    // Project setup helpers
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

    private fun setupJavaModuleProject() {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins {
                id("io.github.shivathapaa.aalekh")
            }
            rootProject.name = "cc-test-project"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("java-library") }
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
            """aalekh { openBrowserAfterReport.set(false) }""".trimIndent()
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

    /**
     * Multi-module project with a layer violation:
     * :feature:login:data depends on :feature:login:ui (data → presentation, not allowed).
     */
    private fun setupLayerViolationProject() {
        listOf("core/domain", "feature/login/ui", "feature/login/data")
            .forEach { projectDir.resolve(it).mkdirs() }

        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "layer-test"
            include(":core:domain", ":feature:login:ui", ":feature:login:data")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                layers {
                    layer("domain") {
                        modules(":core:domain")
                    }
                    layer("data") {
                        modules(":feature:*:data")
                        canOnlyDependOn("domain")
                    }
                    layer("presentation") {
                        modules(":feature:*:ui")
                        canOnlyDependOn("domain", "data")
                    }
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("core/domain/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
        projectDir.resolve("feature/login/ui/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core:domain")) }
            """.trimIndent()
        )
        // The violation: data module depends on presentation module
        projectDir.resolve("feature/login/data/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies {
                implementation(project(":core:domain"))
                implementation(project(":feature:login:ui"))
            }
            """.trimIndent()
        )
    }

    // Task registration

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
        val extractOutcome = result.task(":aalekhExtract")?.outcome
        assertTrue(
            extractOutcome == TaskOutcome.SUCCESS || extractOutcome == TaskOutcome.UP_TO_DATE,
            "aalekhExtract should run before aalekhReport"
        )
    }

    @Test
    fun `aalekhCheck task is registered and passes on a clean project`() {
        setupSingleModuleProject()
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhCheck")?.outcome)
    }

    @Test
    fun `aalekhReport is configuration cache compatible on second run`() {
        setupJavaModuleProject()

        gradleRunner("aalekhReport", "--configuration-cache").build()
        val secondRun = gradleRunner("aalekhReport", "--configuration-cache").build()

        assertTrue(
            secondRun.output.contains("Reusing configuration cache") ||
                    secondRun.output.contains("Configuration cache entry reused"),
            "Second run should reuse the configuration cache"
        )
    }

    // HTML report

    @Test
    fun `aalekhReport generates an HTML file`() {
        setupSingleModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val htmlFile = projectDir.resolve("build/reports/aalekh/index.html")
        assertTrue(htmlFile.exists(), "HTML report was not generated")
        assertTrue(htmlFile.length() > 50_000, "HTML report seems too small")
    }

    @Test
    fun `aalekhReport HTML contains injected graph data script tag`() {
        setupSingleModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertTrue(html.contains("""id="aalekh-graph-data""""))
        assertTrue(html.contains("""id="aalekh-summary-data""""))
    }

    @Test
    fun `aalekhReport HTML does not contain raw injection marker`() {
        setupSingleModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertFalse(html.contains("DATA INJECTED BY KOTLIN GENERATOR"))
    }

    // Graph extraction

    @Test
    fun `graph extraction captures inter-module dependencies correctly`() {
        setupMultiModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertTrue(html.contains(":core:domain"))
        assertTrue(html.contains(":core:data"))
        assertTrue(html.contains(":feature:login"))
    }

    @Test
    fun `graph extraction uses full Gradle paths not short names`() {
        setupMultiModuleProject()
        gradleRunner("aalekhExtract", "--no-configuration-cache").build()
        val json = projectDir.resolve("build/tmp/aalekh/graph.json").readText()
        assertTrue(json.contains(":core:domain"), "graph.json must use full Gradle project paths")
        assertTrue(json.contains(":feature:login"), "graph.json must use full Gradle project paths")
    }

    @Test
    fun `graph json is written to tmp directory`() {
        setupSingleModuleProject()
        gradleRunner("aalekhExtract", "--no-configuration-cache").build()
        val graphJson = projectDir.resolve("build/tmp/aalekh/graph.json")
        assertTrue(graphJson.exists())
        assertTrue(graphJson.length() > 0)
    }

    // aalekhCheck outputs

    @Test
    fun `aalekhCheck is wired into the check lifecycle`() {
        setupSingleModuleProject()
        val result = gradleRunner("check", "--no-configuration-cache").build()
        assertTrue(
            result.tasks.any { it.path == ":aalekhCheck" },
            "aalekhCheck was not executed as part of :check"
        )
    }

    @Test
    fun `aalekhCheck generates JUnit XML output`() {
        setupSingleModuleProject()
        gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        val xmlFile = projectDir.resolve("build/reports/aalekh/aalekh-results.xml")
        assertTrue(xmlFile.exists())
        assertTrue(xmlFile.readText().contains("<testsuites"))
    }

    @Test
    fun `aalekhCheck generates JSON output`() {
        setupSingleModuleProject()
        gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        val jsonFile = projectDir.resolve("build/reports/aalekh/aalekh-results.json")
        assertTrue(jsonFile.exists())
        assertTrue(jsonFile.length() > 0)
    }

    @Test
    fun `aalekhCheck generates SARIF output`() {
        setupSingleModuleProject()
        gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        val sarifFile = projectDir.resolve("build/reports/aalekh/aalekh-results.sarif")
        assertTrue(sarifFile.exists(), "SARIF report was not generated")
        val sarif = sarifFile.readText()
        assertTrue(sarif.contains("\"version\": \"2.1.0\""), "SARIF must declare version 2.1.0")
        assertTrue(sarif.contains("Aalekh"), "SARIF must identify the tool")
    }

    @Test
    fun `aalekhCheck JSON output contains envelope structure`() {
        setupSingleModuleProject()
        gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        val json = projectDir.resolve("build/reports/aalekh/aalekh-results.json").readText()
        assertTrue(json.contains("\"graph\""), "JSON must contain 'graph' field")
        assertTrue(json.contains("\"summary\""), "JSON must contain 'summary' field")
        assertTrue(json.contains("\"violations\""), "JSON must contain 'violations' field")
    }

    // Rule enforcement

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
    fun `aalekhCheck fails build when layer violation is detected`() {
        setupLayerViolationProject()
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("layer-dependency") ||
                    result.output.contains("layer") ||
                    result.output.contains(":feature:login:data"),
            "Build failure output should mention the layer violation"
        )
    }

    @Test
    fun `aalekhCheck passes when layer violation is downgraded to WARNING`() {
        setupLayerViolationProject()
        // Override the root build.gradle.kts to downgrade to WARNING
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                layers {
                    layer("domain") { modules(":core:domain") }
                    layer("data") {
                        modules(":feature:*:data")
                        canOnlyDependOn("domain")
                    }
                    layer("presentation") {
                        modules(":feature:*:ui")
                        canOnlyDependOn("domain", "data")
                    }
                }
                rules {
                    rule("layer-dependency") { severity = com.aalekh.aalekh.model.Severity.WARNING }
                }
            }
            """.trimIndent()
        )
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":aalekhCheck")?.outcome,
            "Build should pass when layer violation is downgraded to WARNING"
        )
    }

    @Test
    fun `SARIF output references build file path for layer violations`() {
        setupLayerViolationProject()
        // Run and ignore failure - we just want the SARIF output
        gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        val sarif = projectDir.resolve("build/reports/aalekh/aalekh-results.sarif").readText()
        assertTrue(
            sarif.contains("build.gradle.kts"),
            "SARIF should reference the build file of the offending module"
        )
    }

    // Guard rails

    @Test
    fun `plugin cannot be applied to non-root project`() {
        projectDir.resolve("submodule").mkdirs()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "guard-test"
            include(":submodule")
            """.trimIndent()
        )
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