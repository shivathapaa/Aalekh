# Configuration

[← Documentation index](README.md) · [Project README](../README.md)

All configuration lives in the `aalekh { }` block in the root `build.gradle.kts`.

```kotlin
// build.gradle.kts (root project)
aalekh {
    // Output directory relative to build/. Default: "reports/aalekh"
    outputDir.set("reports/aalekh")

    // Open the report in the default browser after aalekhReport completes.
    // Set to false in CI environments.
    // Default: true
    openBrowserAfterReport.set(true)

    // Include testImplementation / androidTestImplementation / KMP test edges in the graph.
    // Default: true
    includeTestDependencies.set(true)

    // Include compileOnly edges in the graph.
    // Disabled by default because compileOnly deps are rarely architecturally significant.
    // Default: false
    includeCompileOnlyDependencies.set(false)

    // Write aalekh-metrics.csv alongside the HTML report on every aalekhReport run.
    // Default: false
    exportMetrics.set(false)

    // Path (relative to the root project) of the committed violation baseline.
    // Run ./gradlew aalekhBaseline to write it. Default: "aalekh-baseline.json"
    baselineFile.set("aalekh-baseline.json")

    // Git temporal-coupling analysis (aalekhTemporal). See Gradle tasks → aalekhTemporal.
    temporalCoupling {
        commitWindow.set(500)              // recent non-merge commits to analyse
        minSharedCommits.set(2)            // ignore pairs sharing fewer commits
        hiddenCouplingThreshold.set(0.6)   // flag undeclared pairs at this coupling degree
    }

    // Metric-delta quality gates: fail aalekhCheck when a metric got worse than the baseline.
    qualityGates {
        forbidRegression("cycles", "ccd", "god-modules")   // valid: cycles, god-modules, ccd, tangle, instability, critical-path
        // severity.set(Severity.WARNING)                   // default ERROR
    }

    // Affected-graph diff range for aalekhAffected. See Gradle tasks → aalekhAffected.
    affected {
        baseRef.set("HEAD~1")   // e.g. "origin/main" in CI
        headRef.set("")         // blank diffs against the working tree
    }

    // Focus/exclude filters for the aalekhMermaid diagram exports (.mmd/.md/.dot).
    mermaid {
        focus(":feature:checkout")   // keep this module and its neighbourhood
        depth(1)                     // neighbourhood hops to grow the focus set (default 1)
        exclude(":test:**")          // drop matching modules after focus
    }

    layers { /* see Architecture rules → Layer enforcement */ }
    featureIsolation { /* see Architecture rules → Feature isolation */ }
    teams { /* see Architecture rules → Team ownership */ }
    rules { /* see Architecture rules */ }
}
```

The `layers`, `featureIsolation`, `teams`, and `rules` blocks are documented in
[Architecture rules](rules.md). The `temporalCoupling` block is documented under
[Gradle tasks → aalekhTemporal](tasks.md#aalekhtemporal), and `qualityGates` under
[Architecture rules → Quality gates](rules.md#quality-gates). The `mermaid` focus/exclude filters
are documented under [Gradle tasks → aalekhMermaid](tasks.md#focus-and-exclude-filters).

Quality gates compare the current graph against the `metrics` snapshot recorded in
`aalekh-baseline.json`. They only fire once you have run `aalekhBaseline`; a regression on any
enabled metric (`cycles`, `god-modules`, `ccd`, `tangle`, `instability`, `critical-path`) becomes a
`metric-regression` violation at the configured `severity` (default `ERROR`).

## Configuration option reference

| Option                           | Type      | Default            | Description                                                                  |
|----------------------------------|-----------|--------------------|------------------------------------------------------------------------------|
| `outputDir`                      | `String`  | `"reports/aalekh"` | Output directory relative to `build/`                                        |
| `openBrowserAfterReport`         | `Boolean` | `true`             | Auto-open the HTML report after `aalekhReport` runs                          |
| `includeTestDependencies`        | `Boolean` | `true`             | Include `testImplementation`, `androidTestImplementation`, etc. in the graph |
| `includeCompileOnlyDependencies` | `Boolean` | `false`            | Include `compileOnly` edges in the graph                                     |
| `includeExternalDependencies`    | `Boolean` | `true`             | Capture external (third-party) dependency coordinates for the report inspector |
| `exportMetrics`                  | `Boolean` | `false`                 | Write `aalekh-metrics.csv` alongside the HTML report                    |
| `baselineFile`                   | `String`  | `"aalekh-baseline.json"` | Path (relative to root) of the committed violation baseline; see [rules](rules.md#baseline--freeze) |
| `snapshotFile`                   | `String`  | `"aalekh-snapshot.json"` | Path (relative to root) of the committed architecture snapshot; see [`aalekhDiff`](tasks.md#aalekhdiff) |
| `failOnArchitectureRegression`   | `Boolean` | `false`                  | Fail `aalekhDiff` on a new cycle or a regressed metric instead of only reporting it |
| `temporalCoupling.commitWindow`  | `Int`     | `500`                    | Recent non-merge commits `aalekhTemporal` analyses                          |
| `temporalCoupling.minSharedCommits` | `Int`  | `2`                      | Minimum shared commits before a co-change pair is reported                  |
| `temporalCoupling.hiddenCouplingThreshold` | `Double` | `0.6`           | Coupling degree at/above which an undeclared pair is flagged hidden coupling |
| `affected.baseRef`               | `String`  | `"HEAD~1"`               | Git ref `aalekhAffected` diffs from                                          |
| `affected.headRef`               | `String`  | `""`                     | Git ref to diff to; empty means the working tree                             |
| `mermaid.focus(...)`             | `vararg String` | none               | Module patterns to centre the Mermaid/DOT export on; see [filters](tasks.md#focus-and-exclude-filters) |
| `mermaid.exclude(...)`           | `vararg String` | none               | Module patterns to omit from the Mermaid/DOT export, applied after `focus`   |
| `mermaid.depth(...)`             | `Int`     | `1`                      | How many dependency hops around a focused module to include                  |
| `qualityGates.severity`          | `Severity`| `ERROR`                  | Severity of a `metric-regression` violation; see [quality gates](rules.md#quality-gates) |

## Captured Configurations

Aalekh captures both inter-module project dependencies **and** external (third-party) dependencies
from the following Gradle configurations. External dependencies are read as declared coordinates
(`group:name:version`) at configuration time - no dependency resolution is triggered - and are shown
per module in the report's inspector. Set `includeExternalDependencies = false` to skip them; the
`includeTestDependencies` and `includeCompileOnlyDependencies` flags apply to external dependencies
too.

**Production** (always captured):

| Configuration    | Notes                                                 |
|------------------|-------------------------------------------------------|
| `implementation` | Standard implementation dependency                    |
| `api`            | Leaks to consumers of the declaring module            |
| `compileOnly`    | Captured when `includeCompileOnlyDependencies = true` |
| `runtimeOnly`    | Runtime-only dependency                               |

**Test** (captured when `includeTestDependencies = true`):

| Configuration               | Notes                                  |
|-----------------------------|----------------------------------------|
| `testImplementation`        | JVM/Android unit test dependency       |
| `testApi`                   | Test API dependency                    |
| `testCompileOnly`           | Test compile-only                      |
| `testRuntimeOnly`           | Test runtime-only                      |
| `androidTestImplementation` | Android instrumented test dependency   |
| `androidTestRuntimeOnly`    | Android instrumented test runtime-only |
| `debugImplementation`       | Android debug build type               |
| `releaseImplementation`     | Android release build type             |

**KMP source sets** (captured automatically):

Any configuration whose name ends with `Implementation`, `Api`, `CompileOnly`, or `RuntimeOnly`
and is not a standard configuration above is treated as a KMP source set configuration. Examples:
`commonMainImplementation`, `androidMainApi`, `iosMainCompileOnly`, `jvmTestImplementation`.

The source set name is extracted from the configuration name (e.g. `commonMainImplementation`
→ source set `commonMain`) and stored on the edge for display in the graph.

For multiplatform modules, Aalekh also records the module's **full** source-set list (`commonMain`,
`androidMain`, `iosMain`, their test counterparts, and so on) by reading the Kotlin extension
reflectively during extraction. Non-multiplatform modules record none. The list appears in the
module inspector.

## What else extraction records

Beyond modules, edges, and external coordinates, `aalekhExtract` records:

| Fact | Source | Notes |
|------|--------|-------|
| **Build file path** | `Project.getBuildFile()` | The real location, so a module that does not follow the `:a:b` → `a/b/build.gradle.kts` convention still resolves. Used in violation messages and to map changed files to modules in the git-driven analyses. |
| **Declaration line** | A scan of the module's build file | The 1-based line each `project(...)` dependency is declared on. Recognises `project(":a:b")`, `project(':a:b')`, and the type-safe `projects.a.b` accessor. Reported as `file:line` in cycle-break advice. Null when the declaration cannot be located - never a guessed line. |
| **KMP source sets** | The Kotlin extension, reflectively | Multiplatform modules only. |
| **Module health score** | Computed from the finished graph | See [Metrics](metrics.md#architecture-health-scores). |

The build files are declared as task inputs, so reordering two dependency declarations correctly
re-runs extraction rather than leaving stale line numbers in a cached result.

## Module Types

Aalekh infers the module type from applied plugin class names. Detection runs in priority order -
first match wins.

| Module Type           | Detected when plugin is applied                      | Graph color |
|-----------------------|------------------------------------------------------|-------------|
| `KMP`                 | `org.jetbrains.kotlin.multiplatform`                 | Purple      |
| `KMP_ANDROID_LIBRARY` | `com.android.kotlin.multiplatform.library`           | Teal        |
| `ANDROID_APP`         | `com.android.application`                            | Blue        |
| `ANDROID_LIBRARY`     | `com.android.library`, `com.android.dynamic-feature` | Green       |
| `JVM_LIBRARY`         | `org.jetbrains.kotlin.jvm`, `java-library`, `java`   | Amber       |
| `UNKNOWN`             | *(fallback - no known plugin applied)*               | Gray        |

## Convention plugins and `build-logic`

Aalekh detects module types from the plugin classes actually *applied* to each module, not from the
plugin IDs written in your build scripts. A convention plugin (for example `myapp.android.library`
living in an included `build-logic`) applies the underlying `com.android.library` plugin, so the
real `LibraryPlugin` is on the module and detection resolves it to `ANDROID_LIBRARY`. The convention
wrapper is transparent - there is nothing to configure. Dependencies added inside a convention
plugin (`implementation(project(":core:common"))`) land on the module's configurations like any
other and are captured as normal graph edges.

The `build-logic` composite itself is **not** part of the graph. Aalekh only walks the main build's
subprojects; an included build applied via `includeBuild("build-logic")` to supply convention
plugins is excluded, which is what you want - the graph shows your application's architecture, not
the tooling that builds it.

**Limitation - source-substituting composite builds.** A dependency on a module from a *different*
included build that is wired via dependency substitution (`includeBuild("../shared-lib")` used as a
source replacement for a published coordinate) resolves as an external module dependency, not a
project dependency. It appears under external dependencies in the inspector rather than as a graph
edge to that module. Single-build projects that use `build-logic` only for convention plugins - the
common Android/KMP shape - are fully covered.

## Declaring what Aalekh cannot infer

Aalekh works out a great deal from module paths, applied plugins, and the dependency graph, and every
inference is a chance to be wrong. `.aalekh/modules.json` is the way out: whatever a team states there
is an **observed fact** that overrides any guess. A module's *purpose* in particular can only come
from a human - no amount of graph analysis reveals why a module exists.

```json
{
  "modules": [
    {
      "path": ":core:sync",
      "purpose": "Owns the offline queue and conflict resolution. Everything that writes while
                  offline goes through here.",
      "owner": "platform-team",
      "layer": "core",
      "status": "frozen",
      "links": { "Design doc": "https://example.com/adr-14" }
    }
  ]
}
```

| Field | Effect |
|-------|--------|
| `path` | The module this describes. Required; entries without one are ignored. |
| `purpose` | Shown first in the module inspector and in `modules.md`. The only source of intent Aalekh has. |
| `owner` | Overrides both `teams { }` and `CODEOWNERS` for this module. |
| `layer` | Overrides layer matching for this module. |
| `status` | A lifecycle marker such as `experimental`, `deprecated`, or `frozen`. |
| `links` | Named URLs - design docs, dashboards, ADRs - rendered in the inspector. |

Every field is optional and the file is entirely optional. Describing the eight modules newcomers
keep asking about, and nothing else, is a perfectly good use of it. A malformed file produces a
warning and is treated as absent: documentation must never fail a build.

## Ownership without configuration

If the repository has a `CODEOWNERS` file, Aalekh reads it - `.github/`, the root, `docs/`, and
`.gitlab/` are all checked - and uses it for module ownership. Most projects that care about
ownership already have one, so this is a team map for free.

Where more than one source claims a module, the most specific wins: `.aalekh/modules.json`, then
`teams { }`, then `CODEOWNERS`. Rules that Aalekh cannot model exactly (negation, brace expansion)
are skipped rather than half-applied, because assigning the wrong owner is worse than assigning none.

## Configuration Cache

Aalekh is fully compatible with Gradle's configuration cache, which is enabled by default in
Gradle 9.x.

All task inputs are `@Input` primitives, maps, or `@InputFile` paths captured via provider lambdas
at configuration time. No live `Project`, `Configuration`, or `Dependency` objects are stored in
any task action. The `aalekhExtract` task is `@CacheableTask`, so it is skipped UP-TO-DATE when
nothing has changed.

The intermediate `build/tmp/aalekh/graph.json` is the serialization boundary between the
configuration phase (graph extraction) and the execution phase (report and check tasks).
