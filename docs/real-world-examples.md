# Real-world examples

Aalekh is dogfooded against real multi-module projects. The sample reports linked from the
[README](../README.md#sample-reports) are generated from the configurations below. Each one is a
copy-pasteable reference for a common project shape - a large Android app, a Kotlin/Compose
Multiplatform app with several product shells, and a Compose Multiplatform app with private
dependency repositories.

| Project | Type | Modules | Edges | External deps | Rules | `aalekhCheck` |
|---|---|---:|---:|---:|---:|---|
| [Now in Android](https://github.com/android/nowinandroid) | Android | 44 | 121 | 406 | 9 | passes (WARNING/INFO only) |
| [Now in Android - with cycle](#now-in-android---cyclic-dependency-demo) | Android | 44 | 122 | 406 | 9 | **fails** (`no-cyclic-dependencies`) |
| Tallyo | KMP + Compose MP | 128 | 523 | 1616 | 5 | passes |
| GeoKrishiFarm | Compose MP | 51 | 341 | 856 | 6 | passes |

Counts come from the `aalekhExtract` log line (e.g. `Aalekh extracted 44 modules, 121 edges,
406 external deps`); the rule count is the number of distinct rules shown in the report's Rules tab.

## Applying the plugin

For every project the wiring is identical - the settings plugin plus a local Maven repository while
Aalekh is consumed from `publishToMavenLocal` rather than the Gradle Plugin Portal:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()          // remove once consuming from the Plugin Portal
        google { /* ... */ }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("io.github.shivathapaa.aalekh") version "0.7.0"
}
```

The `aalekh { }` configuration block goes in the root `build.gradle.kts`.

## Now in Android

NIA splits every feature into a thin `:feature:*:api` contract and a `:feature:*:impl`
implementation, so features integrate only through each other's `api` module. The config encodes
NIA's layered architecture, guards the api/impl split with feature isolation, and layers on a set of
reachability and hygiene rules - nine rules in total, all green on the clean graph.

```kotlin
// build.gradle.kts (root)
aalekh {
    openBrowserAfterReport.set(false)
    exportMetrics.set(true)

    // foundation -> data -> ui / domain -> feature -> app
    // canOnlyDependOn lists the layers a module may reach; same-layer edges are always allowed.
    layers {
        layer("foundation") {
            modules(":core:model", ":core:common", ":core:navigation", ":core:datastore-proto")
        }
        layer("data") {
            modules(
                ":core:network", ":core:database", ":core:datastore",
                ":core:notifications", ":core:analytics", ":core:data",
            )
            canOnlyDependOn("foundation")
        }
        layer("ui") {
            modules(":core:designsystem", ":core:ui")
            canOnlyDependOn("foundation", "data")
        }
        layer("domain") {
            modules(":core:domain")
            canOnlyDependOn("foundation", "data")
        }
        layer("feature") {
            modules(":feature:**")
            canOnlyDependOn("foundation", "data", "ui", "domain")
        }
        layer("app") {
            modules(":app", ":app-nia-catalog")
            canOnlyDependOn("foundation", "data", "ui", "domain", "feature")
        }
    }

    // Feature impl modules must never depend on each other's impl - only on :feature:*:api.
    featureIsolation {
        featurePattern = ":feature:*:impl"
    }

    teams {
        team("core") { modules(":core:**") }
        team("features") { modules(":feature:**") }
        team("app") { modules(":app", ":app-nia-catalog") }
    }

    rules {
        // Height / fan-out budgets (WARNING).
        maxGraphHeight(10)
        noTransitiveDependenciesExceeding(45)

        // Dead-weight detection (WARNING). Gradle materialises empty container projects for nested
        // paths (`:feature:bookmarks` for `:feature:bookmarks:api`, etc.), and lint/benchmarks hang
        // off non-production configs; suppress those so only genuine isolated modules would surface.
        noOrphanModules()
        rule("no-orphan-modules") {
            suppressFor(":core")
            suppressFor(":feature")
            suppressFor(":feature:*")
            suppressFor(":sync")
            suppressFor(":benchmarks")
            suppressFor(":lint")
        }

        // Reachability guards (forbidden-transitive-dependency, ERROR).
        forbidReachable(from = ":core:model", to = ":app", because = "the model layer stays independent of the app")
        forbidReachable(from = ":core:data", to = ":core:ui", because = "the data layer must not reach the UI layer")
        forbidReachable(from = ":core:designsystem", to = ":core:data", because = "the design system must not reach data")

        // Every feature implementation must be wired into the app (unreachable-module, ERROR).
        mustBeReachableFrom(module = ":feature:*:impl", from = ":app", because = "an unreachable feature is dead code")
    }

    // Inline predicate rule: features are leaves and must never depend on the app shell.
    forbid {
        from(":feature:**")
        to(":app")
        because("features are independent slices; depending on the app inverts the graph")
    }
}
```

The Rules tab then lists nine rules - `layer-dependency`, `no-feature-to-feature`,
`forbidden-transitive-dependency` (×3), `unreachable-module`, `forbidden-dependency`,
`max-graph-height`, `max-transitive-dependencies`, `no-orphan-modules`, plus the always-on
`no-cyclic-dependencies`. `./gradlew aalekhCheck` passes: no `ERROR` violations, and the WARNING /
INFO rules are clean too.

### Now in Android - cyclic dependency demo

To show cycle detection, add one edge from the low side of an existing dependency, closing a loop
(`:core:ui` already depends on `:core:designsystem`):

```kotlin
// core/designsystem/build.gradle.kts - demo only, do NOT keep this
dependencies {
    implementation(projects.core.ui)   // creates :core:ui -> :core:designsystem -> :core:ui
}
```

`./gradlew aalekhCheck` then fails, and the report's Rules tab shows `no-cyclic-dependencies` in red
(1 violation) while every other rule stays green:

```
Aalekh [no-cyclic-dependencies] ERROR - 1 violation(s):
  x Cyclic dependency in main code: :core:ui -> :core:designsystem. Extract the shared logic into a new module.

Aalekh: 1 error(s) found across 11 rule(s).
Aalekh: to break the detected cycle(s), consider removing:
  * api(project(":core:designsystem")) in core/ui/build.gradle.kts
```

`no-cyclic-dependencies` is built-in and always active. Cycle detection considers main-source edges
only (`!isTest`); test-only cycles are reported separately and never fail the build.

## Tallyo (KMP + Compose Multiplatform)

Tallyo ships several products (main app plus chess, counter, spinbottle, callbreak, truthordare
shells) that share `:core:**` and `:feature:**` modules. It has no api/impl split, so features
depend on each other within a family; a coarse three-layer rule captures the one-way flow while
allowing those same-layer feature edges, and reachability guards keep the core leaves pure.

```kotlin
aalekh {
    openBrowserAfterReport.set(false)
    exportMetrics.set(true)

    // :core:** -> :feature:** -> app shells (:apps:**, :composeApp, :androidApp)
    layers {
        layer("core") {
            modules(":core:**")
        }
        layer("feature") {
            modules(":feature:**")
            canOnlyDependOn("core")
        }
        layer("app") {
            modules(":apps:**", ":composeApp", ":androidApp")
            canOnlyDependOn("core", "feature")
        }
    }

    teams {
        team("core") { modules(":core:**") }
        team("features") { modules(":feature:**") }
        team("apps") { modules(":apps:**", ":composeApp", ":androidApp") }
    }

    rules {
        maxGraphHeight(12)

        // Core leaves stay leaves - never reaching up into features or the app shells.
        forbidReachable(from = ":core:logger", to = ":composeApp", because = "logging stays app-agnostic")
        forbidReachable(from = ":core:datastore", to = ":feature:**", because = "the datastore core must not reach features")
        forbidReachable(from = ":core:resources", to = ":feature:**", because = "shared resources stay feature-agnostic")
    }

    // Inline predicate: shared features must never depend on a product app shell.
    forbid {
        from(":feature:**")
        to(":apps:**")
        because("features are shared slices; depending on a product shell inverts the graph")
    }
}
```

`./gradlew aalekhCheck` reports all five rules passing. Dependencies declared inside KMP
`sourceSets { commonMain.dependencies { ... } }` are captured the same way as flat `dependencies { }`
blocks - the extractor reads every captured configuration (`commonMainImplementation`,
`androidMainImplementation`, and so on), so all 1616 external coordinates surface in the report.

## GeoKrishiFarm (Compose Multiplatform)

GeoKrishiFarm is a Compose Multiplatform app with a clean `core -> feature -> app` layering (no
back-edges) and lateral feature-to-feature edges, so it uses layers plus reachability guards but no
feature isolation. Two intentionally detached modules (`:baselineprofile`, and a not-yet-wired
`:feature:marketprice`) are suppressed from the orphan check.

```kotlin
aalekh {
    openBrowserAfterReport.set(false)
    exportMetrics.set(true)

    layers {
        layer("core") { modules(":core:**") }
        layer("feature") {
            modules(":feature:**")
            canOnlyDependOn("core")
        }
        layer("app") {
            modules(":composeApp", ":androidApp", ":baselineprofile")
            canOnlyDependOn("core", "feature")
        }
    }

    teams {
        team("core") { modules(":core:**") }
        team("features") { modules(":feature:**") }
        team("app") { modules(":composeApp", ":androidApp", ":baselineprofile") }
    }

    rules {
        maxGraphHeight(15)

        noOrphanModules()
        rule("no-orphan-modules") {
            suppressFor(":core"); suppressFor(":core:analytics")
            suppressFor(":feature"); suppressFor(":feature:home"); suppressFor(":feature:bittyasewa")
            suppressFor(":baselineprofile"); suppressFor(":feature:marketprice")
        }

        // Pure core leaves must never reach up into features.
        forbidReachable(from = ":core:logger", to = ":feature:**", because = "logging stays feature-agnostic")
        forbidReachable(from = ":core:models", to = ":feature:**", because = "the model core stays feature-agnostic")
        forbidReachable(from = ":core:resources", to = ":feature:**", because = "shared resources stay feature-agnostic")
    }

    forbid {
        from(":feature:**")
        to(":composeApp")
        because("features are shared slices; depending on the app shell inverts the graph")
    }
}
```

Extraction reads declared coordinates only and never resolves, so the private GitHub Packages /
GitLab / flatDir repositories GeoKrishiFarm uses are irrelevant to `aalekhReport`; the report
generates offline, lists all 856 external dependencies, and all six rules pass.

## Notes

- Extraction never triggers dependency resolution - declared `group:name:version` is read at
  configuration time, keeping the run configuration-cache safe and offline.
- The **Rules** tab lists every configured rule with its severity and a live pass/violation count,
  so the same config renders green on a healthy graph and flags exactly what broke on a bad one (see
  the cyclic demo above).
- The **External dependencies (n)** section appears per module in the inspector, and only for
  modules that declare external dependencies. Structural parent nodes (`:core`, `:feature`) and
  project-only leaves show nothing.
- The top-level **Dependencies** tab aggregates the same data project-wide - one row per
  `group:name`, sorted by module usage, with version-conflict flagging. In these samples Tallyo
  surfaces 1616 declarations and GeoKrishiFarm 856, so the tab is the fastest way to spot a library
  pulled in at several versions.
- Opt out of external-dependency capture with `aalekh { includeExternalDependencies = false }`. The
  `includeTestDependencies` and `includeCompileOnlyDependencies` flags apply to external
  dependencies too.
