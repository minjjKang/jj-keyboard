# Repository Guidelines

## Project Structure & Module Organization

- `hangul/`: pure Kotlin Hangul composition engine, Unicode syllable utilities, and JVM tests.
- `app/src/main/kotlin/io/github/minjj/keyboard/`: Android IME integration (`ime/`), native keyboard views (`ui/`), preferences and setup (`settings/`), and local clipboard history (`clipboard/`).
- `app/src/main/res/`: strings, IDs, themes, icons, and IME metadata.
- `app/src/test/`: JUnit and Robolectric tests. `app/src/androidTest/`: real IME tests using AndroidJUnitRunner and UI Automator.
- `app/src/debug/`: local input and image fixtures; keep these out of release builds.
- `scripts/` automates setup, builds, and device testing. `docs/` contains plans, verification reports, and fixture screenshots.

## Build, Test, and Development Commands

Use JDK 17, Gradle Wrapper 8.13, and Android SDK 36. On this Windows workspace, tools live in `.tools/`.

```powershell
.\scripts\build.ps1
.\scripts\build.ps1 -Tasks ':hangul:test'
.\scripts\device-tests.ps1 -DeviceSerial T860128GB26388620671 -TestName hangulCompositionAndHistoryDeletion
```

The default build runs engine tests, Robolectric tests, debug APK assembly, and Android Lint. Device tests install fixture APKs, inject actual touches, and restore preferences, the previous IME, and rotation settings. Agents may wake the test device and dismiss a non-secure keyguard automatically; request manual unlocking only if PIN, password, pattern, or biometric authentication is required. Do not disable or change the device's lock settings. Results go to `docs/device-runs/`.

For release validation, run `:app:assembleRelease` and `:app:lintRelease`. Release APKs are unsigned. On macOS/Linux, use `sh gradlew` with the same task names.

## Coding Style & Naming Conventions

Use four-space indentation, `PascalCase` types, `camelCase` members, and `UPPER_SNAKE_CASE` constants. Android resources use `lower_snake_case`. Keep composition logic Android-independent and editor state scoped to its input connection. Match surrounding Kotlin style; Android Lint is configured, but no standalone formatter is enforced.

## Testing Guidelines

Select tests for the changed behavior and affected callers. Skip unrelated checks and runtime tests for documentation-only edits. Use `-Tasks` for relevant Gradle tasks and `-TestName` for affected device scenarios. Reserve full suites for release validation or changes with broad impact. Run rotation and throughput stress tests when the change can affect those paths. Use 80 Turbo (`T860128GB26388620671`) for device tests and validate layout changes in both orientations. Name JVM tests with descriptive backticks and device tests in camelCase.

## Commit & Pull Request Guidelines

This checkout has no Git metadata, so existing commit conventions cannot be verified. Use concise imperative subjects, optionally prefixed with `feat:`, `fix:`, or `test:`. PRs should explain the behavior change, link relevant issues, list checks and tested devices/OS versions, and include UI screenshots and known limitations.

## Security & Configuration

Do not commit SDK caches, `local.properties`, signing keys, or personal clipboard captures. Store only explicit clipboard copies locally; exclude sensitive clips and password input. Never log typed text. Address every ADB command to an explicit serial and preserve device settings.
