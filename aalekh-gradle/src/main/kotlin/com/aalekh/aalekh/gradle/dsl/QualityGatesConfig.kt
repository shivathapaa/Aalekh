package com.aalekh.aalekh.gradle.dsl

import com.aalekh.aalekh.analysis.metrics.MetricGate
import com.aalekh.aalekh.model.Severity
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures metric-delta **quality gates** inside the `qualityGates { }` block.
 *
 * A gate fails `aalekhCheck` when a structural metric got **worse** than the committed baseline
 * (`aalekh-baseline.json`, written by `aalekhBaseline`). This lets a team ratchet architecture
 * quality in one direction only - no backsliding on a long refactor.
 *
 * ```kotlin
 * aalekh {
 *     qualityGates {
 *         // Fail the build if any of these regressed versus the baseline.
 *         forbidRegression("cycles", "ccd", "god-modules")
 *         severity.set(Severity.ERROR)   // default ERROR; use WARNING to only report
 *     }
 * }
 * ```
 *
 * Valid metric keys: `cycles`, `god-modules`, `ccd`, `tangle`, `instability`, `critical-path`.
 * Gates only fire once a baseline with a metrics snapshot exists; regenerate it with `aalekhBaseline`.
 */
public abstract class QualityGatesConfig @Inject constructor(objects: ObjectFactory) {

    internal val metrics: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /**
     * Severity assigned to a regression. `ERROR` (the default) fails the build; `WARNING` only
     * reports. `INFO` is treated as a non-failing note.
     */
    public val severity: Property<Severity> =
        objects.property(Severity::class.java).convention(Severity.ERROR)

    /**
     * Enforces "no regression" on the given metric keys versus the baseline. Unknown keys are
     * rejected with the list of valid ones.
     */
    public fun forbidRegression(vararg metric: String) {
        metric.forEach { key ->
            require(MetricGate.fromKey(key) != null) {
                "Unknown quality-gate metric '$key'. Valid metrics: ${MetricGate.KEYS.joinToString(", ")}."
            }
            metrics.add(key)
        }
    }

    /** Enforces "no regression" on every structural metric. */
    public fun forbidAllRegressions() {
        MetricGate.KEYS.forEach { metrics.add(it) }
    }
}
