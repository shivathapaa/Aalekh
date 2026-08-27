# Aalekh sample consumer

A small, self-contained multi-module project that applies Aalekh, so you can see every task and the
`aalekh { }` DSL working end to end without publishing anything.

The module graph is a clean one-way layering:

```
:app  →  :feature:login  →  :core:data  →  :core:domain
   └────────────────────────────┴──────────────┘
```

`:core:domain` is an abstraction (interfaces + a sealed hierarchy); the other modules are concrete
consumers — a deliberate spread so the main-sequence report has something to show.

## How it is wired

This project is **not** part of the root Aalekh build (the root `settings.gradle.kts` does not
include it), so `./gradlew build` at the repo root ignores it. It resolves the plugin straight from
the sibling source build via `includeBuild("..")` in [`settings.gradle.kts`](settings.gradle.kts):
there is nothing to publish and no version to pin. Edit the plugin, re-run a task here, and your
change is picked up immediately.

## Try it

Run from the repository root, pointing Gradle at this directory with `-p sample`:

```bash
./gradlew -p sample aalekhReport        # interactive HTML report → sample/build/reports/aalekh/index.html
./gradlew -p sample aalekhCheck         # evaluate the architecture rules configured in build.gradle.kts
./gradlew -p sample aalekhMermaid       # diffable Mermaid + DOT export (focused on :feature:login)
./gradlew -p sample aalekhMainSequence  # abstractness / instability / distance per module
./gradlew -p sample aalekhMetrics       # run any custom MetricProvider on the classpath
```

Open `sample/build/reports/aalekh/index.html` to explore the graph, the layer swimlane, the team
overlay, and the KPI panel. See the root [`README.md`](../README.md) and [`docs/`](../docs/README.md)
for the full reference.
