# The Report

[← Documentation index](README.md) · [Project README](../README.md)

`./gradlew aalekhReport` produces `build/reports/aalekh/index.html` - a single self-contained file.
Open it in any browser: no server, no internet connection, no assets alongside it.

The report is built around a transit-map metaphor. Layers render as coloured lines, modules as stops,
god modules as interchange rings, and cycles as closed loops, in a dark (*Night Terminal*) and a light
(*Map Paper*) theme. Nine panels share a command palette, a keyboard shortcuts overlay, a print
stylesheet, and an inspector rail.

## Panels

**◧ Overview** - the landing tab, and the answer to "what is this project?".

It opens with a **plain-language summary** - size, entry points, what the project rests on, whether
the graph is sound - followed by **what to understand first**: a computed reading order for someone
who has never opened the codebase, starting where execution starts and spreading across the project
rather than walking one branch to the bottom.

Then **what the analysis found**: every finding, actionable ones first. A finding is one sentence
about the project with the measurements behind it, so `fan-in = 24` reads as *"24 modules depend on
this, more than half the project, so changes here are never local"*. Each carries its evidence with a
one-letter provenance mark - **O**bserved, **C**omputed, **I**nferred, **S**uggested - and anything
inferred or suggested is labelled with its confidence. Findings are assembled from templates, so the
same graph always produces the same words.

Below that: the health dial with its penalty breakdown, KPI cards with trend sparklines, service
alerts, hotspots, and the module-type mix. The health dial
shows the **project** health score and lists every penalty that produced it (cycles, blocking
violations, advisories, coupling hubs, average instability), so the headline number is inspectable
rather than a verdict - hover a row for the arithmetic. See
[Metrics](metrics.md#architecture-health-scores) for the formula.

**⬡ Map** - Topology at four levels of zoom:

- *Regions* - the project collapsed into groups, with the dependencies between them aggregated. Past
  roughly sixty modules this is the view that stays readable without interaction. Grouping follows a
  declared-first cascade - `layers { }`, then `teams { }`, then module path prefixes, then community
  detection over the dependency edges - and the panel states which one it used and whether that is
  observed or inferred. Each card shows how much of a region's coupling stays inside it, and a region
  too large to list flat breaks down into the groups it contains. The flow table underneath counts
  every module dependency crossing each boundary, heaviest first, and marks the pairs that depend on
  each other in both directions.
- *Architecture* - layer swimlane. One lane per architectural layer, making dependency direction
  violations immediately obvious. Lanes come from your declared `layers { }` block, in declaration
  order, resolved exactly as `aalekhCheck` resolves them (first matching pattern wins) - so a module
  is always drawn in the layer whose rules apply to it. A module matched by no declared layer gets
  its own **Unclassified** lane rather than being folded into an arbitrary one; that is the same
  condition the [`uncovered-module`](rules.md) rule reports. With no `layers { }` block declared,
  Aalekh infers lanes from module path segments (`app`, `feature`, `core`, `data`, `foundation`) and
  labels the toolbar chip **Inferred layers**. Hover the chip for the exact provenance.
- *Force* - interactive force-directed graph powered by D3.js. Drag to reposition nodes, scroll to
  zoom, click any node to open the Module Inspector in the sidebar. Nodes are coloured by module
  type; cycle nodes pulse with a red ring; god modules glow orange. Filter edges by type: Impl,
  API, Test, CompileOnly, KMP source sets, Main Cycle, Test Cycle. Hovering a node animates traffic
  along its edges to make dependency direction obvious at a glance.
- *Focus* - one module in the middle, what depends on it to the left, what it depends on to the
  right, with a depth slider. The node count follows the depth rather than the size of the project, so
  this is the one node-link view that stays legible at any size.

### Large projects

The report picks a landing view from project size: a force graph up to about 60 modules, regions
beyond that, and search-first past a few hundred, where no global node-link view is readable. Every
view remains available at every size; the default is only about what is worth showing first.

Measured on a 460-module project:

| | |
|---|---|
| Report size | 1.26 MB |
| Initial render | 1.5 s |
| Slowest panel | 2.7 s |

The report stays a single self-contained HTML file at every size.

**⊞ Browse** - Structural views with two toggleable subpanels:

- *Tree* - hierarchical tree mirroring your Gradle project structure. Expand and collapse groups,
  jump directly to cycle nodes, and see per-module dependency tables split by main vs test scope.
- *Matrix* - adjacency matrix showing all inter-module dependencies at a glance. Sort by
  connectivity, topological order, A–Z, or module type. Hover a cell for details; click a row or
  column label to inspect the module. In topological order, any dependency appearing in the lower
  triangle is a potential layer violation.

**◎ Health** - KPI dashboard: fan-in, fan-out, instability index, critical build
path, god module count, cycle counts (main and test-only separately). Each KPI card includes a
trend sparkline from the last 30 `aalekhReport` runs. Includes a **layer purity** table and a list
of consolidation candidates - module pairs that share many dependents and may be worth merging.
Per-module sortable table with inline bar charts, including each module's health score.

Layer purity reports, per layer, the share of its outgoing production edges that point at a
permitted layer. A layer that declares `canOnlyDependOn(...)` is measured against that allowlist -
the same test `aalekhCheck` applies - and the rest against declaration order. Same-layer edges are
counted in their own column but still divide into the total. The table names the same layers the
Map panel draws, and states whether they were declared or inferred.

**⬢ Dependencies** - Aggregate list of every external (third-party) library declared across the
project, keyed by `group:name` and sorted by how many modules use it. Each row shows the declared
version(s), a module-usage count (hover for the full module list and each module's version), and the
declaration types (api / impl / test) as colour-coded badges. Libraries pulled in at more than one
version are flagged as **version conflicts** - an amber left-edge and amber version pills - and
counted in the panel header, with a "Conflicts only" toggle and a group/name filter. Populated from
the same declared-coordinate data the inspector uses; empty when `includeExternalDependencies` is
off.

**⬒ Build** - the project inventory: what the project is made of, as opposed to how it is wired.
Every plugin applied across the project with its version and the modules using it (plugins declared
at more than one version are highlighted, since Gradle resolves only one); Java toolchains; Kotlin
Multiplatform targets; test source sets; the version catalogs, with each alias resolved to the plugin
id and version it actually points at; ownership from `CODEOWNERS`; and any module metadata declared
in `.aalekh/modules.json`. Everything here is read from the build, so none of it is inferred.

**⚖ Rules** - The active rule set. One card per architecture rule applied to the project - passing
and failing alike - so the enforced ruleset is visible even when the build is green. Each card shows
the rule ID, its effective severity (overrides applied), a plain-language explanation, and a
pass / *N issues* status; failing rules link straight to the Violations tab. Rules sharing an ID
(for example several `forbid { }` predicates) fold into one card that reports how many are
configured. When no rules are declared the panel explains that Aalekh is in visualization-only mode
and points to the configuration blocks that turn enforcement on.

**⚑ Violations** - Structured violation cards for every `aalekhCheck` rule failure. Each card shows
the rule ID, severity badge, the exact dependency edge to remove, a plain-language explanation of
why the rule exists, and a "View in Graph" button that navigates directly to the offending module.
Violation messages include the build file path of the offending dependency declaration.

When no violations exist and no layer rules are configured, the panel analyses your module paths
and suggests a ready-to-paste `layers { }` DSL block based on detected `domain`, `data`, and
`ui`/`presentation` patterns.

**⇄ Diff** - Snapshot comparison. Drag-drop a previous `graph.json` file onto the panel to see which
modules and edges were added or removed since that snapshot. The file is read locally - nothing is
uploaded anywhere. For the same comparison as a pull-request comment, see
[`aalekhDiff`](tasks.md#aalekhdiff).

## Header toolbar

The report header provides global tools available on every panel:

| Control                | Description                                                                                            |
|------------------------|-------------------------------------------------------------------------------------------------------|
| **Status chips**       | At-a-glance badges for cycles, blocking errors, and god modules; click to jump to the relevant panel  |
| **Module search**      | Search across all module paths; press `/` to focus                                                    |
| **⌘K Command palette** | Fuzzy jump to any module or run an action. `⌘K` on macOS, `Ctrl+K` elsewhere                           |
| **Theme toggle**       | Switch between dark and light themes; choice persists in `localStorage`                                |
| **? Shortcuts**        | Open the keyboard shortcuts overlay                                                                    |
| **⬇ JSON**             | Download the raw `graph.json` data                                                                     |
| **⬇ CSV**              | Download per-module metrics as CSV                                                                     |

Contextual controls live on the panel they act on. The **Map** panel carries its own toolbar:
*Heatmap* (colour nodes green → red by instability index), *Path* (shortest dependency path between
two modules, highlighted in the graph), *Export* (save the current Architecture, Force, or Matrix
view as SVG), and *Inspector* (toggle the inspector rail).

## Command palette and keyboard shortcuts

Press `⌘K` (macOS) or `Ctrl+K` (other platforms) to open the command palette. Type to fuzzy-match
any module path or run an action - toggle theme, print, download JSON/CSV/SVG, open the path
finder, switch panel. Navigate with `↑`/`↓`, confirm with `↵`, dismiss with `Esc`. Powered locally;
no network.

**Typed queries.** Past a couple of hundred modules, locating a module is a search problem rather than
a layout one. The palette accepts filters, which combine, and free text after them ranks the matches:

| Filter | Finds |
|--------|-------|
| `layer:core` | modules in a layer |
| `team:platform` | modules a team owns (from `teams { }`, `CODEOWNERS`, or declared metadata) |
| `type:KMP` | modules of a module type |
| `plugin:compose` | modules applying a plugin |
| `dep:okhttp` | modules declaring an external library |
| `uses::core:ui` | modules that depend on a module |
| `usedby::app` | modules a module depends on |
| `region:feature` | modules in a region |
| `cycle:yes` | modules inside a dependency cycle |
| `untested:yes` | modules with no test source set |
| `blast:>50` | modules whose blast radius exceeds a threshold |
| `instability:>0.8` | modules above an instability threshold |
| `influence:>2` | modules above an influence threshold |

`layer:data untested:yes` lists the untested data modules; `blast:>100 cycle:yes` lists the modules
that are both far-reaching and tangled. The palette lists the full vocabulary when it opens empty.

Press `?` at any time to open the keyboard shortcuts overlay:

| Shortcut    | Action                                                |
|-------------|-------------------------------------------------------|
| `⌘K` / `Ctrl+K` | Open command palette                              |
| `/`         | Focus module search                                   |
| `1`–`9`     | Switch to panel by index                              |
| `T`         | Toggle dark/light theme                               |
| `I`         | Toggle inspector sidebar (collapses to a narrow rail) |
| `F`         | Fit graph (Map panel)                                 |
| `R`         | Re-layout graph (Map panel)                           |
| `+` / `−`   | Zoom in / out (Map panel)                             |
| `Esc`       | Clear selection / dismiss overlay                     |
| `E` / `C`   | Expand / collapse all (Browse → Tree)                 |
| `P`         | Presentation mode; `→` / `←` to page, `Esc` to exit    |
| `⌘P` / `Ctrl+P` | Print or save the report as PDF                   |
| `J`         | Download `graph.json`                                 |
| `?`         | Toggle this overlay                                   |

## Presentation mode

Press `P` to step through the report one screen at a time: the project, its shape, its regions, where
to start reading, the risks, and the health score. The slides are built from the same findings the
Overview shows, so there is nothing to prepare or keep in sync. `→` / `←` page, `Esc` exits.

## Themes and print

**Dark + light themes.** The default tracks `prefers-color-scheme`; toggle in the header (`T`), and
the choice persists in `localStorage`. The Hanken Grotesk and Spline Sans Mono typefaces are
base64-inlined as `woff2`, so the report stays fully offline with no web-font request.

**Inspector rail.** When no module is selected, press `I` to collapse the right sidebar to a narrow
rail and reclaim the canvas. Selecting a module re-expands it.

**Print stylesheet.** `⌘P` / `Ctrl+P` switches the report to a paper-tinted layout (chrome and
floating overlays hidden, one panel per page) so the same HTML doubles as a deliverable PDF
artefact.

## URL permalink

The active panel, subpanel, and selected module are encoded into `location.hash` using the readable
scheme `#tab=NAME&sub=NAME&m=:path` (e.g. `#tab=map&sub=graph&m=:feature:login:ui`). Copy the
browser URL to share a specific view - the recipient lands on exactly the same panel with the same
module selected.

## Module Inspector sidebar

Click any node in the graph or explorer to open the module inspector in the right sidebar. It shows:

- **Purpose** - what the module is for, in the team's own words, when declared in
  `.aalekh/modules.json`. Aalekh measures a module's shape but cannot know its intent, so this is the
  only source for it. Shown first, above the metrics
- **Why this module matters** - one sentence stating what a change here costs (*"3 modules depend on
  this one, 50% of the project - a breaking change here is a change to all of them"*), then the
  metrics behind it: blast radius, influence, comprehension cost, depth, betweenness, API surface.
  Hover any of them for the question it answers and how to read it
- **Findings** - every narrative finding that names this module, with what to do about it
- **Build** - the plugins applied, the Java toolchain, KMP targets, and whether it has tests
- Module path and short name
- Module type badge (colour-coded)
- **Layer badge** - the layer this module belongs to. Hover it to see whether that layer was
  declared in `layers { }` or inferred from the module path
- Fan-in, fan-out, and transitive dependency count
- **Blast radius** - number of modules that transitively depend on this one (impact scope of a
  breaking change)
- Instability index bar (green = stable, yellow = mixed, red = unstable)
- Team owner (if configured via `teams { }`)
- KMP source sets (multiplatform modules only; read from the Kotlin extension during extraction)
- Direct dependencies and dependents, each clickable to navigate to that module
- **External dependencies** - the third-party libraries the module declares, each with its full
  `group:name` (wrapped onto its own line so long coordinates are never clipped), version, and
  declaration type (`api`/`impl`/`test`). Omitted when the module has no external dependencies or
  `includeExternalDependencies = false`. For a project-wide, conflict-aware view of the same data,
  see the **Dependencies** panel above

## Cycle detection

Aalekh distinguishes between two kinds of cycles:

- **Main cycles** (`⚠ red`) - circular dependencies in production code. These are genuine
  architectural errors that prevent independent builds and refactoring. `aalekhCheck` fails on these
  by default.
- **Test cycles** (`♻ pink`) - cycles that exist only through `testImplementation` or
  `androidTestImplementation`. These are common, usually acceptable, and do **not** cause a build
  failure.

Both kinds are visible in the Map (Force) graph, the Browse (Tree) view, and the Health panel.
Main and test cycle counts are reported separately in the KPI dashboard.
