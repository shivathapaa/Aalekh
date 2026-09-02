# Metrics & Output

[← Documentation index](README.md) · [Project README](../README.md)

## Graph metrics

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
| **Layer purity**     | Per-layer share of outgoing production edges that point at a permitted layer                                                                                                              |
| **Health score**     | Two separate 0–100 scores - one per module, one per project. See [Architecture health scores](#architecture-health-scores) below                                                          |

## Architecture health scores

Aalekh computes **two** health scores. They answer different questions and are never
interchangeable: a project of individually healthy modules can still score poorly as a whole if
those modules are tangled together. Both are single-sourced in `HealthScoreCalculator`
(`aalekh-analysis`), so the report, the CSV export, and `graph.json` always agree.

### Module health score

*"Is this module in a healthy position in the graph?"* Recorded on every module during
`aalekhExtract`, so it appears in `graph.json`, the `aalekh-metrics.csv` `healthScore` column, the
Health panel's per-module table, and the module inspector. Starts at 100 and deducts:

| Signal               | Weight | Rationale                                                    |
|----------------------|--------|--------------------------------------------------------------|
| Instability index    | 30     | How dependent vs depended-upon the module is                  |
| God module status    | 25     | High fan-in **and** fan-out - hard to change, hard to test     |
| Cycle participation  | 25     | Cycles prevent independent builds and refactoring              |
| Transitive dep count | 20     | Proxy for hidden coupling and build-time impact, zero at 50+   |

100 means stable, not a coupling hotspot, in no cycle, and with few transitive dependencies. Below
40 is a strong signal the module needs architectural attention.

### Project health score

*"Is this project's architecture in good shape right now?"* Shown on the Overview dial and embedded
in the report summary as `health`. It weighs project-level facts a single module cannot express.
Starts at 100 and deducts, each signal **capped** so no single problem can drive the score to zero:

| Signal               | Points each | Cap | Notes                                              |
|----------------------|-------------|-----|-----------------------------------------------------|
| Cycles               | 12          | 40  | Main-code dependency loops                          |
| Blocking violations  | 5           | 25  | `ERROR`-severity rule violations                    |
| Advisories           | 2           | 10  | `WARNING`-severity rule violations                  |
| Coupling hubs        | 4           | 15  | God modules                                         |
| Average instability  | —           | 15  | `(avg − 0.5) × 30`; nothing deducted at or below 0.5 |

Bands: **Healthy** at 80+, **Fair** at 55–79, **At risk** below 55. The Overview panel lists every
penalty that fired, with its arithmetic, so the headline number is inspectable rather than a
verdict.

## Structural metrics

Computed per module from the production graph, and shown in the Health table, the module inspector,
and `modules.md`. Each answers a question a developer actually asks; the report carries the same
definitions inline, so a number never appears without its meaning attached.

| Metric | Question it answers | How to read it |
|--------|---------------------|----------------|
| **Blast radius** | If I make a breaking change here, how much must be rebuilt, retested and reviewed? | The real cost of changing a module. Above about a third of the project, every change is a whole-project change. |
| **Influence** | Which modules is this project actually built on? | PageRank over the dependency graph, normalised so the mean module is `1.0`. Unlike fan-in it weighs *who* depends on you: being used by the app counts for more than being used by a leaf. |
| **Betweenness** | Is this a choke point the project's structure routes through? | The share of shortest dependency paths passing through it. High betweenness means changes ripple in both directions. |
| **Comprehension cost** | How much of the codebase must I read to understand this module? | The modules whose behaviour can affect it. One you cannot understand without reading fifty others is expensive to own. |
| **API surface** | How much of what this module depends on does it re-export? | Every `api` dependency lands on every consumer's compile classpath, widening their blast radius too. |
| **Depth** | How far is this from where execution starts? | `0` is an entry point. Nothing to fix - use it to navigate. |
| **Articulation point** | Would removing this module split the project in two? | A structural bottleneck rather than merely a busy one. Computed on the undirected projection: the question is about connectivity, not direction. |
| **Dependency concentration** | Is dependency spread across the project, or absorbed by a few modules? | Gini coefficient of fan-in. Approaching `1` means a small core the whole project routes through - not wrong, but it makes those modules the bottleneck for every change. |

**Percentiles over thresholds.** The report ranks modules against their own project rather than
against a fixed number: "99th-percentile fan-in *for this project*" means something on a 20-module
build and on a 900-module one, where "fan-in ≥ 5" does not.

## System coupling (Lakos)

Whole-graph coupling numbers, computed over production edges only and embedded in the report summary
JSON and `aalekh-results.json`. They condense "how coupled / how tangled is this architecture" into
a handful of comparable figures.

| Metric        | Description                                                                                                                                                     |
|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **CCD**       | Cumulative Component Dependency - the sum, over every module, of its dependency-set size (itself plus everything it can reach). The headline coupling number.   |
| **ACD**       | Average Component Dependency - `CCD / moduleCount`. The average number of modules each module drags in, itself included.                                        |
| **NCCD**      | Normalized CCD - `CCD` divided by the CCD of a balanced binary tree of the same size. `~1.0` is tree-like and healthy; `> 1.0` is more tangled; `< 1.0` flatter. |
| **%Tangle**   | The percentage of modules that sit inside a dependency cycle (a strongly connected component of size ≥ 2). `0%` for a clean DAG.                                 |

## Temporal coupling

Written by [`aalekhTemporal`](tasks.md#aalekhtemporal) from git history, not from the dependency
graph. Temporal coupling measures how the code *evolves* rather than what it *declares*: two modules
are temporally coupled when they keep changing in the same commits. Computed over the recent commit
window, mapping each changed file to the module that owns its directory.

| Metric              | Description                                                                                                                                         |
|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| **Churn**           | The number of commits in the window that touched a module. High churn marks a **change hotspot**.                                                   |
| **Shared commits**  | For a module pair, the number of commits that touched *both* modules.                                                                               |
| **Coupling degree** | `sharedCommits / min(churnA, churnB)`, in `[0.0, 1.0]`. `1.0` means the less-churned module never changed without the other.                        |
| **Hidden coupling** | A pair that co-changes at or above the degree threshold but has **no** declared dependency - implicit coupling the static graph cannot see.         |
| **Dead structure**  | A declared production edge whose two modules both changed in the window but *never together* - a dependency that may no longer reflect actual usage. |

## Main sequence

Written by [`aalekhMainSequence`](tasks.md#aalekhmainsequence). Places each module on Robert Martin's
*main sequence* - the line `A + I = 1` that a well-designed module should sit near.

| Metric               | Description                                                                                                                              |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **Instability (I)**  | `Ce / (Ca + Ce)` - efferent coupling ratio, from the production graph. `0` = maximally stable (only depended upon), `1` = maximally unstable (only depends). |
| **Abstractness (A)** | `Na / (Na + Nc)` - abstract type declarations over total, from a coarse source scan. `0` = all concrete, `1` = all abstract.            |
| **Distance (D)**     | `\|A + I - 1\|` - distance from the main sequence. `0` sits on the line (ideal balance); `1` is a far corner.                            |
| **Zone of pain**     | Concrete **and** stable (low A, low I): rigid, hard to change, yet heavily depended upon.                                               |
| **Zone of uselessness** | Abstract **and** unstable (high A, high I): abstractions almost nothing depends on.                                                  |

Abstractness is the one metric the dependency graph cannot supply, so it comes from a **coarse lexical
scan** of each module's Kotlin/Java source (interfaces and `abstract`/`sealed` classes count as
abstract; concrete/`data`/`enum`/`value` classes, `object`s and Java `enum`s count as concrete). It
strips comments but is not a full parser - treat the numbers as a directional hint. Modules with no
countable types are omitted.

## Custom metrics (SPI)

Written by [`aalekhMetrics`](tasks.md#aalekhmetrics). Beyond the built-in metrics above, third
parties can contribute their own through the **metric provider SPI** - the measurement counterpart to
the custom-rule SPI.

Implement `com.aalekh.aalekh.analysis.spi.MetricProvider` and register it with the JDK `ServiceLoader`
mechanism (a `META-INF/services/com.aalekh.aalekh.analysis.spi.MetricProvider` file listing your
class), shipped in a jar on the plugin's runtime classpath. Each provider is a **pure function of the
graph**:

| Member                          | Meaning                                                                                     |
|---------------------------------|---------------------------------------------------------------------------------------------|
| `id`                            | Stable, unique kebab-case identifier; the metric's key in the JSON output.                   |
| `displayName`                   | Human-readable label for the report.                                                        |
| `description` / `unit`          | Optional one-line explanation and unit label (e.g. `"%"`).                                   |
| `compute(graph)`                | Returns a `MetricContribution` with a `systemValue` and/or per-module `moduleValues`.        |

The engine runs every discovered provider fail-silent: a blank id, an id already used by another
provider, or a provider that throws is skipped and recorded under `providerFailures` in the report -
never fatal. Values land in `aalekh-custom-metrics.json` (machine-readable) and
`aalekh-custom-metrics.md` (system table + per-module tables, highest first). See
[Extending Aalekh](rules.md#custom-rules) for how the classpath wiring mirrors custom rules.

## Output files reference

| File                                        | Task            | Description                                                                                                                    |
|---------------------------------------------|-----------------|--------------------------------------------------------------------------------------------------------------------------------|
| `build/tmp/aalekh/graph.json`               | `aalekhExtract` | Serialized module dependency graph. Input to all other tasks. Cleaned by `./gradlew clean`.                                    |
| `build/reports/aalekh/index.html`           | `aalekhReport`  | Self-contained interactive HTML report.                                                                                        |
| `build/reports/aalekh/aalekh-metrics.csv`   | `aalekhReport`  | Per-module metrics CSV. Written only when `exportMetrics = true`.                                                              |
| `build/aalekh/trend.json`                   | `aalekhReport`  | Rolling 30-entry build trend history. Read on next run to power sparklines. Not cleaned by `clean`.                            |
| `build/reports/aalekh/aalekh-results.xml`   | `aalekhCheck`   | JUnit XML for CI test reporters.                                                                                               |
| `build/reports/aalekh/aalekh-results.json`  | `aalekhCheck`   | Full machine-readable report: graph summary, all violations, version, timestamp. Read by regression detection on the next run. |
| `build/reports/aalekh/aalekh-results.sarif` | `aalekhCheck`   | SARIF 2.1 for GitHub code scanning PR annotations.                                                                             |
| `build/reports/aalekh/aalekh-codeclimate.json` | `aalekhCheck` | Code Climate JSON for GitLab Code Quality merge-request annotations.                                                        |
| `build/reports/aalekh/aalekh-graph.mmd`     | `aalekhMermaid` | Raw Mermaid graph definition.                                                                                                  |
| `build/reports/aalekh/aalekh-graph.md`      | `aalekhMermaid` | Mermaid graph inside a fenced block, renders on GitHub.                                                                        |
| `build/reports/aalekh/aalekh-graph.dot`     | `aalekhMermaid` | Graphviz DOT `digraph`, for `dot`, Gephi, and other graph tools.                                                             |
| `build/reports/aalekh/aalekh-temporal.md`   | `aalekhTemporal`| Change-coupling report: hotspots, hidden coupling, dead structure.                                                            |
| `build/reports/aalekh/aalekh-temporal.json` | `aalekhTemporal`| The same temporal data, machine-readable.                                                                                     |
| `build/reports/aalekh/aalekh-affected.md`   | `aalekhAffected`| Affected-modules PR comment: changed modules and downstream blast radius for a git diff.                                     |
| `build/reports/aalekh/aalekh-affected.json` | `aalekhAffected`| The same affected-graph data, machine-readable.                                                                              |
| `aalekh-baseline.json`                      | `aalekhBaseline`| Committed baseline: frozen violation fingerprints and a metrics snapshot for quality gates.                                  |
| `build/reports/aalekh/aalekh-main-sequence.md`   | `aalekhMainSequence`| Abstractness/instability/distance table with zone-of-pain and zone-of-uselessness call-outs. |
| `build/reports/aalekh/aalekh-main-sequence.json` | `aalekhMainSequence`| The same main-sequence data, machine-readable.                                          |
| `build/reports/aalekh/aalekh-custom-metrics.md`  | `aalekhMetrics`     | Custom `MetricProvider` SPI values: system table and per-module tables.                  |
| `build/reports/aalekh/aalekh-custom-metrics.json`| `aalekhMetrics`     | The same custom-metric data, machine-readable, with any provider failures.              |
| `build/reports/aalekh/docs/`                     | `aalekhDocs`        | Generated Markdown: `README.md`, `modules.md`, `onboarding.md`, `health.md`, `regions.md`, `build.md`, `dependencies.md`. No timestamp, so unchanged input yields identical files. |
| `aalekh-snapshot.json`                           | `aalekhSnapshot`    | Committed architecture snapshot: modules, edges, layers, cycles, and metrics, all sorted. Path set by `snapshotFile`. |
| `build/reports/aalekh/aalekh-diff.md`            | `aalekhDiff`        | Pull-request comment: new cycles, added and removed dependencies and modules, layer moves, metric deltas. |
| `build/reports/aalekh/aalekh-diff.json`          | `aalekhDiff`        | The same architecture diff, machine-readable.                                           |

## Metrics CSV export

Set `exportMetrics.set(true)` to write `aalekh-metrics.csv` alongside the HTML report on every
`aalekhReport` run. The CSV contains one timestamped row per module with: fan-in, fan-out,
instability, transitive dep count, health score, and boolean flags for god module, critical path,
and cycle participation. Import into Datadog, Grafana, or a spreadsheet for external trending.

## Trend history

Every `aalekhReport` run appends a metrics snapshot to `build/aalekh/trend.json` (rolling window
of 30 entries). The snapshot records: timestamp, total module count, total edge count, cycle count,
god module count, critical path length, and average instability. The data is embedded in the report
and used to render the sparklines in each KPI card on the Health panel.

Failure to read or write the trend file is always non-fatal and never breaks the build.
