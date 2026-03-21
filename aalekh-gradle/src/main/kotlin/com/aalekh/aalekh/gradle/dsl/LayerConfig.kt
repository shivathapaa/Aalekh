package com.aalekh.aalekh.gradle.dsl

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures a single architectural layer inside the `layers { }` block.
 *
 * ```kotlin
 * aalekh {
 *     layers {
 *         layer("domain") {
 *             modules(":core:domain", ":feature:*:domain")
 *         }
 *         layer("data") {
 *             modules(":core:data", ":feature:*:data")
 *             canOnlyDependOn("domain")
 *         }
 *         layer("presentation") {
 *             modules(":feature:*:ui", ":app")
 *             canOnlyDependOn("domain", "data")
 *         }
 *     }
 * }
 * ```
 *
 * Module patterns support a single `*` wildcard matching one path segment,
 * and `**` matching any number of segments.
 */
public abstract class LayerConfig @Inject constructor(
    public val name: String,
    objects: ObjectFactory,
) {
    /** Glob patterns identifying which modules belong to this layer. */
    public val modulePatterns: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Layer names this layer is permitted to depend on. Empty means no restriction. */
    public val allowedDependencyLayers: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Whether [allowedDependencyLayers] is an allowlist (true) or unrestricted (false). */
    public val hasRestriction: Property<Boolean> =
        objects.property(Boolean::class.java).convention(false)

    /** Declares which modules belong to this layer. Supports `*` and `**` glob patterns. */
    public fun modules(vararg patterns: String) {
        modulePatterns.addAll(patterns.toList())
    }

    /**
     * Restricts this layer to only depend on the named layers.
     * Any dependency on a module outside these layers is a violation.
     */
    public fun canOnlyDependOn(vararg layerNames: String) {
        allowedDependencyLayers.addAll(layerNames.toList())
        hasRestriction.set(true)
    }
}