# Roadmap

What Herald does today and what's planned, feature by feature, with the gateway protocol each one
uses. The protocol itself is described in [hermes-protocol-research.md](hermes-protocol-research.md).

- [x] implemented
- [ ] not started
- 🚧 in progress (written next to the item)

---

## Tech stack

- [x] Kotlin Multiplatform project: shared `core` / `designsystem` / `ui` modules plus `androidApp`
- [ ] iOS targets (`iosArm64`/`iosSimulatorArm64`) and a `jvm` desktop target
- [x] Compose Multiplatform UI built on **Compose Unstyled**, with our own design system (`HermesTheme` tokens + primitives), edge-to-edge
- [x] Light/dark color schemes following the system, or forced from Settings, plus pure black
- [x] Ktor client: OkHttp engine on Android, content negotiation, WebSockets plugin installed
- [ ] Ktor Darwin engine (iOS)
- [x] kotlinx.serialization
- [ ] Kotlin models generated from `gateway-contract.openrpc.json`
- [x] Koin for dependency injection, androidx ViewModel (KMP), Coroutines/Flow
- [ ] Navigation library (KMP); a small route state machine (`AppViewModel`) is used for now
- [ ] SQLDelight or Room KMP for the local cache, DataStore for settings
- [x] Secure storage on Android (AES-GCM key in the Android Keystore) for the session cookie jar
- [ ] Secure storage on iOS (Keychain)
- [x] Markdown rendering (`multiplatform-markdown-renderer`)
- [ ] Coil 3 for images
- [x] Unit tests (Ktor `MockEngine`, coroutines-test)
- [x] CI on GitHub Actions: unit tests and a debug build on every push
- [ ] Lint in CI
- [x] Releases: a `v1.2.3` tag builds a signed APK into a draft GitHub release; the app offers newer releases

### Design system (`shared/designsystem`)
- [x] Tokens: colors (light/dark), radii, typography
- [x] `Button` (primary, secondary, outline, ghost, danger; three sizes; loading state; leading icon)
- [x] `TextField` (label, placeholder, supporting text, error, leading icon, clear button)
- [x] `Surface` (flat panel, or elevated with a soft shadow + hairline)
- [x] `Spinner`, `StatusDot`
- [x] `TextField` password mode (masked, show/hide toggle)
- [x] `BottomSheet` with `SheetHeader`/`SheetAction` rows, `Dialog`, `IconButton` (plain or filled), pill `Button`, `Chip`, `SidebarLayout` (drawer on phones, docked and collapsible on wide screens)
- [x] `MarkdownText` (themed GFM via multiplatform-markdown-renderer core, no Material) and `CopyButton`
- [x] `Switch` and `SegmentedControl`; a pure black dark scheme (`PureBlack`)
- [x] The app mark (`HeraldMark`), also the launcher, themed and notification icons
- [ ] Menu, toast, chat bubble, tool card, approval card
- [x] Theme picker (system / light / dark) and pure black
- [ ] Accent color presets

---

## MVP: remote gateway

### 1. Connection & auth
- [x] Add a gateway: URL input that normalizes scheme-less `host:port` and Tailscale IPs
- [x] Probe `GET /api/status` for reachability and to read `auth_required`, `auth_providers` and `auth_flows`
- [x] Username/password sign-in (`POST /auth/password-login`) with a persistent cookie jar for the `hermes_session_at` and `hermes_session_rt` cookies
- [x] Transparent access-token refresh using the refresh-token cookie (done by the server), and re-login when the session expires
- [ ] Legacy static session-token mode (`X-Hermes-Session-Token`, `?token=`)
- [x] WS ticket mint (`POST /api/auth/ws-ticket`)
- [x] Connection check by stage, each with its own fix: server access (`/api/status`), sign-in (`/api/auth/me`) and the live connection (WS ticket, `/api/ws`, `gateway.ready`). The connect screen tests the server and explains local network, Tailscale and HTTPS addresses; Settings → Check connection runs all three
- [x] Warning when a gateway would be reached over plain `http://` on a public address
- [x] Clear error messages for 401, 429, WS close 4401 (bad ticket) and 4403 (Host/peer guard). Note: the server rejects these before the upgrade, so they arrive as an HTTP 403 handshake failure
- [x] Sign out (server-side revoke + local cookie wipe), and "use a different gateway"

### 2. WebSocket JSON-RPC transport
- [x] Connect to `/api/ws` with subprotocols `hermes-gateway-v1` and `hermes-gateway-ticket.<t>`
- [x] Newline-delimited JSON-RPC 2.0: requests, responses, notifications
- [x] Wait for `gateway.ready`, then send `client.capabilities {server_requests: true}`
- [x] Handle server→client requests (`srq-<n>`) and answer them with the matching id (approval, clarify, sudo, secret)
- [x] Methods the app can't show (`preview.*`, `terminal.read`, `window.read`, `tour`, `vault.*`) get the `4404` decline when the gateway counts declines (`client.capabilities` → `declines_not_shown`), so Desktop can still answer them and the agent stops waiting once every client declined; never `-32601`, which would settle them for Desktop too
- [x] `gateway.ping` keepalive every 15 s, 45 s inbound deadline
- [x] Automatic reconnect with backoff (fresh ticket each time)
- [x] Re-attach (`session.resume`) and refetch the transcript after a reconnect; rebuild a running turn from `inflight`
- [x] Exact event replay after reconnect (`session.events.since`): the last applied `seq` is tracked per runtime session and the missed events go through the same reducer, overlap with live frames dropped by seq; a gap in the live stream is filled the same way. A truncated ring, another `replay_epoch` or an older gateway fall back to the transcript refetch and `inflight`. The chat shows "Reconnecting…" / "Waiting for network…" while the link is down
- [x] Connection-state indicator in the UI (connecting, connected, retrying with countdown, refused, session expired)

### 3. Sessions
- [x] Session list with title, preview, model, last-active time, source, message count, and pinned/archived state (`GET /api/sessions?order=recent`, paged; same REST endpoint Desktop uses because the WS `session.list` row has no model or last-active time)
- [x] Pinned chats on top (with a pin), plus an Archived page; the chat list leaves out cron runs (`exclude_sources=cron`, like Desktop), which are reached through their job under Scheduled
- [x] Create a new session (`session.create`) from the New chat button (chat top bar or sidebar); the stored row appears with the first prompt
- [x] Open a session and read its stored history (`GET /api/sessions/{id}/messages`, read-only; tool steps are folded into one reply)
- [x] Long chats load a page at a time: the newest 60 rows on opening, older pages as you scroll to the top (`order=latest` + `offset`), each starting at a prompt so no turn is cut in two; the list keeps its place. Branching loads the rest first so it counts from the first row
- [x] Resume a session live and reply in it (`session.resume {omit_messages}` + REST history, like Desktop)
- [x] The app opens on a chat: the one you had open last on this gateway, or a fresh one if you left on a new chat (remembered per gateway)
- [x] Sessions live in a left sidebar: a swipe-in drawer on phones (swipe right or the panel button; swipe left, tap outside or Back to close), docked and collapsible on wide screens; the open chat is highlighted
- [x] Sidebar layout: title with a round search button, Scheduled / Archived rows, title-only chat rows with a live dot for running sessions, floating "New chat" pill and an initials avatar (with a connection warning dot) that opens the account sheet
- [x] Refetch the list whenever the sidebar opens
- [x] Rename, pin, archive and delete (`PATCH`/`DELETE /api/sessions/{id}`), with optimistic updates that roll back on error
- [x] Chat options (⋮ beside new chat) for the open chat: rename, pin, export as Markdown, copy session ID, archive, delete
- [x] Floating see-through composer over the conversation
- [x] Reading back stays put while a reply streams below; the jump arrow then reads "New reply"
- [x] Live list updates (`sessions.changed`, `session.title` → refetch; also refetch after reconnect)
- [ ] Live per-session status in the list (`session.info`, `session.active_list`)
- [x] Search sessions by title, session id and message text from the sidebar search button (`GET /api/sessions/search`, debounced, with match snippet)

### 4. Chat
- [x] Send a prompt (`prompt.submit`); prompts sent mid-turn are marked queued; unsent text returns to the composer
- [x] A prompt that lost its reply offers Check delivery (reads the transcript again), Resend (warns first that Hermes may get it twice) and Edit; nothing resends on its own. The chat's title line tells no connection, connecting again and signed out apart
- [x] Streaming assistant text (`message.start`, `message.delta`, `message.complete`), including outcome (complete / stopped / error)
- [x] Interim commentary (`message.interim`), folded into the reply
- [x] Markdown rendering (GFM: lists, tables, links, code blocks with language label and copy button; copy whole reply); only web and mail links open
- [x] Pictures from the web load only on a tap, without gateway cookies
- [x] Syntax highlighting in code blocks (Highlights, cached by content; light and dark palettes); long lines scroll sideways or wrap ("Wrap code lines" setting)
- [x] TeX math: inline `$…$` / `\(…\)` and display `$$…$$` / `\[…\]` typeset with JLaTeXMath (display formulas centered and scrollable); prices like "$5 and $10" stay text, code is left alone, and a formula that won't parse shows its source
- [x] A small time under each prompt and finished reply ("Message timestamps" setting): the stored row's time, or when this device sent or saw it finish
- [x] Reasoning/thinking blocks, collapsible (`reasoning.delta`, `thinking.delta`, `reasoning.available`)
- [x] Stop a running turn (`session.interrupt`): the Send button turns into Stop while a turn runs
- [x] Prompts sent from another client (Desktop, CLI, messaging) show up live: a turn this client didn't start refetches the transcript at its start and end, since the gateway streams only the reply
- [x] Send while a reply runs, three ways: Steer (`session.steer`, the agent reads it after its current step; rejected → queued as the next prompt, a gateway without steering → plain `prompt.submit`), Queue (`prompt.submit {queued}`) and Stop & send (`session.interrupt`, wait for the turn to end, then `prompt.submit`). A Send button appears beside Stop; long-press it for a dropdown to pick the way for one message. `/steer <note>` uses `session.steer` too. A steer the turn ended before reading is requeued by the gateway and shows up as the next turn
- [x] Setting "While a reply is running, Send…": Steer (default), Queue or Stop & send
- [x] Voice chat has its own composer button instead of sharing the send button
- [x] Token usage per turn (live turns) and per session with cost, context window and account limits (`session.usage`, `MessageCompletePayload.usage`, `GET /api/sessions/{id}`)
- [x] Error banner and live status line (`error`, `status.update`, failed turns)
- [x] Notices and warnings (`notice`, `MessageCompletePayload.warning`)

### 5. Tool activity
- [x] Tool rows in the reply: name, what it is doing, running spinner → done (`tool.start`, `tool.complete` summary)
- [x] Expandable tool cards with full args and output, `tool.generating`
- [x] Completion details: duration, error flag, output preview, diffs (`tool.complete`)
- [x] Output-risk warnings (`tool.output_risk`)
- [x] Todo list updates (`todo.updated`)
- [x] One live status above the composer while a turn runs: the current action in plain words (waiting on you, the running tool, the plan step, the gateway status, else thinking) with the plan count; the status text, tool and plan fold beneath it, and the reply keeps only folded "Used … tools" and "Reasoning" rows

### 6. Interactive requests (server → client)
- [x] **Approval** panel for dangerous commands, with choices `once`, `session`, `always` (confirmed first), `deny` filtered by the gateway's `choices` / `allow_session`, `allow_permanent` and `smart_denied`. The command wraps, with "Show full command" and "Copy command"; each permission says where and how long it applies, the broader ones in their own section; taps are ignored for a moment after it appears; and the panel keeps a Stop for the running task
- [x] Requests withdrawn or answered elsewhere (`request.cancel`, turn end) and restored after reconnect (`open_requests`)
- [x] **Clarify**: single or batch questions, choices, multi-select and free text
- [x] **Sudo** password prompt (masked)
- [x] **Secret** env-var prompt (masked)
- [x] Desktop-only requests (`preview.*`, `terminal.read`, `window.read`, `tour`, `vault.*`) are left unanswered for another client: an error reply would settle them for every client
- [ ] Vault prompts (`vault.unlock_prompt`, `vault.code`, `vault.save_login`)

### 7. Settings (MVP)
- [x] Model picker in the composer (`model.options`), switched per chat (`config.set model … --session`; picks before the first send go into `session.create`), with a confirm for expensive models
- [x] Thinking level and fast mode per chat (`config.set reasoning` / `fast`), offered when the model supports them
- [x] Profile picker (account sheet → Profile; `GET /api/profiles`, chats and sessions scoped with `profile`)
- [x] Settings screen, opened from the account sheet
- [x] Theme: system, light, dark, plus pure black; window and system bars follow it
- [x] Text size on top of the system font scale
- [x] Chat background: a photo behind the conversation (frosted under the composer), with how strongly it shows
- [x] Show or hide reasoning and tool activity in the chat
- [x] Account (sign out, change gateway) and about (app and gateway versions, update check)
- [x] Manage several saved gateways: add, rename, remove (signs out of it), choose a primary (listed first, taken over when the gateway in use is removed). Switching keeps each one signed in and reopens where you left off on it; the app opens on the last one used

---

## Later

### Attachments & media
- [x] Image attachments from the photo picker or camera (`image.attach_bytes`), upright and scaled to 2048 px; a failed send detaches them (`image.detach`)
- [x] File and PDF attachments (`file.attach` as `@file:` refs, `pdf.attach` with a `file.attach` fallback when the gateway can't render pages)
- [x] Images in history: stored `@image:` refs fetched through `GET /api/media`, `@file:` refs as file cards, the attached-context block hidden
- [x] Pictures and files the agent delivers (`MEDIA:` paths, Markdown images, `::preview` widget files) shown inline or as cards, fetched through `GET /api/files/download`
- [x] Full-screen image viewer with pinch-zoom; Save (Pictures / Download `Herald`) and Share for pictures and files
- [x] Chats open as the `desktop` surface (`source` on `session.create` / `session.resume`), so the agent knows Markdown and files render
- [ ] Run `::preview` widgets inline (a WebView with Desktop's theme prelude)
- [x] Share sheet: text, links, pictures and files shared from other apps open a new chat with them as its draft (up to 10 files, through the composer's size limits); nothing sends until you do, and before sign-in the draft waits

### Voice
- [x] Dictation into the composer (phone mic → `POST /api/audio/transcribe`)
- [x] An empty chat offers "Attach" and "Dictate" under the wordmark, as one split capsule as wide as the lettering
- [x] Voice chat: listen, transcribe, send, read the reply aloud (`/api/audio/speak`, `tts-lease`), say "stop" to end; `/voice`
- [ ] Talk over the reply to interrupt it (barge-in) and speak while the reply streams (`/api/audio/speak-stream`)
- [ ] Live voice mode (`/api/audio/voice-live/*`)
- [ ] Wake word (phone mic → `wake.feed`, `wake.detected`)

### Slash commands & composer
- [x] Slash command catalog and autocomplete (`commands.catalog`, `complete.slash`, `slash.exec`, `command.dispatch`; skills, plus Desktop's own `/new`, `/model`, `/resume`, `/stop`, `/compress`, `/status`, `/btw`, `/reasoning`, `/yolo`, `/title`, `/branch`, `/profile`, `/handoff`, `/skin`)
- [x] Pet: Petdex gallery, adopt or put away, animated on the composer by the agent's activity (`pet.gallery`, `pet.info`, `pet.select`, `pet.disable`; `/pet`)
- [x] Journey: learned skills and memories by month, with their text (`/api/learning/graph`, `/api/learning/node`; `/journey`)
- [x] BTW side questions (`/btw` → `prompt.btw`, answered in place by `btw.complete`)
- [ ] Background prompts (`prompt.background`, `background.complete`)
- [ ] Message reactions (`message.react`)
- [x] Drafts: each chat keeps its unsent text (stored, survives a restart) and picked files (in memory) when you switch chats; the session list marks it "Draft"
- [x] Undo and branch from the composer (`/undo` hands the last prompt back; `/branch` → `session.branch_whole` / `session.branch`)
- [x] Undo and branch from a message: a ⋯ menu on each message with Copy, Edit last prompt (`/undo`, then the transcript reloads) and Branch from here (`session.branch` with the message's position), each change explained before it happens
- [x] Regenerate a reply and edit any prompt: one `prompt.submit` with `truncate_before_row_id` (the prompt's stored row), `confirm_truncate` and `confirm_empty_truncate` cuts the transcript and sends in its place; asks before discarding later messages; off mid-turn and for prompts the live agent no longer holds
- [x] Comment on part of a reply: select text and pick Comment; a numbered card with a note waits above the composer and the text stays highlighted. Each comment names its place (the reply, list item, code block line, tool or command output) and quotes the sentence or line around the selection, so a repeated word is still found; they go out together in one message, shown as cards in the sent bubble. Also on a prompt (its long-press menu), tool and command output and subagent results
- [x] Explain (asks at once when nothing else is waiting) and Ask aside (a `/btw` about the selection) in the same selection menu
- [x] Context compression (`/compress` → `session.compress`)
- [x] Context breakdown in the usage sheet (`session.context_breakdown`): the window split by system prompt, tools, skills, memory, conversation and the rest, plus the files read in

### Agents & processes
- [x] Subagent tree and live progress (`subagent.*`, `subagent.list`, `subagent.interrupt`; saved `spawn_tree.*` snapshots not used)
- [x] Background process list and kill, from the chat menu (`process.list`, `process.kill`; polled while open, with each one's output tail). Live `agent.terminal.output` streaming not used
- [ ] Rollback / checkpoints (`rollback.list`, `rollback.diff`, `rollback.restore`)

### Bots (Hermes Bot Mode; design in `docs/bot-mode-integration.md`)
- [x] Chats | Bots switch at the top of the sidebar, remembered per gateway
- [x] Bot roster (`profiles.list` over the socket, polled every 5 s while shown): Desktop's face (picture, or its shape and hue from the name), name, latest line of the Bot Chat, age, unread dot, working dot; hidden bots left out
- [x] Tapping a bot opens its one permanent Bot Chat in the bot's profile without changing the Chats profile; a bot that never had one gets it started (`session.create` hidden + `follow_profile_config`, `session.title "Bot Chat"`), never a second one when a lookup can't be trusted
- [x] In a Bot Chat, `/new` and `/reset` compress instead; `/title`, Rename, Pin, Archive and Delete are kept away from it
- [x] Messages from other bots (`Message from 🤖 …`) show as the sender's note in every chat, not as the user's prompt
- [x] Reopens the last bot's chat on launch; roster ordered as Desktop's (pinned, then newest activity or creation)
- [x] Transcript: delivery outcomes ("Reply from X", "Still waiting", "Couldn't reach X · why"), folded process rows, other bots' notes with their faces, replies to them folded, silence tokens hidden, routine notes, "Messaged X" for `message_agent`, failed turns; the status line of a turn woken by a reply says so
- [x] "Open elsewhere" (4090 `SESSION_NOT_OWNED`, and the gateway's chat cap) as its own state above the composer, with Send again, in any chat
- [x] Roster: long-press menu (Pin, Hide with a "Hidden" section, Open recent session, New chat with this bot, Start fresh), Thinking/Working, previews that name the bot that wrote, `@handle` for same names, bot face in the chat header; refresh on `sessions.changed` (bots' chats watched) with a 30 s fallback
- [x] Start fresh: archive the Bot Chat (kept, retired) and begin an empty one, from the roster or the chat menu; the open chat follows a bot whose chat was started over elsewhere
- [x] ⚠ attention badge for sign-in, quota or setup failures: each bot's `setup.runtime_check` once per connection, plus its failed turns and failed deliveries to it seen while a chat is open (passing failures like rate limits never count); the hint replaces the row's last line, Check again in its menu
- [x] New bot (the "+" by Bots): face (shape and colour), name → profile id as Desktop makes it, title, description, optional SOUL (else Desktop's generated persona, without the messaging section the gateway injects); `profiles.create` cloning the default's configuration with shared keys, look in `ui_meta`, `setup.runtime_check`, then its Bot Chat with the intro
- [x] Edit a bot (face, title, description, SOUL) sending only what changed; Duplicate (`clone_from`, look copied, "(copy)"); Delete (`DELETE /api/profiles/{name}`, asked first, not for the default)
- [x] Routines per bot (long-press → Routines): the bot's cron jobs (its own store, plus older Desktop ones tagged `[bot:<profile>]` in the launch store); new ones made in the bot's store as `[bot:<profile>] <name>`, reporting to its chat (`deliver: bot-chat`) or anywhere else; runs open in the bot's profile; what went wrong with the last run spelled out; a ⚠ on the bot while one of its routines is failing (not for ones the user paused)
- [ ] Avatar pictures (upload, `image.generate`), Desktop's blob faces, model picker per bot
- [x] Bot notifications: while out of sight, a bot writing in its chat arrives as an Android conversation notification from it (face, name, inline Reply into its chat); driven by `sessions.changed` on the open socket (Stay connected or a running turn), only for the bot's own words, never hidden bots
- [x] Each bot a launcher conversation shortcut; `hermes://bot/<profile>` opens its chat
- [x] @mentions: `@` in the composer offers the bots with their faces; picking inserts `@handle`
- [x] Bot exchanges drawn as a conversation: the other bot speaking in its colour, this bot's messages to it as "To X" notes
- [ ] Needs-you inbox across bots, share-to-bot, "ask <bot>" by voice, a Settings switch for bot notifications
- [ ] Rooms: Desktop rooms read-only from the `hermes-bots-groups` mirror; hosted rooms (`groups.*`) to take part

### Automation & configuration
- [x] Scheduled jobs in the sidebar (`GET /api/cron/jobs`): schedule in plain words, next run, state; a job page with its prompt, last error, Run now, Pause / Resume, and its runs (`/api/cron/jobs/{id}/runs`), each opening as a chat
- [x] Create, edit and delete cron jobs (`POST`/`PUT`/`DELETE /api/cron/jobs`): prompt, free-text schedule with presets, name, and delivery target (`/api/cron/delivery-targets`); refetch on `cron.changed`
- [x] Skills: a Capabilities page in the sidebar, after Desktop's, lists the profile's skills by category with search and an on/off switch (`GET /api/skills`, `PUT /api/skills/toggle`). Installing from the hub (`skills.manage`) not yet
- [x] Toolsets on the Capabilities page with their switches and a "needs setup" tag (`GET /api/tools/toolsets`, `PUT /api/tools/toolsets/{name}`)
- [x] MCP servers on the Capabilities page: switch, and a connection test listing its tools (`/api/mcp/servers`, `…/{name}/enabled`, `…/{name}/test`). Adding servers and OAuth not yet
- [ ] Config viewer and editor (`config.get`, `config.set`)
- [ ] Projects and workspaces (`projects.*`)
- [x] Insights page in the sidebar, after Desktop's (`GET /api/analytics/usage`): cost, sessions, tokens and cache share over 7/30/90 days, tokens by day (tap a day), top models, tools and skills. `usage.bars` (subscription limits) not yet

### Notifications & background
- [x] Foreground service that keeps the WS alive during long runs
- [x] Live Update while a turn runs (Android 16 status-bar chip): current tool or status, Stop button
- [x] Local notifications: approval waiting, run finished; tapping one opens its chat
- [x] Session list filters All / Running / Needs attention, with text labels for a waiting approval or question (tracked across chats this phone has opened), "Running" and "New reply" (read state kept per gateway and profile)
- [x] Answer from the notification: approve/deny, clarify answers, inline reply to a finished turn (each asks for an unlock first)
- [x] Notification toggles in Settings; permission asked on the first turn
- [x] Stay connected: keeps the socket up in the background (quiet notification) so turns started on other devices reach the open chat
- [ ] Gateway-pushed `notification.show`
- [ ] Follow all sessions: notify for chats other than the open one (watch `session.active_list`, attach to sessions that start a turn)
- [x] Notifications anywhere: bot messages reach the phone off the gateway's network, end-to-end encrypted and signed through ntfy, without Google services. The herald-push plugin is installed from Settings in one tap ([protocol](push-protocol.md))
- [ ] Reply and approve from a push notification when the gateway is out of reach (the `p2g` direction)
- [ ] UnifiedPush distributor support, so the ntfy app holds the one connection instead of Herald's foreground service

### Auth & connectivity extras
- [ ] Native OAuth sign-in (RFC 8252 PKCE via Custom Tab and a loopback redirect; `/auth/native/*`)
- [ ] QR-code pairing
- [ ] Tailscale / MagicDNS setup guide
- [ ] Gateway API server "lite" mode (`:8642`, static bearer key, Runs API + SSE)

### Platforms & polish
- [x] 48dp touch targets (`MinTouchTarget`) on icon buttons, the composer, the top bar and the sidebar rows, without enlarging the icons
- [x] Launcher shortcuts New chat and New chat with voice (starts dictating); links `hermes://new-chat`, `hermes://new-chat-voice` and `hermes://session/<id>`
- [x] Web links in replies open in a Custom Tab in the app's colors, or the browser when there's none
- [x] App lock (Settings → Privacy): fingerprint, face or screen lock on launch and after a minute away; content hidden in Recents
- [ ] Tablet and foldable adaptive layout (list and detail side by side)
- [ ] Home-screen widget and Quick Settings tile
- [ ] Offline cache of sessions and messages
- [ ] iOS app
- [ ] Desktop (JVM) app
- [ ] Wear OS companion
- [ ] Localization (`i18n.catalog`)
