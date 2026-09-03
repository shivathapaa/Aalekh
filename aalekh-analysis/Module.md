# Module aalekh-analysis

Pure graph algorithms and the architecture-rule engine. Every function is deterministic and
I/O-free, so the unit tests (including Kotest property tests over graph shapes) run in milliseconds.
Consumes an `aalekh-model` `ModuleDependencyGraph` and produces analysis results, violations, and
metrics — no Gradle types appear here.

Structural analysis considers main edges only (`!isTest`); test-only cycles are reported separately
and never fail a build. Layer resolution and glob matching are single-sourced here (`LayerSpecParser`,
`GlobMatcher`) and reused verbatim by the report, so a classification never resolves one way in
analysis and another in the template.

# Package com.aalekh.aalekh.analysis

Top-level analysis entry points. `GraphAnalyzer` computes topological order, the critical path,
god / leaf / root / isolated modules, `findMainOnlyCycles`, and the graph `summary`.
`HealthScoreCalculator` produces both the per-module `score` and the whole-project `projectScore`.

# Package com.aalekh.aalekh.analysis.rules

The rule engine. `ArchRule` is the rule contract; `RuleEngine` (with `fromConfig`) reconstructs the
active rule set from the serialized DSL strings at execution time. Built-in rules —
`NoCyclicDependenciesRule`, `LayerDependencyRule`, `NoFeatureToFeatureDependencyRule`,
`MaxTransitiveDependenciesRule` — carry stable kebab-case `id`s that are a public contract in SARIF,
JUnit XML, and suppression config. `LayerSpecParser` is the one parser for serialized `layers { }`
entries, shared by the rules and the report.

# Package com.aalekh.aalekh.analysis.metrics

`MetricsEngine` and the derived architecture metrics — Lakos coupling, the main-sequence distance,
and the graph statistics that power the report's KPI tiles and trend sparklines.

# Package com.aalekh.aalekh.analysis.spi

Extension points for user-authored analysis. Custom `ArchRule` implementations ship as a separate jar
and are wired in via `rules { custom("fqcn") }` in the consumer's `aalekh { }` block.
