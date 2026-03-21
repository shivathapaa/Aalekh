package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.report.ReportCoordinator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Generates the interactive HTML dependency report.
 *
 * The report is a single self-contained HTML file with D3.js embedded - no server,
 * no CDN, no internet connection required at render time. It opens automatically
 * in the default browser after generation (disable with `openBrowserAfterReport.set(false)`
 * for CI environments).
 *
 * Run: `./gradlew aalekhReport`
 * Output: `<projectRoot>/build/reports/aalekh/index.html`
 *
 * When [exportMetrics] is true, also writes `aalekh-metrics.csv` alongside the HTML file.
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

    /** When true, writes `aalekh-metrics.csv` alongside the HTML report. */
    @get:Input
    public abstract val exportMetrics: Property<Boolean>

    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    // Rule config inputs mirror AalekhCheckTask so the HTML violations tab
    // shows exactly what the build enforces - no discrepancy between report and check.
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
        exportMetrics.convention(false)
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

        if (exportMetrics.getOrElse(false)) {
            val csvFile = outputPath.resolveSibling("aalekh-metrics.csv")
            csvFile.writeText(report.generateCsv())
            logger.lifecycle("Aalekh metrics → ${csvFile.absolutePath}")
        }

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
 * Evaluates all configured architecture rules and fails the build on `ERROR`-severity violations.
 *
 * On each run, reads the previous run's `aalekh-results.json` to extract the prior cycle count
 * for regression detection. Writes three output files:
 * - `aalekh-results.xml` - JUnit XML for CI test reporters
 * - `aalekh-results.json` - full machine-readable report envelope
 * - `aalekh-results.sarif` - SARIF 2.1 for GitHub code scanning PR annotations
 *
 * Run: `./gradlew aalekhCheck`
 * Also runs automatically as part of `./gradlew check`.
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
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        val previousCycleCount = readPreviousCycleCount(outDir)

        val ruleEngine = RuleEngine.fromConfig(
            layerEntries = layerEntries.get(),
            featurePattern = featurePattern.getOrElse(""),
            featureAllowedPairs = featureAllowedPairs.get(),
            ruleEntries = ruleEntries.get(),
            previousCycleCount = previousCycleCount,
        )
        val ruleResult = ruleEngine.evaluate(graph)
        val report = ReportCoordinator(graph, ruleResult, projectName.get())

        outDir.resolve("aalekh-results.xml").writeText(report.generateJUnitXml())
        outDir.resolve("aalekh-results.json").writeText(report.generateJson())
        outDir.resolve("aalekh-results.sarif").writeText(report.generateSarif())

        logResults(ruleResult, outDir)

        check(!ruleResult.hasBuildFailure) {
            "\nAalekh: ${ruleResult.errorCount} violation(s) found. " +
                    "Run ./gradlew aalekhReport to see the full interactive report."
        }
    }

    private fun logResults(ruleResult: RuleEngineResult, outDir: java.io.File) {
        if (ruleResult.violations.isEmpty()) {
            logger.lifecycle(
                "Aalekh: ✓ All rules passed (${ruleResult.rulesEvaluated} rule(s) evaluated)"
            )
            return
        }

        val errors = ruleResult.violations.filter { it.severity == Severity.ERROR }
        val warnings = ruleResult.violations.filter { it.severity == Severity.WARNING }
        val infos = ruleResult.violations.filter { it.severity == Severity.INFO }

        // Group by ruleId so output is scannable - all violations of the same type together
        val byRule = ruleResult.violations
            .filter { it.severity != Severity.INFO }
            .groupBy { it.ruleId }

        byRule.forEach { (ruleId, violations) ->
            val first = violations.first()
            val level = if (first.severity == Severity.ERROR) "ERROR" else "WARNING"
            logger.lifecycle("\nAalekh [$ruleId] $level - ${violations.size} violation(s):")
            violations.forEach { v ->
                val indent = "  "
                when (v.severity) {
                    Severity.ERROR -> logger.error("$indent✗ ${v.message}")
                    Severity.WARNING -> logger.warn("$indent⚠ ${v.message}")
                    else -> {}
                }
            }
        }

        if (infos.isNotEmpty()) {
            logger.lifecycle("\nAalekh [info] ${infos.size} informational violation(s) - see the report for details.")
        }

        val summary = buildString {
            if (errors.isNotEmpty()) append("${errors.size} error(s)")
            if (warnings.isNotEmpty()) {
                if (isNotEmpty()) append(", ")
                append("${warnings.size} warning(s)")
            }
        }
        logger.lifecycle(
            "\nAalekh: $summary found across ${ruleResult.rulesEvaluated} rule(s). " +
                    "Report: ${outDir.absolutePath}/index.html"
        )
    }

    /**
     * Reads the main-code cycle count from the previous run's results JSON.
     * Returns null when no prior results exist - the regression check is then skipped.
     */
    private fun readPreviousCycleCount(outDir: java.io.File): Int? {
        val previousJson = outDir.resolve("aalekh-results.json")
        if (!previousJson.exists()) return null
        return runCatching {
            val root = taskJson.parseToJsonElement(previousJson.readText())
                .jsonObject
            root["summary"]?.jsonObject?.get("cycleCount")?.jsonPrimitive?.intOrNull
        }.getOrNull()
    }

    private fun readGraph(): ModuleDependencyGraph =
        taskJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val taskJson = Json { ignoreUnknownKeys = true }