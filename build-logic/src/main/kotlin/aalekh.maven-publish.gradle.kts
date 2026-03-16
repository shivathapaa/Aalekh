plugins {
    id("com.vanniktech.maven.publish")
}

val pluginVersion: String by rootProject.extra

mavenPublishing {
    coordinates(
        groupId = "io.github.shivathapaa",
        artifactId = project.name,
        version = pluginVersion
    )

    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set(project.name)
        description.set("Aalekh - Architecture Visualization & Linting for KMP, Android, & other Gradle projects")
        inceptionYear.set("2026")
        url.set("https://github.com/shivathapaa/aalekh")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("shivathapaa")
                name.set("Shiva Thapa")
                email.set("query.shivathapaa.dev@gmail.com")
                url.set("https://github.com/shivathapaa/")
            }
        }
        scm {
            url.set("https://github.com/shivathapaa/aalekh")
            connection.set("scm:git:git://github.com/shivathapaa/aalekh.git")
            developerConnection.set("scm:git:ssh://github.com/shivathapaa/aalekh.git")
        }
    }
}