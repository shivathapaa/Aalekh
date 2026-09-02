package com.aalekh.aalekh.analysis.spi

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Provenance

/**
 * What a [ModuleClassifier] knows about one module. Every field is optional; a classifier fills in
 * only what its convention actually determines.
 *
 * @param layer Architectural layer.
 * @param team Owning team.
 * @param purpose One or two sentences on what the module is for.
 * @param tags Free-form labels, e.g. `"generated"`, `"deprecated"`, `"public-api"`.
 * @param provenance How the classifier knows. [Provenance.OBSERVED] when it read the answer from a
 *   file or a build fact; [Provenance.INFERRED] when it worked it out from a convention. This is what
 *   the report renders next to the value, so a classifier claiming `OBSERVED` for a guess is
 *   defeating the point of the whole provenance model.
 */
public data class ModuleClassification(
    val layer: String? = null,
    val team: String? = null,
    val purpose: String? = null,
    val tags: Set<String> = emptySet(),
    val provenance: Provenance = Provenance.INFERRED,
)

/**
 * Extension point for teaching Aalekh **your** conventions.
 *
 * Aalekh infers a module's layer from path segments and its team from glob patterns, and both are
 * guesses about a convention it was not told. A classifier replaces the guess with the rule the team
 * actually uses - a naming scheme, a marker file, a registry, an annotation - and says whether the
 * answer is observed or inferred.
 *
 * ```kotlin
 * class ServiceRegistryClassifier : ModuleClassifier {
 *     override val id = "service-registry"
 *
 *     override fun classify(modulePath: String, graph: ModuleDependencyGraph) =
 *         registry[modulePath]?.let {
 *             ModuleClassification(
 *                 team = it.owningTeam,
 *                 purpose = it.description,
 *                 provenance = Provenance.OBSERVED,   // read from the registry, not guessed
 *             )
 *         }
 * }
 * ```
 *
 * Register it with a `META-INF/services/com.aalekh.aalekh.analysis.spi.ModuleClassifier` file in a jar
 * on the plugin's runtime classpath.
 *
 * **Precedence.** A classifier sits between the two things it must not override: anything the user
 * declared directly - `layers { }`, `teams { }`, `.aalekh/modules.json` - always wins, because an
 * explicit declaration outranks any rule that infers one; and a classifier always beats Aalekh's own
 * path-segment guesses, because a team's real convention beats a generic heuristic. A classifier that
 * returns null for a module simply leaves it to the next source.
 *
 * Like every Aalekh extension point this is a pure function: no filesystem, no network, no Gradle
 * API, and deterministic. One that throws is skipped and reported; it never breaks the build.
 */
public interface ModuleClassifier {

    /** Stable, kebab-case identifier. Used to attribute failures. */
    public val id: String

    /**
     * Classifies one module, or returns null when this classifier has nothing to say about it -
     * which is the normal case for a classifier that only knows about part of the project.
     */
    public fun classify(modulePath: String, graph: ModuleDependencyGraph): ModuleClassification?
}
