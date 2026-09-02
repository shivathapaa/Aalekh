package com.aalekh.aalekh.gradle.extractor

import com.aalekh.aalekh.model.ModuleMetadata
import kotlinx.serialization.json.Json
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Reads `.aalekh/modules.json` - the file a team uses to tell Aalekh things it cannot work out.
 *
 * Aalekh infers a great deal from paths and plugins, and every inference is a chance to be wrong.
 * This is the way out: whatever a team states here is an **observed fact** and overrides any guess.
 * A module's purpose in particular can only ever come from a human - no amount of graph analysis
 * reveals *why* a module exists.
 *
 * The file is entirely optional and partial by design. Describing the eight modules newcomers keep
 * asking about, and nothing else, is a perfectly good use of it.
 *
 * ```json
 * {
 *   "modules": [
 *     {
 *       "path": ":core:sync",
 *       "purpose": "Owns the offline queue and conflict resolution. Everything writing while
 *                   offline goes through here.",
 *       "owner": "platform-team",
 *       "status": "frozen",
 *       "links": { "Design doc": "https://…" }
 *     }
 *   ]
 * }
 * ```
 *
 * Reading is fail-silent: a malformed file is reported as a warning and treated as absent, because
 * a metadata file is documentation, and broken documentation must never fail a build.
 */
internal object ModuleMetadataReader {

    /** Conventional location, relative to the root project. */
    const val DEFAULT_PATH: String = ".aalekh/modules.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @kotlinx.serialization.Serializable
    private data class MetadataFile(val modules: List<ModuleMetadata> = emptyList())

    /**
     * Reads declared module metadata from [rootDir], keyed by module path.
     *
     * Returns an empty map when the file is absent, empty, or unparseable - in every case the rest of
     * Aalekh carries on with inference, which is what it would have done anyway.
     */
    fun read(rootDir: File, logger: Logger): Map<String, ModuleMetadata> {
        val file = rootDir.resolve(DEFAULT_PATH)
        if (!file.isFile) return emptyMap()

        return runCatching {
            json.decodeFromString(MetadataFile.serializer(), file.readText())
                .modules
                .filter { it.path.isNotBlank() }
                .associateBy { it.path }
        }.getOrElse { ex ->
            logger.warn(
                "Aalekh: could not read $DEFAULT_PATH - ${ex.message}. " +
                    "Module metadata is ignored for this run; the rest of the report is unaffected."
            )
            emptyMap()
        }
    }
}
