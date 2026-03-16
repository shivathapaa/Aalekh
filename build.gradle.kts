plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val pluginVersion: String by extra(libs.versions.plugin.version.get())
val pluginArtId: String by extra("io.github.shivathapaa")

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