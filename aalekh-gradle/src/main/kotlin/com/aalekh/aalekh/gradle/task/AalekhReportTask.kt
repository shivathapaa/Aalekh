package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.baseline.ViolationBaseline
import com.aalekh.aalekh.analysis.graph.CycleAdvisor
import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.analysis.metrics.MetricGate
import com.aalekh.aalekh.analysis.metrics.MetricGateEvaluator
import com.aalekh.aalekh.analysis.rules.LayerSpecParser
import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.MainSequenceReport
import com.aalekh.aalekh.model.MetricSnapshot
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleMainSequence
import com.aalekh.aalekh.model.TemporalCouplingReport
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import com.aalekh.aalekh.report.ReportCoordinator
import com.aalekh.aalekh.report.codeclimate.CodeClimateReporter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
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

    /** Serialized `forbid { }` predicate rules; mirrors [AalekhCheckTask.forbidEntries]. */
    @get:Input
    public abstract val forbidEntries: ListProperty<String>

    /** Serialized reachability rules; mirrors [AalekhCheckTask.reachabilityEntries]. */
    @get:Input
    public abstract val reachabilityEntries: ListProperty<String>

    /** Serialized per-source-set rules; mirrors [AalekhCheckTask.sourceSetEntries]. */
    @get:Input
    public abstract val sourceSetEntries: ListProperty<String>

    /**
     * Serialized team-ownership map from the `teams { }` DSL block.
     * Format: `"team=pat1,pat2;team2=pat3"` (from `TeamOwnershipConfig.toInputString`).
     * Empty string means no teams are declared and the ownership overlay stays off.
     */
    @get:Input
    public abstract val teamEntries: Property<String>

    /**
     * Path to the rolling trend history JSON file (`build/aalekh/trend.json`).
     * Marked `@Internal` so it is not a CC input/output - the file is written as a
     * side-effect and failure to read/write it is always non-fatal.
     */
    @get:Internal
    public abstract val trendFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Generates an interactive HTML module dependency graph. " +
                "Run: ./gradlew aalekhReport"
        exportMetrics.convention(false)
        teamEntries.convention("")
    }

    @TaskAction
    public fun generate() {
        val graph = readGraph()
        val ruleEngine = RuleEngine.fromConfig(
            layerEntries = layerEntries.get(),
            featurePattern = featurePattern.getOrElse(""),
            featureAllowedPairs = featureAllowedPairs.get(),
            ruleEntries = ruleEntries.get(),
            forbidEntries = forbidEntries.get(),
            reachabilityEntries = reachabilityEntries.get(),
            sourceSetEntries = sourceSetEntries.get(),
        )
        val ruleResult = ruleEngine.evaluate(graph)
        // Third-party FindingProviders and ModuleClassifiers are discovered from the plugin
        // classpath, the same route custom rules and metric providers use.
        val report = ReportCoordinator.withExtensions(
            graph = graph,
            ruleResult = ruleResult,
            projectName = projectName.get(),
            classLoader = javaClass.classLoader,
        )
        report.extensionFailures.forEach { logger.warn("Aalekh: extension skipped - $it") }
        val outputPath = outputFile.get().asFile

        // Collect trend history before writing the report so the current run is included.
        val trendJson = updateAndReadTrend(graph)
        val teamOwners = parseTeamEntries(teamEntries.getOrElse(""))
        val mainSequence = readMainSequence(outputPath.resolveSibling("aalekh-main-sequence.json"))
        val temporal = readTemporalReport(outputPath.resolveSibling("aalekh-temporal.json"))

        outputPath.parentFile.mkdirs()
        outputPath.writeText(
            report.generateHtml(
                trendJson = trendJson,
                teamOwners = teamOwners,
                mainSequence = mainSequence,
                hiddenCoupling = temporal?.hiddenCoupling?.take(MAX_HIDDEN_COUPLING) ?: emptyList(),
                churn = temporal?.churn ?: emptyList(),
                layers = LayerSpecParser.parse(layerEntries.get()),
            )
        )

        if (exportMetrics.getOrElse(false)) {
            val csvFile = outputPath.resolveSibling("aalekh-metrics.csv")
            csvFile.writeText(report.generateCsv())
            logger.lifecycle("Aalekh metrics → file://${csvFile.absolutePath}")
        }

        logger.lifecycle("Aalekh report → file://${outputPath.absolutePath}")

        if (openBrowser.getOrElse(true)) {
            openInBrowser(outputPath.absolutePath)
        }
    }

    /**
     * Reads the existing trend history from `build/aalekh/trend.json`, appends a new entry
     * built from the current graph, trims the list to the most recent 30 entries, writes the
     * updated list back to disk, and returns the final JSON array string for HTML injection.
     *
     * A failure at any step is swallowed so that a disk error never fails the build.
     */
    private fun updateAndReadTrend(graph: ModuleDependencyGraph): String {
        val trendFile = trendFile.orNull?.asFile ?: return "[]"

        // 1. Read existing entries (returns empty list on any error).
        val existing: List<JsonObject> = runCatching {
            val text = trendFile.readText()
            taskJson.parseToJsonElement(text).jsonArray
                .mapNotNull { it as? JsonObject }
        }.getOrElse { emptyList() }

        // 2. Build a new trend entry from the current graph.
        val summary = GraphAnalyzer.summary(graph)
        val cycleCount = summary.cycleCount
        val godModuleCount = summary.godModuleCount
        val critPathLen = summary.criticalPathLength
        val avgInstability = summary.averageInstability

        val newEntry = buildJsonObject {
            put("ts", System.currentTimeMillis())
            put("modules", summary.totalModules)
            put("edges", summary.totalEdges)
            put("cycles", cycleCount)
            put("godModules", godModuleCount)
            put("critPathLen", critPathLen)
            put("avgInstability", avgInstability)
        }

        // 3. Append and keep only the last 30 entries.
        val updated = (existing + newEntry).takeLast(30)

        // 4. Serialise back to JSON string.
        val updatedJson = JsonArray(updated).toString()

        // 5. Write to disk - failure is non-fatal.
        runCatching {
            trendFile.parentFile.mkdirs()
            trendFile.writeText(updatedJson)
        }.onFailure { ex ->
            logger.warn("Aalekh: could not write trend history - ${ex.message}")
        }

        return updatedJson
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

    /**
     * Reads the abstractness/instability points written by `aalekhMainSequence` from the sibling
     * `aalekh-main-sequence.json`, so the report can draw the A/I scatter. Returns an empty list when
     * the file is absent (the task was not run) or unreadable - the scatter panel then stays hidden.
     * Never fatal: the report must render whether or not main-sequence data exists.
     */
    private fun readMainSequence(file: java.io.File): List<ModuleMainSequence> =
        if (!file.exists()) {
            emptyList()
        } else {
            runCatching {
                taskJson.decodeFromString<MainSequenceReport>(file.readText()).modules
            }.getOrElse {
                logger.info("Aalekh: could not read main-sequence data for the scatter - ${it.message}")
                emptyList()
            }
        }

    /**
     * Reads the temporal-coupling report written by `aalekhTemporal` from the sibling
     * `aalekh-temporal.json`, so the report can surface hidden coupling and per-module churn. Returns
     * null when the file is absent or unreadable; never fatal - the report renders regardless.
     */
    private fun readTemporalReport(file: java.io.File): TemporalCouplingReport? =
        if (!file.exists()) {
            null
        } else {
            runCatching {
                taskJson.decodeFromString<TemporalCouplingReport>(file.readText())
            }.getOrElse {
                logger.info("Aalekh: could not read temporal data for the report - ${it.message}")
                null
            }
        }

    /**
     * Reconstructs the team → glob-pattern map from the serialized `teams { }` input.
     * Inverse of `TeamOwnershipConfig.toInputString`. Entries without a name or with no
     * patterns are dropped; a blank input yields an empty map (overlay disabled).
     */
    private fun parseTeamEntries(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";")
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = entry.substring(0, separator)
                val patterns = entry.substring(separator + 1)
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (patterns.isEmpty()) null else name to patterns
            }
            .toMap()
    }
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
 *
 * **Caching is intentionally disabled.** `preventRegression` reads the prior run's
 * `aalekh-results.json` to compare cycle counts; that file lives in this task's own
 * `@OutputDirectory` and is not declared as an `@Input`. Caching the task would let
 * Gradle restore a stale PASS and skip the regression check entirely, which is the
 * exact failure mode this rule exists to catch.
 */
@DisableCachingByDefault(because = "regression detection compares against the previous run's output; caching could silently mask a newly introduced cycle")
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

    /**
     * Serialized `forbid { }` predicate rules. Format per entry:
     * `"<fromKind>|<fromValue>|<toKind>|<toValue>|<severity>|<reason>"`. CC-safe plain strings.
     */
    @get:Input
    public abstract val forbidEntries: ListProperty<String>

    /**
     * Serialized reachability rules from `rules { forbidReachable(...) / mustBeReachableFrom(...) }`.
     * Format per entry: `"<kind>|<fromGlob>|<toGlob>|<severity>|<reason>"`. CC-safe plain strings.
     */
    @get:Input
    public abstract val reachabilityEntries: ListProperty<String>

    /**
     * Serialized per-source-set rules from `rules { forbidSourceSetDependency(...) }`.
     * Format per entry: `"<sourceSet>|<toKind>|<toValue>|<severity>|<reason>"`. CC-safe plain strings.
     */
    @get:Input
    public abstract val sourceSetEntries: ListProperty<String>

    /**
     * The committed baseline file (`aalekh-baseline.json`) written by `aalekhBaseline`.
     * When present, violations recorded in it are suppressed and only new ones fail the build.
     *
     * Read manually rather than declared as an `@InputFile` because it is optional (absent until
     * the first `aalekhBaseline` run), lives in the source tree, and this task never caches - so
     * fingerprinting it as an input would add nothing.
     */
    @get:Internal
    public abstract val baselineFile: RegularFileProperty

    /**
     * Metric keys enabled as quality gates via `qualityGates { forbidRegression(...) }`. Each names a
     * structural metric that must not exceed its baseline value. Empty means no gates are enforced.
     */
    @get:Input
    public abstract val qualityGateMetrics: ListProperty<String>

    /** Severity name (`ERROR` / `WARNING` / `INFO`) assigned to a metric regression. */
    @get:Input
    public abstract val qualityGateSeverity: Property<String>

    init {
        group = "aalekh"
        description = "Evaluates architecture rules. Fails the build on ERROR-level violations. " +
                "Run: ./gradlew aalekhCheck"
        qualityGateSeverity.convention(Severity.ERROR.name)
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
            forbidEntries = forbidEntries.get(),
            reachabilityEntries = reachabilityEntries.get(),
            sourceSetEntries = sourceSetEntries.get(),
        )
        val rawResult = ruleEngine.evaluate(graph)

        // Apply the committed baseline (if any): known violations are frozen, only new ones remain.
        val baselined = ViolationBaseline.apply(rawResult.violations, readBaselineFingerprints())
        if (baselined.baselinedCount > 0) {
            logger.lifecycle(
                "Aalekh: ${baselined.baselinedCount} baselined violation(s) suppressed " +
                        "(from ${baselineFile.orNull?.asFile?.name ?: "baseline"})."
            )
        }

        // Metric-delta quality gates run on top of the baselined violations - a regression versus the
        // committed baseline metrics is a fresh violation, never itself suppressed by the baseline.
        val gateViolations = evaluateQualityGates(graph)
        val ruleResult = rawResult.copy(violations = baselined.newViolations + gateViolations)

        val report = ReportCoordinator(graph, ruleResult, projectName.get())

        outDir.resolve("aalekh-results.xml").writeText(report.generateJUnitXml())
        outDir.resolve("aalekh-results.json").writeText(report.generateJson())
        outDir.resolve("aalekh-results.sarif").writeText(report.generateSarif())
        outDir.resolve("aalekh-codeclimate.json").writeText(CodeClimateReporter.generate(graph, ruleResult))

        logResults(ruleResult, outDir)
        logCycleAdvice(graph, ruleResult)

        check(!ruleResult.hasBuildFailure) {
            "\nAalekh: ${ruleResult.errorCount} violation(s) found. " +
                    "Run ./gradlew aalekhReport to see the full interactive report."
        }
    }

    /**
     * Reads the fingerprint set from the baseline file. Returns an empty set when no baseline is
     * configured, the file does not exist, or it cannot be parsed - so a missing or malformed
     * baseline simply disables the freeze rather than failing the build.
     */
    private fun readBaselineFingerprints(): Set<String> {
        val file = baselineFile.orNull?.asFile?.takeIf { it.exists() } ?: return emptySet()
        return runCatching {
            taskJson.parseToJsonElement(file.readText())
                .jsonObject["fingerprints"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.toSet()
                .orEmpty()
        }.getOrElse { emptySet() }
    }

    /**
     * Evaluates the configured metric-delta quality gates against the committed baseline metrics.
     * Returns one violation per metric that regressed. No gates or no baseline metrics -> no
     * violations (nothing to compare against), mirroring cycle `preventRegression`.
     */
    private fun evaluateQualityGates(graph: ModuleDependencyGraph): List<Violation> {
        val gates = qualityGateMetrics.getOrElse(emptyList())
            .mapNotNull { MetricGate.fromKey(it) }
            .toSet()
        if (gates.isEmpty()) return emptyList()

        val baselineMetrics = readBaselineMetrics()
        return when (baselineMetrics) {
            null -> {
                logger.lifecycle(
                    "Aalekh: quality gates are configured but the baseline has no metrics snapshot - " +
                            "run ./gradlew aalekhBaseline to record one."
                )
                emptyList()
            }
            else -> {
                val severity = Severity.entries.firstOrNull { it.name == qualityGateSeverity.getOrElse("ERROR") }
                    ?: Severity.ERROR
                val current = MetricGateEvaluator.snapshot(GraphAnalyzer.summary(graph))
                MetricGateEvaluator.evaluate(current, baselineMetrics, gates, severity)
            }
        }
    }

    /** Reads the metrics snapshot from the baseline file, or null when absent or unparseable. */
    private fun readBaselineMetrics(): MetricSnapshot? {
        val file = baselineFile.orNull?.asFile?.takeIf { it.exists() } ?: return null
        return runCatching {
            val root = taskJson.parseToJsonElement(file.readText()).jsonObject
            root["metrics"]?.let { taskJson.decodeFromJsonElement(MetricSnapshot.serializer(), it) }
        }.getOrNull()
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
     * When the run reported any cycle, prints the specific edges to remove to break it - the
     * feedback-arc-set advice from [CycleAdvisor]. Turns a red `no-cyclic-dependencies` failure into
     * a concrete to-do rather than just a diagnosis.
     */
    private fun logCycleAdvice(graph: ModuleDependencyGraph, ruleResult: RuleEngineResult) {
        if (ruleResult.violations.none { it.ruleId == "no-cyclic-dependencies" }) return
        val advice = CycleAdvisor.suggestBreaks(graph)
        if (advice.isEmpty()) return

        logger.lifecycle("\nAalekh: to break the detected cycle(s), consider removing:")
        advice.forEach { suggestion ->
            val location = suggestion.buildFilePath?.let { path ->
                suggestion.declarationLine?.let { line -> "$path:$line" } ?: path
            } ?: "${suggestion.from}'s build file"
            logger.lifecycle("  • ${suggestion.configuration}(project(\"${suggestion.to}\")) in $location")
        }
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

/** Cap on hidden-coupling pairs surfaced in the report card, keeping the alert scannable. */
private const val MAX_HIDDEN_COUPLING = 8