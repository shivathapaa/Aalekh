package com.aalekh.aalekh.gradle.service

import com.aalekh.aalekh.gradle.AalekhPlugin
import com.aalekh.aalekh.gradle.extractor.GraphExtractor
import com.aalekh.aalekh.gradle.task.AalekhCheckTask
import com.aalekh.aalekh.gradle.task.AalekhReportTask
import com.aalekh.aalekh.model.ModuleDependencyGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Configuration-cache-safe holder for the extracted [ModuleDependencyGraph].
 *
 * ### Why a BuildService?
 * In Gradle 9.x, configuration cache is ON by default. Tasks cannot capture
 * live [Project] references - they are not serializable and will
 * break the cache. A BuildService IS cache-compatible: only its [Parameters]
 * are stored between cache entries, and tasks receive it as a typed [Provider]
 * reference, not a direct object.
 *
 * ### Data flow
 * 1. [AalekhPlugin] registers this service at settings time
 * 2. [GraphExtractor] populates it via [setGraph] during `projectsEvaluated`
 * 3. [AalekhReportTask] and [AalekhCheckTask] read [getGraphJson] during execution
 *
 * The graph is stored as a serialized JSON string (not a live object) to
 * survive configuration cache serialization. The [ModuleDependencyGraph]
 * object is re-hydrated from JSON inside task actions.
 */
public abstract class AalekhBuildService : BuildService<AalekhBuildService.Parameters> {

    public interface Parameters : BuildServiceParameters {
        /**
         * Serialized [ModuleDependencyGraph] JSON.
         * Stored as a String because it must be CC-serializable.
         */
        public val graphJson: Property<String>
    }

    private val serializationJson = Json { encodeDefaults = true }

    /** Called by [GraphExtractor] after all projects are evaluated.*/
    public fun setGraph(graph: ModuleDependencyGraph) {
        parameters.graphJson.set(serializationJson.encodeToString(graph))
    }

    /**
     * Returns the graph as a JSON string.
     * Empty JSON object if the graph has not been set yet (should not happen in normal usage).
     */
    public fun getGraphJson(): String = parameters.graphJson.getOrElse("{}")

    /**
     * Deserializes and returns the graph as a [ModuleDependencyGraph].
     * Use [getGraphJson] instead when embedding in HTML to avoid double serialization.
     */
    public fun getGraph(): ModuleDependencyGraph =
        serializationJson.decodeFromString(getGraphJson())

    public companion object {
        public const val NAME: String = "aalekhBuildService"
    }
}