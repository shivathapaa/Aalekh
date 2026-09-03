plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka) apply false
}

val pluginVersion: String by extra(libs.versions.plugin.version.get())
val pluginArtId: String by extra("io.github.shivathapaa")

// Dokka multi-module aggregation. The root project is the aggregator: it applies Dokka and pulls
// each documented module in through the `dokka` configuration, producing one combined HTML site at
// `build/dokka/html`. Per-module settings (source links, visibility, kotlinx cross-links) live in
// the `aalekh.dokka` convention plugin; only the site-wide identity is set here.
//
// `apply false` above keeps Dokka's classes on the root's classpath; the root then applies it
// imperatively to act as aggregator without also being documented itself.
apply(plugin = "org.jetbrains.dokka")

dependencies {
    "dokka"(project(":aalekh-model"))
    "dokka"(project(":aalekh-analysis"))
    "dokka"(project(":aalekh-report"))
    "dokka"(project(":aalekh-gradle"))
}

extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
    moduleName.set("Aalekh")
}

/**
 * Runs all checks across all subprojects.
 * Usage: ./gradlew checkAll
 */
tasks.register("checkAll") {
    group = "verification"
    description = "Runs tests and static analysis across all subprojects."
    dependsOn(subprojects.map { "${it.path}:check" })
}

/**
 * Prints the full module dependency tree for inspection.
 * Usage: ./gradlew moduleGraph
 */
tasks.register("moduleGraph") {
    group = "aalekh"
    description = "Prints the Aalekh internal module dependency tree."
    doLast {
        println(
            """
            Aalekh Module Dependency Graph
            ─────────────────────────────────
            :aalekh-model
              ← (no production dependencies)
            :aalekh-analysis
              ← :aalekh-model
            :aalekh-report
              ← :aalekh-model
              ← :aalekh-analysis
            :aalekh-gradle
              ← :aalekh-model
              ← :aalekh-analysis
              ← :aalekh-report
              ← Gradle API (gradleApi())
        """.trimIndent()
        )
    }
}