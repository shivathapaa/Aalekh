package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Fails when a module's transitive dependency count exceeds [maxCount].
 *
 * A high transitive count means the module pulls in a large hidden dependency surface,
 * which increases build times, bloats binary size, and creates invisible coupling.
 * This rule makes that cost explicit and enforceable.
 */
internal class MaxTransitiveDependenciesRule(private val maxCount: Int) : ArchRule {

    override val id = "max-transitive-dependencies"
    override val description = "Modules must not exceed $maxCount transitive dependencies."
    override val defaultSeverity = Severity.WARNING
    override val plainLanguageExplanation =
        "Too many transitive dependencies increases build times and creates hidden coupling. " +
                "Consider splitting this module or moving shared dependencies to a dedicated module."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
        graph.modules.mapNotNull { module ->
            val count = graph.transitiveCount(module.path)
            if (count <= maxCount) return@mapNotNull null

            val buildFileHint = module.buildFilePath
                ?.let { " Review dependencies in $it." }
                ?: ""

            Violation(
                ruleId = id,
                severity = defaultSeverity,
                message = "${module.path} has $count transitive dependencies (limit: $maxCount).$buildFileHint",
                source = module.path,
                moduleHint = module.path,
                plainLanguageExplanation = plainLanguageExplanation,
            )
        }
}