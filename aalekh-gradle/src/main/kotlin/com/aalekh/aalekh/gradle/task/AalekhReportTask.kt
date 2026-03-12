package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.ReportCoordinator
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/**
 * Generates the Aalekh HTML dependency report.
 *
 * Usage:  `./gradlew aalekhReport`
 * Output: `<projectRoot>/build/reports/aalekh/index.html`
 */
public abstract class AalekhReportTask : DefaultTask() {
    /** Intermediate graph JSON written by the plugin after projectsEvaluated. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    @get:Input
    public abstract val openBrowser: Property<Boolean>

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Generates an interactive HTML module dependency graph. " +
                "Run: ./gradlew aalekhReport"
    }

    @TaskAction
    public fun generate() {
        val graph = readGraph()
        val report = ReportCoordinator(graph, RuleEngine.withBuiltinRules().evaluate(graph), projectName.get())
        val outputPath = outputFile.get().asFile

        outputPath.parentFile.mkdirs()
        outputPath.writeText(report.generateHtml())

        logger.lifecycle("Aalekh report → ${outputPath.absolutePath}")

        if (openBrowser.getOrElse(true)) {
            openInBrowser(outputPath.absolutePath)
        }
    }

    /**
     * Opens the report in the default browser.
     *
     * The Gradle daemon is a headless JVM - java.awt.Desktop is never supported.
     * We go straight to the OS-level command with the plain absolute path (not a
     * file:// URI): macOS `open`, Linux `xdg-open`, Windows `explorer`.
     *
     * `ProcessBuilder` is used instead of `Runtime.exec` so we can:
     * - Redirect stderr to the build log on failure
     * - Avoid the deprecated `exec(String[])` overload warning in JDK 18+
     */
    private fun openInBrowser(absolutePath: String) {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            "mac" in os -> listOf("open", absolutePath)
            "linux" in os -> listOf("xdg-open", absolutePath)
            "windows" in os -> listOf("explorer", absolutePath)
            else -> {
                logger.info("Aalekh: unsupported OS '$os' - skipping browser open")
                return
            }
        }

        runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            // Wait briefly - if the command fails immediately we can log it.
            // Don't block indefinitely (browser startup is not our concern).
            val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (finished && process.exitValue() != 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                logger.warn("Aalekh: browser open returned non-zero exit. Command: $command. Output: $output")
            }
        }.onFailure { ex ->
            logger.warn("Aalekh: could not open browser - ${ex.message}. Open manually: $absolutePath")
        }
    }

    private fun readGraph(): ModuleDependencyGraph =
        taskJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

/**
 * Evaluates architecture rules and fails the build on violations.
 *
 * Usage: `./gradlew aalekhCheck`
 * Also runs automatically as part of `./gradlew check`.
 *
 * Outputs:
 * - `<outputDir>/aalekh-results.xml`  - JUnit XML (consumed by all CI systems)
 * - `<outputDir>/aalekh-results.json` - Machine-readable JSON
 */
public abstract class AalekhCheckTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    init {
        group = "aalekh"
        description = "Evaluates architecture rules. Fails the build on ERROR-level violations. " +
                "Run: ./gradlew aalekhCheck"
    }

    @TaskAction
    public fun check() {
        val graph = readGraph()
        val ruleResult = RuleEngine.withBuiltinRules().evaluate(graph)
        val report = ReportCoordinator(graph, ruleResult, projectName.get())
        val outDir = outputDir.get().asFile

        outDir.mkdirs()
        outDir.resolve("aalekh-results.xml").writeText(report.generateJUnitXml())
        outDir.resolve("aalekh-results.json").writeText(report.generateJson())

        if (ruleResult.violations.isEmpty()) {
            logger.lifecycle(
                "Aalekh: ✓ All architecture rules passed (${ruleResult.rulesEvaluated} rules evaluated)"
            )
        } else {
            ruleResult.violations.forEach { v ->
                when (v.severity.name) {
                    "ERROR" -> logger.error("Aalekh [${v.ruleId}] ${v.message}")
                    "WARNING" -> logger.warn("Aalekh [${v.ruleId}] ${v.message}")
                    else -> logger.info("Aalekh [${v.ruleId}] ${v.message}")
                }
            }
        }

        check(!ruleResult.hasBuildFailure) {
            "\nAalekh: ${ruleResult.errorCount} architecture violation(s) found. " +
                    "See ${outDir.absolutePath}/aalekh-results.xml for details."
        }
    }

    private fun readGraph(): ModuleDependencyGraph =
        taskJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val taskJson = Json { ignoreUnknownKeys = true }