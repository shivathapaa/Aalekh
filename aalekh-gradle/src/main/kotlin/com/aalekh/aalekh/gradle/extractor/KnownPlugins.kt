package com.aalekh.aalekh.gradle.extractor

/**
 * Maps applied plugin **class** names back to the plugin **ids** a developer would recognise, and
 * identifies the classes that carry no information at all.
 *
 * Gradle's `PluginContainer` exposes instances, not ids, so a class name is all the object model
 * offers for a plugin the build script did not name literally - one applied by a convention plugin,
 * for instance. This table recovers the id for the plugins that matter in a Kotlin or Android build.
 *
 * It is deliberately a **table, not a guess**: an unrecognised class contributes nothing rather than
 * a mangled id derived from the class name. `PluginBlockParser` covers what the build script states
 * directly, and this covers what it applies indirectly; between them the common cases are named.
 */
internal object KnownPlugins {

    /**
     * Package prefixes for plugins Gradle applies to essentially every project.
     *
     * `HelpTasksPlugin`, `WrapperPlugin`, `BasePlugin` and their kin appear on every module of every
     * build. Carrying them means every module's plugin list is three-quarters noise - on a
     * 128-module sample they were 24% of the entire graph file - and no reader has ever needed to
     * see them. They are dropped after module-type detection, which does not look at them anyway.
     */
    private val INFRASTRUCTURE_PREFIXES = listOf(
        "org.gradle.api.plugins.",
        "org.gradle.buildinit.",
        "org.gradle.language.base.",
        "org.gradle.kotlin.dsl.provider.",
        "org.gradle.plugins.ide.",
        "org.gradle.testing.base.",
    )

    /**
     * Class-name fragment to plugin id, longest fragment first so a more specific plugin wins over a
     * more general one that shares a prefix.
     */
    private val CLASS_TO_ID: List<Pair<String, String>> = listOf(
        "com.android.build.gradle.internal.plugins.KotlinMultiplatformAndroidPlugin"
            to "com.android.kotlin.multiplatform.library",
        "com.android.build.gradle.internal.plugins.AppPlugin" to "com.android.application",
        "com.android.build.gradle.internal.plugins.LibraryPlugin" to "com.android.library",
        "com.android.build.gradle.internal.plugins.DynamicFeaturePlugin" to "com.android.dynamic-feature",
        "com.android.build.gradle.internal.plugins.TestPlugin" to "com.android.test",
        "org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper"
            to "org.jetbrains.kotlin.multiplatform",
        "org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper" to "org.jetbrains.kotlin.android",
        "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper" to "org.jetbrains.kotlin.jvm",
        "org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin" to "org.jetbrains.kotlin.kapt",
        "org.jetbrains.kotlinx.serialization.gradle.SerializationGradleSubplugin"
            to "org.jetbrains.kotlin.plugin.serialization",
        "org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradleSubplugin"
            to "org.jetbrains.kotlin.plugin.compose",
        "org.jetbrains.kotlin.gradle.plugin.ParcelizeSubplugin" to "org.jetbrains.kotlin.plugin.parcelize",
        "org.jetbrains.compose.ComposePlugin" to "org.jetbrains.compose",
        "com.google.devtools.ksp.gradle.KspGradleSubplugin" to "com.google.devtools.ksp",
        "dagger.hilt.android.plugin.HiltGradlePlugin" to "com.google.dagger.hilt.android",
        "com.google.gms.googleservices.GoogleServicesPlugin" to "com.google.gms.google-services",
        "com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsPlugin"
            to "com.google.firebase.crashlytics",
        "org.gradle.api.plugins.JavaLibraryPlugin" to "java-library",
        "org.gradle.api.plugins.ApplicationPlugin" to "application",
        "org.gradle.api.plugins.JavaPlugin" to "java",
    ).sortedByDescending { it.first.length }

    /**
     * The plugin id for an applied class, or null when the class is not one Aalekh recognises.
     *
     * Matching is on a class-name fragment because Gradle decorates plugin classes at runtime -
     * `LibraryPlugin` arrives as `LibraryPlugin_Decorated` - so exact equality would match nothing.
     */
    fun idFor(className: String): String? =
        CLASS_TO_ID.firstOrNull { (fragment, _) -> className.startsWith(fragment) }?.second

    /**
     * True when a class is Gradle's own always-applied infrastructure and should not be reported.
     *
     * The `java-library` / `application` / `java` entries in [CLASS_TO_ID] share the infrastructure
     * prefix but *are* meaningful, so a recognised id always wins over the prefix filter.
     */
    fun isInfrastructure(className: String): Boolean =
        idFor(className) == null && INFRASTRUCTURE_PREFIXES.any { className.startsWith(it) }

    /**
     * True when a class name has no package - the shape of a convention plugin compiled in
     * `build-logic`, e.g. `KotlinMultiplatformConventionPlugin`. Worth surfacing: convention-plugin
     * adoption is one of the clearest signals of how consistently a build is configured.
     */
    fun isConventionPlugin(className: String): Boolean =
        !className.contains('.') && className.isNotBlank()
}
