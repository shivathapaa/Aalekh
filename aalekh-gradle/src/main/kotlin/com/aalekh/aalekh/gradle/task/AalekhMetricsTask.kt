package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.spi.CustomMetricEngine
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.custommetrics.CustomMetricReportGenerator
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Runs every custom [com.aalekh.aalekh.analysis.spi.MetricProvider] discovered on the plugin
 * classpath and writes their values as local artefacts.
 *
 * Providers are discovered automatically via the JDK `ServiceLoader` (a
 * `META-INF/services/com.aalekh.aalekh.analysis.spi.MetricProvider` entry in a jar on the plugin's
 * runtime classpath - the same classpath used for `rules { custom(...) }`). Two files are written
 * next to the HTML report:
 * - `aalekh-custom-metrics.md` - a reviewable summary of the system-wide and per-module metrics.
 * - `aalekh-custom-metrics.json` - the same data, machine-readable.
 *
 * With no providers registered the task is a no-op that still writes an empty report. A provider that
 * throws is skipped and noted in the report; it never fails the build.
 *
 * Run: `./gradlew aalekhMetrics`
 *
 * ### Caching
 * Caching is disabled: the result depends on which provider jars sit on the build classpath, which is
 * not a declared task input. The task is still configuration-cache safe; every input is a plain value.
 */
@DisableCachingByDefault(because = "result depends on classpath-discovered providers, not declared inputs")
public abstract class AalekhMetricsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    /** Machine-readable report, written to `aalekh-custom-metrics.json`. */
    @get:OutputFile
    public abstract val jsonFile: RegularFileProperty

    /** Reviewable Markdown, written to `aalekh-custom-metrics.md`. */
    @get:OutputFile
    public abstract val markdownFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Runs custom MetricProvider SPI implementations and writes their values " +
                "(.md + .json). Run: ./gradlew aalekhMetrics"
    }

    @TaskAction
    public fun analyze() {
        val graph = readGraph()
        val report = CustomMetricEngine.loadAndCompute(graph, javaClass.classLoader)

        val md = markdownFile.get().asFile
        md.parentFile.mkdirs()
        md.writeText(CustomMetricReportGenerator.markdown(report, projectName.get()))

        val json = jsonFile.get().asFile
        json.parentFile.mkdirs()
        json.writeText(CustomMetricReportGenerator.json(report))

        report.providerFailures.forEach { logger.warn("Aalekh custom metric skipped - $it") }
        if (report.metrics.isEmpty()) {
            logger.lifecycle("Aalekh custom metrics: no providers contributed a value → file://${md.absolutePath}")
        } else {
            logger.lifecycle(
                "Aalekh custom metrics: ${report.metrics.size} provider(s) contributed → file://${md.absolutePath}"
            )
        }
    }

    private fun readGraph(): ModuleDependencyGraph =
        metricsJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val metricsJson = Json { ignoreUnknownKeys = true }
