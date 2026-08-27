package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.analysis.metrics.MainSequenceAnalyzer
import com.aalekh.aalekh.analysis.metrics.MainSequenceAnalyzer.TypeAbstractness
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Reads a module's Kotlin/Java source and totals its abstract vs concrete type counts - the raw input
 * for the abstractness (A) metric, which the dependency graph alone cannot know.
 *
 * This is only the **I/O half**: it walks the `src` tree and hands each file's text to the pure,
 * unit-tested [MainSequenceAnalyzer.countTypes], which does the coarse lexical counting. It lives in
 * `aalekh-gradle` because it touches the filesystem; per-module failures are swallowed so a broken
 * module never fails the run.
 */
internal object TypeAbstractnessScanner {

    private val sourceExtensions = setOf("kt", "java")

    /** Scans [moduleDir]'s `src` tree and returns the accumulated abstract/concrete type counts. */
    fun scan(moduleDir: File, logger: Logger): TypeAbstractness = runCatching {
        val srcRoot = moduleDir.resolve("src")
        if (!srcRoot.isDirectory) return TypeAbstractness.ZERO

        srcRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in sourceExtensions }
            .fold(TypeAbstractness.ZERO) { acc, file -> acc + MainSequenceAnalyzer.countTypes(file.readText()) }
    }.getOrElse { ex ->
        logger.info("Aalekh: could not scan ${moduleDir.name} for abstractness - ${ex.message}")
        TypeAbstractness.ZERO
    }
}
