package com.aalekh.aalekh.gradle.git

import org.gradle.api.logging.Logger
import java.util.concurrent.TimeUnit

/**
 * Reads the list of files changed between two git refs, for affected-graph analysis.
 *
 * Like [GitHistoryReader], this is the only git-touching code for the affected graph and lives in
 * `aalekh-gradle` because it runs an external process. It runs at task-execution time and is
 * **fail-silent**: a missing git binary, an unknown ref, a non-git directory, or a timeout yields an
 * empty list and a log line rather than a build failure.
 */
internal object GitDiffReader {

    private const val TIMEOUT_SECONDS = 60L

    /**
     * Returns the repo-relative paths changed between [baseRef] and [headRef].
     *
     * When [headRef] is blank, compares [baseRef] against the working tree (`git diff base`).
     * Otherwise uses the three-dot form (`git diff base...head`) - changes on head since the merge
     * base, the standard "what this pull request changed" set. Empty on any failure.
     */
    fun changedFiles(rootDir: String, baseRef: String, headRef: String, logger: Logger): List<String> {
        if (baseRef.isBlank()) return emptyList()
        val range = if (headRef.isBlank()) baseRef else "$baseRef...$headRef"
        val command = listOf("git", "-C", rootDir, "diff", "--name-only", range)
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            when {
                !finished -> {
                    process.destroy()
                    logger.warn("Aalekh: git diff timed out after ${TIMEOUT_SECONDS}s - affected graph skipped.")
                    emptyList()
                }
                process.exitValue() != 0 -> {
                    logger.info("Aalekh: git diff '$range' failed (unknown ref?) - affected graph skipped.")
                    emptyList()
                }
                else -> text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }.getOrElse { ex ->
            logger.info("Aalekh: git not available - affected graph skipped (${ex.message}).")
            emptyList()
        }
    }
}
