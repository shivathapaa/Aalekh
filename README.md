# Aalekh

<p align="center">
  <img src="assets/images/aalekh_banner.png" alt="Aalekh banner" width="100%"/>
</p>

<p align="center">
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.3+-7F52FF.svg?logo=kotlin&logoColor=white" alt="Kotlin"/></a>
  <a href="https://gradle.org"><img src="https://img.shields.io/badge/Gradle-9.x-02303A.svg?logo=gradle&logoColor=white" alt="Gradle"/></a>
  <a href="https://central.sonatype.com/artifact/io.github.shivathapaa/aalekh-model"><img src="https://img.shields.io/maven-central/v/io.github.shivathapaa/aalekh-model?label=Maven%20Central&color=blue" alt="Maven Central"/></a>
</p>

<p align="center">
  <a href="https://plugins.gradle.org/plugin/io.github.shivathapaa.aalekh"><img src="https://img.shields.io/gradle-plugin-portal/v/io.github.shivathapaa.aalekh?label=Gradle%20Plugin%20Portal&color=02303A&logo=gradle" alt="Gradle Plugin Portal"/></a>
  <a href="https://docs.gradle.org/current/userguide/configuration_cache.html"><img src="https://img.shields.io/badge/Configuration%20Cache-compatible-brightgreen" alt="Configuration Cache"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"/></a>
</p>

**Architecture Visualization & Linting for Gradle Multi-Module Projects**

Aalekh is a Gradle plugin that extracts, visualizes, and enforces architectural rules across any
Gradle multi-module project - Kotlin Multiplatform, Android, JVM, or any Gradle project. It gives
teams three capabilities that no existing tool provides together: an **interactive module graph**, a
**Kotlin DSL for architecture rule enforcement**, and **historical metrics tracking** - in a single
plugin, with zero external dependencies beyond the browser.

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

### Sample Report Demo

<p align="center">
  <a href="assets/report_samples/nowinandroid.html">
    <img src="assets/images/nowinandroid_sample.gif" alt="Now in Android App Demo" width="600"/>
  </a>
</p>

## Why Aalekh?

| Tool       | Visualizes | Enforces rules | Tracks metrics | KMP-aware |
|------------|:----------:|:--------------:|:--------------:|:---------:|
| **Aalekh** |   **✓**    |     **✓**      |     **✓**      |   **✓**   |

<details>
<summary><b>Table of Contents</b></summary>

- [Quick Start](#quick-start)
- [Installation](#installation)
    - [Settings plugin (recommended)](#settings-plugin-recommended)
    - [Project plugin (deprecated)](#project-plugin-deprecated)
- [Gradle Tasks](#gradle-tasks)
    - [aalekhReport](#aalekhreport)
    - [aalekhCheck](#aalekhcheck)
    - [aalekhExtract](#aalekhextract)
- [The Report](#the-report)
    - [Seven tabs](#seven-tabs)
    - [Header toolbar](#header-toolbar)
    - [URL permalink](#url-permalink)
    - [Module Inspector sidebar](#module-inspector-sidebar)
    - [Cycle detection](#cycle-detection)
- [Configuration](#configuration)
- [Architecture Rules](#architecture-rules)
    - [Built-in rules](#built-in-rules)
    - [Violation severity levels](#violation-severity-levels)
    - [Layer enforcement](#layer-enforcement)
    - [Feature isolation](#feature-isolation)
    - [Team ownership](#team-ownership)
    - [Gradual adoption](#gradual-adoption)
    - [Transitive dependency limit](#transitive-dependency-limit)
    - [Cycle regression prevention](#cycle-regression-prevention)
    - [SARIF output for GitHub PR annotations](#sarif-output-for-github-pr-annotations)
    - [Custom rules](#custom-rules)
- [Metrics & Output](#metrics--output)
    - [Graph metrics](#graph-metrics)
    - [Output files reference](#output-files-reference)
    - [Metrics CSV export](#metrics-csv-export)
    - [Trend history](#trend-history)
- [Captured Configurations](#captured-configurations)
- [Module Types](#module-types)
- [Configuration Cache](#configuration-cache)
- [CI Setup](#ci-setup)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [License](#license)

</details>

## Quick Start

**1. Add to `settings.gradle.kts`:**

```kotlin
plugins {
    id("io.github.shivathapaa.aalekh") version "0.4.0"
}
```

**2. Run:**

```bash
./gradlew aalekhReport
```

An interactive HTML report opens automatically in your default browser. No configuration required.

## Installation

### Settings plugin (recommended)

Apply in `settings.gradle.kts`. The settings plugin loads in a classloader scope that is stable
across configuration cache entries, preventing cache misses on subsequent runs.

```kotlin
// settings.gradle.kts
plugins {
    id("io.github.shivathapaa.aalekh") version "0.4.0"
}
```

The `aalekh { }` configuration block goes in the root `build.gradle.kts` and stays exactly as-is
regardless of which plugin variant you use.

### Project plugin (deprecated)

> **⚠ Deprecated as of v0.2.0.** The project plugin will be removed in a future release. Please
> migrate to the settings plugin above.

The project plugin is applied via `build.gradle.kts` and produces a deprecation warning at build
time. To migrate: move the plugin declaration to `settings.gradle.kts` and remove it from
`build.gradle.kts`. The `aalekh { }` block stays in place.

```kotlin
// build.gradle.kts (root project only) - deprecated, migrate to settings plugin
plugins {
    id("io.github.shivathapaa.aalekh.project") version "0.4.0"
}
```

> **Why deprecated?** The project plugin is loaded in the `root-project(export)` classloader scope,
> which is not preserved across configuration cache entries. This causes a CC miss on every second
> run. The settings plugin is loaded in the `settings` scope, which is stable.

## Gradle Tasks

Aalekh registers three tasks on the root project, all in the `aalekh` task group.

| Task                      | Description                                                                                 |
|---------------------------|---------------------------------------------------------------------------------------------|
| `./gradlew aalekhExtract` | Extracts the module dependency graph and writes it as JSON to `build/tmp/aalekh/graph.json` |
| `./gradlew aalekhReport`  | Generates the interactive HTML report at `build/reports/aalekh/index.html`                  |
| `./gradlew aalekhCheck`   | Evaluates all architecture rules; fails the build on `ERROR`-severity violations            |

`aalekhReport` and `aalekhCheck` both depend on `aalekhExtract` implicitly - you do not need to
run it manually.

`aalekhCheck` is automatically wired into the standard `check` lifecycle task (when the `base`
plugin is applied, which is the default), so it runs as part of `./gradlew check` without any extra
configuration.

To wire it into CI explicitly:

```kotlin
// build.gradle.kts (root project)
tasks.named("check") {
    dependsOn("aalekhCheck")
}
```

### aalekhReport

```bash
./gradlew aalekhReport
```

Generates `build/reports/aalekh/index.html` - a fully self-contained HTML file with no server, no
CDN, and no internet connection required at render time. The report opens automatically in your
default browser after the task completes. Disable auto-open with `openBrowserAfterReport.set(false)`
for CI environments.

When `exportMetrics` is enabled, also writes `build/reports/aalekh/aalekh-metrics.csv`.

Every run appends a snapshot to `build/aalekh/trend.json` (rolling window of 30 entries) to power
the KPI trend sparklines.

### aalekhCheck

```bash
./gradlew aalekhCheck
```

Evaluates all registered architecture rules against the extracted dependency graph. On completion it
writes three output files:

- `build/reports/aalekh/aalekh-results.xml` - JUnit XML consumed natively by all CI systems
- `build/reports/aalekh/aalekh-results.json` - full machine-readable report: graph, summary,
  violations, version, and timestamp
- `build/reports/aalekh/aalekh-results.sarif` - SARIF 2.1 for GitHub code scanning PR annotations

If any `ERROR`-severity violation is found, the task fails with a summary message and exit code 1:

```
Aalekh: 2 violation(s) found.
Run ./gradlew aalekhReport to see the full interactive report.
```

`WARNING`-severity violations are printed to stdout but do not fail the build. `INFO`-severity
violations are silently collected and visible in the HTML report and JSON only.

Violation output is grouped by rule ID and shows the exact dependency to remove:

```
Aalekh [layer-dependency] ERROR - 1 violation(s):
  ✗ :feature:login:data (layer 'data') depends on :feature:login:ui (layer 'presentation').
    Edit feature/login/data/build.gradle.kts and remove:
    implementation(project(":feature:login:ui"))
```

### aalekhExtract

```bash
./gradlew aalekhExtract
```

Extracts and serializes the full module dependency graph to `build/tmp/aalekh/graph.json`. The
output is `@CacheableTask` - Gradle will skip it when inputs (project structure, dependency
declarations, filter flags) have not changed.

You rarely need to run this directly; both `aalekhReport` and `aalekhCheck` depend on it.

## The Report

`./gradlew aalekhReport` produces `build/reports/aalekh/index.html`. Open it in any browser - no
server required, no internet connection needed.

### Seven tabs

**⬡ Architecture** - Layer swimlane view. Modules are grouped by their declared `layers { }`
configuration and rendered as swim lanes, making dependency direction violations immediately
obvious.
Edge crossings that violate the declared layer order are highlighted. This is the default tab when
the report opens.

**⊹ Explore** - Interactive force-directed graph powered by D3.js. Drag to reposition nodes, scroll
to zoom, click any node to open the Module Inspector in the sidebar. Nodes are coloured by module
type; cycle nodes pulse with a red ring; god modules glow orange. Filter edges by type: Impl, API,
Test, CompileOnly, KMP source sets, Main Cycle, Test Cycle. Hovering a node animates traffic along
its edges to make dependency direction obvious at a glance.

**⊞ Explorer** - Hierarchical tree view mirroring your Gradle project structure. Expand and collapse
groups, jump directly to cycle nodes, and see per-module dependency tables split by main vs test
scope.

**⊟ Matrix** - Adjacency matrix showing all inter-module dependencies at a glance. Sort by
connectivity, topological order, A–Z, or module type. Hover a cell for details; click a row or
column label to inspect the module. In topological order, any dependency appearing in the lower
triangle is a potential layer violation.

**◎ Metrics** - KPI dashboard: fan-in, fan-out, instability index, critical build path, god module
count, cycle counts (main and test-only separately). Each KPI card includes a trend sparkline from
the last 30 `aalekhReport` runs. Includes a per-layer purity table (percentage of edges flowing in
the correct declared direction) and a list of consolidation candidates - module pairs that share
many dependents and may be worth merging. Per-module sortable table with inline bar charts.

**⚑ Violations** - Structured violation cards for every `aalekhCheck` rule failure. Each card shows
the rule ID, severity badge, the exact dependency edge to remove, a plain-language explanation of
why the rule exists, and a "View in Graph" button that navigates directly to the offending module.
Violation messages include the build file path of the offending dependency declaration.

When no violations exist and no layer rules are configured, the panel analyses your module paths
and suggests a ready-to-paste `layers { }` DSL block based on detected `domain`, `data`, and
`ui`/`presentation` patterns.

**⇄ Diff** - Snapshot comparison. Drag-drop a previous `graph.json` file onto the panel to see
exactly which modules and edges were added or removed since that snapshot. The file is read locally
- nothing is uploaded to any server. Useful for reviewing architectural impact during a pull
request.

### Header toolbar

The report header provides global tools available on every tab:

| Control                     | Description                                                                                   |
|-----------------------------|-----------------------------------------------------------------------------------------------|
| **Architecture debt score** | 0–100 badge summarising technical debt across all evaluated rules                             |
| **Module search**           | Search across all module paths; press `/` to focus                                            |
| **⊙ Heatmap**               | Toggle to colour all nodes from green (stable) to red (unstable) by instability index         |
| **⟶ Path finder**           | Find the shortest dependency path between any two modules; result is highlighted in the graph |
| **⬇ JSON**                  | Download the raw `graph.json` data                                                            |
| **⬇ CSV**                   | Download per-module metrics as CSV                                                            |
| **⬇ SVG**                   | Export the current view (Architecture, Explore, or Matrix) as an SVG file                     |

### URL permalink

The active tab and selected module are encoded in `location.hash`. Copy the browser URL to share a
specific view - the recipient will land on exactly the same tab with the same module selected.

### Module Inspector sidebar

Click any node in the graph or explorer to open the module inspector in the right sidebar. It shows:

- Module path and short name
- Module type badge (colour-coded)
- Fan-in, fan-out, and transitive dependency count
- **Blast radius** - number of modules that transitively depend on this one (impact scope of a
  breaking change)
- Instability index bar (green = stable, yellow = mixed, red = unstable)
- Team owner (if configured via `teams { }`)
- KMP source sets (if applicable)
- Direct dependencies and dependents, each clickable to navigate to that module

### Cycle detection

Aalekh distinguishes between two kinds of cycles:

- **Main cycles** (`⚠ red`) - circular dependencies in production code. These are genuine
  architectural errors that prevent independent builds and refactoring. `aalekhCheck` fails on these
  by default.
- **Test cycles** (`♻ pink`) - cycles that exist only through `testImplementation` or
  `androidTestImplementation`. These are common, usually acceptable, and do **not** cause a build
  failure.

Both kinds are visible in the Explore graph, the Explorer tree, and the Metrics panel. Main and
test cycle counts are reported separately in the KPI dashboard.

## Configuration

All configuration lives in the `aalekh { }` block in the root `build.gradle.kts`.

```kotlin
// build.gradle.kts (root project)
aalekh {
    // Output directory relative to build/. Default: "reports/aalekh"
    outputDir.set("reports/aalekh")

    // Open the report in the default browser after aalekhReport completes.
    // Set to false in CI environments.
    // Default: true
    openBrowserAfterReport.set(true)

    // Include testImplementation / androidTestImplementation / KMP test edges in the graph.
    // Default: true
    includeTestDependencies.set(true)

    // Include compileOnly edges in the graph.
    // Disabled by default because compileOnly deps are rarely architecturally significant.
    // Default: false
    includeCompileOnlyDependencies.set(false)

    // Write aalekh-metrics.csv alongside the HTML report on every aalekhReport run.
    // Default: false
    exportMetrics.set(false)

    layers { /* see Layer enforcement */ }
    featureIsolation { /* see Feature isolation */ }
    teams { /* see Team ownership */ }
    rules { /* see Architecture Rules */ }
}
```

### Configuration option reference

| Option                           | Type      | Default            | Description                                                                  |
|----------------------------------|-----------|--------------------|------------------------------------------------------------------------------|
| `outputDir`                      | `String`  | `"reports/aalekh"` | Output directory relative to `build/`                                        |
| `openBrowserAfterReport`         | `Boolean` | `true`             | Auto-open the HTML report after `aalekhReport` runs                          |
| `includeTestDependencies`        | `Boolean` | `true`             | Include `testImplementation`, `androidTestImplementation`, etc. in the graph |
| `includeCompileOnlyDependencies` | `Boolean` | `false`            | Include `compileOnly` edges in the graph                                     |
| `exportMetrics`                  | `Boolean` | `false`            | Write `aalekh-metrics.csv` alongside the HTML report                         |

## Architecture Rules

### Built-in rules

| Rule ID                       | Severity  | Description                                                          |
|-------------------------------|-----------|----------------------------------------------------------------------|
| `no-cyclic-dependencies`      | `ERROR`   | The module dependency graph must be a DAG (no production cycles)     |
| `layer-dependency`            | `ERROR`   | Modules must only depend on modules in their declared allowed layers |
| `no-feature-to-feature`       | `ERROR`   | Feature modules must not depend on each other                        |
| `max-transitive-dependencies` | `WARNING` | Modules must not exceed the configured transitive dependency limit   |

### Violation severity levels

| Severity  | Effect                                                        |
|-----------|---------------------------------------------------------------|
| `ERROR`   | Fails the build. Printed to stderr.                           |
| `WARNING` | Printed to stdout. Build continues.                           |
| `INFO`    | Silently collected. Visible in the HTML report and JSON only. |

### Layer enforcement

Declare layers and enforce the direction of dependencies between them. Module patterns support
`*` (one path segment) and `**` (any number of segments).

```kotlin
aalekh {
    layers {
        layer("domain") {
            modules(":core:domain", ":feature:*:domain")
            // No canOnlyDependOn = no restriction; domain may depend on nothing
        }
        layer("data") {
            modules(":core:data", ":feature:*:data")
            canOnlyDependOn("domain")
        }
        layer("presentation") {
            modules(":feature:*:ui", ":app")
            canOnlyDependOn("domain", "data")
        }
    }
}
```

A layer without `canOnlyDependOn(...)` has no dependency restriction. Any layer that calls
`canOnlyDependOn(...)` is restricted to only depend on the listed layers; a dependency on any
module outside those layers is a `layer-dependency` violation.

When a violation is found, the message names the exact build file and dependency to remove:

```
Aalekh [layer-dependency] :feature:login:data (layer 'data') depends on
:feature:login:ui (layer 'presentation'). Layer 'data' may only depend on:
domain. Edit feature/login/data/build.gradle.kts and remove:
implementation(project(":feature:login:ui"))
```

### Feature isolation

Prevent feature modules from depending on each other. Specific pairs can be explicitly allowed.

```kotlin
aalekh {
    featureIsolation {
        featurePattern = ":feature:**"
        allow(from = ":feature:shared", to = ":feature:*")
    }
}
```

`featurePattern` is a glob matching all feature modules. The rule is inactive when the pattern is
not set. Explicit `allow(from, to)` pairs are exempt and do not produce violations.

### Team ownership

Map team names to module path glob patterns. Team assignments appear as a colour overlay in the
graph and are shown in the Module Inspector sidebar. Cross-team dependency edges are annotated
separately so reviewers can quickly identify dependencies that cross ownership boundaries.

```kotlin
aalekh {
    teams {
        team("auth-team") { modules(":feature:login:**", ":core:auth") }
        team("data-team") { modules(":data:**") }
        team("platform") { modules(":core:**") }
    }
}
```

Module path patterns support `*` (one path segment) and `**` (any number of segments). A module
belongs to the first team whose pattern matches - teams are evaluated in declaration order.

### Gradual adoption

Teams migrating an existing codebase can adopt rules gradually - start with warnings, fix
violations, then promote to errors when the codebase is clean:

```kotlin
aalekh {
    rules {
        rule("layer-dependency") {
            severity = Severity.WARNING   // see violations without blocking CI
            suppressFor(":legacy:**")     // exempt a known legacy subtree entirely
        }
    }
}
```

`suppressFor` accepts a glob pattern. Any module path matching the pattern is excluded from that
rule's evaluation. Multiple `suppressFor` calls on the same rule accumulate.

### Transitive dependency limit

Fail or warn when a module pulls in too many hidden transitive dependencies:

```kotlin
aalekh {
    rules {
        noTransitiveDependenciesExceeding(30)
    }
}
```

The default severity is `WARNING`. Override with:

```kotlin
aalekh {
    rules {
        noTransitiveDependenciesExceeding(30)
        rule("max-transitive-dependencies") { severity = Severity.ERROR }
    }
}
```

### Cycle regression prevention

Once a project is cycle-free, lock that state in so new cycles can never be introduced silently:

```kotlin
aalekh {
    rules {
        rule("no-cyclic-dependencies") {
            preventRegression = true
        }
    }
}
```

When enabled, `aalekhCheck` reads the main-code cycle count from the previous run's
`aalekh-results.json`. If the count increased, the build fails immediately - even if cycles
already existed before. No baseline file to commit, no manual setup. The previous run's output
is the baseline.

### SARIF output for GitHub PR annotations

`aalekhCheck` writes `aalekh-results.sarif` on every run. Upload it in your GitHub Actions
workflow and violations appear as inline annotations directly on the pull request diff:

```yaml
- name: Run architecture check
  run: ./gradlew aalekhCheck

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: build/reports/aalekh/aalekh-results.sarif
```

No token, no custom reporter, no extra setup.

### Custom rules

Implement `ArchRule` from `aalekh-analysis` to create project-specific rules:

```kotlin
// Imports: com.aalekh.aalekh.analysis.rules.ArchRule, com.aalekh.aalekh.model.*

class NoAndroidInDomainRule : ArchRule {
    override val id = "no-android-in-domain"
    override val description = "Domain modules must not depend on Android libraries"
    override val defaultSeverity = Severity.ERROR
    override val plainLanguageExplanation =
        "The domain layer must stay platform-agnostic so it can be shared via KMP."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
        graph.edges
            .filter { it.from.contains(":domain") }
            .filter { graph.moduleByPath(it.to)?.type == ModuleType.ANDROID_LIBRARY }
            .map { edge ->
                Violation(
                    ruleId = id,
                    severity = defaultSeverity,
                    message = "${edge.from} depends on Android module ${edge.to}. " +
                            "Move Android-specific code to the data or presentation layer.",
                    source = "${edge.from} → ${edge.to}",
                    moduleHint = edge.from,
                    plainLanguageExplanation = plainLanguageExplanation,
                )
            }
}
```

Register custom rules by passing them to the `RuleEngine` or by wiring them into the analysis
pipeline in a custom Gradle task that calls `RuleEngine.evaluate()`.

## Metrics & Output

### Graph metrics

| Metric               | Description                                                                                                                                                                               |
|----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Fan-out**          | Number of modules this module directly depends on (production only)                                                                                                                       |
| **Fan-in**           | Number of modules that directly depend on this one (production only)                                                                                                                      |
| **Instability**      | `fanOut / (fanIn + fanOut)`. Range 0.0 (stable, many dependents) to 1.0 (unstable, many dependencies)                                                                                     |
| **Transitive deps**  | Total number of modules reachable by following production dependencies from this module                                                                                                   |
| **Blast radius**     | Total number of modules that transitively depend on this module - impact scope of a breaking change                                                                                       |
| **Critical path**    | Longest dependency chain in the graph - the primary constraint on build parallelism                                                                                                       |
| **God modules**      | Modules with both high fan-in AND high fan-out - architectural hotspots that are difficult to change safely                                                                               |
| **Isolated modules** | Modules with zero fan-in and zero fan-out - candidates for removal                                                                                                                        |
| **Layer purity**     | Per-layer percentage of dependency edges flowing in the correct declared direction                                                                                                        |
| **Health score**     | 0–100 composite score. Weighted from instability (30%), god module (25%), cycle participation (25%), transitive dep count (20%). Shown in the metrics table and module inspector sidebar. |

### Output files reference

| File                                        | Task            | Description                                                                                                                    |
|---------------------------------------------|-----------------|--------------------------------------------------------------------------------------------------------------------------------|
| `build/tmp/aalekh/graph.json`               | `aalekhExtract` | Serialized module dependency graph. Input to all other tasks. Cleaned by `./gradlew clean`.                                    |
| `build/reports/aalekh/index.html`           | `aalekhReport`  | Self-contained interactive HTML report.                                                                                        |
| `build/reports/aalekh/aalekh-metrics.csv`   | `aalekhReport`  | Per-module metrics CSV. Written only when `exportMetrics = true`.                                                              |
| `build/aalekh/trend.json`                   | `aalekhReport`  | Rolling 30-entry build trend history. Read on next run to power sparklines. Not cleaned by `clean`.                            |
| `build/reports/aalekh/aalekh-results.xml`   | `aalekhCheck`   | JUnit XML for CI test reporters.                                                                                               |
| `build/reports/aalekh/aalekh-results.json`  | `aalekhCheck`   | Full machine-readable report: graph summary, all violations, version, timestamp. Read by regression detection on the next run. |
| `build/reports/aalekh/aalekh-results.sarif` | `aalekhCheck`   | SARIF 2.1 for GitHub code scanning PR annotations.                                                                             |

### Metrics CSV export

Set `exportMetrics.set(true)` to write `aalekh-metrics.csv` alongside the HTML report on every
`aalekhReport` run. The CSV contains one timestamped row per module with: fan-in, fan-out,
instability, transitive dep count, health score, and boolean flags for god module, critical path,
and cycle participation. Import into Datadog, Grafana, or a spreadsheet for external trending.

### Trend history

Every `aalekhReport` run appends a metrics snapshot to `build/aalekh/trend.json` (rolling window
of 30 entries). The snapshot records: timestamp, total module count, total edge count, cycle count,
god module count, critical path length, and average instability. The data is embedded in the report
and used to render the sparklines in each KPI card on the Metrics tab.

Failure to read or write the trend file is always non-fatal and never breaks the build.

## Captured Configurations

Aalekh captures inter-module project dependencies from the following Gradle configurations:

**Production** (always captured):

| Configuration    | Notes                                                 |
|------------------|-------------------------------------------------------|
| `implementation` | Standard implementation dependency                    |
| `api`            | Leaks to consumers of the declaring module            |
| `compileOnly`    | Captured when `includeCompileOnlyDependencies = true` |
| `runtimeOnly`    | Runtime-only dependency                               |

**Test** (captured when `includeTestDependencies = true`):

| Configuration               | Notes                                  |
|-----------------------------|----------------------------------------|
| `testImplementation`        | JVM/Android unit test dependency       |
| `testApi`                   | Test API dependency                    |
| `testCompileOnly`           | Test compile-only                      |
| `testRuntimeOnly`           | Test runtime-only                      |
| `androidTestImplementation` | Android instrumented test dependency   |
| `androidTestRuntimeOnly`    | Android instrumented test runtime-only |
| `debugImplementation`       | Android debug build type               |
| `releaseImplementation`     | Android release build type             |

**KMP source sets** (captured automatically):

Any configuration whose name ends with `Implementation`, `Api`, `CompileOnly`, or `RuntimeOnly`
and is not a standard configuration above is treated as a KMP source set configuration. Examples:
`commonMainImplementation`, `androidMainApi`, `iosMainCompileOnly`, `jvmTestImplementation`.

The source set name is extracted from the configuration name (e.g. `commonMainImplementation`
→ source set `commonMain`) and stored on the edge for display in the graph.

## Module Types

Aalekh infers the module type from applied plugin class names. Detection runs in priority order -
first match wins.

| Module Type           | Detected when plugin is applied                      | Graph color |
|-----------------------|------------------------------------------------------|-------------|
| `KMP`                 | `org.jetbrains.kotlin.multiplatform`                 | Purple      |
| `KMP_ANDROID_LIBRARY` | `com.android.kotlin.multiplatform.library`           | Teal        |
| `ANDROID_APP`         | `com.android.application`                            | Blue        |
| `ANDROID_LIBRARY`     | `com.android.library`, `com.android.dynamic-feature` | Green       |
| `JVM_LIBRARY`         | `org.jetbrains.kotlin.jvm`, `java-library`, `java`   | Amber       |
| `UNKNOWN`             | *(fallback - no known plugin applied)*               | Gray        |

## Configuration Cache

Aalekh is fully compatible with Gradle's configuration cache, which is enabled by default in
Gradle 9.x.

All task inputs are `@Input` primitives, maps, or `@InputFile` paths captured via provider lambdas
at configuration time. No live `Project`, `Configuration`, or `Dependency` objects are stored in
any task action. The `aalekhExtract` task is `@CacheableTask`, so it is skipped UP-TO-DATE when
nothing has changed.

The intermediate `build/tmp/aalekh/graph.json` is the serialization boundary between the
configuration phase (graph extraction) and the execution phase (report and check tasks).

## CI Setup

### GitHub Actions

```yaml
- name: Run architecture check
  run: ./gradlew aalekhCheck

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: build/reports/aalekh/aalekh-results.sarif

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

```kotlin
// build.gradle.kts (root project)
aalekh {
    openBrowserAfterReport.set(false)   // never open a browser in CI
    includeTestDependencies.set(true)   // keep test edges for full picture
    exportMetrics.set(true)             // write CSV for external dashboards
}
```

## Compatibility

| Aalekh | Gradle | Kotlin | AGP  | JDK        |
|--------|--------|--------|------|------------|
| 0.4.x  | 9.0+   | 2.3+   | 9.1+ | 11, 17, 21 |
| 0.3.x  | 9.0+   | 2.3+   | 9.1+ | 11, 17, 21 |
| 0.2.x  | 9.0+   | 2.3+   | 9.1+ | 11, 17, 21 |
| 0.1.x  | 9.0+   | 2.3+   | 9.1+ | 11, 17, 21 |

Aalekh requires the **settings plugin** (`settings.gradle.kts`) on Gradle 9.x because configuration
cache is enabled by default and the project plugin cannot safely capture inter-project state across
CC entries. Kotlin DSL (`*.kts`) is required - Groovy DSL is not supported.

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

# Functional tests only (GradleRunner-based, slower)
./gradlew :aalekh-gradle:functionalTest

# Single test class
./gradlew :aalekh-model:test --tests "com.aalekh.aalekh.model.ModuleDependencyGraphTest"
```

### Publishing locally for consumer testing

```bash
./gradlew publishToMavenLocal
```

Then add `mavenLocal()` to your consumer project's `settings.gradle.kts` repository list.

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
  <a href="https://github.com/shivathapaa/aalekh/issues">Request a feature</a>
</p>