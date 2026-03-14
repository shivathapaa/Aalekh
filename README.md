# Aalekh

**Architecture Visualization & Linting for Gradle Multi-Module Projects**

Aalekh is a Gradle plugin that extracts, visualizes, and enforces architectural rules across any
Gradle multi-module project - Kotlin Multiplatform, Android, JVM, or any Gradle projects. It gives
teams three capabilities that no existing tool provides together: an **interactive module graph**, a
**Kotlin DSL for architecture rule enforcement**, and **historical metrics tracking**.

### Sample Reports

- Now in Android App
  - [View locally](assets/report_samples/nowinandroid.html)
  - [View on GitHub Pages](https://shivathapaa.github.io/Aalekh/assets/report_samples/nowinandroid.html)

- Now in Android App - with cyclic dependency
  - [View locally](assets/report_samples/nowinandroid_withcyclic.html)
  - [View on GitHub Pages](https://shivathapaa.github.io/Aalekh/assets/report_samples/nowinandroid_withcyclic.html)

- Tallyo (KMP)
  - [View locally](assets/report_samples/tallyo.html)
  - [View on GitHub Pages](https://shivathapaa.github.io/Aalekh/assets/report_samples/tallyo.html)

## Why Aalekh?

| Tool       | Visualizes | Enforces rules | Tracks metrics | KMP-aware |
|------------|:----------:|:--------------:|:--------------:|:---------:|
| **Aalekh** |   **✓**    |     **✓**      |     **✓**      |   **✓**   |

Aalekh **visualizes, enforces, and tracks** - in a single plugin, with zero external dependencies
beyond the browser.

## Quick Start

**1. Add to `settings.gradle.kts`:**

```kotlin
plugins {
    id("io.github.shivathapaa.aalekh") version "0.0.1-alpha01"
}
```

**2. Run:**

```bash
./gradlew aalekhReport
```

An interactive HTML report opens automatically in your default browser. That's it - no configuration
required.

## Installation

### Settings plugin (recommended)

Apply in `settings.gradle.kts`. This is the preferred approach - the settings plugin loads in a
classloader scope that is stable across configuration cache entries, preventing the "class not found
in classloader" error on second runs.

```kotlin
// settings.gradle.kts
plugins {
    id("io.github.shivathapaa.aalekh") version "0.0.1-alpha01"
}
```

### Project plugin (alternative)

If you prefer applying in the root project's `build.gradle.kts`:

```kotlin
// build.gradle.kts (root project only)
plugins {
    id("io.github.shivathapaa.aalekh.project") version "0.0.1-alpha01"
}
```

> **Note:** The project plugin works correctly when Aalekh is consumed from the Gradle Plugin
> Portal. For `includeBuild`
> setups, use the settings plugin to avoid configuration cache issues.

## Gradle Tasks

Aalekh registers three tasks on the root project, all in the `aalekh` task group.

| Task                      | Description                                                                                 |
|---------------------------|---------------------------------------------------------------------------------------------|
| `./gradlew aalekhExtract` | Extracts the module dependency graph and writes it as JSON to `build/tmp/aalekh/graph.json` |
| `./gradlew aalekhReport`  | Generates the interactive HTML report at `build/reports/aalekh/index.html`                  |
| `./gradlew aalekhCheck`   | Evaluates all architecture rules; fails the build on `ERROR`-severity violations            |

`aalekhCheck` is automatically wired into the standard `check` lifecycle task, so it runs as part of
`./gradlew check` without any extra configuration.

To wire it into CI explicitly:

```kotlin
// build.gradle.kts (root project)
tasks.named("check") {
    dependsOn("aalekhCheck")
}
```

### `aalekhReport` - interactive HTML report

```bash
./gradlew aalekhReport
```

Generates `build/reports/aalekh/index.html` - a fully self-contained HTML file with no server, no
CDN, and no internet connection required. The report opens automatically in your default browser
after the task completes (disable with`openBrowserAfterReport.set(false)` for CI).

The intermediate graph JSON is written to `build/tmp/aalekh/graph.json` and is cleaned by
`./gradlew clean`.

### `aalekhCheck` - architecture rule enforcement

```bash
./gradlew aalekhCheck
```

Evaluates all registered architecture rules against the extracted dependency graph. On completion it
writes:

- `build/reports/aalekh/aalekh-results.xml` - JUnit XML
- `build/reports/aalekh/aalekh-results.json` - machine-readable JSON for dashboards and custom
  tooling

If any `ERROR`-severity violation is found, the task fails with a summary message pointing to the
XML report:

```
Aalekh: 2 architecture violation(s) found.
See build/reports/aalekh/aalekh-results.xml for details.
```

`WARNING`-severity violations are printed to stdout but do not fail the build. `INFO`-severity
violations are silently collected and visible in the HTML report only.

### `aalekhExtract` - raw graph JSON

```bash
./gradlew aalekhExtract
```

Extracts and serializes the full module dependency graph to `build/tmp/aalekh/graph.json`. Useful
for integrating Aalekh data into custom tooling or piping into other scripts. Both `aalekhReport`
and `aalekhCheck`depend on this task implicitly - you rarely need to run it directly.

## The Report

`./gradlew aalekhReport` produces `build/reports/aalekh/index.html`. Open it in any browser - no
server required.

### Five panels

**⬡ Graph** - Interactive force-directed visualization powered by D3.js. Drag to orbit, scroll to
zoom, click any node to inspect it in the sidebar. Nodes are coloured by module type; cycle nodes
pulse with a red ring; god modules glow orange. Filter edges by type: Impl, API, Test, CompileOnly,
KMP source sets, Main Cycle, Test Cycle.

**⊞ Explorer** - Hierarchical tree view mirroring your Gradle project structure. Expand and collapse
groups, jump directly to cycle nodes, and see per-module dependency tables split by main vs test
scope.

**⊟ Matrix** - Adjacency matrix showing all inter-module dependencies at a glance. Hover a cell for
details; click a row or column label to inspect the module and sync with the graph.

**◎ Metrics** - KPI dashboard with fan-in, fan-out, instability index, critical build path, god
module count, and cycle counts. Main cycles and test-only cycles are reported separately. Per-module
sortable table with inline bar charts.

**⚑ Violations** - Structured violation cards for every `aalekhCheck` failure. Each card shows the
rule ID, severity badge, the exact dependency edge to remove, and a plain-language explanation of
why the rule exists.

### Sidebar - Module Inspector

Click any node in the graph to open the module inspector in the right sidebar. It shows:

- Module path and short name
- Module type badge (colour-coded)
- Fan-in, fan-out, and transitive dependency count
- Instability index bar (green = stable, yellow = mixed, red = unstable)
- KMP source sets (if applicable)
- Organisational tags inferred from the path
- Direct dependencies (clickable chips that navigate to the target node)
- Direct dependents (clickable chips that navigate to the source node)

### Cycle detection

Aalekh distinguishes between two kinds of cycles:

- **Main cycles** (`⚠ red`) - circular dependencies in production code only. These are genuine
  architectural errors that prevent independent builds and refactoring. `aalekhCheck` fails on these
  by default.
- **Test cycles** (`♻ pink`) - cycles that exist only through `testImplementation` or
  `androidTestImplementation`. These are common, usually acceptable, and do **not** cause a build
  failure.

## Configuration

All configuration lives in the `aalekh { }` block, which can be placed in either
`settings.gradle.kts` (settings plugin) or the root `build.gradle.kts` (project plugin).

```kotlin
aalekh {
    // Output directory relative to build/. Default: "reports/aalekh"
    // Full resolved path: <projectRoot>/build/reports/aalekh/index.html
    outputDir.set("reports/aalekh")

    // Open the report in the default browser after aalekhReport completes.
    // Default: true. Set to false in CI environments.
    openBrowserAfterReport.set(true)

    // Include testImplementation / androidTestImplementation edges in the graph.
    // Default: true. Test edges are shown separately and excluded from main
    // cycle detection regardless of this setting.
    includeTestDependencies.set(true)

    // Include compileOnly / runtimeOnly edges in the graph.
    // Default: false.
    includeCompileOnlyDependencies.set(false)
}
```

### Configuration option reference

| Option                           | Type      | Default            | Description                                                                  |
|----------------------------------|-----------|--------------------|------------------------------------------------------------------------------|
| `outputDir`                      | `String`  | `"reports/aalekh"` | Output directory relative to `build/`. Final path: `build/<outputDir>/`      |
| `openBrowserAfterReport`         | `Boolean` | `true`             | Auto-open the HTML report in the default browser after `aalekhReport` runs   |
| `includeTestDependencies`        | `Boolean` | `true`             | Include `testImplementation`, `androidTestImplementation`, etc. in the graph |
| `includeCompileOnlyDependencies` | `Boolean` | `false`            | Include `compileOnly` and `runtimeOnly` edges in the graph                   |

## Architecture Rules

The rule engine is implemented and running. The built-in rule `no-cyclic-dependencies` is always
active and fails the build on any production dependency cycle.

### Built-in rules

| Rule ID                  | Severity | Description                                                      |
|--------------------------|----------|------------------------------------------------------------------|
| `no-cyclic-dependencies` | `ERROR`  | The module dependency graph must be a DAG (no production cycles) |

### Violation severity levels

| Severity  | Effect                                                        |
|-----------|---------------------------------------------------------------|
| `ERROR`   | Fails the build. Printed to stderr.                           |
| `WARNING` | Printed to stdout. Build continues.                           |
| `INFO`    | Silently collected. Visible in the HTML report and JSON only. |

### Custom rules *(coming soon)*

A Kotlin DSL for declaring layer boundaries and custom rules is coming in the next release - letting
you fail the build when modules cross architectural boundaries:

```kotlin
// Coming in next release
aalekh {
    rules {
        layer("domain") {
            modules { path.contains(":domain") }
            mustNotDependOn { type == ModuleType.ANDROID_LIBRARY }
        }
        maxFanOut(limit = 8, severity = Severity.WARNING)
    }
}
```

## Module Types

Aalekh infers the module type from applied plugin IDs. Detection runs in priority order - first
match wins - because a KMP module often also applies the Android library plugin.

| Module Type           | Plugin ID                                            | Color  |
|-----------------------|------------------------------------------------------|--------|
| `KMP`                 | `org.jetbrains.kotlin.multiplatform`                 | Purple |
| `KMP_ANDROID_LIBRARY` | `com.android.kotlin.multiplatform.library`           | Teal   |
| `ANDROID_APP`         | `com.android.application`                            | Blue   |
| `ANDROID_LIBRARY`     | `com.android.library`, `com.android.dynamic-feature` | Green  |
| `JVM_LIBRARY`         | `org.jetbrains.kotlin.jvm`, `java-library`, `java`   | Amber  |
| `UNKNOWN`             | *(fallback - no known plugin applied)*               | Gray   |

## Graph Metrics

The following metrics are computed per module and shown in the **Metrics** panel and the module
inspector sidebar:

| Metric               | Description                                                                  |
|----------------------|------------------------------------------------------------------------------|
| **Fan-out**          | Number of modules this module directly depends on (production only)          |
| **Fan-in**           | Number of modules that directly depend on this one (production only)         |
| **Instability**      | `fanOut / (fanIn + fanOut)`. Range 0.0 (stable) to 1.0 (unstable)            |
| **Transitive deps**  | Total number of modules reachable by following dependencies from this module |
| **Critical path**    | Longest dependency chain in the graph - constrains build parallelism         |
| **God modules**      | Modules with both high fan-in AND high fan-out - architectural hotspots      |
| **Isolated modules** | Modules with zero fan-in and zero fan-out - candidates for removal           |

## Project Structure

```
aalekh/
├── aalekh-model/          ← Data classes only. No Gradle dependency.
│                             ModuleDependencyGraph, ModuleNode, DependencyEdge,
│                             Violation, Severity, ModuleType
│
├── aalekh-analysis/       ← Pure Kotlin. No Gradle API.
│                             GraphAnalyzer  - topological sort, critical path,
│                                              god/leaf/root/isolated module detection
│                             RuleEngine     - evaluates ArchRule implementations
│                             MetricsEngine  - per-module and project-wide metrics
│
├── aalekh-report/         ← Report generators. No Gradle API.
│                             HtmlReportGenerator - self-contained HTML with D3.js
│                             JUnitXmlWriter      - CI-compatible XML
│                             JsonReporter        - machine-readable JSON
│
├── aalekh-gradle/         ← Gradle plugin entry point and tasks.
│                             AalekhPlugin / AalekhSettingsPlugin
│                             AalekhExtractTask, AalekhReportTask, AalekhCheckTask
│                             AalekhExtension (DSL)
│                             GraphExtractor, ModuleTypeDetector
│
└── build-logic/           ← Convention plugins for the Aalekh build itself.
                              Not part of the published plugin.
```

The layering is intentional: `aalekh-model` ← `aalekh-analysis` ← `aalekh-report` ← `aalekh-gradle`.
The Gradle API only appears in the outermost module. All analysis and report logic is pure Kotlin
and testable without Gradle on the classpath.

## Configuration Cache

Aalekh is fully compatible with Gradle's configuration cache, which is enabled by default in Gradle
9.x. All task inputs are `@Input` primitives or `@InputFile` paths - no live `Project`,
`Configuration`, or `Dependency` objects are captured inside task actions.

The intermediate graph JSON written to `build/tmp/aalekh/graph.json` is the serialization boundary
between the configuration phase (graph extraction) and the execution phase (report and check tasks).

## CI Setup

### GitHub Actions

```yaml
- name: Run architecture check
  run: ./gradlew aalekhCheck

- name: Upload Aalekh report
  uses: actions/upload-artifact@v4
  if: always()
  with:
    name: aalekh-report
    path: build/reports/aalekh/

- name: Publish test results
  uses: mikepenz/action-junit-report@v4
  if: always()
  with:
    report_paths: build/reports/aalekh/aalekh-results.xml
```

### Recommended CI configuration

Disable browser auto-open and keep test dependencies in graph for full visibility:

```kotlin
aalekh {
    openBrowserAfterReport.set(false)
    includeTestDependencies.set(true)
}
```

## Roadmap

| Phase      | Theme                                                 | Status         |
|------------|-------------------------------------------------------|----------------|
| **Alpha**  | Graph extraction + interactive HTML report            | ✅ Released     |
| **Next**   | Rule engine DSL + layer boundary enforcement          | 🔨 In progress |
| **Later**  | Metrics tracking + historical trend reports           | 📋 Planned     |
| **Future** | Source-level analysis via KSP2 + stable API guarantee | 📋 Planned     |

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

### Building locally

```bash
git clone https://github.com/shivathapaa/aalekh.git
cd aalekh
./gradlew build
```

### Running tests

```bash
# Unit tests across all modules
./gradlew checkAll

# Functional tests only
./gradlew :aalekh-gradle:functionalTest
```

## License

```
Copyright 2026 Shiva Thapa

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```

<p align="center">
  Made with ♥ for the Kotlin community
  <br/>
  <a href="https://github.com/shivathapaa/aalekh/issues">Report a bug</a> ·
  <a href="https://github.com/shivathapaa/aalekh/discussions">Request a feature</a>
</p>