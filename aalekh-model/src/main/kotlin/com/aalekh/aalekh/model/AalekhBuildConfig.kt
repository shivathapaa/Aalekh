package com.aalekh.aalekh.model

object AalekhBuildConfig {
    val VERSION: String by lazy {
        AalekhBuildConfig::class.java
            .getResourceAsStream("/aalekh.properties")
            ?.use { java.util.Properties().apply { load(it) }.getProperty("version") }
            ?: "unknown"
    }
}