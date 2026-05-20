# Changelog

All notable changes to this project are documented here. Format based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `rules { custom("fully.qualified.ClassName") }` DSL entry registers a
  user-defined `ArchRule` implementation by class name. The rule class must be
  reachable from the plugin's runtime classpath (consumer buildscript classpath
  or an `includeBuild` composite project) and expose a public no-arg
  constructor. Load failures surface as an `aalekh-custom-rule` ERROR violation
  rather than crashing the build.
- `.github/workflows/compatibility-matrix.yml` runs `checkAll` + functional
  tests on JDK 11, 17, and 21, plus an explicit configuration-cache reuse
  assertion job, backing the published compatibility table claims.
- This `CHANGELOG.md`, satisfying the `CONTRIBUTING.md` PR checklist.

### Changed
- HTML report is now genuinely offline. `d3.min.js` is vendored into
  `aalekh-report/src/main/resources` and inlined into the report at generation
  time; the Google Fonts `<link>` tags are gone and the UI falls back to the OS
  system font stack. View the report anywhere - no CDN, no fonts, no network.

### Fixed
- `AalekhCheckTask` no longer caches its result. The previous-run
  `aalekh-results.json` is read for `preventRegression` but is not declared as an
  `@Input`, so a build-cache hit could silently mask a newly introduced cycle.
- `ModuleDependencyGraph.hasCycle` and `findCycles`, plus
  `GraphAnalyzer.findMainOnlyCycles`, are now genuinely iterative DFS over an
  explicit frame stack - safe on graphs deep enough to blow a recursive call
  stack. KDoc previously claimed iterative but the implementations were
  recursive.
- KDoc references to `GraphExtractor` from `ModuleDependencyGraph` now point at
  the live extraction site (`AalekhExtractTask`).

### Changed
- `ModuleDependencyGraph.edgesFrom` / `edgesTo` now use lazy adjacency-map
  indices, the same pattern as the existing `moduleIndex`. Hot algorithms
  (`topologicalOrder`, `criticalPath`, `potentiallyCoupledModules`) drop from
  `O(V·E)` / `O(V²·E)` worst case to near-linear in the relevant inputs.
- KMP / variant configuration classification is unified behind one internal
  `ConfigurationClassifier` object. Behaviour-preserving: previously duplicated
  across the settings plugin, project plugin, and `AalekhExtractTask` with a
  diverging suffix list.
- Stale `version "0.1.1"` strings in the project plugin's runtime warning and
  KDoc removed; messages now point at the README for the current version.

### Removed
- `GraphExtractor` and `AalekhBuildService`, plus the unused
  `ModuleTypeDetector.detect(Project)` overload. Both `GraphExtractor` and
  `AalekhBuildService` were public objects but never invoked by either plugin;
  the live extraction path is `AalekhExtractTask` and operates on plain strings
  for configuration-cache safety.

### Tooling
- Detekt is now applied in the `aalekh.kotlin-library` convention plugin. Each
  published module ships a `detekt-baseline.xml` that grandfathers existing
  findings, so new violations fail `check` while the historical set stays
  silent. Regenerate with `./gradlew detektBaseline`.

## [0.4.0] - 2026-05

### Added
- 14 interactive features in the HTML report (path finder, heatmap, SVG export,
  module inspector enrichments, URL permalink, etc.).
- Rolling build-trend history (`build/aalekh/trend.json`, 30-entry window) wired
  into KPI sparklines on the Metrics tab.
- `teams { }` DSL block (`TeamOwnershipConfig`) mapping module patterns to team
  owners; rendered as a colour overlay in the report.
- Optional `reason` and `adrUrl` fields on `DependencyEdge`.

### Changed
- README rewrite covering the seven report tabs, configuration reference,
  rule list, metrics, output files, and CI setup.

## [0.3.0]

### Added
- Module health score (0–100 composite of instability, god-module status,
  cycle participation, transitive-dep count) plus blast-radius metric.
- `noTransitiveDependenciesExceeding(n)` rule.
- `preventRegression` option on `no-cyclic-dependencies` reading the prior
  `aalekh-results.json`.
- `exportMetrics` flag writing `aalekh-metrics.csv` next to the HTML report.
- Build-file line numbers on violation messages (`DependencyEdge.declarationLine`).

### Changed
- Output paths printed with `file://` prefix so terminals make them clickable.

## [0.2.0]

### Added
- Layer enforcement DSL (`layers { layer(...) { canOnlyDependOn(...) } }`),
  feature isolation, per-rule severity overrides and `suppressFor(...)`.
- SARIF 2.1 output for GitHub code-scanning PR annotations.

### Changed
- Kotlin upgraded to 2.3.20.

## [0.1.0]

### Added
- First release of the settings plugin
  (`io.github.shivathapaa.aalekh`). Project plugin
  (`io.github.shivathapaa.aalekh.project`) deprecated and kept for
  backwards compatibility.

## [0.0.1-alpha01]

### Added
- Initial public preview: `aalekhExtract`, `aalekhReport`, `aalekhCheck` tasks;
  basic interactive HTML graph; cycle detection.

[Unreleased]: https://github.com/shivathapaa/aalekh/compare/0.4.0...HEAD
[0.4.0]: https://github.com/shivathapaa/aalekh/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/shivathapaa/aalekh/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/shivathapaa/aalekh/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/shivathapaa/aalekh/compare/0.0.1-alpha01...0.1.0
[0.0.1-alpha01]: https://github.com/shivathapaa/aalekh/releases/tag/0.0.1-alpha01
