plugins {
    id("aalekh.kotlin-library")
}

val pluginVersion: String by rootProject.extra
val pluginArtId: String by rootProject.extra

group = pluginArtId
version = pluginVersion

dependencies {
    api(project(":aalekh-model"))

    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.kotest.property)   // Property-based testing for graph algorithms
    testRuntimeOnly(libs.bundles.testing.unit.runtime)
}