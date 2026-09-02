package com.aalekh.aalekh.analysis.rules

/**
 * One architectural layer as declared in the consumer's `layers { }` block.
 *
 * @param name Layer name, e.g. `"domain"`. Unique within a declaration set.
 * @param modulePatterns Glob patterns selecting the modules in this layer, in declaration order.
 * @param allowedLayers Names of the layers this one may depend on. Meaningless unless
 *   [hasRestriction] is true.
 * @param hasRestriction True when the layer called `canOnlyDependOn(...)`, making [allowedLayers] an
 *   allowlist. False means the layer is declared but unconstrained.
 */
public data class LayerSpec(
    val name: String,
    val modulePatterns: List<String>,
    val allowedLayers: List<String>,
    val hasRestriction: Boolean,
)

/**
 * The single parser for the serialized `layers { }` declarations that cross the configuration-cache
 * boundary as plain task `@Input` strings.
 *
 * Format per entry: `"name|pat1,pat2|allowed1,allowed2|hasRestriction"`. The Gradle plugin writes it;
 * [LayerDependencyRule], [UncoveredModuleRule], and the HTML report all read it back through here, so
 * there is exactly one definition of what a valid entry is. Entries with fewer than four fields are
 * dropped rather than partially interpreted - a half-parsed layer would silently change which modules
 * a rule constrains.
 */
public object LayerSpecParser {

    private const val FIELD_COUNT = 4
    private const val PATTERNS_INDEX = 1
    private const val ALLOWED_INDEX = 2
    private const val RESTRICTION_INDEX = 3

    /** Parses serialized layer entries, preserving declaration order and dropping malformed ones. */
    public fun parse(entries: List<String>): List<LayerSpec> = entries.mapNotNull { entry ->
        val parts = entry.split("|")
        if (parts.size < FIELD_COUNT) return@mapNotNull null
        LayerSpec(
            name = parts[0],
            modulePatterns = parts[PATTERNS_INDEX].split(",").filter { it.isNotBlank() },
            allowedLayers = parts[ALLOWED_INDEX].split(",").filter { it.isNotBlank() },
            hasRestriction = parts[RESTRICTION_INDEX].toBoolean(),
        )
    }

    /**
     * The layer a module belongs to, or null when no declared pattern matches it.
     *
     * First declared match wins, so the resolution order is the order the layers appear in the
     * consumer's `layers { }` block. This is the behaviour [LayerDependencyRule] enforces against, and
     * the HTML report mirrors it, so the swimlane a module is drawn in is always the layer whose rules
     * apply to it.
     */
    public fun layerOf(layers: List<LayerSpec>, modulePath: String): LayerSpec? =
        layers.firstOrNull { GlobMatcher.matchesAny(it.modulePatterns, modulePath) }
}
