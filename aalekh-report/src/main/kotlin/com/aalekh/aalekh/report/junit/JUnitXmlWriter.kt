package com.aalekh.aalekh.report.junit

import com.aalekh.aalekh.analysis.rules.ArchRule
import com.aalekh.aalekh.analysis.rules.RuleEngineResult
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation
import java.time.Instant

/**
 * Generates JUnit XML test reports from rule engine results.
 *
 * Schema: each [ArchRule] becomes a test suite; each [Violation] becomes a test failure.
 */
public object JUnitXmlWriter {
    /**
     * Generates a JUnit XML string from rule engine results.
     * Output path: `build/reports/aalekh/aalekh-results.xml`
     */
    public fun generate(
        projectName: String,
        result: RuleEngineResult,
    ): String {
        val timestamp = Instant.now().toString()
        val failures = result.violations.count { it.severity == Severity.ERROR }
        val warnings = result.violations.count { it.severity == Severity.WARNING }
        val total = result.violations.size

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<testsuites name="Aalekh Architecture Check" tests="$total" failures="$failures" errors="0" timestamp="$timestamp">""")
            appendLine("""  <testsuite name="$projectName" tests="$total" failures="$failures" warnings="$warnings">""")

            if (result.violations.isEmpty()) {
                appendLine("""    <testcase name="All architecture rules passed" classname="$projectName"/>""")
            } else {
                result.violations.forEach { violation ->
                    appendLine("""    <testcase name="${xmlEscape(violation.ruleId)}" classname="${xmlEscape(violation.source)}">""")
                    if (violation.severity == Severity.ERROR) {
                        appendLine("""      <failure message="${xmlEscape(violation.message)}" type="${violation.severity}">""")
                        appendLine("""        ${xmlEscape(violation.message)}""")
                        appendLine("""      </failure>""")
                    }
                    appendLine("""    </testcase>""")
                }
            }

            appendLine("""  </testsuite>""")
            appendLine("""</testsuites>""")
        }
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}