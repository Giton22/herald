# Hermes Kotlin

A modern Kotlin Multiplatform client for [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Android first, with iOS and desktop to follow. It connects to a **remote Hermes gateway**, meaning a
`hermes dashboard` running on your server, homelab, VPS or Tailnet, the same way the official Desktop
app's "Remote gateway" connection does.

The protocol notes are in [docs/hermes-protocol-research.md](docs/hermes-protocol-research.md).

## Status legend

- [x] implemented
- [ ] not started
- 🚧 in progress (written next to the item)

---

## Tech stack

- [x] Kotlin Multiplatform project: shared `core` / `designsystem` / `ui` modules plus `androidApp`
- [ ] iOS targets (`iosArm64`/`iosSimulatorArm64`) and a `jvm` desktop target
- [x] Compose Multiplatform UI built on **Compose Unstyled**, with our own design system (`HermesTheme` tokens + primitives), edge-to-edge
- [x] Light/dark color schemes following the system
- [x] Ktor client: OkHttp engine on Android, content negotiation, WebSockets plugin installed
- [ ] Ktor Darwin engine (iOS)
- [x] kotlinx.serialization
- [ ] Kotlin models generated from `gateway-contract.openrpc.json`
- [x] Koin for dependency injection, androidx ViewModel (KMP), Coroutines/Flow
- [ ] Navigation library (KMP); a small route state machine (`AppViewModel`) is used for now
- [ ] SQLDelight or Room KMP for the local cache, DataStore for settings
- [x] Secure storage on Android (AES-GCM key in the Android Keystore) for the session cookie jar
- [ ] Secure storage on iOS (Keychain)
- [ ] Markdown rendering (`multiplatform-markdown-renderer`) and Coil 3 for images
- [x] Unit tests (Ktor `MockEngine`, coroutines-test)
- [ ] CI: build, lint and unit tests

### Design system (`shared/designsystem`)
- [x] Tokens: colors (light/dark), radii, typography
- [x] `Button` (primary, secondary, outline, ghost, danger; three sizes; loading state; leading icon)
- [x] `TextField` (label, placeholder, supporting text, error)
- [x] `Surface` (flat panel, or elevated with a soft shadow + hairline)
- [x] `Spinner`, `StatusDot`
- [x] `TextField` password mode (masked, show/hide toggle)
- [ ] Sheet / dialog, menu, list row, toast, chat bubble, tool card, approval card
- [ ] Theme picker (system / light / dark), optional Hermes-style accent presets

---

## MVP: remote gateway

### 1. Connection & auth
- [x] Add a gateway: URL input that normalizes scheme-less `host:port` and Tailscale IPs
- [x] Probe `GET /api/status` for reachability and to read `auth_required`, `auth_providers` and `auth_flows`
- [x] Username/password sign-in (`POST /auth/password-login`) with a persistent cookie jar for the `hermes_session_at` and `hermes_session_rt` cookies
- [x] Transparent access-token refresh using the refresh-token cookie (done by the server), and re-login when the session expires
- [ ] Legacy static session-token mode (`X-Hermes-Session-Token`, `?token=`)
- [x] WS ticket mint (`POST /api/auth/ws-ticket`)
- [ ] Test-connection button that checks both the HTTP and WebSocket legs (🚧 HTTP leg done)
- [x] Clear error messages for 401, 429, WS close 4401 (bad ticket) and 4403 (Host/peer guard). Note: the server rejects these before the upgrade, so they arrive as an HTTP 403 handshake failure
- [x] Sign out (server-side revoke + local cookie wipe), and "use a different gateway"

### 2. WebSocket JSON-RPC transport
- [x] Connect to `/api/ws` with subprotocols `hermes-gateway-v1` and `hermes-gateway-ticket.<t>`
- [x] Newline-delimited JSON-RPC 2.0: requests, responses, notifications
- [x] Wait for `gateway.ready`, then send `client.capabilities {server_requests: true}`
- [x] Handle server→client requests (`srq-<n>`) and answer them with the matching id (unhandled ones get `-32601` right away so the agent never stalls)
- [x] `gateway.ping` keepalive every 15 s, 45 s inbound deadline
- [x] Automatic reconnect with backoff (fresh ticket each time)
- [ ] `session.resume` / `session.events.since` replay after reconnect
- [x] Connection-state indicator in the UI (connecting, connected, retrying with countdown, refused, session expired)

### 3. Sessions
- [ ] Session list (`session.list`) with title, preview, model, last-active time, and pinned/archived state
- [ ] Create a new session (`session.create`)
- [ ] Open or resume a session and load its history (`session.resume`, `session.history`)
- [ ] Rename (`session.title`), delete (`session.delete`), archive (`session.archive`)
- [ ] Live list updates (`sessions.changed`, `session.title`, `session.info`)
- [ ] Search sessions by title

### 4. Chat
- [ ] Send a prompt (`prompt.submit`)
- [ ] Streaming assistant text (`message.start`, `message.delta`, `message.complete`)
- [ ] Interim commentary (`message.interim`)
- [ ] Markdown rendering with code blocks, syntax highlighting and a copy button
- [ ] Reasoning/thinking blocks, collapsible (`reasoning.delta`, `thinking.delta`)
- [ ] Stop or interrupt a running turn (`session.interrupt`)
- [ ] Steer a running turn (`session.steer`)
- [ ] Token usage and cost per turn and per session (`session.usage`, `MessageCompletePayload.usage`)
- [ ] Error, warning and notice display (`error`, `notice`, `status.update`)

### 5. Tool activity
- [ ] Tool call cards: name, args preview, running state (`tool.start`, `tool.generating`)
- [ ] Completion state: duration, error flag, output preview (`tool.complete`)
- [ ] Output-risk warnings (`tool.output_risk`)
- [ ] Todo list updates (`todo.updated`)

### 6. Interactive requests (server → client)
- [ ] **Approval** sheet for dangerous commands, with choices `once`, `session`, `always`, `deny` filtered by `allow_session`, `allow_permanent` and `smart_denied`
- [ ] Approval cancelled or withdrawn (`approval.cancelled`, `request.cancel`)
- [ ] **Clarify** (1–5 questions)
- [ ] **Sudo** password prompt (masked)
- [ ] **Secret** env-var prompt (masked)
- [ ] Graceful "unsupported" reply for desktop-only requests (`preview.*`, `terminal.read`, `window.read`, `tour`)

### 7. Settings (MVP)
- [ ] Model picker (`model.options`)
- [ ] Profile picker (`profiles.list`)
- [ ] Theme: system, light, dark (🚧 follows system)
- [ ] Manage several saved gateways (add, edit, remove, choose primary)

---

## Later

### Attachments & media
- [ ] Image attachments from the gallery or camera (`image.attach_bytes`, `image.detach`)
- [ ] File and PDF attachments (`file.attach`, `pdf.attach`)
- [ ] Inline images in history (`inline_images`)
- [ ] Share sheet: "Send to Hermes" from other apps

### Voice
- [ ] Voice input / transcription (`voice.record`, `voice.transcript`)
- [ ] Text-to-speech playback (`voice.tts`)
- [ ] Voice mode toggle and status (`voice.toggle`, `voice.status`)

### Slash commands & composer
- [ ] Slash command catalog and autocomplete (`commands.catalog`, `complete.slash`, `slash.exec`)
- [ ] Background prompts and BTW side questions (`prompt.background`, `prompt.btw`, `background.complete`, `btw.complete`)
- [ ] Message reactions (`message.react`)
- [ ] Undo, branch and fork sessions (`session.undo`, `session.branch`)
- [ ] Context compression (`session.compress`, `session.context_breakdown`)

### Agents & processes
- [ ] Subagent tree and live progress (`subagent.*`, `spawn_tree.*`)
- [ ] Background process list, kill and stop (`process.*`, `agent.terminal.output`)
- [ ] Rollback / checkpoints (`rollback.list`, `rollback.diff`, `rollback.restore`)

### Automation & configuration
- [ ] Cron jobs: list, create, pause, resume, run (`cron.manage`, `cron.changed`)
- [ ] Skills browser and management (`skills.manage`)
- [ ] Tools and toolsets toggles (`tools.list`, `tools.configure`, `toolsets.list`)
- [ ] MCP servers (`mcp.*`)
- [ ] Config viewer and editor (`config.get`, `config.set`)
- [ ] Projects and workspaces (`projects.*`)
- [ ] Insights and usage analytics (`insights.get`, `usage.bars`)

### Notifications & background
- [ ] Foreground service that keeps the WS alive during long runs
- [ ] Local notifications: approval waiting, run finished, `notification.show`
- [ ] ntfy integration as a push channel without Google services (Hermes ntfy platform adapter)

### Auth & connectivity extras
- [ ] Native OAuth sign-in (RFC 8252 PKCE via Custom Tab and a loopback redirect; `/auth/native/*`)
- [ ] QR-code pairing
- [ ] Tailscale / MagicDNS setup guide
- [ ] Gateway API server "lite" mode (`:8642`, static bearer key, Runs API + SSE)

### Platforms & polish
- [ ] Tablet and foldable adaptive layout (list and detail side by side)
- [ ] Home-screen widget and Quick Settings tile
- [ ] Offline cache of sessions and messages
- [ ] iOS app
- [ ] Desktop (JVM) app
- [ ] Wear OS companion
- [ ] Localization (`i18n.catalog`)

---

## Development

Requirements: JDK 17+ (Android Studio's bundled JBR works) and the Android SDK with API 37.

```bash
./gradlew :androidApp:assembleDebug
```

```bash
./gradlew :shared:core:allTests :shared:ui:allTests
```

```bash
./gradlew :androidApp:installDebug
```

Layout:

```
shared/core          protocol, auth, network, repositories (no UI)
shared/designsystem  HermesTheme tokens + primitives on Compose Unstyled
shared/ui            screens and view models (commonMain)
androidApp           Android host: Application, Activity, manifest
```

> **Windows note:** if host tests fail with `Could not find or load main class Files\...`, your `PATH`
> contains a stray `"` character. Remove it from the environment variable.
