package com.aalekh.aalekh.gradle.dsl

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configures the affected-graph analysis inside the `affected { }` block.
 *
 * ```kotlin
 * aalekh {
 *     affected {
 *         baseRef.set("origin/main")   // compare against the PR's merge target
 *         headRef.set("HEAD")          // ...up to this ref (blank = working tree)
 *     }
 * }
 * ```
 *
 * Drives `aalekhAffected`, which runs `git diff` at execution time and writes a local
 * `aalekh-affected.md` / `aalekh-affected.json` a CI job can post as a pull-request comment.
 */
public abstract class AffectedGraphConfig @Inject constructor(objects: ObjectFactory) {

    /**
     * The base git ref to diff against. Default: `"HEAD~1"` (the previous commit). Set to the PR's
     * merge target, e.g. `"origin/main"`, in CI.
     */
    public val baseRef: Property<String> =
        objects.property(String::class.java).convention("HEAD~1")

    /**
     * The head git ref. Blank (the default) diffs [baseRef] against the working tree; a non-blank
     * value uses the three-dot form `baseRef...headRef` - changes on head since the merge base.
     */
    public val headRef: Property<String> =
        objects.property(String::class.java).convention("")
}
