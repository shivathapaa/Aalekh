plugins { kotlin("jvm") version "2.3.0" }

dependencies {
    implementation(project(":core:domain"))
    api("com.squareup.moshi:moshi:1.15.1")
}
