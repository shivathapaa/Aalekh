package com.aalekh.aalekh.analysis.rules

import com.aalekh.aalekh.analysis.graph.GraphAnalyzer
import com.aalekh.aalekh.model.ModuleDependencyGraph
import com.aalekh.aalekh.model.Severity
import com.aalekh.aalekh.model.Violation

/**
 * Flags "orphan" modules - modules that neither depend on anything nor are depended on by
 * anything (zero fan-in and zero fan-out over production edges).
 *
 * An orphan is dead weight in the build: it is compiled and configured on every run but nothing
 * consumes it, so it is either an abandoned module that should be deleted or a module that was
 * never wired into the graph. Surfacing it keeps the module list honest.
 *
 * Test-only edges do not rescue a module from orphan status: a module wired in solely through
 * `testImplementation` still contributes nothing to production and is reported.
 */
internal class NoOrphanModulesRule : ArchRule {

    override val id = "no-orphan-modules"
    override val description = "Modules must be connected to the graph - no isolated, unused modules."
    override val defaultSeverity = Severity.WARNING
    override val plainLanguageExplanation =
        "A module that nothing depends on and that depends on nothing is dead weight - it is " +
                "built on every run but never used. Delete it, or wire it into the graph if it is " +
                "meant to be consumed."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
        GraphAnalyzer.isolatedModules(graph).map { module ->
            val buildFileHint = module.buildFilePath?.let { " (declared in $it)" } ?: ""
            Violation(
                ruleId = id,
                severity = defaultSeverity,
                message = "Module ${module.path} is isolated$buildFileHint - nothing depends on it " +
                        "and it depends on nothing. Remove it, or wire it into the graph.",
                source = module.path,
                moduleHint = module.path,
                plainLanguageExplanation = plainLanguageExplanation,
            )
        }
}
