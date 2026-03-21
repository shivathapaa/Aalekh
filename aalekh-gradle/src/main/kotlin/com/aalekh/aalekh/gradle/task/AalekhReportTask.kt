package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.ReportCoordinator
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Generates the Aalekh HTML dependency report.
 *
 * Run: `./gradlew aalekhReport`
 * Output: `<projectRoot>/build/reports/aalekh/index.html`
 */
@DisableCachingByDefault(because = "HTML reports should always reflect the current project state; the task also opens a browser window")
public abstract class AalekhReportTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    @get:Input
    public abstract val openBrowser: Property<Boolean>

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    @get:Input
    public abstract val layerEntries: ListProperty<String>

    @get:Input
    public abstract val featurePattern: Property<String>

    @get:Input
    public abstract val featureAllowedPairs: ListProperty<String>

    @get:Input
    public abstract val ruleEntries: ListProperty<String>

    init {
        group = "aalekh"
        description = "Generates an interactive HTML module dependency graph. " +
                "Run: ./gradlew aalekhReport"
    }

    @TaskAction
    public fun generate() {
        val graph = readGraph()
        val ruleEngine = RuleEngine.fromConfig(
            layerEntries = layerEntries.get(),
            featurePattern = featurePattern.getOrElse(""),
            featureAllowedPairs = featureAllowedPairs.get(),
            ruleEntries = ruleEntries.get(),
        )
        val report = ReportCoordinator(graph, ruleEngine.evaluate(graph), projectName.get())
        val outputPath = outputFile.get().asFile

        outputPath.parentFile.mkdirs()
        outputPath.writeText(report.generateHtml())

        logger.lifecycle("Aalekh report → ${outputPath.absolutePath}")

        if (openBrowser.getOrElse(true)) {
            openInBrowser(outputPath.absolutePath)
        }
    }

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
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (finished && process.exitValue() != 0) {
                logger.warn("Aalekh: browser open failed. Open manually: $absolutePath")
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
 * Run: `./gradlew aalekhCheck`
 * Also runs as part of `./gradlew check`.
 *
 * Outputs:
 * - `<outputDir>/aalekh-results.xml`  - JUnit XML for CI systems
 * - `<outputDir>/aalekh-results.json` - Full machine-readable report
 * - `<outputDir>/aalekh-results.sarif` - SARIF for GitHub code scanning annotations
 */
@CacheableTask
public abstract class AalekhCheckTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    @get:OutputDirectory
    public abstract val outputDir: DirectoryProperty

    // Rule configuration inputs
    // All rule config is passed as plain strings so the task remains CC-safe.
    // RuleEngine.fromConfig() reconstructs the full engine from these at execution time.

    /**
     * Serialized layer declarations from the `layers { }` DSL block.
     * Format per entry: `"layerName|pat1,pat2|allowedLayer1,allowedLayer2|hasRestriction"`.
     */
    @get:Input
    public abstract val layerEntries: ListProperty<String>

    /**
     * Glob pattern for feature modules from `featureIsolation { featurePattern = "..." }`.
     * Empty string means the feature isolation rule is inactive.
     */
    @get:Input
    public abstract val featurePattern: Property<String>

    /**
     * Serialized allow-pairs from `featureIsolation { allow(...) }`.
     * Format per entry: `"fromPattern->toPattern"`.
     */
    @get:Input
    public abstract val featureAllowedPairs: ListProperty<String>

    /**
     * Serialized rule overrides from the `rules { }` DSL block.
     * Format per entry: `"ruleId:severity:LEVEL"` or `"ruleId:suppress:pattern"`.
     */
    @get:Input
    public abstract val ruleEntries: ListProperty<String>

    init {
        group = "aalekh"
        description = "Evaluates architecture rules. Fails the build on ERROR-level violations. " +
                "Run: ./gradlew aalekhCheck"
    }

    @TaskAction
    public fun check() {
        val graph = readGraph()
        val ruleEngine = RuleEngine.fromConfig(
            layerEntries = layerEntries.get(),
            featurePattern = featurePattern.getOrElse(""),
            featureAllowedPairs = featureAllowedPairs.get(),
            ruleEntries = ruleEntries.get(),
        )
        val ruleResult = ruleEngine.evaluate(graph)
        val report = ReportCoordinator(graph, ruleResult, projectName.get())
        val outDir = outputDir.get().asFile

        outDir.mkdirs()
        outDir.resolve("aalekh-results.xml").writeText(report.generateJUnitXml())
        outDir.resolve("aalekh-results.json").writeText(report.generateJson())
        outDir.resolve("aalekh-results.sarif").writeText(report.generateSarif())

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