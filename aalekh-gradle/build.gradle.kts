plugins {
    id("aalekh.gradle-plugin")
    id("aalekh.publishing")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":aalekh-model"))
    implementation(project(":aalekh-analysis"))
    implementation(project(":aalekh-report"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.testing.unit)
    testRuntimeOnly(libs.bundles.testing.unit.runtime)
}