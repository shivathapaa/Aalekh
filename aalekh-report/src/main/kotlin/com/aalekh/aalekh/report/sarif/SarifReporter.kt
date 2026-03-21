package com.aalekh.aalekh.report.sarif

import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Generates a SARIF 2.1.0 report from rule engine results.
 *
 * SARIF (Static Analysis Results Interchange Format) is the format GitHub uses
 * for code scanning. When `aalekh-results.sarif` is uploaded as a GitHub Actions
 * artifact with the `github/codeql-action/upload-sarif` action, violations appear
 * as inline annotations on pull request diffs - no custom reporter or token needed.
 *
 * ### GitHub Actions integration
 * ```yaml
 * - name: Run Aalekh
 *   run: ./gradlew aalekhCheck
 *
 * - name: Upload SARIF results
 *   uses: github/codeql-action/upload-sarif@v3
 *   if: always()
 *   with:
 *     sarif_file: build/reports/aalekh/aalekh-results.sarif
 * ```
 *
 * The `if: always()` ensures annotations are posted even when `aalekhCheck` fails.
 */
public object SarifReporter {

    private const val SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
    private const val SARIF_VERSION = "2.1.0"
    private const val TOOL_NAME = "Aalekh"
    private const val TOOL_URI = "https://github.com/shivathapaa/aalekh"

    public fun generate(
        graph: ModuleDependencyGraph,
        result: RuleEngineResult,
    ): String {
        val rulesJson = buildRulesJson(result)
        val resultsJson = buildResultsJson(result.violations, graph)

        return """
{
  "${'$'}schema": "$SARIF_SCHEMA",
  "version": "$SARIF_VERSION",
  "runs": [
    {
      "tool": {
        "driver": {
          "name": "$TOOL_NAME",
          "informationUri": "$TOOL_URI",
          "version": "${graph.metadata["aalekhVersion"] ?: "unknown"}",
          "rules": [$rulesJson]
        }
      },
      "results": [$resultsJson]
    }
  ]
}""".trimIndent()
    }

    private fun buildRulesJson(result: RuleEngineResult): String {
        // Collect unique rule IDs from violations - only emit rules that fired
        val ruleIds = result.violations.map { it.ruleId }.distinct()
        return ruleIds.joinToString(",\n") { ruleId ->
            val violation = result.violations.first { it.ruleId == ruleId }
            val level = violation.severity.toSarifLevel()
            val explanation =
                violation.plainLanguageExplanation?.let { json(it) } ?: json(violation.message)
            """
        {
          "id": ${json(ruleId)},
          "shortDescription": { "text": ${json(ruleId)} },
          "fullDescription": { "text": $explanation },
          "defaultConfiguration": { "level": "$level" }
        }""".trimIndent()
        }
    }

    private fun buildResultsJson(
        violations: List<Violation>,
        graph: ModuleDependencyGraph
    ): String {
        if (violations.isEmpty()) return ""
        return violations.joinToString(",\n") { v ->
            val level = v.severity.toSarifLevel()
            val locationJson = buildLocationJson(v, graph)
            """
        {
          "ruleId": ${json(v.ruleId)},
          "level": "$level",
          "message": { "text": ${json(v.message)} },
          "locations": [$locationJson]
        }""".trimIndent()
        }
    }

    private fun buildLocationJson(violation: Violation, graph: ModuleDependencyGraph): String {
        // Resolve the build file path from moduleHint or fall back to source parsing
        val modulePath = violation.moduleHint
            ?: violation.source.split(" ").first()
        val buildFilePath = graph.moduleByPath(modulePath)?.buildFilePath
            ?: (modulePath.trimStart(':').replace(':', '/') + "/build.gradle.kts")

        return """
      {
        "physicalLocation": {
          "artifactLocation": {
            "uri": ${json(buildFilePath)},
            "uriBaseId": "%SRCROOT%"
          },
          "region": { "startLine": 1 }
        },
        "logicalLocations": [
          {
            "name": ${json(modulePath)},
            "kind": "module"
          }
        ]
      }""".trimIndent()
    }

    private fun Severity.toSarifLevel(): String = when (this) {
        Severity.ERROR -> "error"
        Severity.WARNING -> "warning"
        Severity.INFO -> "note"
    }

    /** Wraps a string as a JSON string literal with proper escaping. */
    private fun json(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}