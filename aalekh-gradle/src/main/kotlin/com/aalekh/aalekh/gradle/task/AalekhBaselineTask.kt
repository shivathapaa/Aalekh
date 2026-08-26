package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.baseline.ViolationBaseline
import com.aalekh.aalekh.analysis.rules.RuleEngine
import com.aalekh.aalekh.model.AalekhBuildConfig
import com.aalekh.aalekh.model.ModuleDependencyGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.time.Instant

/**
 * Writes the current architecture violations to a committed **baseline** file.
 *
 * Run `./gradlew aalekhBaseline` once, commit the generated `aalekh-baseline.json`, and from then
 * on `aalekhCheck` suppresses every violation recorded in it and fails only on new ones. This is
 * the standard "freeze the debt, block regressions" workflow that lets a team turn on strict rules
 * against a large existing codebase without fixing everything first.
 *
 * The baseline records one stable fingerprint (`ruleId|source`) per non-`INFO` violation. Re-run
 * the task after you have legitimately fixed or accepted violations to refresh it.
 *
 * Caching is disabled: the task deliberately overwrites a source-tree file to reflect current state.
 */
@DisableCachingByDefault(because = "writes a source-tree baseline file that must reflect the current run")
public abstract class AalekhBaselineTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    @get:Input
    public abstract val layerEntries: ListProperty<String>

    @get:Input
    public abstract val featurePattern: Property<String>

    @get:Input
    public abstract val featureAllowedPairs: ListProperty<String>

    @get:Input
    public abstract val ruleEntries: ListProperty<String>

    /** The baseline file to write, e.g. `<rootDir>/aalekh-baseline.json`. */
    @get:OutputFile
    public abstract val baselineOutputFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Records current violations as a committed baseline so aalekhCheck fails " +
                "only on new ones. Run: ./gradlew aalekhBaseline"
    }

    @TaskAction
    public fun writeBaseline() {
        val graph = readGraph()
        val ruleEngine = RuleEngine.fromConfig(
            layerEntries = layerEntries.get(),
            featurePattern = featurePattern.getOrElse(""),
            featureAllowedPairs = featureAllowedPairs.get(),
            ruleEntries = ruleEntries.get(),
        )
        val fingerprints = ViolationBaseline.toFingerprints(ruleEngine.evaluate(graph).violations)

        val envelope = buildJsonObject {
            put("version", AalekhBuildConfig.VERSION)
            put("generatedAt", Instant.now().toString())
            put("fingerprints", JsonArray(fingerprints.map { JsonPrimitive(it) }))
        }

        val file = baselineOutputFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(baselineJson.encodeToString(JsonObject.serializer(), envelope))

        logger.lifecycle(
            "Aalekh baseline written with ${fingerprints.size} frozen violation(s) → " +
                    "file://${file.absolutePath}\n" +
                    "Commit this file. aalekhCheck will now fail only on NEW violations."
        )
    }

    private fun readGraph(): ModuleDependencyGraph =
        baselineJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val baselineJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}
