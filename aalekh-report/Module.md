# Module aalekh-report

The report generators. Turns an analyzed `ModuleDependencyGraph` into the interactive HTML report and
the machine-readable exports (JSON, JUnit XML, SARIF, CSV, DOT, Mermaid). Still Gradle-free and
I/O-light: generators return strings and byte content that the Gradle tasks write to disk.

`ReportCoordinator` is the single facade — tasks call it, never the individual generators. The HTML
report is fully offline: D3.js is vendored and inlined, and the typefaces are base64 `woff2`, so the
rendered report makes no CDN or web-font request.

# Package com.aalekh.aalekh.report

`ReportCoordinator`, the facade every task goes through to select and produce a report format.

# Package com.aalekh.aalekh.report.html

`HtmlReportGenerator`, which fills `resources/aalekh-report-template.html` to produce the single
self-contained interactive report emitted by `aalekhReport`.

# Package com.aalekh.aalekh.report.sarif

The SARIF generator, emitting rule violations keyed by stable `ArchRule.id` for consumption by code
scanning tools and CI dashboards.

# Package com.aalekh.aalekh.report.junit

The JUnit XML generator, mapping violations to test cases so `aalekhCheck` results surface in any CI
that reads JUnit reports.
