package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.model.ModulePlugin
import com.aalekh.aalekh.model.PluginSource

/**
 * Reads the `plugins { }` block out of a build script.
 *
 * Gradle's object model exposes which plugin *classes* were applied, but not the ids the build script
 * asked for - and the id is what a developer recognises. `com.android.library` is meaningful;
 * `com.android.build.gradle.internal.plugins.LibraryPlugin` is trivia. The ids also carry information
 * the classes cannot: a convention plugin's own id (`myapp.android.library`), and the version-catalog
 * alias a plugin was applied through.
 *
 * This is a deliberately narrow **textual** scan of the `plugins { }` block, recognising the forms a
 * Kotlin or Groovy build script can name a plugin with:
 *
 * ```
 * id("com.android.library")                      // Kotlin DSL
 * id 'com.android.library'                       // Groovy DSL
 * id("com.foo") version "1.2.3"                  // with an explicit version
 * kotlin("jvm")                                  // kotlin() shorthand -> org.jetbrains.kotlin.jvm
 * kotlin("plugin.serialization")                 // -> org.jetbrains.kotlin.plugin.serialization
 * alias(libs.plugins.androidApplication)         // version-catalog alias
 * `java-library`                                 // backtick-quoted core plugin
 * application                                    // bare core plugin accessor
 * ```
 *
 * It is not a Kotlin parser and does not try to be. Anything it cannot recognise is left out rather
 * than guessed at, and the applied-class table in [KnownPlugins] covers what the scan misses.
 */
internal object PluginBlockParser {

    private val ID_CALL = Regex("""\bid\s*\(\s*["']([^"']+)["']\s*\)""")
    private val ID_GROOVY = Regex("""\bid\s+["']([^"']+)["']""")
    private val KOTLIN_CALL = Regex("""\bkotlin\s*\(\s*["']([^"']+)["']\s*\)""")
    private val ALIAS_CALL = Regex("""\balias\s*\(\s*([A-Za-z_][\w.]*)\s*\)""")
    private val BACKTICKED = Regex("""`([a-z][a-z0-9-]*)`""")
    private val BARE_ACCESSOR = Regex("""^\s*([a-z][a-zA-Z0-9-]*)\s*$""")
    private val VERSION_SUFFIX = Regex("""\bversion\s+["']([^"']+)["']""")

    /** Core Gradle plugins that can be applied by a bare accessor with no `id(...)` wrapper. */
    private val BARE_CORE_PLUGINS = setOf(
        "application", "java", "war", "base", "distribution", "groovy", "scala",
        "checkstyle", "pmd", "jacoco", "signing", "idea", "eclipse", "antlr",
    )

    /**
     * Parses the `plugins { }` block of a build script.
     *
     * @param lines The build script, already split into lines.
     * @return The plugins the script declares, in declaration order, deduplicated by id.
     */
    fun parse(lines: List<String>): List<ModulePlugin> =
        pluginBlock(lines)
            .flatMap { line -> parseLine(line) }
            .distinctBy { it.id }

    /**
     * The lines inside the first top-level `plugins { }` block.
     *
     * Brace counting rather than a regex over the whole file: a `plugins { }` block inside a
     * `subprojects { }` or `allprojects { }` closure applies to other modules, and treating it as
     * this module's would attribute plugins to the wrong place.
     */
    private fun pluginBlock(lines: List<String>): List<String> {
        val start = lines.indexOfFirst { PLUGINS_HEADER.containsMatchIn(it) }
        if (start < 0) return emptyList()

        val block = mutableListOf<String>()
        var depth = 0
        for (index in start until lines.size) {
            // The opening line contributes only what follows its brace, so `plugins { id("x") }`
            // on one line is read without also re-reading the word "plugins".
            val line = if (index == start) lines[index].substringAfter('{') else lines[index]
            depth += lines[index].count { it == '{' } - lines[index].count { it == '}' }
            block += line
            if (depth <= 0) break
        }
        return block
    }

    private fun parseLine(line: String): List<ModulePlugin> {
        val code = line.substringBefore("//").trim()
        if (code.isEmpty()) return emptyList()
        val version = VERSION_SUFFIX.find(code)?.groupValues?.get(1)

        val found = mutableListOf<ModulePlugin>()
        ID_CALL.findAll(code).forEach { found += ModulePlugin(it.groupValues[1], version) }
        if (found.isEmpty()) ID_GROOVY.findAll(code).forEach { found += ModulePlugin(it.groupValues[1], version) }
        KOTLIN_CALL.findAll(code).forEach { found += ModulePlugin(kotlinPluginId(it.groupValues[1]), version) }
        ALIAS_CALL.findAll(code).forEach { match ->
            aliasOf(match.groupValues[1])?.let { alias ->
                // The id is unknown until the catalog is consulted; the alias is what the script said.
                found += ModulePlugin(id = alias, version = null, alias = alias)
            }
        }
        BACKTICKED.findAll(code).forEach { found += ModulePlugin(it.groupValues[1], version) }
        if (found.isEmpty()) {
            BARE_ACCESSOR.find(code)?.groupValues?.get(1)
                ?.takeIf { it in BARE_CORE_PLUGINS }
                ?.let { found += ModulePlugin(it, null) }
        }
        return found.map { it.copy(source = PluginSource.BUILD_SCRIPT) }
    }

    /**
     * The plugin id behind a `kotlin("...")` shorthand.
     *
     * `kotlin("jvm")` is `org.jetbrains.kotlin.jvm`; the Kotlin DSL accessor simply prefixes the
     * namespace. Reproducing that here keeps `kotlin("jvm")` and `id("org.jetbrains.kotlin.jvm")`
     * reporting as the same plugin, which they are.
     */
    private fun kotlinPluginId(shortName: String): String = "org.jetbrains.kotlin.$shortName"

    /**
     * The catalog alias in `libs.plugins.androidApplication`, or null when the reference is not a
     * plugin accessor. Nested aliases (`libs.plugins.android.application`) flatten to the
     * dot-separated form the catalog itself uses.
     */
    private fun aliasOf(reference: String): String? {
        val marker = ".plugins."
        val index = reference.indexOf(marker)
        if (index < 0) return null
        return reference.substring(index + marker.length).takeIf { it.isNotBlank() }
    }

    private val PLUGINS_HEADER = Regex("""^\s*plugins\s*\{""")
}
