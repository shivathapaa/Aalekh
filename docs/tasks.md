# Gradle Tasks

[← Documentation index](README.md) · [Project README](../README.md)

Aalekh registers seven tasks on the root project, all in the `aalekh` task group.

| Task                       | Description                                                                                  |
|----------------------------|----------------------------------------------------------------------------------------------|
| `./gradlew aalekhExtract`  | Extracts the module dependency graph and writes it as JSON to `build/tmp/aalekh/graph.json`  |
| `./gradlew aalekhReport`   | Generates the interactive HTML report at `build/reports/aalekh/index.html`                   |
| `./gradlew aalekhCheck`    | Evaluates all architecture rules; fails the build on `ERROR`-severity violations             |
| `./gradlew aalekhMermaid`  | Exports the graph as diffable Mermaid text (`aalekh-graph.mmd` + `aalekh-graph.md`)          |
| `./gradlew aalekhBaseline` | Records current violations to a committed baseline so `aalekhCheck` fails only on new ones   |
| `./gradlew aalekhTemporal` | Analyses git history for temporal (change) coupling and hotspots (`aalekh-temporal.md`/`.json`) |
| `./gradlew aalekhAffected` | Computes modules affected by a git diff and their blast radius (`aalekh-affected.md`/`.json`) |

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

Every run appends a snapshot to `build/aalekh/trend.json` (rolling window of 30 entries) to power
the KPI trend sparklines.

See [The report](report.md) for a full tour of the HTML output.

## aalekhCheck

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
inline on GitHub, GitLab, and most IDEs. Two files are written next to the HTML report:

- `build/reports/aalekh/aalekh-graph.mmd` - the raw Mermaid definition, for `mermaid-cli` or manual
  embedding.
- `build/reports/aalekh/aalekh-graph.md` - the same graph inside a ` ```mermaid ` fenced block.
  Commit it next to your code to keep a versioned, reviewable diagram of the architecture.

Production dependencies render as solid arrows, test-only dependencies as dashed arrows, and nodes
are colour-coded by module type. Output is deterministic, so the file only changes when the graph
does - it diffs cleanly in pull requests, unlike the binary SVG export. The task is `@CacheableTask`.

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

## aalekhExtract

```bash
./gradlew aalekhExtract
```

Extracts and serializes the full module dependency graph to `build/tmp/aalekh/graph.json`. The
output is `@CacheableTask` - Gradle will skip it when inputs (project structure, dependency
declarations, filter flags) have not changed.

You rarely need to run this directly; every other Aalekh task depends on it.
