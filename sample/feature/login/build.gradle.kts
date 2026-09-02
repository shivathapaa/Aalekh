plugins { kotlin("jvm") version "2.3.0" }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.gson)
    testImplementation(libs.junit.api)
}
