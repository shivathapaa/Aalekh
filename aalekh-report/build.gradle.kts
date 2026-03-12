plugins {
    id("aalekh.kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":aalekh-model"))
    implementation(project(":aalekh-analysis"))

    testImplementation(libs.bundles.testing.unit)
    testRuntimeOnly(libs.bundles.testing.unit.runtime)
}