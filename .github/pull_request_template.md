<!--
Thanks for contributing to Aalekh! Please fill in the sections below and tick the checklist.
See CONTRIBUTING.md for the full guidelines.
-->

## What & why

<!-- What does this change do, and why? Link any related issue: Closes #123 -->

## Checklist

- [ ] `./gradlew checkAll` passes locally
- [ ] New rule has a dedicated test file (clean graph, violating graph, severity override, suppression)
- [ ] New `public` API has KDoc
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]`
- [ ] No new Gradle API imports outside `aalekh-gradle`
- [ ] `README.md` / `docs/` updated if behaviour or configuration changed
- [ ] Configuration cache still reused on a second consecutive run (for `aalekh-gradle` changes)

## Notes for reviewers

<!-- Anything worth calling out: trade-offs, follow-ups, areas to scrutinise. -->
