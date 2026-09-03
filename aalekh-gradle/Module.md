# Module aalekh-gradle

The Gradle plugin itself — the only module that may touch the Gradle API. Applies the settings /
project plugins, exposes the `aalekh { }` DSL, extracts the module graph from the Gradle project
model, and wires the `aalekh*` tasks. Ships to the Gradle Plugin Portal (the other three modules go to
Maven Central).

Everything here is written to keep Gradle 9.x's configuration cache intact: no live `Project`,
`Configuration`, `Dependency`, or `Settings` reference survives into a task action. Plugins collect
data inside `provider { }` lambdas as plain strings, and `build/tmp/aalekh/graph.json` is the
serialization boundary between the configuration and execution phases.

# Package com.aalekh.aalekh.gradle

The plugin entry points. `AalekhSettingsPlugin` is canonical (apply it in `settings.gradle.kts`);
`AalekhPlugin` is the deprecated project plugin, retained only for backward compatibility.
`AalekhExtension` is the root of the configuration surface.

# Package com.aalekh.aalekh.gradle.dsl

The typed DSL reached from the `aalekh { }` block — `layers { }`, `rules { }`, module metadata, and
the rule-override entries. Configuration is serialized to delimited strings so it survives the
configuration cache and is rebuilt by `RuleEngine.fromConfig` at execution time.

# Package com.aalekh.aalekh.gradle.extractor

The single place the Gradle project model is read. `SubprojectDataCollector` gathers every subproject's
dependencies and plugins; `ConfigurationClassifier`, `ModuleTypeDetector`, and `DeclarationLineFinder`
turn that raw build state into typed, serializable facts. Extraction is fail-silent per module: a
broken module is logged and included as `ModuleType.UNKNOWN`, never crashing the run.

# Package com.aalekh.aalekh.gradle.task

The task types. `AalekhExtractTask` writes the graph JSON; `AalekhReportTask` produces the report and
`AalekhCheckTask` enforces the rules. `aalekhExtract` and `aalekhCheck` are cacheable; `aalekhReport`
is not (it must always reflect current state and may open a browser).
