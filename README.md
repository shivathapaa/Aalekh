# Aalekh

**Architecture Visualization & Linting for Gradle Multi-Module Projects**

Aalekh is a Gradle plugin that extracts, visualizes, and enforces architectural rules across any Gradle multi-module project - Kotlin Multiplatform, Android, JVM, or any mix of the three. It gives teams three capabilities that no existing tool provides together: an **interactive module graph**, a **Kotlin DSL for architecture rule enforcement**, and **historical metrics tracking**.

---

## Why Aalekh?

| Tool | Visualizes | Enforces rules | Tracks metrics | KMP-aware |
|------|:---:|:---:|:---:|:---:|
| **Aalekh** | **✓** | **✓** | **✓** | **✓** |

ArchUnit is for Java monoliths. Aalekh is for modern Kotlin multi-module projects. Where ArchUnit tests, Aalekh **visualizes, enforces, and tracks**.

---

## Quick Start

**Add to `settings.gradle.kts`** (recommended - settings plugin, fully configuration-cache safe):

```kotlin
plugins {
    id("io.github.shivathapaa.aalekh") version "0.0.1-alpha01"
}
```

**Run:**

```bash
./gradlew aalekhReport
```

That's it. An interactive HTML report opens in your browser.

---

## Installation

### Settings plugin (recommended)

Apply in `settings.gradle.kts`. This is the preferred approach - the settings plugin loads in a classloader scope that is stable across configuration cache entries, preventing the "class not found in classloader" error on second runs.

```kotlin
// settings.gradle.kts
plugins {
    id("io.github.shivathapaa.aalekh") version "0.0.1-alpha01"
}
```

### Project plugin (alternative)

If you prefer applying in `build.gradle.kts` of the root project:

```kotlin
// build.gradle.kts (root project only)
plugins {
    id("io.github.shivathapaa.aalekh.project") version "0.0.1-alpha01"
}
```

> **Note:** The project plugin (`io.github.shivathapaa.aalekh.project`) works correctly when Aalekh is consumed from the Gradle Plugin Portal. For `includeBuild` setups, use the settings plugin to avoid configuration cache issues.

---

## Gradle Tasks

| Task | Description |
|---|---|
| `./gradlew aalekhExtract` | Extracts the module dependency graph and writes it as JSON to `build/tmp/aalekh/graph.json` |
| `./gradlew aalekhReport` | Generates the interactive HTML report at `build/reports/aalekh/index.html` |
| `./gradlew aalekhCheck` | Evaluates all architecture rules; fails the build on `ERROR`-severity violations |

Wire `aalekhCheck` into your CI verification lifecycle:

```kotlin
// build.gradle.kts (root project)
tasks.named("check") {
    dependsOn("aalekhCheck")
}
```

---

## The Report

`./gradlew aalekhReport` produces a fully self-contained HTML file - no server, no CDN, no internet connection required. It opens automatically in your default browser after the task completes.

### Five panels

**⬡ Graph** - Interactive force-directed visualization. Drag to orbit, scroll to zoom, click any node to inspect it. Nodes are coloured by module type; cycle nodes pulse with a red ring; god modules glow orange. Filter edges by type (Impl, API, Test, CompileOnly, KMP source sets, Main Cycle, Test Cycle).

**⊞ Explorer** - Hierarchical tree view mirroring your Gradle project structure. Expand/collapse groups, jump directly to cycle nodes, and see per-module dependency tables split by main vs test scope.

**⊟ Matrix** - Adjacency matrix showing all inter-module dependencies at a glance. Hover a cell for details; click a row/column label to inspect the module and sync with the graph.

**◎ Metrics** - KPI dashboard with fan-in, fan-out, instability index, critical build path, god module count, and cycle counts. Main cycles and test-only cycles are reported separately. Per-module sortable table with inline bar charts.

**⚑ Violations** - Structured violation cards for every `aalekhCheck` failure. Each card includes the rule ID, severity, the exact dependency to remove, and a plain-language explanation of why the rule exists.

### Cycle detection

Aalekh distinguishes between two kinds of cycles:

- **Main cycles** (`⚠ red`) - circular dependencies in production code only. These are genuine architectural errors that prevent independent builds and refactoring. `aalekhCheck` fails on these.
- **Test cycles** (`♻ pink`) - cycles that exist only through `testImplementation` / `androidTestImplementation`. These are common, usually acceptable, and do **not** cause a build failure.

---

## Configuration

```kotlin
// settings.gradle.kts or build.gradle.kts
aalekh {
    // Output directory (relative to build/). Default: "reports/aalekh"
    // Final path: <root>/build/reports/aalekh/index.html
    outputDir.set("reports/aalekh")

    // Automatically open the report in your browser after aalekhReport.
    // Disable in CI: set to false.
    openBrowserAfterReport.set(true)

    // Include testImplementation / androidTestImplementation edges in the graph.
    // Default: true. They are shown separately from main deps and excluded from
    // main cycle detection regardless of this setting.
    includeTestDependencies.set(true)

    // Include compileOnly / runtimeOnly edges. Default: false.
    includeCompileOnlyDependencies.set(false)
}
```

---

## Architecture Rules *(coming soon)*

The rule engine core is implemented. A Kotlin DSL for declaring layer boundaries and custom rules is coming in the next release - letting you fail the build when modules cross architectural boundaries.

---

## Module Types

Aalekh detects module types from applied plugin IDs (first match wins):

| Module Type | Plugin ID | Color |
|---|---|---|
| KMP | `org.jetbrains.kotlin.multiplatform` | Purple |
| KMP Android Lib | `com.android.kotlin.multiplatform.library` | Teal |
| Android App | `com.android.application` | Blue |
| Android Library | `com.android.library` | Green |
| JVM Library | `org.jetbrains.kotlin.jvm` | Amber |
| Unknown | - (fallback) | Gray |

---

## Project Structure

```
aalekh/
├── aalekh-model/          ← Data classes: ModuleDependencyGraph, ModuleNode,
│                             DependencyEdge, Violation - no Gradle dependency
├── aalekh-analysis/       ← GraphAnalyzer, RuleEngine, MetricsEngine
│                             pure Kotlin, no Gradle API
├── aalekh-report/         ← HTML generator, JUnit XML writer, JSON reporter
│                             self-contained HTML with embedded D3.js
├── aalekh-gradle/         ← Gradle plugin, tasks, extension DSL
│                             AalekhSettingsPlugin, AalekhPlugin, AalekhExtractTask,
│                             AalekhReportTask, AalekhCheckTask
└── build-logic/           ← Convention plugins for the build itself
```

---

## Roadmap

| Phase | Theme | Status |
|---|---|---|
| **Alpha** | Graph extraction + 3D interactive HTML report | ✅ Released |
| **Next** | Rule engine DSL + build failure enforcement | 🔨 In progress |
| **Later** | Metrics tracking + historical trend reports | 📋 Planned |
| **Future** | Source-level analysis via KSP2 + stable API guarantee | 📋 Planned |

---

## Configuration Cache

Aalekh is fully compatible with Gradle's configuration cache, which is enabled by default in Gradle 9.x. All task inputs are plain `@Input` primitives - no live `Project`, `Configuration`, or `Dependency` objects are captured in task actions.

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

---

## License

```
Copyright 2026 Shiva Thapa

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```

---

<p align="center">
  Made with ♥ for the Kotlin community
  <br/>
  <a href="https://github.com/shivathapaa/aalekh/issues">Report a bug</a> ·
  <a href="https://github.com/shivathapaa/aalekh/discussions">Request a feature</a>
</p>