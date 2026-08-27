package com.aalekh.aalekh.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
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

    /**
     * Multi-module project with a `teams { }` block so the ownership overlay has data to render.
     */
    private fun setupTeamOwnershipProject() {
        listOf("core/domain", "feature/login").forEach { projectDir.resolve(it).mkdirs() }

        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "team-test"
            include(":core:domain", ":feature:login")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                teams {
                    team("auth-team") { modules(":feature:login") }
                    team("core-team") { modules(":core:**") }
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("core/domain/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
        projectDir.resolve("feature/login/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core:domain")) }
            """.trimIndent()
        )
    }

    /**
     * Multi-module project with an isolated (`:orphan`) module and the `no-orphan-modules`
     * rule promoted to ERROR so the orphan fails the build.
     */
    private fun setupOrphanProject() {
        listOf("core/domain", "feature/login", "orphan").forEach { projectDir.resolve(it).mkdirs() }

        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "orphan-test"
            include(":core:domain", ":feature:login", ":orphan")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                rules {
                    noOrphanModules()
                    rule("no-orphan-modules") { severity = com.aalekh.aalekh.model.Severity.ERROR }
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("core/domain/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
        projectDir.resolve("feature/login/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core:domain")) }
            """.trimIndent()
        )
        // No dependencies in or out - an orphan.
        projectDir.resolve("orphan/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
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

    @Test
    fun `aalekhReport wires the teams DSL through to the report teamOwners map`() {
        setupTeamOwnershipProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertTrue(
            html.contains(""""auth-team":[":feature:login"]"""),
            "teams { } config must reach summary.teamOwners so the overlay can render"
        )
        assertTrue(
            html.contains(""""core-team":[":core:**"]"""),
            "Every declared team must appear in the report's teamOwners map"
        )
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
    fun `aalekhCheck prints cycle break advice naming the edge to remove`() {
        setupCyclicProject()
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("to break the detected cycle"),
            "aalekhCheck must print break-up advice when a cycle is detected",
        )
        assertTrue(
            result.output.contains("project(\":module-a\")") || result.output.contains("project(\":module-b\")"),
            "the advice must name the specific project dependency to remove",
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

    // Mermaid export

    @Test
    fun `aalekhMermaid writes a diffable mmd and md graph`() {
        setupMultiModuleProject()
        val result = gradleRunner("aalekhMermaid", "--no-configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhMermaid")?.outcome)

        val mmd = projectDir.resolve("build/reports/aalekh/aalekh-graph.mmd")
        assertTrue(mmd.exists(), "aalekhMermaid must write the .mmd file")
        val mmdText = mmd.readText()
        assertTrue(mmdText.contains("graph TD"), "Mermaid output must declare a graph")
        assertTrue(mmdText.contains(":core:domain"), "Mermaid output must label modules by path")

        val md = projectDir.resolve("build/reports/aalekh/aalekh-graph.md")
        assertTrue(md.exists(), "aalekhMermaid must write the Markdown wrapper")
        assertTrue(md.readText().contains("```mermaid"), "Markdown wrapper must fence a mermaid block")
    }

    @Test
    fun `aalekhMermaid is configuration cache compatible on second run`() {
        // Single java-library module, mirroring the aalekhReport CC test - this avoids the
        // Kotlin-Gradle-plugin build-service-across-sibling-projects quirk that breaks CC store
        // in a multi-module TestKit build, which is orthogonal to Aalekh's own CC safety.
        setupJavaModuleProject()
        gradleRunner("aalekhMermaid", "--configuration-cache").build()
        val secondRun = gradleRunner("aalekhMermaid", "--configuration-cache").build()
        assertTrue(
            secondRun.output.contains("Reusing configuration cache") ||
                    secondRun.output.contains("Configuration cache entry reused"),
            "Second aalekhMermaid run should reuse the configuration cache"
        )
    }

    // New rules

    @Test
    fun `maxGraphHeight promoted to ERROR fails the build on a tall graph`() {
        setupMultiModuleProject()
        // Critical path :feature:login -> :core:data -> :core:domain has height 3.
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                rules {
                    maxGraphHeight(2)
                    rule("max-graph-height") { severity = com.aalekh.aalekh.model.Severity.ERROR }
                }
            }
            """.trimIndent()
        )
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("max-graph-height") || result.output.contains("height is"),
            "Build failure should mention the graph-height rule"
        )
    }

    @Test
    fun `noOrphanModules promoted to ERROR fails the build on an isolated module`() {
        setupOrphanProject()
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("no-orphan-modules") || result.output.contains(":orphan"),
            "Build failure should mention the isolated module"
        )
    }

    // Baseline / freeze

    @Test
    fun `aalekhBaseline freezes an existing violation so aalekhCheck passes`() {
        setupLayerViolationProject()
        // Sanity: without a baseline the layer violation fails the build.
        gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()

        val baselineResult = gradleRunner("aalekhBaseline", "--no-configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, baselineResult.task(":aalekhBaseline")?.outcome)
        val baselineFile = projectDir.resolve("aalekh-baseline.json")
        assertTrue(baselineFile.exists(), "aalekhBaseline must write the baseline file")
        assertTrue(
            baselineFile.readText().contains("layer-dependency"),
            "The baseline must record the existing layer violation"
        )

        val checkResult = gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        assertEquals(
            TaskOutcome.SUCCESS,
            checkResult.task(":aalekhCheck")?.outcome,
            "Once baselined, the known violation must no longer fail the build"
        )
        assertTrue(
            checkResult.output.contains("baselined"),
            "aalekhCheck should report how many violations the baseline suppressed"
        )
    }

    @Test
    fun `a new violation still fails aalekhCheck even with a baseline present`() {
        setupLayerViolationProject()
        gradleRunner("aalekhBaseline", "--no-configuration-cache").build()

        // Add a brand-new rule (as ERROR) that the baseline never captured.
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
                    maxGraphHeight(1)
                    rule("max-graph-height") { severity = com.aalekh.aalekh.model.Severity.ERROR }
                }
            }
            """.trimIndent()
        )
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("max-graph-height") || result.output.contains("height is"),
            "A violation absent from the baseline must still fail the build"
        )
    }

    // Predicate rule DSL (forbid { })

    private fun setupForbiddenDependencyProject(severityLine: String = "") {
        listOf("feature/a", "feature/b").forEach { projectDir.resolve(it).mkdirs() }
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "forbid-test"
            include(":feature:a", ":feature:b")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                forbid {
                    from(":feature:**")
                    to(":feature:**")
                    because("features must not depend on each other")
                    $severityLine
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("feature/a/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":feature:b")) }
            """.trimIndent()
        )
        projectDir.resolve("feature/b/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
    }

    @Test
    fun `forbid predicate rule fails the build on a matching dependency`() {
        setupForbiddenDependencyProject()
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("forbidden-dependency") || result.output.contains("must not depend"),
            "a forbid { } predicate must fail aalekhCheck and name the violation",
        )
        assertTrue(
            result.output.contains("features must not depend on each other"),
            "the because(...) reason must appear in the violation message",
        )
    }

    @Test
    fun `forbid predicate downgraded to WARNING does not fail the build`() {
        setupForbiddenDependencyProject("severity(com.aalekh.aalekh.model.Severity.WARNING)")
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":aalekhCheck")?.outcome,
            "a WARNING-severity forbid rule reports but must not fail the build",
        )
    }

    // Quality gates (metric-delta regression)

    /** Modules a -> b with c isolated, and quality gates forbidding ccd / critical-path regressions. */
    private fun setupQualityGateProject() {
        listOf("a", "b", "c").forEach { projectDir.resolve(it).mkdirs() }
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "gate-test"
            include(":a", ":b", ":c")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                qualityGates {
                    forbidRegression("ccd", "critical-path")
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("a/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":b")) }
            """.trimIndent()
        )
        projectDir.resolve("b/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
        projectDir.resolve("c/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
    }

    @Test
    fun `quality gate fails the build when a metric regresses past the baseline`() {
        setupQualityGateProject()
        gradleRunner("aalekhBaseline", "--no-configuration-cache").build()
        assertTrue(
            projectDir.resolve("aalekh-baseline.json").readText().contains("\"metrics\""),
            "the baseline must record a metrics snapshot for the gates to compare against",
        )

        // Deepen the graph: b now depends on c, lengthening the critical path and raising CCD.
        projectDir.resolve("b/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":c")) }
            """.trimIndent()
        )
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("metric-regression") || result.output.contains("regressed"),
            "a metric regression past the baseline must fail aalekhCheck",
        )
    }

    @Test
    fun `quality gate passes when no metric regresses`() {
        setupQualityGateProject()
        gradleRunner("aalekhBaseline", "--no-configuration-cache").build()
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":aalekhCheck")?.outcome,
            "an unchanged graph must not trip any quality gate",
        )
    }

    // Temporal coupling

    private fun runGit(vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", projectDir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        require(finished && process.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed: $output"
        }
    }

    private fun gitCommitAll(message: String) {
        runGit("add", "-A")
        runGit(
            "-c", "user.email=test@aalekh.dev",
            "-c", "user.name=Aalekh Test",
            "-c", "commit.gpgsign=false",
            "commit", "-m", message, "--no-gpg-sign",
        )
    }

    private fun writeSource(relativePath: String, contents: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(contents)
    }

    @Test
    fun `aalekhTemporal writes a temporal report from git history`() {
        setupMultiModuleProject()
        writeSource("core/domain/src/main/kotlin/D.kt", "object D { const val V = 0 }")
        writeSource("core/data/src/main/kotlin/R.kt", "object R { const val V = 0 }")
        writeSource("feature/login/src/main/kotlin/L.kt", "object L { const val V = 0 }")

        runGit("init", "-q")
        gitCommitAll("init")
        // core:domain and core:data change together across two more commits.
        writeSource("core/domain/src/main/kotlin/D.kt", "object D { const val V = 1 }")
        writeSource("core/data/src/main/kotlin/R.kt", "object R { const val V = 1 }")
        gitCommitAll("change 1")
        writeSource("core/domain/src/main/kotlin/D.kt", "object D { const val V = 2 }")
        writeSource("core/data/src/main/kotlin/R.kt", "object R { const val V = 2 }")
        gitCommitAll("change 2")

        val result = gradleRunner("aalekhTemporal", "--no-configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhTemporal")?.outcome)

        val md = projectDir.resolve("build/reports/aalekh/aalekh-temporal.md")
        assertTrue(md.exists(), "aalekhTemporal must write the Markdown report")
        val mdText = md.readText()
        assertTrue(mdText.contains("## Change hotspots"), "report must include the hotspots section")
        assertTrue(mdText.contains("## Top co-changing pairs"), "report must include co-changing pairs")
        assertTrue(mdText.contains(":core:domain"), "co-changing modules must be named")
        assertTrue(mdText.contains(":core:data"))

        val json = projectDir.resolve("build/reports/aalekh/aalekh-temporal.json")
        assertTrue(json.exists(), "aalekhTemporal must write the JSON report")
        assertTrue(json.readText().contains("\"commitsAnalyzed\""), "JSON must carry the analysed commit count")
    }

    @Test
    fun `aalekhTemporal degrades gracefully when there is no git history`() {
        setupMultiModuleProject()
        // No `git init` - the project directory is not a repository.
        val result = gradleRunner("aalekhTemporal", "--no-configuration-cache").build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":aalekhTemporal")?.outcome,
            "a missing git history must not fail the build",
        )
        val md = projectDir.resolve("build/reports/aalekh/aalekh-temporal.md")
        assertTrue(md.exists(), "an empty report file must still be written")
        assertTrue(md.readText().contains("No git history"), "the empty report must explain why it is empty")
    }

    @Test
    fun `aalekhAffected reports the modules changed by a diff and their blast radius`() {
        setupMultiModuleProject()
        writeSource("core/domain/src/main/kotlin/D.kt", "object D { const val V = 0 }")
        writeSource("core/data/src/main/kotlin/R.kt", "object R { const val V = 0 }")
        writeSource("feature/login/src/main/kotlin/L.kt", "object L { const val V = 0 }")
        runGit("init", "-q")
        gitCommitAll("init")
        // Change only core:domain; core:data and feature:login depend on it (blast radius).
        writeSource("core/domain/src/main/kotlin/D.kt", "object D { const val V = 1 }")
        gitCommitAll("touch domain")

        val result = gradleRunner("aalekhAffected", "--no-configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhAffected")?.outcome)

        val md = projectDir.resolve("build/reports/aalekh/aalekh-affected.md").readText()
        assertTrue(md.contains("### Changed"), "the report must have a changed-modules section")
        assertTrue(md.contains(":core:domain"), "the changed module must be listed")
        assertTrue(md.contains(":feature:login"), "a downstream dependent must be in the affected set")

        val json = projectDir.resolve("build/reports/aalekh/aalekh-affected.json")
        assertTrue(json.exists(), "aalekhAffected must write the JSON artefact")
        assertTrue(json.readText().contains("\"affected\""), "JSON must carry the affected set")
    }

    @Test
    fun `aalekhAffected degrades gracefully when there is no git history`() {
        setupMultiModuleProject()
        val result = gradleRunner("aalekhAffected", "--no-configuration-cache").build()
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":aalekhAffected")?.outcome,
            "a missing git history must not fail the build",
        )
        val md = projectDir.resolve("build/reports/aalekh/aalekh-affected.md")
        assertTrue(md.exists(), "an affected report file must still be written")
        assertTrue(md.readText().contains("No module sources changed"))
    }

    @Test
    fun `aalekhTemporal is configuration cache compatible on second run`() {
        // Single java-library module, mirroring the aalekhReport CC test, to avoid the
        // Kotlin-Gradle-plugin build-service-across-sibling-projects quirk that breaks CC store.
        setupJavaModuleProject()
        gradleRunner("aalekhTemporal", "--configuration-cache").build()
        val secondRun = gradleRunner("aalekhTemporal", "--configuration-cache").build()
        assertTrue(
            secondRun.output.contains("Reusing configuration cache") ||
                    secondRun.output.contains("Configuration cache entry reused"),
            "Second aalekhTemporal run should reuse the configuration cache",
        )
    }

    // Reachability & layer-coverage rules

    /**
     * Graph: app -> feature:a -> core, with feature:b wired to nobody. [configBlock] is injected
     * inside the `aalekh { }` block so each test supplies its own rule.
     */
    private fun setupReachabilityProject(configBlock: String) {
        listOf("app", "feature/a", "feature/b", "core").forEach { projectDir.resolve(it).mkdirs() }
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "reach-test"
            include(":app", ":feature:a", ":feature:b", ":core")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                $configBlock
            }
            """.trimIndent()
        )
        projectDir.resolve("app/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":feature:a")) }
            """.trimIndent()
        )
        projectDir.resolve("feature/a/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core")) }
            """.trimIndent()
        )
        projectDir.resolve("feature/b/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
        projectDir.resolve("core/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )
    }

    @Test
    fun `mustBeReachableFrom fails the build for a feature not wired into the app`() {
        setupReachabilityProject(
            """rules { mustBeReachableFrom(module = ":feature:*", from = ":app", because = "wire every feature in") }"""
        )
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("unreachable-module"),
            "the unreachable feature must be reported under the stable rule id",
        )
        assertTrue(
            result.output.contains(":feature:b"),
            "the specific unreachable module must be named",
        )
    }

    @Test
    fun `forbidReachable fails on an indirect dependency the direct rule would miss`() {
        // app -> feature:a -> core: app reaches core only transitively.
        setupReachabilityProject(
            """rules { forbidReachable(from = ":app", to = ":core", because = "app must not reach core") }"""
        )
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("forbidden-transitive-dependency"),
            "the transitive forbidden dependency must be reported under the stable rule id",
        )
        assertTrue(
            result.output.contains("app must not reach core"),
            "the because(...) reason must appear in the violation message",
        )
    }

    @Test
    fun `requireLayerForAllModules promoted to ERROR fails on an uncovered module`() {
        setupReachabilityProject(
            """
            layers { layer("features") { modules(":feature:**") } }
            rules {
                requireLayerForAllModules()
                rule("uncovered-module") { severity = com.aalekh.aalekh.model.Severity.ERROR }
            }
            """.trimIndent()
        )
        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains("uncovered-module"),
            "a module in no declared layer must be reported",
        )
        assertTrue(
            result.output.contains(":app") || result.output.contains(":core"),
            "an uncovered module (\":app\" or \":core\") must be named",
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