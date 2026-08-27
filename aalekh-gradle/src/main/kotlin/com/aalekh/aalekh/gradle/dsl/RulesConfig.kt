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

    /**
     * Serialized reachability rules from [forbidReachable] / [mustBeReachableFrom], kept in a
     * separate channel from [entries] because their `from`/`to` globs contain `:` and cannot use
     * the `ruleId:kind:value` layout. Format per entry:
     * `"<kind>|<fromGlob>|<toGlob>|<severity>|<reason>"`, kind = `forbid` or `require`.
     */
    internal val reachabilityEntries: ListProperty<String> =
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
     * Forbids a KMP module's `commonMain` source set from depending on a platform-only (JVM- or
     * Android-only) module - such a dependency does not compile for the module's other targets.
     * Reports `kmp-common-main-platform-dependency` at ERROR (override with
     * `rule("kmp-common-main-platform-dependency") { severity = WARNING }`). No-op on projects with no
     * KMP source-set dependencies.
     */
    public fun noCommonMainPlatformDependencies() {
        entries.add("kmp-common-main-platform-dependency:option:enabled")
    }

    /**
     * Requires every module to belong to a declared `layers { }` layer. A module matched by no layer
     * pattern is invisible to [layer enforcement][com.aalekh.aalekh.analysis.rules.LayerDependencyRule]
     * and can depend on anything; this makes layer coverage exhaustive. Reports the `uncovered-module`
     * rule at WARNING (promote with `rule("uncovered-module") { severity = ERROR }`). Does nothing
     * until at least one layer is declared.
     */
    public fun requireLayerForAllModules() {
        entries.add("uncovered-module:option:enabled")
    }

    /**
     * Forbids modules matching [from] from *transitively* depending on modules matching [to] - the
     * indirect counterpart to `forbid { }`, which only checks direct edges. Follows the full
     * production dependency closure, so a banned module reached through any chain is a violation.
     * Reports `forbidden-transitive-dependency` at [severity] (default ERROR).
     *
     * ```kotlin
     * rules { forbidReachable(from = ":core:domain", to = ":platform:android", because = "keep domain pure") }
     * ```
     */
    public fun forbidReachable(
        from: String,
        to: String,
        because: String = "",
        severity: Severity = Severity.ERROR,
    ) {
        reachabilityEntries.add(serializeReachability("forbid", from, to, severity, because))
    }

    /**
     * Requires every module matching [module] to be reachable from a module matching [from] over
     * production dependencies - e.g. every `:feature:*` must be reachable from `:app`. A module no
     * production path from [from] reaches is dead in that entry point. Reports `unreachable-module`
     * at [severity] (default ERROR).
     *
     * ```kotlin
     * rules { mustBeReachableFrom(module = ":feature:*", from = ":app", because = "wire every feature into the app") }
     * ```
     */
    public fun mustBeReachableFrom(
        module: String,
        from: String,
        because: String = "",
        severity: Severity = Severity.ERROR,
    ) {
        reachabilityEntries.add(serializeReachability("require", from, module, severity, because))
    }

    /** Serializes one reachability rule to `"<kind>|<from>|<to>|<severity>|<reason>"`. */
    private fun serializeReachability(
        kind: String,
        from: String,
        to: String,
        severity: Severity,
        reason: String,
    ): String {
        val safeReason = reason.replace('|', '/').replace('\n', ' ').trim()
        return "$kind|$from|$to|${severity.name}|$safeReason"
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