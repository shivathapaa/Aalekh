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
| **Layer purity**     | Per-layer percentage of dependency edges flowing in the correct declared direction                                                                                                        |
| **Health score**     | 0–100 composite score. Weighted from instability (30%), god module (25%), cycle participation (25%), transitive dep count (20%). Shown in the metrics table and module inspector sidebar. |

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
