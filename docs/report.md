# The Report

[← Documentation index](README.md) · [Project README](../README.md)

`./gradlew aalekhReport` produces `build/reports/aalekh/index.html`. Open it in any browser - no
server required, no internet connection needed. 0.5.1 rebuilt the report around a transit-map
metaphor — architectural tiers render as coloured lines, modules as stops, god modules as
interchange rings, and cycles as closed loops — with dark (*Night Terminal*) and light (*Map Paper*)
themes, inlined Hanken Grotesk + Spline Sans Mono typefaces (fully offline), and a network-status
Overview (health dial, route schematic, departures board, alerts, hotspots). It keeps the multi-panel
layout, command palette, keyboard shortcuts overlay, print stylesheet, and inspector rail.

## Panels

**◧ Overview** - KPI landing. Health hero, plus compact lists of hotspot modules, cycles, the
critical build path, and the module-type mix. Default tab when the report opens.

**⬡ Map** - Topology view with two toggleable subpanels:

- *Architecture* - layer swimlane. Modules grouped by their declared `layers { }` configuration and
  rendered as swim lanes, making dependency direction violations immediately obvious. Edge
  crossings that violate the declared layer order are highlighted.
- *Force* - interactive force-directed graph powered by D3.js. Drag to reposition nodes, scroll to
  zoom, click any node to open the Module Inspector in the sidebar. Nodes are coloured by module
  type; cycle nodes pulse with a red ring; god modules glow orange. Filter edges by type: Impl,
  API, Test, CompileOnly, KMP source sets, Main Cycle, Test Cycle. Hovering a node animates traffic
  along its edges to make dependency direction obvious at a glance.

**⊞ Browse** - Structural views with two toggleable subpanels:

- *Tree* - hierarchical tree mirroring your Gradle project structure. Expand and collapse groups,
  jump directly to cycle nodes, and see per-module dependency tables split by main vs test scope.
- *Matrix* - adjacency matrix showing all inter-module dependencies at a glance. Sort by
  connectivity, topological order, A–Z, or module type. Hover a cell for details; click a row or
  column label to inspect the module. In topological order, any dependency appearing in the lower
  triangle is a potential layer violation.

**◎ Health** - KPI dashboard (formerly Metrics): fan-in, fan-out, instability index, critical build
path, god module count, cycle counts (main and test-only separately). Each KPI card includes a
trend sparkline from the last 30 `aalekhReport` runs. Includes a per-layer purity table (percentage
of edges flowing in the correct declared direction) and a list of consolidation candidates - module
pairs that share many dependents and may be worth merging. Per-module sortable table with inline
bar charts.

**⬢ Dependencies** - Aggregate list of every external (third-party) library declared across the
project, keyed by `group:name` and sorted by how many modules use it. Each row shows the declared
version(s), a module-usage count (hover for the full module list and each module's version), and the
declaration types (api / impl / test) as colour-coded badges. Libraries pulled in at more than one
version are flagged as **version conflicts** - an amber left-edge and amber version pills - and
counted in the panel header, with a "Conflicts only" toggle and a group/name filter. Populated from
the same declared-coordinate data the inspector uses; empty when `includeExternalDependencies` is
off.

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

**⇄ Diff** - Snapshot comparison. Drag-drop a previous `graph.json` file onto the panel to see
exactly which modules and edges were added or removed since that snapshot. The file is read locally
- nothing is uploaded to any server. Useful for reviewing architectural impact during a pull
request.

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

Press `?` at any time to open the keyboard shortcuts overlay:

| Shortcut    | Action                                                |
|-------------|-------------------------------------------------------|
| `⌘K` / `Ctrl+K` | Open command palette                              |
| `/`         | Focus module search                                   |
| `1`–`6`     | Switch to panel by index                              |
| `T`         | Toggle dark/light theme                               |
| `I`         | Toggle inspector sidebar (collapses to a narrow rail) |
| `F`         | Fit graph (Map panel)                                 |
| `R`         | Re-layout graph (Map panel)                           |
| `+` / `−`   | Zoom in / out (Map panel)                             |
| `Esc`       | Clear selection / dismiss overlay                     |
| `E` / `C`   | Expand / collapse all (Browse → Tree)                 |
| `⌘P` / `Ctrl+P` | Print or save the report as PDF                   |
| `J`         | Download `graph.json`                                 |
| `?`         | Toggle this overlay                                   |

## Themes and print

**Dark + light themes.** Default tracks `prefers-color-scheme`; toggle in the header (`T`). Choice
persists in `localStorage`. Both themes share the transit-map identity - tiers as coloured lines,
modules as stops, cycles as closed loops - as a warm graphite dark theme (*Night Terminal*) and a
cool paper light theme (*Map Paper*). The Hanken Grotesk and Spline Sans Mono typefaces are
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

- Module path and short name
- Module type badge (colour-coded)
- Fan-in, fan-out, and transitive dependency count
- **Blast radius** - number of modules that transitively depend on this one (impact scope of a
  breaking change)
- Instability index bar (green = stable, yellow = mixed, red = unstable)
- Team owner (if configured via `teams { }`)
- KMP source sets (if applicable)
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
