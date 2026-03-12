# Contributing to Aalekh

Thank you for considering contributing to Aalekh! Contributions of all kinds are welcome - bug fixes, new features, documentation improvements, and more. By contributing, you help make this project better for everyone.

## Code of Conduct

This project adheres to a Code of Conduct. By participating, you are expected to uphold this code. Be kind to everyone!

## Getting Started

1. Fork the repository: click the **Fork** button at the top of this repository.
2. Clone your fork:
   ```bash
   git clone https://github.com/shivathapaa/aalekh.git
   ```
3. Create a branch:
   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b bugfix/your-bug-fix-name
   ```
4. Make your changes following the guidelines below.
5. Ensure all commits are signed using GPG. Unsigned commits will not be accepted. Learn how to [sign commits](https://docs.github.com/en/authentication/managing-commit-signature-verification/signing-commits).
6. Commit your changes:
   ```bash
   git commit -m "Describe your changes"
   ```
7. Push your branch:
   ```bash
   git push origin feature/your-feature-name
   ```
8. Submit a pull request: go to the repository on GitHub and open a pull request.

## How to Contribute

### Reporting Bugs

If you find a bug, please:

1. Search existing issues to see if it has already been reported.
2. Open a new issue (using the bug report template) and include:
    - A clear and descriptive title.
    - A detailed description of the bug.
    - Steps to reproduce it.
    - Your Gradle version, AGP version (if applicable), and Kotlin version.
    - Any relevant screenshots, stack traces, or code snippets.

### Suggesting Features

To suggest a feature:

1. Check existing feature requests to see if it has already been suggested.
2. Open a new issue with the title `Feature Request: [Your Feature]` and provide:
    - A detailed description of the feature.
    - The *why* - what problem does it solve or what workflow does it improve?
    - Any examples or use cases.

### Submitting Pull Requests

> Please avoid opening pull requests for minor typos that don't affect code logic. Feel free to [open an issue](https://github.com/shivathapaa/aalekh/issues/new/choose) for those instead.

When you're ready to submit a pull request:

1. Ensure your code follows the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
2. Include tests for any new features or bug fixes.
3. Ensure all existing tests pass: `./gradlew checkAll`.
4. Add KDoc to any new public API.
5. Update documentation as needed.
6. Open a pull request with a clear description of what you've done and why.

## New to Git?

Resources: https://lab.github.com and https://try.github.io/

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).