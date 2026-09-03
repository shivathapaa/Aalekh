# Module aalekh-model

The serializable vocabulary of Aalekh. Pure `@Serializable` data classes with no analysis and no
I/O, depending only on `kotlinx-serialization-json`. Every type here crosses the configuration →
execution boundary as JSON (`build/tmp/aalekh/graph.json`), so each field is part of a cross-process
contract, not an implementation detail.

`ModuleDependencyGraph` is the central structure that flows through the whole pipeline — extracted in
`aalekh-gradle`, analyzed in `aalekh-analysis`, rendered in `aalekh-report`. `AalekhBuildConfig.VERSION`
is the single source of the plugin version, expanded from `gradle/libs.versions.toml` at build time.

# Package com.aalekh.aalekh.model

The full serializable model: `ModuleDependencyGraph`, `ModuleNode`, `DependencyEdge`, `ModuleType`,
`Severity`, and `Violation`, together with the richer analysis carriers (`ArchitectureSnapshot`,
`MainSequence`, `AffectedModules`, `BuildInventory`, `CycleBreakSuggestion`, `CustomMetric`) and the
`Provenance` marker (`OBSERVED` / `COMPUTED` / `INFERRED` / `SUGGESTED`) that records whether a value
was declared, derived, or guessed. `AalekhBuildConfig` exposes the build-stamped `VERSION`.
