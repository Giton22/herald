<!-- Thanks for the PR. CONTRIBUTING.md has the details behind each item below. -->

## What and why



Fixes #

## Gateway

<!-- For a new or changed gateway call: link the hermes-agent code it follows, and say what happens on a gateway
that doesn't have it. Delete this section if the PR doesn't talk to the gateway. -->

## How I tested it

- Tests added:
- On a device:

## Screenshots

<!-- Before and after, for a UI change. No real chats, prompts, scheduled jobs, bots or gateway addresses. -->

## Checklist

- [ ] One feature or fix, and its ROADMAP item is ticked
- [ ] Up to date with `master`, and the command under "Tests" in CONTRIBUTING.md passes
- [ ] A late answer after a profile, gateway or chat switch is dropped; reconnect, rotation and a second tap work
- [ ] Anything cached or stored is keyed by gateway and profile
- [ ] Tested with unusual text, if it shows replies or other gateway text: code fences, very long words, CRLF, empty
  values
- [ ] No tokens in URLs, and no fetches of an address on the user's own network that a reply or page links to
- [ ] Reuses the app's components, fits at 360 dp in both themes, and icon buttons have content descriptions
- [ ] The desktop app builds (`./gradlew :desktopApp:compileKotlinJvm`)
- [ ] "Allow edits by maintainers" is on
