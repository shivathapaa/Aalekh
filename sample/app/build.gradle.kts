plugins { kotlin("jvm") version "2.3.0" }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":feature:login"))
    implementation(libs.okhttp)
}
