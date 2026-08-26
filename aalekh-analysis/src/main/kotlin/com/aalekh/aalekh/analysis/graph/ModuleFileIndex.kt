package com.aalekh.aalekh.analysis.graph

import com.aalekh.aalekh.model.ModuleDependencyGraph

/**
 * Maps repo-relative file paths to the module that owns them, by directory prefix.
 *
 * Shared by the git-driven analyses (temporal coupling, affected graph): each maps changed files to
 * modules the same way. A file belongs to the module with the **longest** directory prefix that
 * contains it, so nested modules resolve to the deepest owner. The root project (empty directory) is
 * excluded - it owns no subproject files of its own. Pure; the directory list is built once and
 * reused across many files.
 */
internal object ModuleFileIndex {

    /** A module's source directory (repo-relative, no trailing slash) and its Gradle path. */
    data class ModuleDir(val directory: String, val path: String)

    /** Module directories sorted deepest-first, so the first prefix match is the most specific. */
    fun directories(graph: ModuleDependencyGraph): List<ModuleDir> =
        graph.modules
            .map { module ->
                val directory = module.buildFilePath?.substringBeforeLast('/', missingDelimiterValue = "")
                    ?: module.path.trimStart(':').replace(':', '/')
                ModuleDir(directory, module.path)
            }
            .filter { it.directory.isNotEmpty() }
            .sortedWith(compareByDescending<ModuleDir> { it.directory.length }.thenBy { it.directory })

    /** The module path owning [file], or null when it falls outside every module directory. */
    fun moduleForFile(file: String, directories: List<ModuleDir>): String? =
        directories.firstOrNull { dir ->
            file == dir.directory || file.startsWith(dir.directory + "/")
        }?.path

    /** The distinct set of modules touched by any of [files]. */
    fun modulesTouched(files: Collection<String>, directories: List<ModuleDir>): Set<String> =
        files.mapNotNullTo(LinkedHashSet()) { moduleForFile(it, directories) }
}
