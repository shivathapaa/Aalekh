# CI Setup

[← Documentation index](README.md) · [Project README](../README.md)

## Recommended configuration

```kotlin
// build.gradle.kts (root project)
aalekh {
    openBrowserAfterReport.set(false)   // never open a browser in CI
    includeTestDependencies.set(true)   // keep test edges for the full picture
    exportMetrics.set(true)             // write CSV for external dashboards
}
```

## Enforcing rules

`aalekhCheck` fails the build on `ERROR`-severity violations and writes SARIF, JUnit XML, and GitLab
Code Quality output alongside the HTML report.

```yaml
- name: Run architecture check
  run: ./gradlew aalekhCheck

- name: Upload SARIF
  uses: github/codeql-action/upload-sarif@v3
  if: always()
  with:
    sarif_file: build/reports/aalekh/aalekh-results.sarif

- name: Upload Aalekh report
  uses: actions/upload-artifact@v4
  if: always()
  with:
    name: aalekh-report
    path: build/reports/aalekh/

- name: Publish test results
  uses: mikepenz/action-junit-report@v4
  if: always()
  with:
    report_paths: build/reports/aalekh/aalekh-results.xml
```

See [Architecture rules → SARIF output](rules.md#sarif-output-for-github-pr-annotations) for how
violations surface as inline PR annotations.

## Reporting architecture changes on a pull request

Commit an architecture snapshot once:

```bash
./gradlew aalekhSnapshot
git add aalekh-snapshot.json
```

`aalekhDiff` then compares each branch against it and writes `aalekh-diff.md` - a comment listing the
dependencies, modules, and cycles the change added or removed. Aalekh only writes the file; posting
it is your job:

```yaml
- name: Report architecture changes
  run: ./gradlew aalekhDiff

- name: Comment on the pull request
  uses: peter-evans/create-or-update-comment@v4
  if: github.event_name == 'pull_request'
  with:
    issue-number: ${{ github.event.pull_request.number }}
    body-path: build/reports/aalekh/aalekh-diff.md
```

The task succeeds when no snapshot is committed, so adding it to a pipeline before creating the
baseline is safe. To block regressions instead of only reporting them:

```kotlin
aalekh {
    failOnArchitectureRegression.set(true)   // fail on a new cycle or a regressed metric
}
```

Refresh the baseline with `./gradlew aalekhSnapshot` when a change is intended, and commit it in the
same pull request.

## Keeping generated documentation current

`aalekhDocs` writes Markdown to `build/reports/aalekh/docs/`. The output has no timestamp, so
regenerating an unchanged project produces identical files. That makes it practical to commit the
documentation and fail CI when it drifts:

```yaml
- name: Generate architecture documentation
  run: ./gradlew aalekhDocs

- name: Check the committed documentation is current
  run: |
    cp -r build/reports/aalekh/docs/. docs/architecture/
    git diff --exit-code docs/architecture/
```

## Tracking metrics over time

`exportMetrics` writes `aalekh-metrics.csv` on every `aalekhReport` run - one timestamped row per
module - for import into a dashboard. For a single pass/fail signal instead, use
[quality gates](rules.md#quality-gates) to ratchet the structural metrics against a committed
baseline.
