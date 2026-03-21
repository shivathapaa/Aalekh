package com.aalekh.aalekh.gradle.dsl

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures feature module isolation inside the `featureIsolation { }` block.
 *
 * ```kotlin
 * aalekh {
 *     featureIsolation {
 *         featurePattern = ":feature:**"
 *         allow(from = ":feature:shared", to = ":feature:*")
 *     }
 * }
 * ```
 *
 * Feature modules matched by [featurePattern] must not depend on each other,
 * except for pairs explicitly listed via [allow].
 */
public abstract class FeatureIsolationConfig @Inject constructor(objects: ObjectFactory) {

    /**
     * Glob pattern identifying feature modules.
     * No default - must be set explicitly. The rule is inactive if left blank.
     */
    public val featurePattern: Property<String> =
        objects.property(String::class.java).convention("")

    /** Explicitly permitted feature-to-feature dependency pairs. */
    public val allowedPairs: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /**
     * Permits a specific feature-to-feature dependency that would otherwise be a violation.
     *
     * @param from The depending module path or glob pattern.
     * @param to The dependency module path or glob pattern.
     */
    public fun allow(from: String, to: String) {
        allowedPairs.add("$from->$to")
    }
}