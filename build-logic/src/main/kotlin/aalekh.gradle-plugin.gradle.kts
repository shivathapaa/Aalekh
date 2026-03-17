plugins {
    id("aalekh.kotlin-library")
    `java-gradle-plugin`
    id("com.gradle.plugin-publish")
}

val functionalTestSourceSet: SourceSet = sourceSets.create("functionalTest") {
    java.srcDir("src/functionalTest/kotlin")
    resources.srcDir("src/functionalTest/resources")
}

configurations["functionalTestImplementation"]
    .extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"]
    .extendsFrom(configurations["testRuntimeOnly"])

val functionalTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs functional (GradleRunner) tests."
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }

    maxParallelForks = 1

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
    }
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.named("check") {
    dependsOn(functionalTest)
}