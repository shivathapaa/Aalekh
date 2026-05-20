package com.aalekh.aalekh.gradle.extractor

/**
 * Single source of truth for classifying Gradle configuration names during
 * dependency-graph extraction.
 *
 * Centralises the standard / KMP / variant configuration vocabulary that the
 * settings plugin, the project plugin, and [com.aalekh.aalekh.gradle.task.AalekhExtractTask]
 * all need to agree on. Behaviour-preserving extraction of logic previously
 * duplicated across those three call sites.
 *
 * The two standard sets differ on purpose:
 * - [STANDARD] also covers `androidTest*` / `debugImplementation` / `releaseImplementation`
 *   so they are always captured.
 * - [SOURCE_SET_STANDARD] is the subset that should NOT be treated as a KMP
 *   source-set configuration when computing [kmpSourceSetName]. Variant
 *   configurations like `androidTestImplementation` fall through to source-set
 *   stripping (`"androidTest"`) to preserve historical edge attribution.
 */
internal object ConfigurationClassifier {

    /** Standard production project-dependency configurations. */
    val PRODUCTION: Set<String> = setOf(
        "implementation", "api", "compileOnly", "runtimeOnly",
    )

    /** Test and Android-variant configurations whose project deps are still architecturally relevant. */
    val TEST: Set<String> = setOf(
        "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly",
        "androidTestImplementation", "androidTestRuntimeOnly",
        "debugImplementation", "releaseImplementation",
    )

    /** [PRODUCTION] + [TEST]. The full set of standard configurations Aalekh captures. */
    val STANDARD: Set<String> = PRODUCTION + TEST

    /**
     * Configurations excluded from [kmpSourceSetName] stripping. Anything outside this
     * set that ends in a [KMP_SUFFIXES] entry is treated as a KMP source-set configuration.
     * Kept narrower than [STANDARD] so `androidTestImplementation` still yields `"androidTest"`
     * as the edge's source-set tag.
     */
    private val SOURCE_SET_STANDARD: Set<String> = setOf(
        "implementation", "api", "compileOnly", "runtimeOnly",
        "testImplementation", "testRuntimeOnly", "testApi", "testCompileOnly",
    )

    private val KMP_SUFFIXES: List<String> = listOf(
        "Implementation", "Api", "CompileOnly", "RuntimeOnly",
    )

    /** True when [name] looks like a KMP source-set-scoped configuration (e.g. `iosMainApi`). */
    fun isKmpSourceSetConfig(name: String): Boolean =
        KMP_SUFFIXES.any { name.endsWith(it) } && name !in SOURCE_SET_STANDARD

    /** True when project-to-project deps in this configuration should be captured. */
    fun isCaptured(name: String): Boolean =
        name in STANDARD || isKmpSourceSetConfig(name)

    /**
     * KMP source-set name for a configuration, or null for standard (non-KMP) configs.
     *
     * Examples:
     * - `"commonMainImplementation"` → `"commonMain"`
     * - `"androidMainApi"` → `"androidMain"`
     * - `"androidTestImplementation"` → `"androidTest"` (variant, treated as source-set tag)
     * - `"implementation"` → `null`
     */
    fun kmpSourceSetName(name: String): String? {
        if (name in SOURCE_SET_STANDARD) return null
        val suffix = KMP_SUFFIXES.firstOrNull { name.endsWith(it) } ?: return null
        return name.removeSuffix(suffix).ifEmpty { null }
    }

    fun isTestConfig(name: String): Boolean =
        name.contains("test", ignoreCase = true)

    fun isCompileOnlyConfig(name: String): Boolean =
        name.contains("compileOnly", ignoreCase = true)
}
