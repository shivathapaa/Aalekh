package com.aalekh.aalekh.gradle.git

import com.aalekh.aalekh.analysis.temporal.CommitChange
import org.gradle.api.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * Reads recent commit history from a local git repository, for temporal-coupling analysis.
 *
 * This is the **only** git-touching code in Aalekh, and it lives in `aalekh-gradle` because it runs
 * an external process - I/O that must stay out of the pure `aalekh-analysis` / `aalekh-report`
 * modules. It runs at task-execution time (never at configuration time), returns plain
 * [CommitChange] data, and is **fail-silent**: a missing git binary, a non-git directory, a shallow
 * clone, a timeout, or any parse error yields an empty list and a log line rather than a build
 * failure.
 */
internal object GitHistoryReader {

    // ASCII SOH - emitted once per commit by `--pretty=format:%x01` to delimit commit blocks. It
    // cannot occur in a file path, so it is an unambiguous separator.
    private const val COMMIT_MARKER = "\u0001"
    private const val GIT_FORMAT = "--pretty=format:%x01"
    private const val TIMEOUT_SECONDS = 60L

    /**
     * Returns up to [maxCommits] most-recent non-merge commits (newest first), each with the list of
     * files it changed. Empty when history cannot be read for any reason.
     */
    fun read(rootDir: String, maxCommits: Int, logger: Logger): List<CommitChange> {
        if (maxCommits <= 0) return emptyList()
        return runGitLog(rootDir, maxCommits, logger)?.let { parse(it) } ?: emptyList()
    }

    private fun runGitLog(rootDir: String, maxCommits: Int, logger: Logger): String? {
        val command = listOf(
            "git", "-C", rootDir, "log",
            "--no-merges",
            "--name-only",
            GIT_FORMAT,
            "-n", maxCommits.toString(),
        )
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            // Drain stdout fully before waiting so a large history cannot deadlock on a full pipe.
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            when {
                !finished -> {
                    process.destroy()
                    logger.warn("Aalekh: git log timed out after ${TIMEOUT_SECONDS}s - temporal coupling skipped.")
                    null
                }
                process.exitValue() != 0 -> {
                    logger.info("Aalekh: git log unavailable (not a git repository?) - temporal coupling skipped.")
                    null
                }
                else -> text
            }
        }.getOrElse { ex ->
            logger.info("Aalekh: git not available - temporal coupling skipped (${ex.message}).")
            null
        }
    }

    /**
     * Parses `git log --name-only --pretty=format:<marker>` output. Each commit is a marker line
     * followed by its changed file paths, one per line; a blank line separates commits.
     */
    private fun parse(output: String): List<CommitChange> {
        val commits = mutableListOf<CommitChange>()
        var current: MutableList<String>? = null
        output.lineSequence().forEach { line ->
            when {
                line.startsWith(COMMIT_MARKER) -> {
                    current?.let { commits += CommitChange(it) }
                    current = mutableListOf()
                }
                line.isBlank() -> Unit
                else -> current?.add(line.trim())
            }
        }
        current?.let { commits += CommitChange(it) }
        return commits
    }
}
