plugins { kotlin("jvm") version "2.3.0" }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
}
