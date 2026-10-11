# Contributing to Herald

Thanks for helping. Bug fixes, missing gateway features and UI polish are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first (bug report or feature request), so we can agree on the approach before you write it.
- [docs/ROADMAP.md](docs/ROADMAP.md) lists what's done and what's missing. Unticked items are good places to start.
- Herald talks to the gateway the way Hermes Desktop does: JSON-RPC over the dashboard WebSocket (`/api/ws`), plus its REST endpoints. Check [docs/hermes-protocol-research.md](docs/hermes-protocol-research.md) and the hermes-agent source for the method you need before adding a call.

## Setup

You need JDK 17+ (Android Studio's bundled JBR works) and the Android SDK with API 37. You also need a Hermes gateway to test against: your own, or a throwaway one.

```bash
./gradlew :androidApp:installDebug
```

On Windows, if host tests fail with `Could not find or load main class Files\...`, your `PATH` contains a stray `"`. Remove it.

## Making a change

- **One feature or fix per pull request.** Small PRs get reviewed and merged faster. Don't mix refactors into a feature PR.
- **Small, logical commits.** Write the subject as `Area: what changed`, in plain words, for example `Composer: a Dictation switch` or `Recent: exclude all machine sources (fixes #107)`. Don't use `feat:`/`fix:` prefixes.
- **Match the code around you**: naming, idioms, and how much it comments. KDoc says what a thing is for, in plain English.
- **Tick the ROADMAP item** your change completes, in the same PR, with a short note on what it covers.

## Tests

Add a test with every behaviour change. A bug fix should come with a test that fails without it.

- Chat and protocol logic goes in `shared/core/src/commonTest`. Use `ChatReducerTest` for events and `ChatSessionTest` for calls to the gateway; they have a fake gateway.
- View-model and UI logic goes in `shared/ui/src/commonTest`.

Before you push, run what CI runs:

```bash
./gradlew --continue :shared:core:testAndroidHostTest :shared:designsystem:testAndroidHostTest :shared:ui:testAndroidHostTest :androidApp:assembleDebug :androidApp:lintDebug
```

Lint fails on any new issue. Fix it instead of adding it to `lint-baseline.xml`.

## Try it on a device

Unit tests aren't enough for anything you can see or that talks to the gateway. Run the debug build on an emulator or phone against a real gateway, and use the feature the way a user would.

- **Check narrow screens.** Many phones are 360 dp wide. On an emulator, try `adb shell wm density 480` (and `adb shell wm density reset` afterwards) to make sure rows and the composer still fit.
- **Check both themes** if you touched colours or layout.

## Screenshots

For a PR that changes the UI, add before/after screenshots **to the PR description**. Don't commit them to the repo.

Never show private data: no real chats, prompts, scheduled jobs, bots or gateway addresses. Use a test gateway, or the debug `PreviewGalleryActivity`, which draws each screen from sample data (see the README).

## Pull requests

- Say **what** changed and **why**, and **how you tested it**: which tests you added, and what you checked on a device.
- Link the issue (`Fixes #123`).
- Leave **"Allow edits by maintainers"** on.

## Review

A maintainer reads the code, then installs your branch on an emulator and tests the feature against a live gateway. Bugs found in review are fixed with a test, usually as extra commits pushed to your branch, so the PR can be merged without another round trip. You'll see what changed and why in the PR. Please pull before pushing more work.

Releases are cut by maintainers from tagged commits on `master`.
