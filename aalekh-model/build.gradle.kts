plugins {
    id("aalekh.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.testing.unit)
    testRuntimeOnly(libs.bundles.testing.unit.runtime)
}

val pluginVersion: String by rootProject.extra

tasks.named<ProcessResources>("processResources") {
    val version = libs.versions.plugin.version.get()
    inputs.property("version", version)

    filesMatching("aalekh.properties") {
        expand("version" to version)
    }
}