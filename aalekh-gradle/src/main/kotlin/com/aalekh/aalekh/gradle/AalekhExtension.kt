package com.aalekh.aalekh.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL extension for configuring Aalekh in build.gradle.kts.
 *
 * ```kotlin
 * aalekh {
 *     // outputDir is relative to build/ - default is "reports/aalekh"
 *     // Final output: <projectRoot>/build/reports/aalekh/index.html
 *     outputDir.set("reports/aalekh")
 *     openBrowserAfterReport.set(true)
 *     includeTestDependencies.set(false)
 * }
 * ```
 *
 * ### Why outputDir is relative to build/, not the project root
 * Gradle's `layout.buildDirectory` is the canonical anchor for all build outputs.
 * Using it guarantees the output is always inside `<projectRoot>/build/`,
 * survives `./gradlew clean`, and never accidentally writes into a temp directory.
 */
public abstract class AalekhExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * Output directory for all Aalekh report files, **relative to `build/`**.
     *
     * Default: `"reports/aalekh"`
     * Full resolved path: `<projectRoot>/build/reports/aalekh/`
     *
     * The `build/` prefix is intentionally omitted here because the plugin
     * anchors the path to `layout.buildDirectory` - so the actual disk path
     * is always `<projectRoot>/build/<outputDir>/`.
     */
    public val outputDir: Property<String> = objects.property(String::class.java)
        .convention("reports/aalekh")

    /**
     * Automatically open the generated HTML report in the default browser
     * after `aalekhReport` completes successfully.
     * Default: `true`. Disable in CI: `openBrowserAfterReport.set(false)`
     */
    public val openBrowserAfterReport: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /**
     * Include test configurations (testImplementation, androidTestImplementation, etc.)
     * in the dependency graph.
     * Default: `true`
     */
    public val includeTestDependencies: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(true)

    /**
     * Include compileOnly configurations in the dependency graph.
     * Default: `false`
     */
    public val includeCompileOnlyDependencies: Property<Boolean> = objects.property(Boolean::class.java)
        .convention(false)

    public companion object {
        public const val NAME: String = "aalekh"
    }
}