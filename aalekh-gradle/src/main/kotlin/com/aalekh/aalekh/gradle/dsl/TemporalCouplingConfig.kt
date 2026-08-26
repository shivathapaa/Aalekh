package com.aalekh.aalekh.gradle.dsl

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures the git temporal-coupling analysis inside the `temporalCoupling { }` block.
 *
 * ```kotlin
 * aalekh {
 *     temporalCoupling {
 *         commitWindow.set(1000)              // analyse the last 1000 commits
 *         minSharedCommits.set(3)             // ignore pairs sharing fewer than 3 commits
 *         hiddenCouplingThreshold.set(0.7)    // flag undeclared pairs at degree >= 0.7
 *     }
 * }
 * ```
 *
 * Drives `aalekhTemporal`, which reads `git log` at execution time and writes a local
 * `aalekh-temporal.md` / `aalekh-temporal.json` artefact. All values are plain, configuration-cache
 * safe task inputs.
 */
public abstract class TemporalCouplingConfig @Inject constructor(objects: ObjectFactory) {

    /**
     * How many recent (non-merge) commits to analyse. Larger windows reveal more coupling but take
     * longer to read. Default: `500`.
     */
    public val commitWindow: Property<Int> =
        objects.property(Int::class.java).convention(DEFAULT_COMMIT_WINDOW)

    /**
     * The minimum number of commits a module pair must share before it is reported. Filters
     * incidental single-commit overlaps. Default: `2`.
     */
    public val minSharedCommits: Property<Int> =
        objects.property(Int::class.java).convention(DEFAULT_MIN_SHARED_COMMITS)

    /**
     * The coupling degree (`sharedCommits / min(churnA, churnB)`, in `[0.0, 1.0]`) at or above which
     * an **undeclared** pair is flagged as hidden coupling. Default: `0.6`.
     */
    public val hiddenCouplingThreshold: Property<Double> =
        objects.property(Double::class.java).convention(DEFAULT_HIDDEN_COUPLING_THRESHOLD)

    public companion object {
        private const val DEFAULT_COMMIT_WINDOW = 500
        private const val DEFAULT_MIN_SHARED_COMMITS = 2
        private const val DEFAULT_HIDDEN_COUPLING_THRESHOLD = 0.6
    }
}
