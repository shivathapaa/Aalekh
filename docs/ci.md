# CI Setup

[← Documentation index](README.md) · [Project README](../README.md)

## GitHub Actions

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

## Recommended CI configuration

```kotlin
// build.gradle.kts (root project)
aalekh {
    openBrowserAfterReport.set(false)   // never open a browser in CI
    includeTestDependencies.set(true)   // keep test edges for full picture
    exportMetrics.set(true)             // write CSV for external dashboards
}
```

See [Architecture rules → SARIF output](rules.md#sarif-output-for-github-pr-annotations) for how
violations surface as inline PR annotations.
