// Standalone demo consumer for Aalekh. It is NOT part of the root build (the root
// settings.gradle.kts does not include it), so `./gradlew build` at the repo root ignores it.
//
// The Aalekh plugin is resolved straight from the sibling source build via `includeBuild("..")`,
// so there is nothing to publish and no version to pin - edit the plugin, re-run a sample task, and
// the change is picked up. Run from the repo root, e.g.:
//
//   ./gradlew -p sample aalekhReport
//   ./gradlew -p sample aalekhCheck
//   ./gradlew -p sample aalekhMermaid
pluginManagement {
    includeBuild("..")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.github.shivathapaa.aalekh")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "aalekh-sample"

include(":app")
include(":core:domain")
include(":core:data")
include(":feature:login")
