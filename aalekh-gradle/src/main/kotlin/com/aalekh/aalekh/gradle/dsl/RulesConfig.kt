package com.aalekh.aalekh.gradle.dsl

import com.aalekh.aalekh.model.Severity
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import javax.inject.Inject

/**
 * Configures rules inside the `rules { }` block.
 *
 * ```kotlin
 * aalekh {
 *     rules {
 *         rule("layer-dependency") {
 *             severity = Severity.WARNING
 *             suppressFor(":legacy:**")
 *         }
 *         noTransitiveDependenciesExceeding(30)
 *         rule("no-cyclic-dependencies") {
 *             preventRegression = true
 *         }
 *     }
 * }
 * ```
 */
public abstract class RulesConfig @Inject constructor(objects: ObjectFactory) {

    internal val entries: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    public fun rule(id: String, configure: RuleOverride.() -> Unit) {
        val override = RuleOverride(id)
        override.configure()
        override.serialize().forEach { entries.add(it) }
    }

    /**
     * Adds a rule that warns when a module pulls in more than [max] transitive dependencies.
     * Default severity is WARNING. Override with `rule("max-transitive-dependencies") { severity = ERROR }`.
     */
    public fun noTransitiveDependenciesExceeding(max: Int) {
        entries.add("max-transitive-dependencies:threshold:$max")
    }

    /**
     * Adds a rule that warns when the module graph's longest dependency chain (its *height*)
     * exceeds [max] modules. Graph height is the floor on build parallelism - a tall graph forces
     * long sequential compile chains. Default severity is WARNING. Override with
     * `rule("max-graph-height") { severity = ERROR }`.
     */
    public fun maxGraphHeight(max: Int) {
        entries.add("max-graph-height:threshold:$max")
    }

    /**
     * Adds a rule that warns about "orphan" modules - modules that neither depend on anything nor
     * are depended on by anything over production edges. Orphans are dead weight in the build.
     * Default severity is WARNING. Override with `rule("no-orphan-modules") { severity = ERROR }`.
     */
    public fun noOrphanModules() {
        entries.add("no-orphan-modules:option:enabled")
    }

    /**
     * Registers a user-defined [com.aalekh.aalekh.analysis.rules.ArchRule] implementation
     * by its fully qualified class name.
     *
     * ```kotlin
     * aalekh {
     *     rules {
     *         custom("com.example.NoAndroidInDomainRule")
     *     }
     * }
     * ```
     *
     * The class must implement `com.aalekh.aalekh.analysis.rules.ArchRule`, expose a
     * no-argument public constructor, and be reachable from the plugin's runtime
     * classpath. Place the rule class in a module that the consumer adds to the
     * settings (or root project) `buildscript { dependencies { classpath(...) } }`
     * block, or in an `includeBuild` composite project alongside the plugin.
     *
     * If the class cannot be loaded or instantiated, `aalekhCheck` surfaces an
     * ERROR violation with the underlying cause rather than crashing the build.
     */
    public fun custom(className: String) {
        require(className.isNotBlank()) { "Custom rule class name must not be blank" }
        entries.add("custom:class:$className")
    }
}

public class RuleOverride(private val id: String) {

    public var severity: Severity? = null

    /**
     * When true, any increase in cycle count since the last `aalekhCheck` output
     * is treated as an ERROR. Requires `aalekh-results.json` from a previous run
     * to be present; silently skips the regression check if no prior result exists.
     *
     * Only meaningful on the `no-cyclic-dependencies` rule.
     */
    public var preventRegression: Boolean = false

    private val suppressPatterns = mutableListOf<String>()

    public fun suppressFor(pattern: String) {
        suppressPatterns += pattern
    }

    internal fun serialize(): List<String> {
        val result = mutableListOf<String>()
        severity?.let { result += "$id:severity:${it.name}" }
        if (preventRegression) result += "$id:option:preventRegression"
        suppressPatterns.forEach { result += "$id:suppress:$it" }
        return result
    }
}