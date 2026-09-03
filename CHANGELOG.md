# Changelog

All notable changes to this project are documented here. Format based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **API reference (KDoc via Dokka).** `./gradlew dokkaGenerate` builds a combined, fully offline API
  reference for all four modules into `build/dokka/html`. Public declarations only, with GitHub source
  links back to the defining line and a per-module overview from each module's `Module.md`. The
  Maven Central `-javadoc` jars for `aalekh-model`, `aalekh-analysis`, and `aalekh-report` now carry
  this generated documentation instead of an empty placeholder. A `pages.yml` workflow publishes the
  site to GitHub Pages from `main` at
  [shivathapaa.github.io/Aalekh/api](https://shivathapaa.github.io/Aalekh/api/).

## [0.7.0] - 2026-09-02

### Added
- **Plain-language findings in the report.** The Overview opens with a summary of the project, a
  suggested reading order for someone seeing it for the first time, and a list of findings - each one
  a sentence about the project with the measurements behind it, so `fan-in = 24` is shown as "24
  modules depend on this, more than half the project". Every finding carries its evidence, and each
  piece of evidence is marked observed, computed, inferred, or suggested. Findings are assembled from
  templates, so the same graph always produces the same text.
- **Recommendations**: split candidates (a module whose consumers fall into groups sharing no other
  dependency), merge candidates (two modules with identical dependents and dependencies), and
  dependency-inversion candidates (a stable module depending on an unstable one). Each states its
  confidence and the evidence behind it.
- **New per-module metrics**: blast radius, influence (PageRank over the dependency graph),
  betweenness, articulation points, comprehension cost, API surface ratio, and depth from the nearest
  entry point. Plus fan-in concentration (Gini) for the whole project. A `MetricCatalog` gives each
  metric a question it answers and how to read it, and ships with the report.
- **`aalekhDocs`** writes architecture documentation as Markdown to `build/reports/aalekh/docs/`:
  a summary and the findings, a module catalogue ranked by blast radius, an onboarding reading order,
  a metric glossary, and region, build, and dependency references. The output has no timestamp and is
  byte-identical when nothing changed, so it can be committed and reviewed as a diff.
- **`aalekhSnapshot` and `aalekhDiff`.** `aalekhSnapshot` records the architecture as a small, sorted,
  committable `aalekh-snapshot.json`. `aalekhDiff` compares the current graph against it and writes
  `aalekh-diff.md` - a pull-request comment listing new cycles, added and removed dependencies and
  modules, layer moves, and metric deltas. With no snapshot committed it explains how to create one
  and succeeds. Opt into `failOnArchitectureRegression` to fail on a new cycle or a regressed metric.
- **Region map** (Map → Regions). Groups modules and aggregates the dependencies between the groups,
  for projects too large to read module by module. Grouping uses the first source that yields a usable
  split: `layers { }`, `teams { }`, module path prefixes, then community detection over the dependency
  edges. The panel states which was used. A region too large to list flat is broken into the groups it
  contains.
- **Focus view** (Map → Focus). One module in the middle, its dependents on the left and its
  dependencies on the right, with a depth slider. The number of nodes drawn is bounded by the depth
  rather than by the size of the project.
- **Typed queries in the command palette**: `layer:`, `team:`, `type:`, `plugin:`, `dep:`, `uses:`,
  `usedby:`, `region:`, `cycle:`, `untested:`, `blast:>50`, `instability:>0.8`, and `influence:>2`.
  Filters combine, and free text after them ranks the matches. The vocabulary is listed when the
  palette opens empty.
- **Build panel and build inventory.** Extraction records plugin ids and versions - read from each
  `plugins { }` block and resolved through Gradle's version-catalog model, so `alias(libs.plugins.x)`
  reports both the alias and what it resolves to - along with version catalogs, Java toolchains,
  Kotlin Multiplatform targets, test source sets, and the detected AGP and Kotlin versions. Findings
  report split toolchains, plugins applied at more than one version, convention-plugin adoption,
  single-use plugins, and untested modules ranked by blast radius.
- **`.aalekh/modules.json`**, an optional committed file describing what Aalekh cannot infer: a
  module's purpose, owner, layer, status, and links. Values declared there override inference, and the
  purpose is shown first in the module inspector and the generated documentation.
- **Ownership from `CODEOWNERS`.** Read from `.github/`, the repository root, `docs/`, or `.gitlab/`
  when present, so module ownership works without a `teams { }` block. Where sources overlap, the most
  specific wins: `.aalekh/modules.json`, then `teams { }`, then `CODEOWNERS`.
- **Presentation mode** (`P`). Steps through the findings one screen at a time - project, shape,
  regions, where to start, risks, health - for architecture reviews and onboarding sessions.
- **Two new extension points.** `FindingProvider` contributes findings; `ModuleClassifier` contributes
  a module's layer, team, or purpose from a project's own convention. Both are discovered with
  `ServiceLoader` like `MetricProvider`, and both are fail-silent. A classifier is overridden by
  anything declared explicitly and overrides Aalekh's path-based inference.
- **Module inspector** now shows the declared purpose, what a change to the module costs, the metrics
  behind that (each with the question it answers on hover), any findings naming the module, and its
  plugins, toolchain, targets, and test source sets.

### Fixed
- **The report groups by the declared `layers { }` block.** The Architecture swimlane and the layer
  purity table previously grouped modules using a path-keyword heuristic in the report's JavaScript,
  ignoring `layers { }` entirely - so the report could show a different architecture from the one
  `aalekhCheck` enforced. Layers are now injected into the report and resolved the same way the rules
  resolve them (first declared pattern wins), in declaration order. A module matching no declared
  layer gets its own **Unclassified** lane. With no `layers { }` block the report still infers lanes
  from module paths, but labels them **Inferred layers**. Layer purity measures a restricted layer
  against its own `canOnlyDependOn(...)` allowlist.
- **Declared layers keep their declaration order.** Gradle's named container iterates alphabetically,
  so `domain, data, presentation` reached the rule engine as `data, domain, presentation`, making
  overlapping-pattern resolution depend on layer names. Layers now carry their declaration index. If
  two layers claim the same module, the one declared first wins.
- **Fixing a dependency cycle no longer trips the `critical-path` quality gate.** A cyclic graph has
  no topological order, so its critical-path length is recorded as `0`. A baseline captured while the
  project had a cycle therefore held `0`, and breaking the cycle made the length jump to its real
  value and fail `forbidRegression("critical-path")`. The comparison is skipped when either side was
  cyclic. `aalekhDiff` applies the same rule.
- **`ModuleNode.healthScore` is populated.** The field was documented but never written, leaving the
  `healthScore` column of `aalekh-metrics.csv` blank. `aalekhExtract` now records it, so `graph.json`,
  the CSV, the Health table, and the inspector read the same number.
- **`ModuleNode.sourceSets` is populated.** Multiplatform modules record their source-set names, read
  reflectively from the Kotlin extension. The inspector's KMP source-sets section now renders.
- **`DependencyEdge.declarationLine` is populated.** Extraction scans each module's build file for the
  line a `project(...)` dependency is declared on, recognising `project(":a:b")`, `project(':a:b')`,
  and the type-safe `projects.a.b` accessor. Cycle-break advice now prints `file:line`. Build files are
  declared task inputs, so reordering declarations re-runs extraction.
- **`ModuleNode.buildFilePath` uses the real build file location** from the Gradle model instead of
  deriving it from the module path, so modules with a non-standard layout resolve. This also improves
  how `aalekhTemporal` and `aalekhAffected` map changed files to modules.
- **One project health formula.** The Overview dial computed its score in JavaScript with weights that
  appeared nowhere else. It now comes from `HealthScoreCalculator.projectScore`, ships in the report
  summary, and is documented in [Metrics](docs/metrics.md#architecture-health-scores). The dial lists
  every penalty that fired.
- **One glob implementation.** The report's JavaScript glob compiled `*` to `[^:]*` and `**` to `.*`,
  disagreeing with the Kotlin `GlobMatcher` on empty and partial segments. It now matches segment by
  segment.
- **Gradle's own infrastructure plugins are no longer recorded.** `HelpTasksPlugin`, `WrapperPlugin`
  and their kin are applied to every module of every build and were 24% of `graph.json` on a
  128-module project. Module type detection is unaffected.
- **The AGP and Kotlin versions are detected.** They are looked up reflectively against the plugins'
  own version constants, but `Class.forName` was searching Aalekh's classpath. A settings plugin
  loads in a different scope from the build scripts that apply them, so neither was ever found and
  the Build panel always said "not detected". The buildscript classloaders are searched first now.
- **Number keys reach every panel.** `1`-`6` covered six of the nine tabs, skipping Dependencies,
  Build, and Rules. The keys now follow the visual tab order, `1`-`9`.
- **A clean `aalekhCheck` run no longer logs a blank summary.** It read
  `Aalekh:  found across 11 rule(s).` when there were neither errors nor warnings.
- **The reading order no longer pads itself with tooling modules.** It filled to seven steps with
  whatever ranked next, which on a real project meant modules 2% of it depends on; a step now has to
  be depended on by at least 5%. Entry points are ordered by how far they reach rather than
  alphabetically, and only an application module claims to be where execution starts - the rest say
  what is actually known, that nothing depends on them.
- **The Focus view no longer leaks onto the other Map sub-views.** Its module and depth bar was
  rendered on the Architecture and Regions views too, and its graph did not build until the sub-view
  was opened. It is now shown only when Focus is active.
- **Map sub-views resize with the space they have.** The Architecture and Force graph refit when the
  inspector opens or closes, the line index collapses, or the window resizes - previously they were
  left stretched or cropped.
- **Findings and generated documentation no longer over-wrap.** Long evidence lists (a run of module
  paths) wrapped into a narrow right-aligned column; each value now sits below its label and uses the
  card width. The inspector's plugin list is one plugin per line instead of a run-on that overflowed.
- **A plural is formed correctly.** `library` now reads as `libraries`, not `librarys`.
- **Permalinks to the Dependencies, Build, and Rules tabs restore those tabs** instead of falling
  back to the Overview.

### Changed
- **The line index (the Lines / Legend rail) shows only on the Map, and can be collapsed there.** It
  is the Map's legend, so on every other panel its column is reclaimed for the report. The collapse
  state is remembered.
- **The report explains its own controls.** The status chips now open the panel they summarise; the
  plugin usage bar, region cohesion bars, region flows, and the region "+N" chips describe themselves
  on hover, and "+N" expands in place to show the modules it stood for. The canvas legend can be
  collapsed.
- A `Provenance` type (`OBSERVED` / `COMPUTED` / `INFERRED` / `SUGGESTED`) records how Aalekh knows a
  value. A declared value always overrides an inferred one, and the report says which is in effect.
- Graph extraction is shared between the settings plugin and the deprecated project plugin
  (`SubprojectDataCollector`) rather than duplicated. Serialized layer declarations are produced in one
  place (`AalekhExtension.serializedLayers`).

## [0.6.1] - 2026-08-31

### Added
- **Zoom controls for the Matrix view in the HTML report.** The coupling matrix (Browse → Matrix)
  gains a floating zoom-strip - **fit**, **zoom in**, **zoom out** - with a live percentage readout,
  plus **Ctrl / Cmd + mouse-wheel** zoom that anchors on the cursor. Large matrices are now legible
  without relying on browser page zoom. Zoom scales the rendered SVG size against a natural-size
  `viewBox`, so aspect ratio is preserved and the scroll container tracks the scaled canvas; the
  level persists across sort re-orders. Client-side only - no new task inputs, still fully offline.
  The four bundled sample reports were regenerated to match.

## [0.6.0] - 2026-08-27

### Added
- **Rules panel in the HTML report.** A new top-level **Rules** tab lists every architecture rule
  applied to the project - passing and failing alike - so the enforced rule set is visible even when
  the build is green. Each card shows the rule ID, its effective severity (overrides applied), a
  plain-language explanation, and a pass / *N issues* status; rules sharing an ID (e.g. several
  `forbid { }` predicates) fold into one card. Driven by a new `summary.rules` payload derived from
  `RuleEngineResult.appliedRules`; no new task inputs, still fully offline. When no rules are
  configured the panel explains that Aalekh is in visualization-only mode.
- **Dependencies panel in the HTML report.** A new top-level **Dependencies** tab aggregates every
  external library declared across the project, keyed by `group:name` and sorted by module usage.
  Each row shows the declared version(s), a module-usage count (hover for the module list), and
  colour-coded `api`/`impl`/`test` declaration badges. Libraries resolved at more than one version
  are flagged as **version conflicts** (amber highlight, header count, "Conflicts only" toggle) and
  the panel has a group/name filter. Built entirely client-side from the embedded
  `externalDependencies` data - no new inputs, still fully offline.
- **External (third-party) dependencies in the HTML report.** Extraction now captures each module's
  declared external dependencies - `group:name:version` plus declaration type - and the module
  inspector lists them in an "External dependencies (n)" section. Coordinates are read from the
  configuration's declared dependencies (never resolved), so extraction stays configuration-cache
  safe and offline; the data rides inside `graph.json` (and `aalekh-results.json`). Opt out with
  `aalekh { includeExternalDependencies = false }`; the existing `includeTestDependencies` /
  `includeCompileOnlyDependencies` flags apply to external dependencies too.
- **Hidden-coupling alert in the HTML report.** When `aalekhTemporal` has run, the report reads the
  sibling `aalekh-temporal.json` and surfaces a "Hidden Coupling" card - module pairs that change
  together in git history but declare no dependency - in the Metrics panel, and a per-module **Churn**
  card in the module inspector. With no temporal data both are omitted; the report stays fully offline.
- **Abstractness/Instability scatter in the HTML report.** When `aalekhMainSequence` has run, the
  report's Metrics panel draws the classic A/I scatter - each module by instability and abstractness
  against the `A + I = 1` main sequence, coloured by zone. The report picks up the sibling
  `aalekh-main-sequence.json` automatically and injects the points as `summary.mainSequence`; with no
  data the panel stays hidden. Rendered as an inline SVG, so the report remains fully offline.
- **Per-source-set dependency rules** (`forbidSourceSetDependency(...)` /
  `forbidSourceSetDependencyOnType(...)`). The general form of the `commonMain` rule: forbid
  dependencies declared in any named source set (e.g. `iosMain`, `androidMain`) from targeting a
  chosen path glob or module type - for example, `iosMain` must not depend on any `ANDROID_LIBRARY`.
  Uses the source set already recorded on each edge, so it needs no compiler; reports the stable
  `source-set-dependency` rule at `ERROR`. (Expect/actual *matching* stays the compiler's job - Aalekh
  enforces source-set direction at the module-graph level.)
- **Signed build provenance for published artifacts.** The publish workflow now attaches a signed
  provenance attestation (via GitHub OIDC, `actions/attest-build-provenance`) to every published jar,
  and a post-publish smoke test applies the freshly published plugin end to end. A tag-triggered
  release workflow and `FUNDING.yml` round out the repository automation.
- **Coupling KPI cards in the HTML report.** The Metrics/Health board now shows the Lakos
  **CCD**, **NCCD**, and **% Tangled** figures alongside the existing KPIs. The numbers were already
  computed and injected into the report's summary data; this surfaces them as cards using the same
  layout as every other KPI (no new markup, no new assets - the report stays fully offline).
- **Mermaid/DOT focus and exclude filters** (`mermaid { }`). Keep a large graph's exported diagram
  readable: `focus(...)` restricts it to chosen modules plus their neighbourhood (grown `depth(n)`
  hops in either direction, default 1), and `exclude(...)` drops modules after focus. The filters
  apply to all three `aalekhMermaid` outputs (`.mmd`, `.md`, `.dot`); with none declared the full
  graph is exported exactly as before. Subsetting is a pure function (`GraphFilter` in
  `aalekh-analysis`, unit-tested); the diagram generators are unchanged. Filter selectors are plain
  `@Input` lists, so `aalekhMermaid` stays `@CacheableTask` and configuration-cache safe.
- **Custom metric SPI** (`aalekhMetrics`). A `ServiceLoader`-based extension point for measuring the
  graph, the counterpart to the custom-rule SPI. Implement
  `com.aalekh.aalekh.analysis.spi.MetricProvider` (a pure function of the graph returning a
  `MetricContribution` of system-wide and/or per-module numbers), register it via a
  `META-INF/services/...MetricProvider` entry on the plugin classpath, and the new `aalekhMetrics`
  task discovers and runs every provider - writing `aalekh-custom-metrics.json` / `.md`. Discovery and
  execution are fail-silent: a blank or duplicate metric id, or a provider that throws, is recorded as
  a failure in the report and the rest still run. The engine (`CustomMetricEngine`) lives in
  `aalekh-analysis` and is unit-tested including real `ServiceLoader` discovery; only the classpath
  I/O sits in `aalekh-gradle`.
- **KMP source-set rule** (`noCommonMainPlatformDependencies()`). Forbids a Kotlin Multiplatform
  module's `commonMain` from depending on a platform-only (JVM- or Android-only) module - a dependency
  that does not compile for the module's other targets. Aalekh already records the owning source set of
  each edge (from the KMP configuration name), so the rule works at the module-graph level with no
  compiler. Only `commonMain` edges to genuinely single-platform targets fire; other multiplatform
  modules and `UNKNOWN`-typed targets are allowed. Opt-in, reports
  `kmp-common-main-platform-dependency` at `ERROR`.
- **Graphviz DOT export.** `aalekhMermaid` now also writes `aalekh-graph.dot` - the same deterministic
  module graph as a Graphviz `digraph`, for `dot -Tsvg`/`-Tpng`, Gephi, and the wider graph-tooling
  ecosystem. Solid edges are production, dashed are test-only, nodes are coloured by module type.
- **Code Climate report for GitLab.** `aalekhCheck` now also writes `aalekh-codeclimate.json`, the
  format GitLab's Code Quality widget consumes. Point a `codequality` report artifact at it and every
  architecture violation annotates the merge-request diff - the GitLab counterpart to the SARIF report
  Aalekh already writes for GitHub. Severities map `ERROR → critical`, `WARNING → minor`, `INFO → info`
  with a stable per-issue fingerprint.
- **Main-sequence metrics** (`aalekhMainSequence`). Computes each module's distance from Robert
  Martin's main sequence `D = |A + I - 1|`, writing a local `aalekh-main-sequence.md` (a
  worst-distance-first table with zone-of-pain / zone-of-uselessness call-outs) and `.json`.
  Instability (I) comes from the production graph; **abstractness (A)** - the one metric the graph
  cannot supply - comes from a deliberately coarse lexical scan of each module's Kotlin/Java source
  (interfaces and `abstract`/`sealed` classes vs concrete/`data`/`enum`/`object`). The pure counting
  and A/I/D maths live in `aalekh-analysis` (`MainSequenceAnalyzer`); only the file I/O
  (`TypeAbstractnessScanner`) sits in `aalekh-gradle`. The task is not cached (source is not a
  declared input) and never fails the build - a module with no countable types is omitted.
- **API-leak predicate** (`forbid { ... apiOnly() }`). Restricts a `forbid { }` rule to `api`
  configurations only: the *from* module may still depend on *to* via `implementation`, it just may
  not re-export it onto its own consumers - the "don't widen the public surface" check. Recognises
  the plain `api` configuration and KMP / build-type source-set variants (`commonMainApi`,
  `androidMainApi`, `debugApi`); test api configurations never count. Reuses the existing
  `forbidden-dependency` rule id and the configuration-cache-safe `forbid` serialization.
- **Reachability rules** (`forbidReachable(...)`, `mustBeReachableFrom(...)`). Two rules that reason
  over the full production reachability closure rather than direct edges. `forbidReachable(from, to)`
  fails when `from` *transitively* reaches `to` - catching the indirect leak `forbid { }` misses
  (reported as `forbidden-transitive-dependency`). `mustBeReachableFrom(module, from)` fails when a
  module is not reachable from a declared root, e.g. "every `:feature:*` must be reachable from
  `:app`" - the targeted counterpart to `no-orphan-modules` (reported as `unreachable-module`). Both
  follow production edges only, default to `ERROR`, and serialize to a configuration-cache-safe
  string. Pure `GraphReachability` closure in `aalekh-analysis`.
- **Exhaustive layer coverage** (`requireLayerForAllModules()`). Opt-in rule that flags any module
  belonging to no declared `layers { }` layer - a module that would otherwise slip past
  `layer-dependency` and depend on anything. Reported as `uncovered-module` at `WARNING` (promote to
  `ERROR`); inert until at least one layer is declared. Draws its patterns from the existing layer
  declarations, so no extra configuration is needed.
- **Predicate rule DSL** (`forbid { }`). Declares a one-off structural rule inline -
  `forbid { from(":core:domain"); toModuleType(ModuleType.ANDROID_LIBRARY); because("...") }` - instead
  of writing and shipping a custom `ArchRule` jar for the common "X must not depend on Y" case. Each
  side is selected by path glob (`from` / `to`) or module type (`fromModuleType` / `toModuleType`);
  `severity(...)` defaults to `ERROR`. Predicates serialize to a declarative string, never a captured
  lambda, so they stay configuration-cache safe. All share the stable id `forbidden-dependency`.
- **Affected-graph from a git diff** (`aalekhAffected`). Runs `git diff` (offline) between two refs,
  maps changed files to modules, and expands to the downstream blast radius a build must rebuild and
  retest. Writes a local `aalekh-affected.md` (a ready-to-post PR comment: "N of M modules affected")
  and `aalekh-affected.json`. Configure the range with `affected { baseRef.set("origin/main") }`.
  Aalekh only writes local files - a consumer's CI posts the comment. Pure `AffectedGraphAnalyzer`;
  git I/O isolated in `aalekh-gradle`.
- **Metric-delta quality gates** (`qualityGates { }`). Generalises the cycle-only regression check to
  any structural metric: `aalekhCheck` fails (or warns) when `cycles`, `god-modules`, `ccd`, `tangle`,
  `instability`, or `critical-path` got worse than the committed baseline - letting a team ratchet
  architecture quality in one direction only. `aalekhBaseline` now records a `metrics` snapshot
  alongside the violation fingerprints; enable gates with `forbidRegression("ccd", "cycles", ...)` or
  `forbidAllRegressions()` and set `severity`. Pure evaluator in `MetricGateEvaluator`.
- **Cycle break-up advice.** When `aalekhCheck` detects a dependency cycle it now prints the specific
  edge(s) to remove to break it - e.g. `implementation(project(":module-b")) in module-a/build.gradle.kts`.
  For every cycle (strongly connected component) a feedback-arc-set heuristic (Eades-Lin-Smyth) picks
  a small set of edges whose removal makes it acyclic, mapped back to the exact declaration to delete.
  The suggestions are also emitted in `aalekh-results.json` under `cycleBreakSuggestions`. Pure graph
  algorithm in `CycleAdvisor`; honest as a *suggested* cut since minimum feedback arc set is NP-hard.
- **Git temporal-coupling analysis** (`aalekhTemporal`). Reads the recent commit window from
  `git log` (offline, at execution time) and writes a local, diffable `aalekh-temporal.md` +
  `aalekh-temporal.json`. Surfaces three signals the static graph cannot: **change hotspots** (the
  most-committed modules), **hidden coupling** (modules that change together but declare no
  dependency), and **dead structure** (declared edges whose modules both change yet never
  co-change). Configure the commit window and thresholds via `temporalCoupling { }`. Fail-silent in
  a shallow clone or non-git directory. All git I/O stays in `aalekh-gradle`; the ranking is a pure
  function in `aalekh-analysis`.
- **Mermaid export** (`aalekhMermaid`). Writes the module graph as diffable Mermaid text -
  `build/reports/aalekh/aalekh-graph.mmd` (raw) and `aalekh-graph.md` (a fenced ` ```mermaid `
  block that renders as a diagram directly on GitHub). Production edges are solid, test-only edges
  dashed, and nodes are colour-coded by module type. Unlike the binary SVG export, the output diffs
  cleanly in pull requests.
- **Committed baseline / freeze** (`aalekhBaseline`). Records current violations to a committed
  `aalekh-baseline.json`; from then on `aalekhCheck` suppresses everything in the baseline and fails
  only on new violations. Lets a team turn on strict rules against an existing codebase immediately -
  the debt is frozen, regressions are blocked. Delete the file to stop applying it. Configure the
  path with `baselineFile` (default `"aalekh-baseline.json"`).
- **Lakos system-coupling metrics** - CCD (Cumulative Component Dependency), ACD, NCCD (normalized
  against a balanced binary tree), and **%Tangle** (share of modules inside a dependency cycle).
  Computed over main edges via `GraphAnalyzer` / `CouplingAnalyzer` and embedded in the report
  summary JSON and `aalekh-results.json`. One number for "how tangled is this graph".
- **`max-graph-height` rule** (`rules { maxGraphHeight(n) }`). Warns when the longest dependency
  chain exceeds `n` modules - the floor on build parallelism. Default `WARNING`.
- **`no-orphan-modules` rule** (`rules { noOrphanModules() }`). Warns about isolated modules that
  neither depend on anything nor are depended on. Default `WARNING`.
- **`aalekhMermaid`** and **`aalekhBaseline`** are `@CacheableTask` / regeneration-on-demand
  respectively and are registered by both the settings and (deprecated) project plugins.
- **Dogfood CI** (`.github/workflows/dogfood.yml`). Builds the plugin and applies it to Aalekh's own
  module graph, enforcing the `model ← analysis ← report ← gradle` layering so any regression of the
  one-way boundary fails the build.

### Fixed
- **External-dependency rows in the module inspector no longer clip the coordinate.** Each row now
  stacks onto two lines so the full `group:name` is always readable in the narrow sidebar, with the
  version and declaration badge on a second line.
- Structural cycle counting is now consistently production-only. `MetricsEngine.computeProjectMetrics`
  reported `hasCycles` via the test-edge-inclusive `graph.hasCycle()`, contradicting the rest of the
  analysis (and the "test-only cycles never fail the build" invariant); it now uses
  `GraphAnalyzer.findMainOnlyCycles`, so a cycle formed only by `testImplementation` edges no longer
  registers as a structural cycle. Regression-tested in `MetricsEngineTest`.
- Team ownership overlay is now wired end-to-end. The `teams { }` DSL was fully
  implemented (config, report JS, and docs) but never connected: `aalekhReport`
  did not carry the configured teams into the report and the generator hardcoded an
  empty `teamOwners` map, so the colour overlay and team legend never appeared. The
  serialized team map now flows from the extension through a new `teamEntries` task
  `@Input` (configuration-cache safe) into `summary.teamOwners`, and the report
  resolves module→team client-side as designed.

### Documentation
- Split the monolithic README into a lean landing page plus focused reference guides under
  `docs/` (tasks, report, configuration, rules, metrics, CI) behind a `docs/README.md` index.
- Documented how convention plugins and `build-logic` interact with module-type detection and graph
  extraction, including the source-substituting composite-build limitation, in
  `docs/configuration.md`.

## [0.5.1] - 2026-07-16

### Changed
- HTML report **Interchange redesign**. The report is rebuilt around a
  transit-map metaphor. Only the report *template* changed -
  every data, task, and output contract is unchanged.
- Dependency and toolchain updates

## [0.5.0] - 2026-05-20

### Added
- HTML report **blueprint redesign**. Light + dark themes (default tracks
  `prefers-color-scheme`, toggle via header button or `T`) with a drafting-table
  visual identity: faint grid background, ink + drafting-cyan palette,
  monospaced metadata labels, registration-mark accents. System font stack
  only — no Google Fonts, fully offline.
- Information architecture consolidated from 7 tabs to **6 panels**:
  *Overview* (new KPI landing with health hero, hotspot/cycle/critical-path/
  module-mix lists), *Map* (Architecture + Force graph as toggleable
  subpanels), *Browse* (Tree + Matrix as toggleable subpanels), *Health*
  (formerly Metrics), *Violations*, *Diff*.
- **Command palette** (`⌘K` / `Ctrl+K`) — fuzzy jump to any module or run an
  action (toggle theme, print, download JSON/CSV/SVG, path finder, switch
  view). Powered locally; no network.
- **Keyboard shortcuts overlay** (`?`) listing tab digits, view actions,
  density/theme/inspector toggles, export shortcuts.
- **Print stylesheet** so the report doubles as a deliverable PDF artefact
  (hides chrome, switches to paper-tint, page-breaks per panel).
- **Density toggle** (compact / comfy, `D`) persists to `localStorage`.
- **Inspector rail** — sidebar collapses to a narrow rail (`I`) when no module
  is selected, reclaiming canvas real-estate.
- Hash-state permalink now uses the readable `#tab=NAME&sub=NAME&m=:path`
  scheme so links can be shared and skimmed.
- `rules { custom("fully.qualified.ClassName") }` DSL entry registers a
  user-defined `ArchRule` implementation by class name. The rule class must be
  reachable from the plugin's runtime classpath (consumer buildscript classpath
  or an `includeBuild` composite project) and expose a public no-arg
  constructor. Load failures surface as an `aalekh-custom-rule` ERROR violation
  rather than crashing the build.
- `.github/workflows/compatibility-matrix.yml` runs `checkAll` + functional
  tests on JDK 17 and 21 (the JDKs Gradle 9 supports for the daemon),
  verifies the published library JARs ship as Java 11 bytecode (class-file
  major version 55), and asserts configuration-cache reuse on a second
  invocation.
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

[Unreleased]: https://github.com/shivathapaa/aalekh/compare/0.6.1...HEAD
[0.6.1]: https://github.com/shivathapaa/aalekh/compare/0.6.0...0.6.1
[0.6.0]: https://github.com/shivathapaa/aalekh/compare/0.5.1...0.6.0
[0.5.1]: https://github.com/shivathapaa/aalekh/compare/0.5.0...0.5.1
[0.5.0]: https://github.com/shivathapaa/aalekh/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/shivathapaa/aalekh/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/shivathapaa/aalekh/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/shivathapaa/aalekh/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/shivathapaa/aalekh/compare/0.0.1-alpha01...0.1.0
[0.0.1-alpha01]: https://github.com/shivathapaa/aalekh/releases/tag/0.0.1-alpha01
