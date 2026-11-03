# Contributing to Herald

Thanks for helping. Bug fixes, missing gateway features and UI polish are all welcome.

## Before you start

- For anything bigger than a small fix, open an issue first (bug report or feature request), so we can agree on the approach before you write it.
- [docs/ROADMAP.md](docs/ROADMAP.md) lists what's done and what's missing. Unticked items are good places to start.
- Herald talks to the gateway the way Hermes Desktop does: JSON-RPC over the dashboard WebSocket (`/api/ws`), plus its REST endpoints. Check [docs/hermes-protocol-research.md](docs/hermes-protocol-research.md) and the hermes-agent source for the method you need before adding a call.

## Setup

You need JetBrains Runtime 25 (Android Studio's bundled JBR is one; Gradle downloads it when none is found) and the Android SDK with API 37.1. You also need a Hermes gateway to test against: your own, or a throwaway one.

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
./gradlew --continue :shared:core:testAndroidHostTest :shared:designsystem:testAndroidHostTest :shared:ui:testAndroidHostTest :shared:wearlink:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDev :androidApp:lintDebug :wearApp:testDebugUnitTest :wearApp:assembleDebug :wearApp:lintDebug
```

Lint fails on any new issue. Fix it instead of adding it to `lint-baseline.xml`.

## Try it on a device

Unit tests aren't enough for anything you can see or that talks to the gateway. Run the debug build on an emulator or phone against a real gateway, and use the feature the way a user would.

- **Check narrow screens.** Many phones are 360 dp wide. On an emulator, try `adb shell wm density 480` (and `adb shell wm density reset` afterwards) to make sure rows and the composer still fit.
- **Check both themes** if you touched colours or layout.

## What reviews usually fix

Nearly every contributed PR so far got fixes in review, and most were the same few things. Check these before you ask for a review.

- **Answers that come in late.** A reply can arrive after the user switched profile, gateway or chat. Drop it instead of showing it. Also check that a lost and regained connection reloads what's on screen, that a rotation keeps it, that a second tap doesn't send or create something twice, and that every spinner or request ends, with a result or an error.
- **Data from another gateway or profile.** Users can have several of each. Key anything you cache or store by gateway and profile.
- **Guesses about the gateway.** Follow the hermes-agent source for the method or route, including which fields can be missing or null. Older gateways don't have newer methods: fall back, don't fail.
- **Unusual text.** Replies contain code fences, very long words, very tall tables, CRLF line ends and empty values. Test with them. The app must not crash on any of them.
- **Secrets and private addresses.** Don't put tokens or tickets in URLs. Never fetch an address on the user's own network because a reply links to it. Give a component only the token it needs.
- **New UI where the app already has some.** Reuse its rows, composer, viewers, banners, sheets and menus. Actions on a chat message are inline icons under the reply, or a long-press dropdown on the user's prompt, never a sheet or a ⋯ button. Give icon buttons a content description. Menus and sheets must fit a short landscape window. A new kind of alert is off by default.
- **Words.** Say what happened, in the user's terms: a job that didn't run is "missed" and a process the user stopped is "stopped", not "failed". When something fails, say why.
- **Leftovers.** Remove fields and helpers that nothing uses, and don't add a second loader or provider for something that has one. Don't sort, copy or read again on every frame.
- **The desktop app.** `shared:core`, `shared:designsystem` and `shared:ui` also run in the desktop app. Build it too (`./gradlew :desktopApp:compileKotlinJvm`), and hide in it what doesn't apply to a desktop. The command under "Tests" already builds the watch app.
- **An old branch.** Merge `master` into your branch before you ask for a review, and again if it falls behind.
- **Previews.** A new screen gets a `PreviewScene` in `HeraldPreviews.kt`, which the debug `PreviewGalleryActivity` shows, with made-up sample data.

## Screenshots

For a PR that changes the UI, add before/after screenshots **to the PR description**. Don't commit them to the repo.

Never show private data: no real chats, prompts, scheduled jobs, bots or gateway addresses. Use a test gateway, or the debug `PreviewGalleryActivity`, which draws each screen from sample data (see the README).

## Website

The landing page at [kyllene.app](https://kyllene.app) is the static site in `website/`: plain HTML and CSS, no build
step. Preview it with any static server, for example `npx http-server website`, and check it at 375 px wide as well as
on a desktop. Its screenshots are copies from `docs/screenshots`, so the same rules apply. Maintainers deploy it to
Cloudflare Pages by hand:

```bash
npx wrangler pages deploy website --project-name kyllene --branch master
```

## Pull requests

- Say **what** changed and **why**, and **how you tested it**: which tests you added, and what you checked on a device.
- Link the issue (`Fixes #123`).
- Leave **"Allow edits by maintainers"** on.

## Review

A maintainer reads the code, then installs your branch on an emulator and tests the feature against a live gateway. Bugs found in review are fixed with a test, usually as extra commits pushed to your branch, so the PR can be merged without another round trip. You'll see what changed and why in the PR. Please pull before pushing more work.

Releases are cut by maintainers from tagged commits on `master`.
