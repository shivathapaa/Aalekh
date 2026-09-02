# Gradle Tasks

[← Documentation index](README.md) · [Project README](../README.md)

Aalekh registers twelve tasks on the root project, all in the `aalekh` task group.

| Task                          | Description                                                                                  |
|-------------------------------|----------------------------------------------------------------------------------------------|
| `./gradlew aalekhExtract`     | Extracts the module dependency graph and writes it as JSON to `build/tmp/aalekh/graph.json`  |
| `./gradlew aalekhReport`      | Generates the interactive HTML report at `build/reports/aalekh/index.html`                   |
| `./gradlew aalekhCheck`       | Evaluates all architecture rules; fails the build on `ERROR`-severity violations             |
| `./gradlew aalekhMermaid`     | Exports the graph as diffable Mermaid and Graphviz DOT text (`aalekh-graph.mmd`/`.md`/`.dot`) |
| `./gradlew aalekhBaseline`    | Records current violations to a committed baseline so `aalekhCheck` fails only on new ones   |
| `./gradlew aalekhTemporal`    | Analyses git history for temporal (change) coupling and hotspots (`aalekh-temporal.md`/`.json`) |
| `./gradlew aalekhAffected`    | Computes modules affected by a git diff and their blast radius (`aalekh-affected.md`/`.json`) |
| `./gradlew aalekhMainSequence`| Computes each module's abstractness, instability and distance from the main sequence (`aalekh-main-sequence.md`/`.json`) |
| `./gradlew aalekhMetrics`     | Runs custom `MetricProvider` SPI implementations and writes their values (`aalekh-custom-metrics.md`/`.json`) |
| `./gradlew aalekhDocs`        | Writes architecture documentation as Markdown to `build/reports/aalekh/docs/`                |
| `./gradlew aalekhSnapshot`    | Records the current architecture as a committable `aalekh-snapshot.json`                     |
| `./gradlew aalekhDiff`        | Reports what this change did to the architecture (`aalekh-diff.md`/`.json`)                  |

Every task depends on `aalekhExtract` implicitly - you do not need to run it manually.

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

## aalekhReport

```bash
./gradlew aalekhReport
```

Generates `build/reports/aalekh/index.html` - a fully self-contained HTML file. D3.js is inlined
from the bundled `d3.min.js` resource and the Hanken Grotesk / Spline Sans Mono typefaces are
base64-inlined as `woff2`, so the report renders without a server, without any CDN, and without
internet access at view time. The report opens automatically in your default browser after the task
completes. Disable auto-open with `openBrowserAfterReport.set(false)` for CI environments.

When `exportMetrics` is enabled, also writes `build/reports/aalekh/aalekh-metrics.csv`.

If `aalekh-main-sequence.json` is present next to the report (i.e. you ran
[`aalekhMainSequence`](#aalekhmainsequence) first), the report's Metrics panel additionally draws the
**Abstractness vs Instability scatter** from it. Likewise, if `aalekh-temporal.json` is present (from
[`aalekhTemporal`](#aalekhtemporal)), the panel shows a **Hidden Coupling** card - pairs that
co-change in git history without a declared dependency. Both are omitted when their file is absent.

Every run appends a snapshot to `build/aalekh/trend.json` (rolling window of 30 entries) to power
the KPI trend sparklines.

See [The report](report.md) for a full tour of the HTML output.

## aalekhCheck

```bash
./gradlew aalekhCheck
```

Evaluates all registered architecture rules against the extracted dependency graph. On completion it
writes four output files:

- `build/reports/aalekh/aalekh-results.xml` - JUnit XML consumed natively by all CI systems
- `build/reports/aalekh/aalekh-results.json` - full machine-readable report: graph, summary,
  violations, version, and timestamp
- `build/reports/aalekh/aalekh-results.sarif` - SARIF 2.1 for GitHub code scanning PR annotations
- `build/reports/aalekh/aalekh-codeclimate.json` - Code Climate JSON for GitLab Code Quality MR annotations

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

When a **dependency cycle** is detected, `aalekhCheck` also prints break-up advice - the specific
edge(s) to remove to make the graph acyclic, computed as a feedback arc set and mapped to the exact
declaration:

```
Aalekh: to break the detected cycle(s), consider removing:
  • implementation(project(":module-a")) in module-b/build.gradle.kts
```

The same suggestions are written to `aalekh-results.json` under `cycleBreakSuggestions`. Because
minimum feedback arc set is NP-hard, this is a good greedy *suggestion*, not a proven-minimal cut;
removing every suggested edge for a cycle is guaranteed to break it.

See [Architecture rules](rules.md) for the full rule reference.

## aalekhMermaid

```bash
./gradlew aalekhMermaid
```

Exports the module graph as [Mermaid](https://mermaid.js.org) text - plain, diffable, and rendered
inline on GitHub, GitLab, and most IDEs. Three files are written next to the HTML report:

- `build/reports/aalekh/aalekh-graph.mmd` - the raw Mermaid definition, for `mermaid-cli` or manual
  embedding.
- `build/reports/aalekh/aalekh-graph.md` - the same graph inside a ` ```mermaid ` fenced block.
  Commit it next to your code to keep a versioned, reviewable diagram of the architecture.
- `build/reports/aalekh/aalekh-graph.dot` - the same graph as a [Graphviz](https://graphviz.org) DOT
  `digraph`, for `dot -Tsvg`/`dot -Tpng`, Gephi, and the wider graph-tooling ecosystem.

Production dependencies render as solid arrows, test-only dependencies as dashed arrows, and nodes
are colour-coded by module type. Output is deterministic, so the files only change when the graph
does - they diff cleanly in pull requests, unlike the binary SVG export. The task is `@CacheableTask`.

### Focus and exclude filters

A whole-repo diagram becomes unreadable past a few dozen modules. Narrow it with the `mermaid { }`
block - the filters apply to all three outputs (`.mmd`, `.md`, `.dot`):

```kotlin
aalekh {
    mermaid {
        focus(":feature:checkout")   // keep :feature:checkout and its neighbours
        depth(2)                     // ...grow the neighbourhood 2 hops (default 1)
        exclude(":test:**")          // then drop test-only modules
    }
}
```

- **`focus(vararg globs)`** - restrict the diagram to the modules matching any glob plus their
  neighbourhood. Growth follows dependency edges in *both* directions (dependencies and dependents),
  so a focused module keeps its context. With no `focus`, every module is exported.
- **`depth(hops)`** - how far to grow the focus set: `0` keeps only the focused modules, `1` (the
  default) adds their direct neighbours. Ignored when no `focus` is set.
- **`exclude(vararg globs)`** - remove matching modules, applied *after* focus. An edge survives only
  when both of its endpoints do.

Globs use the same syntax as the rest of Aalekh (`*` within a path segment, `**` across segments).
With no filters declared the full graph is exported exactly as before. The subsetting is a pure
function (`GraphFilter` in `aalekh-analysis`), so it is fully unit-tested and the diagram generators
are unchanged.

## aalekhBaseline

```bash
./gradlew aalekhBaseline
```

Records the project's current architecture violations to a committed baseline file
(`aalekh-baseline.json` by default; configure with `baselineFile`). Run it once, commit the file,
and from then on `aalekhCheck` suppresses every violation already in the baseline and fails only on
**new** ones. This is the standard "freeze the debt, block regressions" workflow that lets you turn
on strict rules against a large existing codebase without fixing everything first.

Re-run the task after you have legitimately fixed or accepted violations to refresh the baseline.
Delete the file to stop applying it. See [Architecture rules → Baseline / freeze](rules.md#baseline--freeze).

## aalekhTemporal

```bash
./gradlew aalekhTemporal
```

Analyses **temporal (change) coupling** from git history and writes two files next to the HTML
report:

- `build/reports/aalekh/aalekh-temporal.md` - a reviewable Markdown report, ready to commit or paste
  into a pull request.
- `build/reports/aalekh/aalekh-temporal.json` - the same data, machine-readable.

Where the dependency graph shows the architecture you *declared*, temporal coupling shows how the
code *actually evolves* - which modules keep changing together. Reading the recent commit window
(via `git log`, offline, at execution time) it surfaces three signals the static graph cannot:

- **Change hotspots** - the modules committed most often; prime refactoring and test-hardening
  targets.
- **Hidden coupling** - pairs that change together but declare *no* dependency between them; implicit
  coupling worth making explicit or breaking.
- **Dead structure** - declared edges whose two modules both changed in the window but *never*
  together; a dependency that may no longer reflect how the code is used.

Configure the commit window and thresholds in the `temporalCoupling { }` block:

```kotlin
aalekh {
    temporalCoupling {
        commitWindow.set(1000)             // analyse the last 1000 non-merge commits (default 500)
        minSharedCommits.set(3)            // ignore pairs sharing fewer than 3 commits (default 2)
        hiddenCouplingThreshold.set(0.7)   // flag undeclared pairs at degree >= 0.7 (default 0.6)
    }
}
```

The task is **fail-silent**: a shallow clone, a directory that is not a git repository, a missing
`git` binary, or a timeout produces an empty report and a log line rather than a build failure. All
git I/O lives in the plugin module; the coupling ranking itself is a pure function, so it is fully
tested without git. See [Metrics & output → Temporal coupling](metrics.md#temporal-coupling) for the
metric definitions.

## aalekhAffected

```bash
./gradlew aalekhAffected
```

Computes the **affected graph** for a git diff - which modules a change touches and the downstream
blast radius a build must therefore rebuild and retest - and writes two files next to the HTML
report:

- `build/reports/aalekh/aalekh-affected.md` - a Markdown summary ("N of M modules affected", changed
  and affected module lists) that a CI job can post as a pull-request comment.
- `build/reports/aalekh/aalekh-affected.json` - the same data, machine-readable.

A change to module `X` forces everything that depends on `X` to rebuild, so the affected set is the
changed modules plus every production dependent reachable from them. Configure the diff range in the
`affected { }` block:

```kotlin
aalekh {
    affected {
        baseRef.set("origin/main")   // compare against the PR's merge target (default "HEAD~1")
        headRef.set("HEAD")          // ...up to this ref; blank (default) diffs against the working tree
    }
}
```

Aalekh only writes local files - **posting the comment is the consumer CI's job**, in keeping with
Aalekh never publishing anything itself. Like `aalekhTemporal`, the task is fail-silent on a missing
git binary or unknown ref: it writes an empty report rather than failing the build.

## aalekhMainSequence

```bash
./gradlew aalekhMainSequence
```

Computes where each module sits relative to Robert Martin's **main sequence** - the line `A + I = 1`
that balances abstractness against instability - and writes two files next to the HTML report:

- `build/reports/aalekh/aalekh-main-sequence.md` - a reviewable table, worst-distance first, with the
  off-sequence modules called out.
- `build/reports/aalekh/aalekh-main-sequence.json` - the same data, machine-readable.

For each module it reads **instability (I)** from the dependency graph and estimates **abstractness
(A)** - the ratio of abstract to total type declarations - from a coarse scan of the module's Kotlin
and Java source. The **distance D = |A + I - 1|** is how far the module sits from the ideal balance:
`0` is on the line, `1` is a far corner. A concrete-and-stable module (low A, low I) lands in the
**zone of pain** (rigid, hard to change, yet heavily depended upon); an abstract-and-unstable module
(high A, high I) lands in the **zone of uselessness** (abstractions almost nothing uses).

The abstractness scan is a deliberately coarse lexical estimate (it strips comments and counts
declaration keywords, not a full parse), so treat the numbers as a hint, not a precise measurement.
The task is not cached - it re-scans source on every run - and never fails the build; a module with no
scannable source is simply omitted. See [Metrics & output → Main sequence](metrics.md#main-sequence)
for the metric definitions.

## aalekhMetrics

```bash
./gradlew aalekhMetrics
```

Runs every **custom metric provider** discovered on the plugin classpath and writes their values as
two files next to the HTML report:

- `build/reports/aalekh/aalekh-custom-metrics.md` - a reviewable summary: system-wide metrics in one
  table, then a per-module table (highest first) for each metric that carries module values.
- `build/reports/aalekh/aalekh-custom-metrics.json` - the same data, machine-readable.

Where `rules { custom(...) }` lets you *enforce* structure, a `MetricProvider` lets you *measure* it.
Implement `com.aalekh.aalekh.analysis.spi.MetricProvider`, register it via the JDK `ServiceLoader`
mechanism (a `META-INF/services/com.aalekh.aalekh.analysis.spi.MetricProvider` file listing your
class), and put the jar on the plugin's runtime classpath - the same classpath used for custom rules.
The task then discovers every provider automatically; no DSL wiring is needed.

```kotlin
class LeafRatioMetric : MetricProvider {
    override val id = "leaf-ratio"
    override val displayName = "Leaf module ratio"
    override val description = "Share of modules that nothing depends on."
    override val unit = "%"
    override fun compute(graph: ModuleDependencyGraph): MetricContribution {
        val leaves = graph.modules.count { m -> graph.edges.none { it.to == m.path && !it.isTest } }
        val ratio = if (graph.modules.isEmpty()) 0.0 else leaves * 100.0 / graph.modules.size
        return MetricContribution(systemValue = ratio)
    }
}
```

With no providers registered the task is a no-op that still writes an empty report explaining how to
add one. A provider is a **pure function of the graph** (no I/O, no Gradle API); one that throws is
skipped and noted in the report - it never fails the build. The task is not cached, since which
provider jars sit on the classpath is not a declared input. See
[Metrics & output → Custom metrics (SPI)](metrics.md#custom-metrics-spi) and
[Extending Aalekh](rules.md#custom-rules) for the provider contract.

## aalekhSnapshot

Records the current architecture as a committable snapshot.

```bash
./gradlew aalekhSnapshot
git add aalekh-snapshot.json
```

Architecture changes are invisible in a normal review: adding one line to a `dependencies { }` block
can wire two subsystems together, and the diff shows one line. Committing a snapshot and comparing
against it with [`aalekhDiff`](#aalekhdiff) makes the consequence visible.

The file is deliberately small and sorted so it diffs line by line - module paths, dependency pairs as
`from>to`, cycle membership, layer assignments, and the structural metrics worth watching. It is not
the whole graph, which would rewrite itself on every unrelated change.

Change the location with `aalekh { snapshotFile.set("config/architecture.json") }`.

## aalekhDiff

Reports what the current architecture changed relative to the committed snapshot.

```bash
./gradlew aalekhDiff
```

Writes `aalekh-diff.md` - a ready-to-post pull-request comment - and `aalekh-diff.json`. The report
leads with the most consequential change, so a reviewer skimming from the top sees the thing most
worth their attention first:

```markdown
This change **introduces a dependency cycle** across 2 modules and adds 1 dependency.
Worth a closer look before merging.

### 🔴 New dependency cycles
- `:app`
- `:feature:login`

### ➕ Dependencies added
- `:feature:login` → `:app`
```

With no snapshot committed the task explains how to create one and **succeeds** - the first run of a
new tool must never fail a build. Aalekh writes local files only; posting the comment is your CI job:

```yaml
- run: ./gradlew aalekhDiff
- uses: peter-evans/create-or-update-comment@v4
  with:
    issue-number: ${{ github.event.pull_request.number }}
    body-path: build/reports/aalekh/aalekh-diff.md
```

Set `aalekh { failOnArchitectureRegression.set(true) }` to fail the build when the change makes the
architecture structurally worse - a new cycle, or a metric that regressed. Off by default, because a
diff is a report: it should describe what changed without deciding that the change is wrong.

## aalekhDocs

Writes the project's architecture documentation as Markdown to `build/reports/aalekh/docs/`.

```bash
./gradlew aalekhDocs
```

The HTML report is for exploring; these files are for **reading and reviewing**. They render on
GitHub with no build step, so a reviewer sees them without cloning, and they diff line by line, so an
architectural change shows up in a pull request next to the code that caused it.

| File | What's inside |
|------|---------------|
| `README.md` | Plain-language summary of the project, then every finding grouped by category |
| `modules.md` | Module catalogue ranked by blast radius, plus any purpose declared in `.aalekh/modules.json` |
| `onboarding.md` | The reading order for someone new: entry points, foundation, and what to read first |
| `health.md` | Whole-project metrics with the definition of each and how to read it |
| `regions.md` | How the project divides into regions and what crosses the boundaries (omitted when too small to group) |
| `build.md` | Plugins, versions, catalogs and toolchains (omitted when nothing was captured) |
| `dependencies.md` | Third-party libraries and version conflicts (omitted when none are declared) |

Every sentence is templated from measured values, and the output carries **no timestamp**, so
re-running on an unchanged project rewrites identical bytes. That is what makes the output worth
committing: a diff means the architecture moved, not that the tool ran again. Teams that want the
documentation reviewed alongside the code copy the directory into the repository and add a CI step
that fails when a re-run produces a diff:

```yaml
- run: ./gradlew aalekhDocs
- run: cp -r build/reports/aalekh/docs/. docs/architecture/
- run: git diff --exit-code docs/architecture/
```

Files a project no longer warrants are deleted on the next run, so the directory never keeps a stale
document describing something that has since been removed.

## aalekhExtract

```bash
./gradlew aalekhExtract
```

Extracts and serializes the full module dependency graph to `build/tmp/aalekh/graph.json`. The
output is `@CacheableTask` - Gradle will skip it when inputs (project structure, dependency
declarations, filter flags) have not changed.

You rarely need to run this directly; every other Aalekh task depends on it.
