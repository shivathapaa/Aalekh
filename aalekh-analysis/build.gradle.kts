plugins {
    id("aalekh.kotlin-library")
}

dependencies {
    api(project(":aalekh-model"))

    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.kotest.property)   // Property-based testing for graph algorithms
    testRuntimeOnly(libs.bundles.testing.unit.runtime)
}