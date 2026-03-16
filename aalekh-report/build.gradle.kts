plugins {
    id("aalekh.kotlin-library")
    id("aalekh.maven-publish")
    alias(libs.plugins.kotlin.serialization)
}

val pluginVersion: String by rootProject.extra
val pluginArtId: String by rootProject.extra

group = pluginArtId
version = pluginVersion

dependencies {
    api(project(":aalekh-model"))
    implementation(project(":aalekh-analysis"))

    testImplementation(libs.bundles.testing.unit)
    testRuntimeOnly(libs.bundles.testing.unit.runtime)
}