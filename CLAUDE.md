# Kyllene: notes for Claude

Setup, commit style, tests and PR rules are in [CONTRIBUTING.md](CONTRIBUTING.md). This file adds what a reviewer
should check, so every review (local `/code-review`, a fresh reviewer agent, or one in CI) looks for the same things.

## Review checklist

Report only what breaks one of these, or a real bug. Leave out style points the surrounding code already follows,
and issues already in `androidApp/lint-baseline.xml`.

### Gateway

- A new call matches the hermes-agent source for that method or route, not a guess from its name.
- `is_active` in `/api/sessions` means the session is loaded, not that a turn is running. Use `session.resume`'s
  `running` and the `message.start` / `message.complete` events for "Running".
- `PATCH` / `DELETE /api/sessions/{id}` go through `SessionsApi.mutate`, which sends the identity from
  `GET …/mutation-snapshot`. Older gateways lack that route: its 404 can say "No such API endpoint" or "Not Found",
  and a non-JSON reply counts the same. Then the write goes without identity.
- Hosted rooms: `groups.send` sends `room_id`, `event_id` (so a retry isn't sent twice) and a payload of `text`,
  `thread_id` and optional `attachments`. See `RoomsApi.send`.

### Common fixes

- Each item in "What reviews usually fix" in CONTRIBUTING.md holds. Fetches the rule there forbids are of addresses a
  reply or page links to, not calls to the gateway, which is often on the user's own network.

### Release builds (R8)

- Release and `dev` builds are minified; debug isn't. A library whose native code looks up classes by name needs a
  keep rule in `androidApp/proguard-rules.pro` (for example `org.jni_zero.**` for WebRTC), or the app crashes on
  load only in minified builds. CI builds `assembleDev` and checks the WebRTC classes survive.

### UI

- Rows, pills and the composer still fit at 360 dp wide, and colours and layout work in both themes. See "Try it
  on a device" in CONTRIBUTING.md.
- Actions on a chat message are inline icons under the reply, or a long-press dropdown on the user's prompt. No
  bottom sheets and no ⋯ menu buttons.

### Tests

- The rules in "Tests" in CONTRIBUTING.md hold. Gateway code is tested with `MockEngine` for REST and `FakeTransport`
  (as in `ChatSessionTest`) for JSON-RPC, including the fallback for older gateways.

### Public repo

- The "Screenshots" rules in CONTRIBUTING.md also cover code, test fixtures and PR text: no real chats, prompts,
  scheduled jobs, bot names or gateway addresses.
- Don't name third-party clients or plugins that inspired a feature.

### Scope

- "Making a change" in CONTRIBUTING.md: one feature or fix per PR, ROADMAP item ticked.
