plugins { alias(libs.plugins.kotlinJvm) }

// A declared toolchain: Aalekh reports which Java version each module is built against.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
