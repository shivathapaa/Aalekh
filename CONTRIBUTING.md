# Contributing to Aalekh

Thank you for your interest in contributing. This guide covers everything you need to
build, test, and submit changes.

## Module structure

```
aalekh-model/       Data classes. No Gradle API. No analysis logic.
aalekh-analysis/    Pure Kotlin analysis. No Gradle API. Testable in milliseconds.
aalekh-report/      Report generators. No Gradle API.
aalekh-gradle/      Gradle plugin. The only module that may use the Gradle API.
build-logic/        Convention plugins for building Aalekh itself. Not published.
```

The layering rule is strict: `aalekh-model` ← `aalekh-analysis` ← `aalekh-report` ← `aalekh-gradle`.
The Gradle API never appears below `aalekh-gradle`. If you find yourself importing a Gradle type
in `aalekh-analysis` or `aalekh-report`, the design is wrong.

## Building locally

Requires JDK 17 or JDK 21. Gradle 9.x is bundled via the wrapper.

```bash
git clone https://github.com/shivathapaa/aalekh.git
cd aalekh
./gradlew build
```

## Running tests

```bash
# All unit tests
./gradlew test

# Functional tests (spins up real Gradle builds - slower)
./gradlew :aalekh-gradle:functionalTest

# Everything
./gradlew checkAll
```

Unit tests run in milliseconds because `aalekh-analysis` and `aalekh-model` have no Gradle
dependency. Only the functional tests in `aalekh-gradle` are slow.

## Submitting a built-in rule

Built-in rules live in `aalekh-analysis/src/main/kotlin/.../analysis/rules/`.

A rule must:
1. Implement `ArchRule`
2. Have a stable `id` string (kebab-case, never changes after first release)
3. Populate `moduleHint` on every `Violation` so "View in Graph" works
4. Populate `plainLanguageExplanation` - one or two sentences explaining *why* the rule
   exists, written for a developer who has never heard of it
5. Exclude test dependencies (`it.isTest`) unless the rule explicitly targets test code
6. Have a test file covering: no violations on a clean graph, violation on a violating graph,
   violation message content, violation `ruleId` stability, `moduleHint` is set

Register the rule in `RuleEngine.fromConfig()` in `ArchRule.kt` and expose it via a method
on `RulesConfig` if it requires configuration.

## Code style

- KDoc on all `public` classes and functions
- Comments only where the logic is non-obvious
- Self-explanatory names over comments
- `internal` visibility for everything not part of the public API

## Commit messages

Use conventional commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`.

```
feat: add NoAndroidInDomainRule built-in rule
fix: NoCyclicDependenciesRule removeLast() crash on JDK < 21
docs: add KDoc to ModuleDependencyGraph public API
```

## Pull request checklist

- [ ] `./gradlew checkAll` passes locally
- [ ] New rule has a test file (see "Submitting a built-in rule" above)
- [ ] New public API has KDoc
- [ ] CHANGELOG.md has an entry under `[Unreleased]`
- [ ] No new Gradle API imports outside `aalekh-gradle`

## Good first issues

Look for issues labelled `good-first-issue`. Typical examples:

- **Beginner**: add a new graph export format (Mermaid, DOT), add a node colour scheme option
- **Intermediate**: add a new built-in rule (e.g. `noDeprecatedModules`, `maxModuleDepth`)
- **Advanced**: configuration cache edge case, composite build support

## Getting help

Open a [GitHub Issue](https://github.com/shivathapaa/aalekh/issues) for questions, bug reports, or feature requests.
Open an issue only for confirmed bugs or concrete feature requests.