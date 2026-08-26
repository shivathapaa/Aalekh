package com.aalekh.aalekh.report.affected

import com.aalekh.aalekh.model.AffectedModules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Renders an [AffectedModules] result as the two local artefacts `aalekhAffected` writes: a
 * PR-ready Markdown comment and a machine-readable JSON envelope. A consumer's own CI posts the
 * Markdown as a pull-request comment - Aalekh never posts anything itself.
 *
 * Pure string transforms - no Gradle, no I/O.
 */
public object AffectedReportGenerator {

    private const val PERCENT = 100.0

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Serialises the affected graph, tagged with the diff range, to pretty-printed JSON. */
    public fun json(affected: AffectedModules, baseRef: String, headRef: String): String =
        prettyJson.encodeToString(
            AffectedJsonReport.serializer(),
            AffectedJsonReport(baseRef, headRef, affected),
        )

    /** Renders the affected graph as a Markdown pull-request comment. */
    public fun markdown(
        affected: AffectedModules,
        baseRef: String,
        headRef: String,
        projectName: String,
    ): String = buildString {
        appendLine("## Aalekh - affected modules")
        appendLine()
        appendLine("Impact of `$baseRef...$headRef` on **$projectName**.")
        appendLine()
        if (affected.changed.isEmpty()) {
            appendLine("_No module sources changed in this range._")
            return@buildString
        }
        val percent = if (affected.totalModules == 0) {
            0
        } else {
            Math.round(affected.affected.size.toDouble() / affected.totalModules * PERCENT).toInt()
        }
        appendLine(
            "**${affected.affected.size} of ${affected.totalModules} modules affected** ($percent%) - " +
                "${affected.changed.size} changed, " +
                "${affected.affected.size - affected.changed.size} downstream."
        )
        appendLine()
        appendModuleList("Changed", affected.changed)
        appendModuleList("Affected (rebuild / retest)", affected.affected)
    }

    private fun StringBuilder.appendModuleList(heading: String, modules: List<String>) {
        appendLine("### $heading")
        appendLine()
        if (modules.isEmpty()) {
            appendLine("_None._")
        } else {
            modules.forEach { appendLine("- `$it`") }
        }
        appendLine()
    }
}

/** JSON envelope for `aalekh-affected.json`: the diff range plus the affected-module sets. */
@Serializable
public data class AffectedJsonReport(
    val baseRef: String,
    val headRef: String,
    val affected: AffectedModules,
)
