package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Forbids a Kotlin Multiplatform module's `commonMain` source set from depending on a platform-only
 * module.
 *
 * `commonMain` code is compiled for *every* target the module declares (JVM, Android, iOS, ...), so it
 * can only reference APIs that exist on all of them. A `commonMain` dependency on a JVM-only or
 * Android-only module does not compile for the other targets - it silently constrains the module to a
 * single platform, defeating the point of putting the code in `commonMain`. Aalekh knows the owning
 * source set of each edge (from the KMP configuration name), so it can catch this at the module graph
 * level without a compiler.
 *
 * Only edges owned by `commonMain` are checked, and only when the target is a genuinely single-platform
 * module ([ModuleType.ANDROID_APP], [ModuleType.ANDROID_LIBRARY], [ModuleType.JVM_LIBRARY]); depending
 * on another multiplatform module ([ModuleType.KMP], [ModuleType.KMP_ANDROID_LIBRARY]) is fine, and
 * `UNKNOWN`-typed targets are skipped to avoid false positives on modules Aalekh could not classify.
 * Opt in with `rules { noCommonMainPlatformDependencies() }`.
 */
internal class KmpCommonMainRule : ArchRule {

    override val id: String = "kmp-common-main-platform-dependency"
    override val description: String =
        "A KMP module's commonMain must not depend on a platform-only (JVM/Android) module."
    override val defaultSeverity: Severity = Severity.ERROR
    override val plainLanguageExplanation: String =
        "commonMain is compiled for every platform the module targets, so it can only use multiplatform " +
                "dependencies. Depending on a JVM-only or Android-only module breaks the other targets. " +
                "Move the dependency to the matching platform source set (e.g. androidMain), or make the " +
                "target module multiplatform."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
        graph.edges
            .asSequence()
            .filter { !it.isTest && it.sourceSet == COMMON_MAIN && it.from != it.to }
            .filter { graph.moduleByPath(it.to)?.type in PLATFORM_ONLY_TYPES }
            .map { edge -> violation(edge.from, edge.to, graph.moduleByPath(edge.to)?.type) }
            .toList()

    private fun violation(from: String, to: String, toType: ModuleType?): Violation {
        val typeLabel = toType?.name ?: ModuleType.UNKNOWN.name
        return Violation(
            ruleId = id,
            severity = defaultSeverity,
            message = "commonMain of $from depends on platform-only module $to ($typeLabel). " +
                    "commonMain compiles for every target, so move this to the matching platform " +
                    "source set (e.g. androidMain) or make $to multiplatform.",
            source = "$from → $to",
            moduleHint = from,
            plainLanguageExplanation = plainLanguageExplanation,
        )
    }

    companion object {
        private const val COMMON_MAIN = "commonMain"

        /** Single-platform module types a `commonMain` dependency cannot safely target. */
        private val PLATFORM_ONLY_TYPES = setOf(
            ModuleType.ANDROID_APP,
            ModuleType.ANDROID_LIBRARY,
            ModuleType.JVM_LIBRARY,
        )
    }
}
