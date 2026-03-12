plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.gradle.plugin.publish)
    implementation(libs.detekt.gradlePlugin)
}