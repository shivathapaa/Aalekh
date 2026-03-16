plugins {
    `maven-publish`
    signing
}

val pluginVersion: String by rootProject.extra
val pluginArtId: String by rootProject.extra

group = pluginArtId
version = pluginVersion

pluginManager.withPlugin("java") {
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }
}

afterEvaluate {
    publishing {
        publications {
            // aalekh-gradle already has "pluginMaven" from com.gradle.plugin-publish
            // all other submodules need "maven" created
            if (publications.findByName("pluginMaven") == null) {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    applyPom(project)
                }
            } else {
                named<MavenPublication>("pluginMaven") {
                    applyPom(project)
                }
            }
        }

        repositories {
            maven {
                name = "MavenCentral"
                url = uri(
                    if (pluginVersion.endsWith("SNAPSHOT"))
                        "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    else
                        "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                )
                credentials {
                    username = System.getenv("MAVEN_CENTRAL_USERNAME")
                    password = System.getenv("MAVEN_CENTRAL_PASSWORD")
                }
            }
        }
    }

    val signingKey: String? = System.getenv("GPG_KEY_CONTENTS")
    if (!signingKey.isNullOrBlank()) {
        signing {
            useInMemoryPgpKeys(
                System.getenv("SIGNING_KEY_ID"),
                signingKey,
                System.getenv("SIGNING_PASSWORD")
            )
            sign(publishing.publications)  // signs all publications in this subproject
        }
    } else {
        tasks.withType<Sign>().configureEach {
            enabled = false
        }
    }
}

fun MavenPublication.applyPom(project: Project) {
    pom {
        name.set(project.name)
        description.set("Aalekh - Architecture Visualization & Linting for KMP & Android")
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