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
    fun `exportMetrics writes a per-module metrics CSV`() {
        setupMultiModuleProject()
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                exportMetrics.set(true)
            }
            """.trimIndent()
        )
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val csv = projectDir.resolve("build/reports/aalekh/aalekh-metrics.csv")
        assertTrue(csv.exists(), "exportMetrics = true must write aalekh-metrics.csv")
        val text = csv.readText()
        assertTrue(
            text.lineSequence().first().startsWith("timestamp,module,type,fanIn,fanOut"),
            "the CSV must start with the metrics header row",
        )
        assertTrue(text.contains(":core:domain"), "the CSV must carry one row per module")
    }

    @Test
    fun `exportMetrics defaults off - no CSV is written`() {
        setupSingleModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        assertFalse(
            projectDir.resolve("build/reports/aalekh/aalekh-metrics.csv").exists(),
            "the metrics CSV must not appear unless exportMetrics is enabled",
        )
    }

    @Test
    fun `aalekhReport accumulates one trend history entry per run`() {
        setupSingleModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        gradleRunner("aalekhReport", "--no-configuration-cache", "--rerun-tasks").build()
        gradleRunner("aalekhReport", "--no-configuration-cache", "--rerun-tasks").build()

        val trend = projectDir.resolve("build/aalekh/trend.json")
        assertTrue(trend.exists(), "the report must write the rolling trend history")
        val entries = Regex("\"ts\"").findAll(trend.readText()).count()
        assertEquals(3, entries, "each report run should append exactly one trend entry")
    }

    @Test
    fun `aalekhReport wires the layers DSL through to the report in declaration order`() {
        setupLayerViolationProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()

        assertTrue(
            html.contains(""""layerSource":"OBSERVED""""),
            "With layers { } declared the report must not group modules by an inference"
        )
        assertTrue(
            html.contains(
                """"layers":[{"name":"domain","patterns":[":core:domain"],"allowed":[],""" +
                    """"restricted":false},{"name":"data","patterns":[":feature:*:data"],""" +
                    """"allowed":["domain"],"restricted":true},{"name":"presentation",""" +
                    """"patterns":[":feature:*:ui"],"allowed":["domain","data"],"restricted":true}]"""
            ),
            "Layers must reach the report in declaration order with their patterns and allowlists, " +
                    "not in the named container's alphabetical order"
        )
    }

    @Test
    fun `aalekhReport falls back to inferred layers and says so when none are declared`() {
        setupMultiModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()

        assertTrue(html.contains(""""layers":[]"""), "No declared layers must serialize as empty")
        assertTrue(
            html.contains(""""layerSource":"INFERRED""""),
            "Without a layers { } block the report must mark the grouping as inferred"
        )
    }

    @Test
    fun `extraction populates health scores and declaration lines`() {
        setupMultiModuleProject()
        gradleRunner("aalekhExtract", "--no-configuration-cache").build()
        val graph = projectDir.resolve("build/tmp/aalekh/graph.json").readText()

        assertFalse(
            Regex(""""healthScore":null""").containsMatchIn(graph),
            "every module must carry a health score so the CSV and report read one number"
        )
        assertFalse(
            Regex(""""declarationLine":null""").containsMatchIn(graph),
            "every edge declared in a build file must carry the line it was declared on"
        )
        assertTrue(
            graph.contains(""""buildFilePath":"feature/login/build.gradle.kts""""),
            "build file paths must come from the Gradle model"
        )
    }

    @Test
    fun `aalekhReport embeds a project health score with its component breakdown`() {
        setupMultiModuleProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()

        assertTrue(html.contains(""""health":{"score":"""), "project health must reach the report")
        assertTrue(
            html.contains(""""label":"Cycles""""),
            "the health components must be embedded so the dial can explain its own number"
        )
    }

    @Test
    fun `aalekhSnapshot writes a committable snapshot and aalekhDiff reports what changed`() {
        setupMultiModuleProject()
        gradleRunner("aalekhSnapshot", "--no-configuration-cache").build()
        val snapshot = projectDir.resolve("aalekh-snapshot.json")

        assertTrue(snapshot.exists(), "the snapshot must be written into the source tree to be committed")
        assertTrue(
            snapshot.readText().contains(""":core:data>:core:domain"""),
            "dependencies must be recorded in a line-diffable form: ${snapshot.readText()}",
        )

        // Nothing changed yet, so the diff must say so rather than inventing a change.
        gradleRunner("aalekhDiff", "--no-configuration-cache").build()
        assertTrue(
            projectDir.resolve("build/reports/aalekh/aalekh-diff.md").readText()
                .contains("No architectural change"),
        )

        // Now wire a cycle and confirm the report leads with it.
        projectDir.resolve("core/domain/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core:data")) }
            """.trimIndent()
        )
        val result = gradleRunner("aalekhDiff", "--no-configuration-cache").build()
        val report = projectDir.resolve("build/reports/aalekh/aalekh-diff.md").readText()

        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhDiff")?.outcome)
        assertTrue(
            report.contains("introduces a dependency cycle"),
            "the headline must lead with the most consequential change: $report",
        )
        assertTrue(
            report.contains("`:core:domain` → `:core:data`"),
            "the report must name the dependency that caused it: $report",
        )
    }

    @Test
    fun `aalekhDiff succeeds and explains itself when no snapshot is committed`() {
        // The first run of a new tool must never fail a build.
        setupMultiModuleProject()
        val result = gradleRunner("aalekhDiff", "--no-configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhDiff")?.outcome)
        assertTrue(
            result.output.contains("no committed snapshot found"),
            "the task must say how to create one: ${result.output}",
        )
    }

    @Test
    fun `aalekhDiff fails on a regression only when asked to`() {
        setupMultiModuleProject()
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                failOnArchitectureRegression.set(true)
            }
            """.trimIndent()
        )
        gradleRunner("aalekhSnapshot", "--no-configuration-cache").build()

        projectDir.resolve("core/domain/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core:data")) }
            """.trimIndent()
        )
        val result = gradleRunner("aalekhDiff", "--no-configuration-cache").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":aalekhDiff")?.outcome)
        assertTrue(result.output.contains("structurally worse"), result.output)
    }

    @Test
    fun `aalekhDocs writes readable Markdown documentation`() {
        setupLayerViolationProject()
        gradleRunner("aalekhDocs", "--no-configuration-cache").build()
        val docs = projectDir.resolve("build/reports/aalekh/docs")

        assertTrue(docs.resolve("README.md").exists(), "the docs set must have a landing document")
        assertTrue(docs.resolve("modules.md").exists())
        assertTrue(docs.resolve("onboarding.md").exists())
        assertTrue(docs.resolve("health.md").exists())

        val readme = docs.resolve("README.md").readText()
        assertTrue(readme.startsWith("# layer-test"), "the README must name the project: $readme")
        assertTrue(
            readme.contains("is a Gradle project of"),
            "the README must open with a plain-language summary: $readme",
        )
        assertTrue(readme.contains("| Modules |"), "the README must carry the at-a-glance table")

        val modules = docs.resolve("modules.md").readText()
        assertTrue(
            modules.contains("Blast radius"),
            "the catalogue must state what a change to each module costs: $modules",
        )
        assertTrue(modules.contains("`:core:domain`"), "every module must appear: $modules")
    }

    @Test
    fun `aalekhDocs output is byte-identical when nothing changed`() {
        // The property that makes committing the docs worthwhile: a diff means the architecture
        // moved, never that the tool ran again. A timestamp in the output would destroy it.
        setupMultiModuleProject()
        gradleRunner("aalekhDocs", "--no-configuration-cache").build()
        val first = projectDir.resolve("build/reports/aalekh/docs/README.md").readText()

        gradleRunner("aalekhDocs", "--no-configuration-cache", "--rerun-tasks").build()
        val second = projectDir.resolve("build/reports/aalekh/docs/README.md").readText()

        assertEquals(first, second, "regenerating unchanged docs must not produce a diff")
    }

    @Test
    fun `aalekhDocs removes a document it no longer produces`() {
        setupMultiModuleProject()
        gradleRunner("aalekhDocs", "--no-configuration-cache").build()
        val stale = projectDir.resolve("build/reports/aalekh/docs/regions.md")
        stale.writeText("# Left over from an older run")

        gradleRunner("aalekhDocs", "--no-configuration-cache", "--rerun-tasks").build()

        assertFalse(
            stale.exists(),
            "a document the project no longer warrants must not linger and mislead",
        )
    }

    @Test
    fun `extraction records the build inventory - plugins, catalog, owners and declared metadata`() {
        listOf("core/domain", "feature/login", "gradle", ".github", ".aalekh")
            .forEach { projectDir.resolve(it).mkdirs() }

        projectDir.resolve("gradle/libs.versions.toml").writeText(
            """
            [versions]
            kotlin = "2.3.0"

            [plugins]
            kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }

            [libraries]
            okhttp = { module = "com.squareup.okhttp3:okhttp", version = "4.12.0" }
            """.trimIndent()
        )
        projectDir.resolve(".github/CODEOWNERS").writeText(
            """
            *              @org/platform
            /core/         @org/core-team
            """.trimIndent()
        )
        projectDir.resolve(".aalekh/modules.json").writeText(
            """
            {
              "modules": [
                {
                  "path": ":core:domain",
                  "purpose": "Pure business rules. No Android, no IO, no frameworks.",
                  "owner": "domain-guild",
                  "status": "frozen"
                }
              ]
            }
            """.trimIndent()
        )
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "inventory-test"
            include(":core:domain", ":feature:login")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """aalekh { openBrowserAfterReport.set(false) }"""
        )
        projectDir.resolve("core/domain/build.gradle.kts").writeText(
            """
            plugins { alias(libs.plugins.kotlinJvm) }
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
            """.trimIndent()
        )
        projectDir.resolve("feature/login/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":core:domain")) }
            """.trimIndent()
        )

        gradleRunner("aalekhExtract", "--no-configuration-cache").build()
        val graph = projectDir.resolve("build/tmp/aalekh/graph.json").readText()

        assertTrue(
            graph.contains(""""alias":"kotlinJvm""""),
            "a plugin applied through a catalog alias must record the alias: $graph"
        )
        assertTrue(
            graph.contains(""""coordinates":"org.jetbrains.kotlin.jvm""""),
            "the catalog must resolve the alias to a real plugin id: $graph"
        )
        assertTrue(
            graph.contains(""""javaToolchain":"17""""),
            "a declared Java toolchain must be recorded: $graph"
        )
        assertTrue(
            graph.contains("@org/core-team"),
            "CODEOWNERS must give ownership for free when no teams { } block is declared: $graph"
        )
        assertTrue(
            graph.contains("Pure business rules"),
            "declared module metadata must reach the graph verbatim: $graph"
        )
        assertTrue(
            graph.contains(""""owner":"domain-guild""""),
            "a declared owner must be recorded: $graph"
        )
        assertFalse(
            graph.contains("HelpTasksPlugin"),
            "Gradle's own infrastructure plugins must not be carried in the payload: $graph"
        )
    }

    @Test
    fun `a malformed module metadata file warns and never fails the build`() {
        setupMultiModuleProject()
        projectDir.resolve(".aalekh").mkdirs()
        projectDir.resolve(".aalekh/modules.json").writeText("{ this is not json")

        val result = gradleRunner("aalekhExtract", "--no-configuration-cache").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhExtract")?.outcome)
        assertTrue(
            result.output.contains("could not read .aalekh/modules.json"),
            "a broken metadata file must be reported, not swallowed silently",
        )
    }

    @Test
    fun `extraction records KMP source sets on multiplatform modules only`() {
        projectDir.resolve("shared").mkdirs()
        projectDir.resolve("jvmlib").mkdirs()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "kmp-test"
            include(":shared", ":jvmlib")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """aalekh { openBrowserAfterReport.set(false) }"""
        )
        projectDir.resolve("shared/build.gradle.kts").writeText(
            """
            plugins { kotlin("multiplatform") version "2.3.0" }
            kotlin { jvm() }
            """.trimIndent()
        )
        projectDir.resolve("jvmlib/build.gradle.kts").writeText(
            """plugins { kotlin("jvm") version "2.3.0" }"""
        )

        gradleRunner("aalekhExtract", "--no-configuration-cache").build()
        val graph = projectDir.resolve("build/tmp/aalekh/graph.json").readText()

        assertTrue(
            graph.contains("commonMain") && graph.contains("jvmMain"),
            "a multiplatform module must record its source sets, so the inspector and the " +
                    "per-source-set rules have something to work with: $graph"
        )
        assertTrue(
            graph.contains(""""path":":jvmlib","name":"jvmlib","type":"JVM_LIBRARY","plugins""") &&
                    graph.substringAfter(""""path":":jvmlib"""").substringBefore("}")
                        .contains(""""sourceSets":[]"""),
            "a non-multiplatform module must record no source sets: $graph"
        )
    }

    @Test
    fun `aalekhExtract is configuration cache compatible on second run`() {
        setupJavaModuleProject()

        gradleRunner("aalekhExtract", "--configuration-cache").build()
        val secondRun = gradleRunner("aalekhExtract", "--configuration-cache").build()

        assertTrue(
            secondRun.output.contains("Reusing configuration cache") ||
                    secondRun.output.contains("Configuration cache entry reused"),
            "Second run should reuse the configuration cache"
        )
    }

    @Test
    fun `aalekhExtract reruns when a build file dependency order changes`() {
        setupMultiModuleProject()
        gradleRunner("aalekhExtract").build()
        val loginBuildFile = projectDir.resolve("feature/login/build.gradle.kts")
        val before = projectDir.resolve("build/tmp/aalekh/graph.json").readText()

        // Swapping the two declarations changes only the recorded lines. Without the build files as
        // a task input the task would stay UP-TO-DATE and report stale line numbers.
        loginBuildFile.writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:domain"))
            }
            """.trimIndent()
        )
        val rerun = gradleRunner("aalekhExtract").build()

        assertEquals(TaskOutcome.SUCCESS, rerun.task(":aalekhExtract")?.outcome)
        assertFalse(
            projectDir.resolve("build/tmp/aalekh/graph.json").readText() == before,
            "reordering dependency declarations must change the recorded declaration lines"
        )
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

    @Test
    fun `aalekhReport lists the applied rules in the report summary`() {
        setupLayerViolationProject()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()
        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertTrue(
            html.contains(""""id":"layer-dependency""""),
            "the configured layer rule must reach summary.rules so the Rules panel can list it"
        )
        assertTrue(
            html.contains("""data-p="rules""""),
            "the report shell must ship the Rules tab"
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

    // Extraction walks rootProject.subprojects, so the external dep must be declared in a
    // subproject (not the root). [aalekhBlock] is the root build.gradle.kts content.
    private fun setupExternalDepProject(aalekhBlock: String) {
        projectDir.resolve("app").mkdirs()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "ext-dep-test"
            include(":app")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(aalekhBlock)
        projectDir.resolve("app/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
            """.trimIndent()
        )
    }

    @Test
    fun `graph extraction captures external dependency coordinates`() {
        setupExternalDepProject("""aalekh { openBrowserAfterReport.set(false) }""")
        gradleRunner("aalekhExtract", "--no-configuration-cache").build()
        val graph = projectDir.resolve("build/tmp/aalekh/graph.json").readText()
        assertTrue(graph.contains("com.squareup.okhttp3"), "external dep group must be captured")
        assertTrue(graph.contains("okhttp"), "external dep name must be captured")
        assertTrue(graph.contains("4.12.0"), "external dep version must be captured")
    }

    @Test
    fun `includeExternalDependencies false excludes external dependencies`() {
        setupExternalDepProject(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                includeExternalDependencies.set(false)
            }
            """.trimIndent()
        )
        gradleRunner("aalekhExtract", "--no-configuration-cache").build()
        val graph = projectDir.resolve("build/tmp/aalekh/graph.json").readText()
        assertFalse(graph.contains("okhttp"), "external deps must be excluded when the flag is off")
        assertTrue(
            graph.contains("\"externalDependencies\":[]"),
            "the external list must be empty when opted out",
        )
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
    fun `aalekhCheck generates Code Climate output for GitLab`() {
        setupLayerViolationProject()
        gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        val ccFile = projectDir.resolve("build/reports/aalekh/aalekh-codeclimate.json")
        assertTrue(ccFile.exists(), "Code Climate report was not generated")
        val cc = ccFile.readText()
        assertTrue(cc.contains("\"check_name\""), "Code Climate issues must carry a check_name")
        assertTrue(cc.contains("\"fingerprint\""), "Code Climate issues must carry a fingerprint")
        assertTrue(cc.contains("\"severity\""), "Code Climate issues must carry a severity")
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
        assertTrue(
            Regex("""build\.gradle\.kts:\d+""").containsMatchIn(result.output),
            "the advice must point at the exact line to delete, not just the file: ${result.output}",
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

        val dot = projectDir.resolve("build/reports/aalekh/aalekh-graph.dot")
        assertTrue(dot.exists(), "aalekhMermaid must also write the Graphviz DOT file")
        val dotText = dot.readText()
        assertTrue(dotText.contains("digraph Aalekh {"), "DOT output must declare a digraph")
        assertTrue(dotText.contains("\":core:domain\""), "DOT output must name modules by path")
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

    @Test
    fun `aalekhMermaid honours the focus and exclude filters`() {
        setupMultiModuleProject()
        // exclude: :core:data must not appear in the diagram.
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                mermaid { exclude(":core:data") }
            }
            """.trimIndent()
        )
        gradleRunner("aalekhMermaid", "--no-configuration-cache").build()
        val excluded = projectDir.resolve("build/reports/aalekh/aalekh-graph.mmd").readText()
        assertTrue(excluded.contains(":core:domain"), "unfiltered modules must remain in the diagram")
        assertFalse(excluded.contains(":core:data"), "an excluded module must be gone from the diagram")

        // focus at depth 0: keep only :core:domain.
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                mermaid { focus(":core:domain"); depth(0) }
            }
            """.trimIndent()
        )
        gradleRunner("aalekhMermaid", "--no-configuration-cache").build()
        val focused = projectDir.resolve("build/reports/aalekh/aalekh-graph.mmd").readText()
        assertTrue(focused.contains(":core:domain"), "the focused module must be present")
        assertFalse(focused.contains(":feature:login"), "a module outside the focus must be absent")
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

    @Test
    fun `forbid apiOnly fails on an api edge but ignores an implementation edge`() {
        listOf("public", "internal", "consumer").forEach { projectDir.resolve(it).mkdirs() }
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "apionly-test"
            include(":public", ":internal", ":consumer")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh {
                openBrowserAfterReport.set(false)
                forbid {
                    from(":public")
                    to(":internal")
                    apiOnly()
                    because(":internal must stay an implementation detail")
                }
            }
            """.trimIndent()
        )
        // :public re-exports :internal via api (violation); :consumer uses it via implementation (fine).
        projectDir.resolve("public/build.gradle.kts").writeText(
            """
            plugins { `java-library` }
            dependencies { api(project(":internal")) }
            """.trimIndent()
        )
        projectDir.resolve("consumer/build.gradle.kts").writeText(
            """
            plugins { `java-library` }
            dependencies { implementation(project(":internal")) }
            """.trimIndent()
        )
        projectDir.resolve("internal/build.gradle.kts").writeText("""plugins { `java-library` }""")

        val result = gradleRunner("aalekhCheck", "--no-configuration-cache").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":aalekhCheck")?.outcome)
        assertTrue(
            result.output.contains(":public") && result.output.contains("expose"),
            "the api re-export from :public must be flagged as an exposure",
        )
        assertFalse(
            result.output.contains(":consumer"),
            "an implementation dependency must not trip an apiOnly rule",
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
    fun `aalekhReport surfaces hidden coupling from the temporal analysis`() {
        // :a and :b declare no dependency on each other but always change together -> hidden coupling.
        listOf("a", "b").forEach { projectDir.resolve(it).mkdirs() }
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "hidden-test"
            include(":a", ":b")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText("""aalekh { openBrowserAfterReport.set(false) }""")
        projectDir.resolve("a/build.gradle.kts").writeText("""plugins { kotlin("jvm") version "2.3.0" }""")
        projectDir.resolve("b/build.gradle.kts").writeText("""plugins { kotlin("jvm") version "2.3.0" }""")
        writeSource("a/src/main/kotlin/A.kt", "object A { const val V = 0 }")
        writeSource("b/src/main/kotlin/B.kt", "object B { const val V = 0 }")
        runGit("init", "-q")
        gitCommitAll("init")
        for (i in 1..3) {
            writeSource("a/src/main/kotlin/A.kt", "object A { const val V = $i }")
            writeSource("b/src/main/kotlin/B.kt", "object B { const val V = $i }")
            gitCommitAll("change $i")
        }

        gradleRunner("aalekhTemporal", "--no-configuration-cache").build()
        gradleRunner("aalekhReport", "--no-configuration-cache").build()

        val html = projectDir.resolve("build/reports/aalekh/index.html").readText()
        assertTrue(
            html.contains("\"hiddenCoupling\":[{\"a\":\":a\",\"b\":\":b\""),
            "the report must inject the hidden-coupling pair produced by aalekhTemporal",
        )
        assertTrue(
            Regex("\"churn\":\\{[^}]*\":a\":").containsMatchIn(html),
            "the report must inject per-module churn for the inspector",
        )
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
    fun `aalekhMainSequence writes an abstractness-vs-instability report`() {
        listOf("api", "app").forEach { projectDir.resolve(it).mkdirs() }
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins { id("io.github.shivathapaa.aalekh") }
            rootProject.name = "mainseq-test"
            include(":api", ":app")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            aalekh { openBrowserAfterReport.set(false) }
            """.trimIndent()
        )
        projectDir.resolve("api/build.gradle.kts").writeText("""plugins { kotlin("jvm") version "2.3.0" }""")
        projectDir.resolve("app/build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") version "2.3.0" }
            dependencies { implementation(project(":api")) }
            """.trimIndent()
        )
        // :api is a stable abstraction (an interface); :app is an unstable concrete consumer.
        writeSource("api/src/main/kotlin/Repo.kt", "package api\ninterface Repo")
        writeSource("app/src/main/kotlin/App.kt", "package app\nclass App")

        val result = gradleRunner("aalekhMainSequence", "--no-configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhMainSequence")?.outcome)

        val md = projectDir.resolve("build/reports/aalekh/aalekh-main-sequence.md").readText()
        assertTrue(md.contains("main sequence"), "the report must be titled")
        assertTrue(md.contains(":api") && md.contains(":app"), "both modules must be analysed")
        assertTrue(md.contains("Abstractness"), "the module table must be present")

        val json = projectDir.resolve("build/reports/aalekh/aalekh-main-sequence.json")
        assertTrue(json.exists(), "aalekhMainSequence must write the JSON artefact")
        assertTrue(json.readText().contains("\"averageDistance\""), "JSON must carry the average distance")
    }

    // Custom-metric SPI

    @Test
    fun `aalekhMetrics writes a custom-metrics report even with no providers`() {
        setupMultiModuleProject()
        val result = gradleRunner("aalekhMetrics", "--no-configuration-cache").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":aalekhMetrics")?.outcome)

        val md = projectDir.resolve("build/reports/aalekh/aalekh-custom-metrics.md")
        assertTrue(md.exists(), "aalekhMetrics must write the Markdown report")
        assertTrue(
            md.readText().contains("No custom metric providers were discovered"),
            "with no providers on the classpath the report must explain how to register one",
        )

        val json = projectDir.resolve("build/reports/aalekh/aalekh-custom-metrics.json")
        assertTrue(json.exists(), "aalekhMetrics must write the JSON artefact")
        assertTrue(json.readText().contains("\"metrics\""), "JSON must carry the metrics envelope")
    }

    @Test
    fun `aalekhMetrics is configuration cache compatible on second run`() {
        // Single java-library module, mirroring the aalekhReport CC test, to avoid the
        // Kotlin-Gradle-plugin build-service-across-sibling-projects quirk that breaks CC store.
        setupJavaModuleProject()
        gradleRunner("aalekhMetrics", "--configuration-cache").build()
        val secondRun = gradleRunner("aalekhMetrics", "--configuration-cache").build()
        assertTrue(
            secondRun.output.contains("Reusing configuration cache") ||
                    secondRun.output.contains("Configuration cache entry reused"),
            "Second aalekhMetrics run should reuse the configuration cache",
        )
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