package com.aalekh.aalekh.analysis.spi

import com.aalekh.aalekh.model.Finding
import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Extension point for contributing a **finding** - something Aalekh should say about the project.
 *
 * The third of Aalekh's three extension points, and the one for explanation rather than enforcement
 * or measurement:
 *
 * - `ArchRule` decides whether the build should **fail**.
 * - `MetricProvider` contributes a **number**.
 * - `FindingProvider` contributes a **sentence**.
 *
 * Use it for the things only your team knows. Aalekh can see that `:core:legacy` has forty
 * dependents; it cannot know that the migration off it is half-finished and which half. A provider
 * turns that into a finding that appears in the report, the generated documentation, and the
 * presentation alongside the built-in ones.
 *
 * ```kotlin
 * class MigrationStatusFinding : FindingProvider {
 *     override val id = "legacy-migration"
 *
 *     override fun find(graph: ModuleDependencyGraph): List<Finding> {
 *         val remaining = graph.edges.filter { it.to == ":core:legacy" && !it.isTest }
 *         if (remaining.isEmpty()) return emptyList()
 *         return listOf(
 *             Finding(
 *                 id = "legacy-migration",
 *                 category = FindingCategory.STRUCTURE,
 *                 severity = Severity.WARNING,
 *                 title = "${remaining.size} modules still depend on :core:legacy",
 *                 detail = "The migration to :core:platform is not finished. Each remaining " +
 *                     "dependency blocks deleting the legacy module.",
 *                 subjects = remaining.map { it.from },
 *                 action = "Move each consumer to :core:platform, then delete :core:legacy.",
 *             )
 *         )
 *     }
 * }
 * ```
 *
 * Register it the same way as a `MetricProvider`: a
 * `META-INF/services/com.aalekh.aalekh.analysis.spi.FindingProvider` file listing the class, in a jar
 * on the plugin's runtime classpath.
 *
 * A provider is a **pure function of the graph**. It must not touch the filesystem, the network, or
 * the Gradle API, and it must be deterministic - the same graph must always produce the same
 * sentences, because findings are written into committed documentation and compared in review. A
 * provider that throws is skipped and reported; it never breaks the build.
 */
public interface FindingProvider {

    /**
     * Stable, kebab-case identifier for this provider. Used to attribute failures and to suppress the
     * provider's findings. A blank id, or one already registered, is skipped and reported.
     */
    public val id: String

    /**
     * Returns the findings for [graph], or an empty list when the provider has nothing to say about
     * this project. Returning nothing is the normal case for a targeted provider and is never an
     * error.
     */
    public fun find(graph: ModuleDependencyGraph): List<Finding>
}
