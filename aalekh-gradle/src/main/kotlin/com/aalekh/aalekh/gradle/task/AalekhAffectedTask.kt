package com.aalekh.aalekh.gradle.task

import com.aalekh.aalekh.analysis.graph.AffectedGraphAnalyzer
import com.aalekh.aalekh.gradle.git.GitDiffReader
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.report.affected.AffectedReportGenerator
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
 * Computes the **affected graph** for a git diff and writes it as a local, PR-ready artefact.
 *
 * Runs `git diff` (offline, at execution time) between the configured refs, maps the changed files
 * to modules, and expands to the downstream blast radius a build must rebuild and retest. Two files
 * are written next to the HTML report:
 * - `aalekh-affected.md` - a Markdown summary a CI job can post as a pull-request comment.
 * - `aalekh-affected.json` - the same data, machine-readable (changed + affected module sets).
 *
 * Aalekh only writes local files; posting the comment is the consumer CI's job.
 *
 * Run: `./gradlew aalekhAffected`
 *
 * ### Caching
 * Caching is disabled - the output depends on live git refs, not on declared inputs. The task is
 * still configuration-cache safe (every input is a plain value).
 */
@DisableCachingByDefault(because = "output depends on live git refs, which are not declared inputs")
public abstract class AalekhAffectedTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val graphJsonFile: RegularFileProperty

    @get:Input
    public abstract val projectName: Property<String>

    /** Absolute path of the git working tree to diff (the root project directory). */
    @get:Input
    public abstract val rootDir: Property<String>

    /** Base git ref to diff against, e.g. `"origin/main"` or `"HEAD~1"`. */
    @get:Input
    public abstract val baseRef: Property<String>

    /** Head git ref; blank diffs the base against the working tree. */
    @get:Input
    public abstract val headRef: Property<String>

    /** Machine-readable report, written to `aalekh-affected.json`. */
    @get:OutputFile
    public abstract val jsonFile: RegularFileProperty

    /** PR-comment Markdown, written to `aalekh-affected.md`. */
    @get:OutputFile
    public abstract val markdownFile: RegularFileProperty

    init {
        group = "aalekh"
        description = "Computes the modules affected by a git diff and its downstream blast radius " +
                "(.md + .json). Run: ./gradlew aalekhAffected"
    }

    @TaskAction
    public fun analyze() {
        val graph = readGraph()
        val base = baseRef.getOrElse("HEAD~1")
        val head = headRef.getOrElse("")
        val changedFiles = GitDiffReader.changedFiles(rootDir.get(), base, head, logger)
        val affected = AffectedGraphAnalyzer.analyze(graph, changedFiles)

        val md = markdownFile.get().asFile
        md.parentFile.mkdirs()
        md.writeText(AffectedReportGenerator.markdown(affected, base, head, projectName.get()))

        val json = jsonFile.get().asFile
        json.parentFile.mkdirs()
        json.writeText(AffectedReportGenerator.json(affected, base, head))

        if (affected.changed.isEmpty()) {
            logger.lifecycle("Aalekh affected: no module sources changed in '$base...${head.ifBlank { "WORKTREE" }}'.")
        } else {
            logger.lifecycle(
                "Aalekh affected: ${affected.affected.size}/${affected.totalModules} module(s) affected " +
                        "(${affected.changed.size} changed) → file://${md.absolutePath}"
            )
        }
    }

    private fun readGraph(): ModuleDependencyGraph =
        affectedJson.decodeFromString(graphJsonFile.get().asFile.readText())
}

private val affectedJson = Json { ignoreUnknownKeys = true }
