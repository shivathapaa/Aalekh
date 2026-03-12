# Changelog

All notable changes to Aalekh are documented in this file.  
Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) · Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.0.1-alpha01] - 2026-03-13

The first public release. Apply the plugin, run one task, get an interactive report - no configuration needed.

### Added

**Plugin & Tasks**
- Settings plugin `io.github.shivathapaa.aalekh` (recommended) and project plugin `io.github.shivathapaa.aalekh.project` (alternative)
- `aalekhExtract` - extracts the dependency graph as JSON
- `aalekhReport` - generates a self-contained HTML report and opens it in the browser
- `aalekhCheck` - fails the build on architecture violations; outputs JUnit XML and JSON for CI
- `AalekhExtension` DSL: `outputDir`, `openBrowserAfterReport`, `includeTestDependencies`, `includeCompileOnlyDependencies`

**Graph Extraction**
- Detects module types from plugin IDs: KMP, KMP Android Library (AGP 9.x), Android App, Android Library, JVM Library
- Captures all standard configurations including KMP source set variants (`commonMainImplementation`, `commonMainApi`, per-target equivalents)

**Architecture Analysis**
- Circular dependency detection in **production code only** - test-scoped cycles are tracked separately and never cause a build failure
- Metrics: fan-in, fan-out, instability index, transitive dep count, critical path, god module detection

**Interactive HTML Report** - five panels, fully self-contained, no server or internet required
- **⬡ Graph** - force-directed graph. Orbit, zoom, click to inspect. Edge filters shown only for configurations present in your project. Separate filter tags for main cycles and test cycles
- **⊞ Explorer** - module tree with cycle-jump navigation and per-module dep breakdown split by main vs test
- **⊟ Matrix** - adjacency matrix with hover details, sort modes, and cross-panel selection sync
- **◎ Metrics** - KPI dashboard; main cycle count and violation count correctly exclude test-only cycles
- **⚑ Violations** - one card per violation with plain-language explanation; test cycles excluded from the list

### Known Limitations

- Rule DSL (`layers {}`, `rules {}`) is not yet exposed - only `NoCyclicDependenciesRule` runs automatically
- Groovy DSL (`build.gradle`) is not supported - Kotlin DSL only
- Nested composite builds (`includeBuild` inside `includeBuild`) are untested
- Browser auto-open may not work on all Linux desktop environments; the report is always written to `build/reports/aalekh/index.html`

---

[0.0.1-alpha01]: https://github.com/shivathapaa/aalekh/releases/tag/0.0.1-alpha01