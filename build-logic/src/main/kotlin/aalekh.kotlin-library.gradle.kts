import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("io.gitlab.arturbosch.detekt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

private val Project.libs
    get(): VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(libs.findBundle("testing-unit").get())
    testRuntimeOnly(libs.findBundle("testing-unit-runtime").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Static analysis. Each module keeps its own detekt-baseline.xml so newly
// introduced issues fail the build while pre-existing ones stay grandfathered.
// Detekt ships with a Kotlin compiler embedded in 1.9.x; for Kotlin 2.x source
// the non-type-resolution rules still work cleanly.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    baseline = file("detekt-baseline.xml")
    autoCorrect = false
    parallel = true
    ignoreFailures = false
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
    }
    // Detekt's Kotlin compiler embeddable lags the project Kotlin version; the
    // mismatch is harmless for non-type-resolution rules but produces a noisy
    // warning otherwise.
    jvmTarget = "11"
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "11"
}
