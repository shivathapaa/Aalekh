import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import java.net.URI

/**
 * Per-module Dokka configuration for a documented Aalekh module.
 *
 * Emits API reference for public declarations only, wires GitHub source links so every symbol
 * points back at the exact line that defines it, and cross-links kotlinx-serialization types. Shared
 * config lives here so every module documents identically; the root project owns the multi-module
 * aggregation that stitches these into one site at `build/dokka/html`.
 */
plugins {
    id("org.jetbrains.dokka")
}

private val moduleRelPath = path.removePrefix(":").replace(':', '/')

extensions.configure<DokkaExtension> {
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
        reportUndocumented.set(false)
        skipEmptyPackages.set(true)

        val moduleDoc = layout.projectDirectory.file("Module.md")
        if (moduleDoc.asFile.exists()) includes.from(moduleDoc)

        sourceLink {
            localDirectory.set(layout.projectDirectory.dir("src"))
            remoteUrl.set(
                URI("https://github.com/shivathapaa/Aalekh/blob/main/$moduleRelPath/src")
            )
            remoteLineSuffix.set("#L")
        }

        externalDocumentationLinks.register("kotlinx-serialization") {
            url.set(URI("https://kotlinlang.org/api/kotlinx.serialization/"))
        }
    }
}
