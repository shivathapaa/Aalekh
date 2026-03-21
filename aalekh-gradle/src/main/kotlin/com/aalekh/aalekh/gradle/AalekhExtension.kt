package com.aalekh.aalekh.gradle

import com.aalekh.aalekh.gradle.dsl.FeatureIsolationConfig
import com.aalekh.aalekh.gradle.dsl.LayerConfig
import com.aalekh.aalekh.gradle.dsl.RulesConfig
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL extension for configuring Aalekh in the root project's `build.gradle.kts`.
 *
 * ```kotlin
 * aalekh {
 *     outputDir.set("reports/aalekh")
 *     openBrowserAfterReport.set(false)
 *
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
 *
 *     featureIsolation {
 *         featurePattern = ":feature:**"
 *         allow(from = ":feature:shared", to = ":feature:*")
 *     }
 *
 *     rules {
 *         rule("layer-dependency") {
 *             severity = Severity.WARNING
 *             suppressFor(":legacy:**")
 *         }
 *     }
 * }
 * ```
 */
public abstract class AalekhExtension @Inject constructor(private val objects: ObjectFactory) {

    /** Output directory for report files, relative to `build/`. Default: `"reports/aalekh"`. */
    public val outputDir: Property<String> = objects.property(String::class.java)
        .convention("reports/aalekh")

    /** Open the HTML report in the default browser after `aalekhReport` completes. Default: `true`. */
    public val openBrowserAfterReport: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /** Include test configurations in the dependency graph. Default: `true`. */
    public val includeTestDependencies: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /** Include compileOnly configurations in the dependency graph. Default: `false`. */
    public val includeCompileOnlyDependencies: Property<Boolean> =
        objects.property(Boolean::class.java)
            .convention(false)

    // Rule DSL

    /**
     * Named container of [LayerConfig] objects. Each entry declares one architectural
     * layer and optionally restricts which other layers it may depend on.
     */
    public val layerContainer: NamedDomainObjectContainer<LayerConfig> =
        objects.domainObjectContainer(LayerConfig::class.java) { name ->
            objects.newInstance(LayerConfig::class.java, name)
        }

    /** Declares architectural layers and their permitted dependency directions. */
    public fun layers(configure: Action<NamedDomainObjectContainer<LayerConfig>>) {
        configure.execute(layerContainer)
    }

    /** Convenience for Kotlin lambda syntax: `layers { layer("domain") { ... } }`. */
    public fun layers(configure: NamedDomainObjectContainer<LayerConfig>.() -> Unit) {
        layerContainer.configure()
    }

    /** Adds a named layer and configures it. Shorthand for use inside the `layers { }` block. */
    public fun NamedDomainObjectContainer<LayerConfig>.layer(
        name: String,
        configure: LayerConfig.() -> Unit,
    ) {
        create(name).configure()
    }

    /**
     * Configures the feature isolation rule. Feature modules matching the declared
     * pattern must not depend on each other unless explicitly allowed.
     */
    public val featureIsolationConfig: FeatureIsolationConfig =
        objects.newInstance(FeatureIsolationConfig::class.java)

    public fun featureIsolation(configure: FeatureIsolationConfig.() -> Unit) {
        featureIsolationConfig.configure()
    }

    /** Configures per-rule severity overrides and module-level suppressions. */
    public val rulesConfig: RulesConfig = objects.newInstance(RulesConfig::class.java)

    public fun rules(configure: RulesConfig.() -> Unit) {
        rulesConfig.configure()
    }

    public companion object {
        public const val NAME: String = "aalekh"
    }
}