plugins {
    id("com.gradle.plugin-publish")
}

val pluginVersion: String by rootProject.extra

group = "io.github.shivathapaa"
version = pluginVersion

gradlePlugin {
    website = "https://github.com/shivathapaa/aalekh"
    vcsUrl = "https://github.com/shivathapaa/aalekh.git"

    plugins {
        // Settings plugin - fully CC-safe when used with includeBuild
        create("aalekhSettings") {
            id = "io.github.shivathapaa.aalekh"
            implementationClass = "com.aalekh.aalekh.gradle.AalekhSettingsPlugin"
            displayName = "Aalekh - Architecture Visualization & Linting for KMP & Android"
            description =
                "Extracts, visualizes, and enforces architectural rules in Kotlin Multiplatform " +
                        "and Android projects. Interactive HTML graph. Architecture rule DSL. CI-ready."
            tags = listOf(
                "kotlin", "android", "kmp", "kotlin-multiplatform",
                "architecture", "visualization", "dependency-graph", "linting"
            )
        }
        // Project plugin - alternate entry point for users who prefer applying in build.gradle.kts
        create("aalekhProject") {
            id = "io.github.shivathapaa.aalekh.project"
            implementationClass = "com.aalekh.aalekh.gradle.AalekhPlugin"
            displayName = "Aalekh - Architecture Visualization & Linting (project plugin)"
            description = "Project-plugin variant of Aalekh. Prefer the settings plugin for includeBuild setups."
            tags = listOf(
                "kotlin", "android", "kmp", "kotlin-multiplatform",
                "architecture", "visualization", "dependency-graph", "linting"
            )
        }
    }
}