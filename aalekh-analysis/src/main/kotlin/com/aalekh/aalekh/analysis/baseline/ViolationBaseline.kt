package com.aalekh.aalekh.analysis.baseline

import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Pure support for the committed **baseline** (a.k.a. freeze) workflow.
 *
 * A baseline is a set of violation *fingerprints* captured from a known state of the project and
 * committed to the repository. On later runs, `aalekhCheck` suppresses every violation already in
 * the baseline and fails only on genuinely new ones. This lets a team adopt strict rules on a messy
 * codebase immediately - the existing debt is frozen, and the build blocks only regressions.
 *
 * This object is deliberately I/O-free: the Gradle layer reads and writes the baseline file and
 * calls these functions. That keeps baseline logic unit-testable in milliseconds.
 */
public object ViolationBaseline {

    /**
     * A stable identity for a violation, independent of run timestamp. Two violations share a
     * fingerprint when they are the "same" problem: same rule, same offending edge or module.
     */
    public fun fingerprint(violation: Violation): String = "${violation.ruleId}|${violation.source}"

    /**
     * The fingerprints to persist for [violations]. `INFO` violations are advisory and never fail
     * the build, so they are excluded from the baseline. The result is de-duplicated and sorted so
     * the committed file is stable and diffs cleanly.
     */
    public fun toFingerprints(violations: List<Violation>): List<String> =
        violations
            .filter { it.severity != Severity.INFO }
            .map { fingerprint(it) }
            .distinct()
            .sorted()

    /**
     * Filters [violations] against a set of baseline [fingerprints].
     *
     * A non-`INFO` violation whose fingerprint is present in the baseline is dropped (it is known
     * debt). `INFO` violations always pass through untouched. Returns both the surviving (new)
     * violations and how many were suppressed by the baseline.
     */
    public fun apply(violations: List<Violation>, fingerprints: Set<String>): Result {
        if (fingerprints.isEmpty()) return Result(violations, baselinedCount = 0)

        val surviving = mutableListOf<Violation>()
        var baselined = 0
        for (violation in violations) {
            val known = violation.severity != Severity.INFO && fingerprint(violation) in fingerprints
            if (known) baselined++ else surviving += violation
        }
        return Result(surviving, baselined)
    }

    /**
     * The outcome of [apply].
     *
     * @param newViolations Violations not covered by the baseline - these still count and can fail the build.
     * @param baselinedCount How many violations were suppressed because they were already in the baseline.
     */
    public data class Result(
        val newViolations: List<Violation>,
        val baselinedCount: Int,
    )
}
