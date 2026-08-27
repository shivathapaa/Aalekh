# Architecture Rules

[← Documentation index](README.md) · [Project README](../README.md)

## Built-in rules

| Rule ID                       | Severity  | Description                                                          |
|-------------------------------|-----------|----------------------------------------------------------------------|
| `no-cyclic-dependencies`      | `ERROR`   | The module dependency graph must be a DAG (no production cycles)     |
| `layer-dependency`            | `ERROR`   | Modules must only depend on modules in their declared allowed layers |
| `no-feature-to-feature`       | `ERROR`   | Feature modules must not depend on each other                        |
| `max-transitive-dependencies` | `WARNING` | Modules must not exceed the configured transitive dependency limit   |
| `max-graph-height`            | `WARNING` | The longest dependency chain must not exceed the configured height   |
| `no-orphan-modules`           | `WARNING` | Modules must not be isolated (zero fan-in and zero fan-out)          |
| `uncovered-module`            | `WARNING` | Every module must belong to a declared `layers { }` layer            |
| `kmp-common-main-platform-dependency` | `ERROR` | A KMP module's `commonMain` must not depend on a platform-only (JVM/Android) module |
| `source-set-dependency`       | `ERROR`   | A `forbidSourceSetDependency(...)` rule: a named source set must not depend on the selected modules |
| `forbidden-dependency`        | `ERROR`   | A `forbid { }` predicate rule: the *from* modules must not depend on the *to* modules |
| `forbidden-transitive-dependency` | `ERROR` | A `forbidReachable(...)` rule: *from* must not **transitively** reach *to* |
| `unreachable-module`          | `ERROR`   | A `mustBeReachableFrom(...)` rule: *module* must be reachable from a declared root |
| `metric-regression`           | `ERROR`   | A quality-gated metric must not exceed the committed baseline value  |

`max-transitive-dependencies`, `max-graph-height`, `no-orphan-modules`, and `uncovered-module` are
inactive until you opt in from the `rules { }` block (`kmp-common-main-platform-dependency` too);
`forbidden-dependency`, `forbidden-transitive-dependency`, `unreachable-module`, `source-set-dependency`,
and `metric-regression` are active only when you declare the corresponding `forbid { }`,
`forbidReachable(...)`, `mustBeReachableFrom(...)`, `forbidSourceSetDependency(...)`, or
`qualityGates { }` rule. The first three rules
(`no-cyclic-dependencies`, `layer-dependency`, `no-feature-to-feature`) are always on.

## Violation severity levels

| Severity  | Effect                                                        |
|-----------|---------------------------------------------------------------|
| `ERROR`   | Fails the build. Printed to stderr.                           |
| `WARNING` | Printed to stdout. Build continues.                           |
| `INFO`    | Silently collected. Visible in the HTML report and JSON only. |

## Layer enforcement

Declare layers and enforce the direction of dependencies between them. Module patterns support
`*` (one path segment) and `**` (any number of segments).

```kotlin
aalekh {
    layers {
        layer("domain") {
            modules(":core:domain", ":feature:*:domain")
            // No canOnlyDependOn = no restriction; domain may depend on nothing
        }
        layer("data") {
            modules(":core:data", ":feature:*:data")
            canOnlyDependOn("domain")
        }
        layer("presentation") {
            modules(":feature:*:ui", ":app")
            canOnlyDependOn("domain", "data")
        }
    }
}
```

A layer without `canOnlyDependOn(...)` has no dependency restriction. Any layer that calls
`canOnlyDependOn(...)` is restricted to only depend on the listed layers; a dependency on any
module outside those layers is a `layer-dependency` violation.

When a violation is found, the message names the exact build file and dependency to remove:

```
Aalekh [layer-dependency] :feature:login:data (layer 'data') depends on
:feature:login:ui (layer 'presentation'). Layer 'data' may only depend on:
domain. Edit feature/login/data/build.gradle.kts and remove:
implementation(project(":feature:login:ui"))
```

## Feature isolation

Prevent feature modules from depending on each other. Specific pairs can be explicitly allowed.

```kotlin
aalekh {
    featureIsolation {
        featurePattern = ":feature:**"
        allow(from = ":feature:shared", to = ":feature:*")
    }
}
```

`featurePattern` is a glob matching all feature modules. The rule is inactive when the pattern is
not set. Explicit `allow(from, to)` pairs are exempt and do not produce violations.

## Team ownership

Map team names to module path glob patterns. Team assignments appear as a colour overlay in the
graph and are shown in the Module Inspector sidebar. Cross-team dependency edges are annotated
separately so reviewers can quickly identify dependencies that cross ownership boundaries.

```kotlin
aalekh {
    teams {
        team("auth-team") { modules(":feature:login:**", ":core:auth") }
        team("data-team") { modules(":data:**") }
        team("platform") { modules(":core:**") }
    }
}
```

Module path patterns support `*` (one path segment) and `**` (any number of segments). A module
belongs to the first team whose pattern matches - teams are evaluated in declaration order.

## Gradual adoption

Teams migrating an existing codebase can adopt rules gradually - start with warnings, fix
violations, then promote to errors when the codebase is clean:

```kotlin
aalekh {
    rules {
        rule("layer-dependency") {
            severity = Severity.WARNING   // see violations without blocking CI
            suppressFor(":legacy:**")     // exempt a known legacy subtree entirely
        }
    }
}
```

`suppressFor` accepts a glob pattern. Any module path matching the pattern is excluded from that
rule's evaluation. Multiple `suppressFor` calls on the same rule accumulate.

## Transitive dependency limit

Fail or warn when a module pulls in too many hidden transitive dependencies:

```kotlin
aalekh {
    rules {
        noTransitiveDependenciesExceeding(30)
    }
}
```

The default severity is `WARNING`. Override with:

```kotlin
aalekh {
    rules {
        noTransitiveDependenciesExceeding(30)
        rule("max-transitive-dependencies") { severity = Severity.ERROR }
    }
}
```

## Graph height limit

Cap the longest chain of production dependencies (the graph *height*). Graph height is the floor on
build parallelism: a height of 8 means at least 8 sequential compile steps no matter how many cores
you have. Keeping it low keeps builds fast.

```kotlin
aalekh {
    rules {
        maxGraphHeight(6)
    }
}
```

The default severity is `WARNING`; promote it with `rule("max-graph-height") { severity = Severity.ERROR }`.
A graph with a production cycle has no well-defined height, so the rule stays silent in that case -
the cycle rule handles it.

## Orphan modules

Flag modules that neither depend on anything nor are depended on by anything over production edges.
An orphan is compiled on every build but never consumed - either abandoned code to delete or a module
that was never wired in. Test-only edges do not rescue a module from orphan status.

```kotlin
aalekh {
    rules {
        noOrphanModules()
    }
}
```

The default severity is `WARNING`; promote it with `rule("no-orphan-modules") { severity = Severity.ERROR }`.

## Exhaustive layer coverage

Layer enforcement only holds if *every* module is actually placed in a layer. A module matched by
none of the `layers { }` patterns slips past `layer-dependency` entirely - it can depend on anything
and nothing constrains what depends on it. `requireLayerForAllModules()` makes coverage exhaustive:
any module in no declared layer is reported under `uncovered-module`.

```kotlin
aalekh {
    layers {
        layer("domain") { modules(":core:domain", ":feature:*:domain") }
        layer("data") { modules(":core:data", ":feature:*:data"); canOnlyDependOn("domain") }
        layer("presentation") { modules(":feature:*:ui", ":app"); canOnlyDependOn("domain", "data") }
    }
    rules {
        requireLayerForAllModules()
    }
}
```

The default severity is `WARNING`; promote it with
`rule("uncovered-module") { severity = Severity.ERROR }`. The rule does nothing until at least one
layer is declared - there is no coverage map to check against otherwise.

## Reachability rules

`forbid { }` and `layer-dependency` check *direct* edges. Two rules reason over the full production
**reachability closure** - every chain of production dependencies, however long.

**`forbidReachable(from, to)`** - modules matching `from` must not *transitively* reach modules
matching `to`. Catches the indirect leak a direct-edge rule misses: `:core:domain` pulling in an
Android module three hops away through an innocent-looking utility. Reports
`forbidden-transitive-dependency`.

```kotlin
aalekh {
    rules {
        forbidReachable(
            from = ":core:domain",
            to = ":platform:android",
            because = "the domain layer must stay platform-agnostic, even indirectly",
        )
    }
}
```

**`mustBeReachableFrom(module, from)`** - every module matching `module` must be reachable from a
module matching `from` over production edges. Use it to prove modules are wired into an entry point:
"every `:feature:*` must be reachable from `:app`". A feature no production path from `:app` reaches
is dead in that entry point - it compiles but ships to nobody. It is the targeted, root-relative
counterpart to `no-orphan-modules`. Reports `unreachable-module`.

```kotlin
aalekh {
    rules {
        mustBeReachableFrom(
            module = ":feature:*",
            from = ":app",
            because = "every feature must be wired into the app",
        )
    }
}
```

Both default to `ERROR`; pass `severity = Severity.WARNING` to only report. Both follow production
edges only - a `testImplementation`-only path does not count as reachable. The `from`/`to`/`module`
selectors are path globs (`*`, `**`); like every rule, they are serialized to a
configuration-cache-safe string, never captured as a lambda.

## KMP source-set rules

Aalekh knows the owning source set of each dependency edge (from the Kotlin Multiplatform
configuration name, e.g. `commonMainImplementation` → `commonMain`), so it can enforce
multiplatform-safety rules at the module-graph level without a compiler.

`noCommonMainPlatformDependencies()` forbids a KMP module's **`commonMain`** from depending on a
platform-only module. `commonMain` is compiled for *every* target the module declares, so it can only
reference dependencies that exist on all of them - a `commonMain` dependency on a JVM-only or
Android-only module does not compile for the other targets and silently locks the module to one
platform.

```kotlin
aalekh {
    rules {
        noCommonMainPlatformDependencies()
    }
}
```

Only `commonMain`-owned edges are checked, and only when the target is genuinely single-platform
(`ANDROID_APP`, `ANDROID_LIBRARY`, `JVM_LIBRARY`); depending on another multiplatform module (`KMP`,
`KMP_ANDROID_LIBRARY`) is allowed, and `UNKNOWN`-typed targets are skipped to avoid false positives.
Reports `kmp-common-main-platform-dependency` at `ERROR` (promote/demote with
`rule("kmp-common-main-platform-dependency") { severity = Severity.WARNING }`). The fix is to move the
dependency to the matching platform source set (e.g. `androidMain`) or make the target module
multiplatform.

### Per-source-set direction rules

`noCommonMainPlatformDependencies()` is the built-in special case of a more general rule:
`forbidSourceSetDependency(...)` forbids dependencies declared in **any** named source set from
targeting a selected set of modules. Because every edge records its owning source set, this enforces
arbitrary per-source-set direction constraints with no compiler:

```kotlin
aalekh {
    rules {
        // iOS code must never pull in an Android module.
        forbidSourceSetDependencyOnType(sourceSet = "iosMain", moduleType = ModuleType.ANDROID_LIBRARY)

        // androidMain must not reach the desktop-only platform modules.
        forbidSourceSetDependency(sourceSet = "androidMain", to = ":platform:desktop:**")
    }
}
```

Use `forbidSourceSetDependency(sourceSet, to)` for a path glob and
`forbidSourceSetDependencyOnType(sourceSet, moduleType)` for a module-type target. Only production
edges owned by the given source set are checked (a self-loop never counts). All instances report the
stable `source-set-dependency` id at `ERROR` by default; pass `severity = Severity.WARNING` (or
`because = "..."`) to adjust. Note that expect/actual *matching* itself stays the Kotlin compiler's
job - Aalekh operates at the module-graph level, so it enforces *direction*, not type-level
expect/actual resolution.

## Cycle regression prevention

Once a project is cycle-free, lock that state in so new cycles can never be introduced silently:

```kotlin
aalekh {
    rules {
        rule("no-cyclic-dependencies") {
            preventRegression = true
        }
    }
}
```

When enabled, `aalekhCheck` reads the main-code cycle count from the previous run's
`aalekh-results.json`. If the count increased, the build fails immediately - even if cycles
already existed before. No baseline file to commit, no manual setup. The previous run's output
is the baseline.

## Baseline / freeze

`preventRegression` above is cycle-specific. For **every** rule at once, use a committed baseline:
freeze the violations that exist today and fail the build only on new ones. This is how you turn on
strict rules against a large existing codebase without fixing everything first.

```bash
# 1. Record the current violations and commit the file.
./gradlew aalekhBaseline
git add aalekh-baseline.json && git commit -m "chore: freeze Aalekh baseline"
```

From then on, `aalekhCheck` suppresses every violation recorded in the baseline and fails only on
new ones:

```
Aalekh: 12 baselined violation(s) suppressed (from aalekh-baseline.json).
Aalekh: ✓ All rules passed
```

- The baseline stores one stable fingerprint (`ruleId|source`) per non-`INFO` violation, sorted so
  the file diffs cleanly.
- Re-run `aalekhBaseline` after you legitimately fix or accept violations to refresh it.
- Delete `aalekh-baseline.json` to stop applying the baseline entirely.
- A missing or malformed baseline simply disables the freeze - it never fails the build.
- Change the path with `baselineFile` in the [configuration](configuration.md) block.

Baseline and `preventRegression` are complementary: the baseline freezes the current set of
violations, while `preventRegression` additionally guards the cycle count.

## SARIF output for GitHub PR annotations

`aalekhCheck` writes `aalekh-results.sarif` on every run. Upload it in your GitHub Actions
workflow and violations appear as inline annotations directly on the pull request diff:

```yaml
- name: Run architecture check
  run: ./gradlew aalekhCheck

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: build/reports/aalekh/aalekh-results.sarif
```

No token, no custom reporter, no extra setup.

## Code Climate output for GitLab Code Quality

`aalekhCheck` also writes `aalekh-codeclimate.json` on every run - the
[Code Climate](https://docs.gitlab.com/ee/ci/testing/code_quality.html) format GitLab's **Code
Quality** widget consumes. Point a `codequality` report artifact at it and every violation shows up
on the merge-request diff:

```yaml
aalekh:
  script: ./gradlew aalekhCheck
  artifacts:
    reports:
      codequality: build/reports/aalekh/aalekh-codeclimate.json
```

Severities map `ERROR → critical`, `WARNING → minor`, `INFO → info`; each issue carries a stable
fingerprint so GitLab can track it across pipelines. It is the GitLab counterpart to the SARIF report
Aalekh writes for GitHub.

## Predicate rules (`forbid { }`)

For the common "X must not depend on Y" case you don't need a custom rule class - declare it inline
with `forbid { }`. Each side is selected by path glob (`from` / `to`) or by module type
(`fromModuleType` / `toModuleType`):

```kotlin
import com.aalekh.aalekh.model.ModuleType
import com.aalekh.aalekh.model.Severity

aalekh {
    forbid {
        from(":core:domain")
        toModuleType(ModuleType.ANDROID_LIBRARY)
        because("the domain layer stays platform-agnostic so it can be shared via KMP")
    }
    forbid {
        from(":feature:**")
        to(":feature:**")
        because("features must not depend on each other")
        severity(Severity.WARNING)   // default ERROR
    }
}
```

Every predicate rule reports under the stable id `forbidden-dependency`; the `because(...)` reason
distinguishes them in the message and report. Predicates are serialized to a declarative form (never
a captured lambda), so they are configuration-cache safe. Reach for the full [custom rule](#custom-rules)
SPI when a rule needs logic a `from → to` predicate cannot express.

Add `apiOnly()` to forbid only the `api` configuration - an **API-leak** rule. The *from* module may
still depend on *to* via `implementation`; it just may not re-export it onto its own consumers. Use it
to stop a module widening its public surface:

```kotlin
aalekh {
    forbid {
        from(":core:public")
        to(":core:internal")
        apiOnly()
        because(":core:internal must stay an implementation detail, never re-exported")
    }
}
```

`apiOnly()` recognises the plain `api` configuration and KMP / build-type source-set variants
(`commonMainApi`, `androidMainApi`, `debugApi`, ...); test api configurations never count.

## Quality gates

Quality gates fail (or warn) `aalekhCheck` when a **structural metric got worse** than the committed
baseline - a one-directional ratchet so architecture quality cannot silently backslide. Enable them
in the `qualityGates { }` block; they compare against the `metrics` snapshot in `aalekh-baseline.json`
and therefore only fire once you have run [`aalekhBaseline`](tasks.md#aalekhbaseline).

```kotlin
import com.aalekh.aalekh.model.Severity

aalekh {
    qualityGates {
        forbidRegression("cycles", "ccd", "god-modules")   // or forbidAllRegressions()
        severity.set(Severity.ERROR)   // default ERROR
    }
}
```

Valid metric keys: `cycles`, `god-modules`, `ccd`, `tangle`, `instability`, `critical-path`. A
regression on any enabled metric becomes a `metric-regression` violation. Refresh the baseline with
`aalekhBaseline` after a legitimate, accepted change.

## Custom rules

Implement `ArchRule` from `aalekh-analysis` to create project-specific rules:

```kotlin
// Imports: com.aalekh.aalekh.analysis.rules.ArchRule, com.aalekh.aalekh.model.*

class NoAndroidInDomainRule : ArchRule {
    override val id = "no-android-in-domain"
    override val description = "Domain modules must not depend on Android libraries"
    override val defaultSeverity = Severity.ERROR
    override val plainLanguageExplanation =
        "The domain layer must stay platform-agnostic so it can be shared via KMP."

    override fun evaluate(graph: ModuleDependencyGraph): List<Violation> =
        graph.edges
            .filter { it.from.contains(":domain") }
            .filter { graph.moduleByPath(it.to)?.type == ModuleType.ANDROID_LIBRARY }
            .map { edge ->
                Violation(
                    ruleId = id,
                    severity = defaultSeverity,
                    message = "${edge.from} depends on Android module ${edge.to}. " +
                            "Move Android-specific code to the data or presentation layer.",
                    source = "${edge.from} → ${edge.to}",
                    moduleHint = edge.from,
                    plainLanguageExplanation = plainLanguageExplanation,
                )
            }
}
```

Register a custom rule from your `aalekh { rules { } }` block by fully qualified class name:

```kotlin
aalekh {
    rules {
        custom("com.example.NoAndroidInDomainRule")
    }
}
```

The class must implement `com.aalekh.aalekh.analysis.rules.ArchRule`, expose a public
no-argument constructor, and be reachable from the plugin's runtime classpath. Add the rule
artifact to the consumer's buildscript classpath:

```kotlin
// settings.gradle.kts
buildscript {
    dependencies {
        classpath("com.example:my-aalekh-rules:1.0.0")
    }
}
```

or include the rule module via `includeBuild`. If the class cannot be loaded or instantiated,
`aalekhCheck` surfaces a single `aalekh-custom-rule` ERROR violation with the cause instead of
crashing the build.
