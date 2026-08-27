package com.aalekh.aalekh.report.codeclimate

import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Generates a [Code Climate](https://github.com/codeclimate/platform/blob/master/spec/analyzers/SPEC.md)
 * JSON report - the format GitLab's **Code Quality** widget consumes to annotate merge requests.
 *
 * Point a GitLab CI `codequality` report artifact at `aalekh-codeclimate.json` and every architecture
 * violation shows up as a Code Quality finding on the MR diff, no plugin or token needed:
 *
 * ```yaml
 * aalekh:
 *   script: ./gradlew aalekhCheck
 *   artifacts:
 *     reports:
 *       codequality: build/reports/aalekh/aalekh-codeclimate.json
 * ```
 *
 * It is the GitLab counterpart to the SARIF report Aalekh already writes for GitHub. Pure string
 * transform - no Gradle, no I/O.
 */
public object CodeClimateReporter {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Serialises [result]'s violations as a Code Climate issue array. */
    public fun generate(graph: ModuleDependencyGraph, result: RuleEngineResult): String {
        val issues = result.violations.map { violation -> toIssue(violation, graph) }
        return prettyJson.encodeToString(issues)
    }

    private fun toIssue(violation: Violation, graph: ModuleDependencyGraph): CodeClimateIssue {
        val modulePath = violation.moduleHint ?: violation.source.split(" ").first()
        val filePath = graph.moduleByPath(modulePath)?.buildFilePath
            ?: (modulePath.trimStart(':').replace(':', '/') + "/build.gradle.kts")
        return CodeClimateIssue(
            description = violation.message,
            checkName = violation.ruleId,
            fingerprint = fingerprint(violation),
            severity = violation.severity.toCodeClimateSeverity(),
            location = CodeClimateLocation(filePath, CodeClimateLines(1)),
        )
    }

    private fun Severity.toCodeClimateSeverity(): String = when (this) {
        Severity.ERROR -> "critical"
        Severity.WARNING -> "minor"
        Severity.INFO -> "info"
    }

    /**
     * A stable 64-bit FNV-1a hash (hex) of the violation's identity. GitLab uses the fingerprint to
     * track a finding across pipelines, so it must be deterministic and distinct per violation.
     */
    private fun fingerprint(violation: Violation): String {
        val identity = "${violation.ruleId}|${violation.source}|${violation.message}"
        var hash = FNV_OFFSET
        for (char in identity) {
            hash = hash xor char.code.toLong()
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(HEX_RADIX).padStart(HEX_WIDTH, '0')
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 14695981039346656037 (unsigned) as Long
    private const val FNV_PRIME = 0x100000001b3L
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 16
}

@Serializable
private data class CodeClimateIssue(
    val description: String,
    @SerialName("check_name") val checkName: String,
    val fingerprint: String,
    val severity: String,
    val location: CodeClimateLocation,
)

@Serializable
private data class CodeClimateLocation(val path: String, val lines: CodeClimateLines)

@Serializable
private data class CodeClimateLines(val begin: Int)
