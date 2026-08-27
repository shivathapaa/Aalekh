package com.aalekh.aalekh.gradle.dsl

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures the graph export inside the `mermaid { }` block - focus and exclude filters that keep a
 * large graph's Mermaid/DOT diagram readable.
 *
 * ```kotlin
 * aalekh {
 *     mermaid {
 *         focus(":feature:checkout")   // keep :feature:checkout and its immediate neighbours
 *         depth(2)                     // ...growing out 2 hops instead of the default 1
 *         exclude(":test:**")          // drop test-only modules from the diagram
 *     }
 * }
 * ```
 *
 * With no `focus`/`exclude` declared the whole graph is exported, exactly as before. The filters drive
 * `aalekhMermaid`; the diagram generators are unchanged - they simply receive a subset graph.
 */
public abstract class MermaidConfig @Inject constructor(objects: ObjectFactory) {

    /** Focus globs; empty keeps every module. Set via [focus]. */
    internal val focusEntries: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Exclude globs, applied after focus. Set via [exclude]. */
    internal val excludeEntries: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())

    /** Hops to grow the focus set outward, in either direction. Default `1`. Set via [depth]. */
    internal val depthValue: Property<Int> =
        objects.property(Int::class.java).convention(1)

    /**
     * Restricts the diagram to modules matching [patterns] plus their neighbourhood (see [depth]).
     * Glob syntax matches the rest of Aalekh (`*`, `**`, e.g. `":feature:**"`). Repeatable.
     */
    public fun focus(vararg patterns: String) {
        focusEntries.addAll(patterns.toList())
    }

    /**
     * Removes modules matching [patterns] from the diagram, applied after [focus]. Repeatable.
     */
    public fun exclude(vararg patterns: String) {
        excludeEntries.addAll(patterns.toList())
    }

    /**
     * Number of dependency hops to grow the [focus] set by, in either direction. `0` keeps only the
     * focused modules; `1` (default) adds their direct neighbours. Ignored when no [focus] is set.
     */
    public fun depth(hops: Int) {
        depthValue.set(hops)
    }
}
