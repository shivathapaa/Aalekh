plugins {
    id("aalekh.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.testing.unit)
    testRuntimeOnly(libs.bundles.testing.unit.runtime)
}