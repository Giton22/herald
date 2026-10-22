# Roadmap

What Herald does today and what's planned, feature by feature, with the gateway protocol each one
uses. The protocol itself is described in [hermes-protocol-research.md](hermes-protocol-research.md).

- [x] implemented
- [ ] not started
- 🚧 in progress (written next to the item)
- ⛔ not planned (written next to the item)

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
- [x] Room KMP for the local cache
- [ ] DataStore for settings
- [x] Secure storage on Android (AES-GCM key in the Android Keystore) for the session cookie jar
- [ ] Secure storage on iOS (Keychain)
- [x] Markdown rendering (`multiplatform-markdown-renderer`)
- [ ] Coil 3 for images
- [x] Unit tests (Ktor `MockEngine`, coroutines-test)
- [x] CI on GitHub Actions: unit tests and a debug build on every push
- [x] Android lint in CI (`lintDebug`); a baseline holds the old issues, so only new ones fail the build
- [x] Releases: a `v1.2.3` tag builds a signed APK into a draft GitHub release; the app offers newer releases
- [x] Per-ABI APKs beside the universal one, a "Which APK?" table in the notes, and the update banner downloads the device's own

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
- [x] `DropdownMenu`, and `Toast` with an action ("Archived · Undo")
- [ ] Chat bubble, tool card, approval card as design-system components (they live in `shared/ui` for now)
- [x] Theme picker (system / light / dark) and pure black
- [x] Accent color presets (Settings → Appearance → Accent): blue, violet, green, orange, pink and teal, each tuned for light, dark and pure black
- [x] App icon (Settings → Appearance → App icon): the mark on each accent color, or over hearts, marks or chat bubbles
- [x] Glow switch (Settings → Appearance): off, the colored glows go (the halo behind each screen, and the light under prompts, the app mark, Send, New chat, main buttons, live subagent cards, the voice screen, and the assistant edge's halo, which keeps its crisp line) and everything sits flat

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
- [x] Gateways behind Cloudflare Access: a per-address service token (`CF-Access-Client-Id` / `CF-Access-Client-Secret`, stored encrypted, sent over HTTPS only) on every call and the `/api/ws` upgrade; an Access login redirect or Cloudflare 403 is named as such in the connection check and errors
- [x] Clear error messages for 401, 429, WS close 4401 (bad ticket) and 4403 (Host/peer guard). Note: the server rejects these before the upgrade, so they arrive as an HTTP 403 handshake failure
- [x] Sign out (server-side revoke + local cookie wipe), and "use a different gateway"

### 2. WebSocket JSON-RPC transport
- [x] Connect to `/api/ws` with subprotocols `hermes-gateway-v1` and `hermes-gateway-ticket.<t>`
- [x] Newline-delimited JSON-RPC 2.0: requests, responses, notifications
- [x] Wait for `gateway.ready`, then send `client.capabilities {server_requests: true}`
- [x] Handle server→client requests (`srq-<n>`) and answer them with the matching id (approval, clarify, sudo, secret, `vault.unlock_prompt`, `vault.code`, `vault.save_login`)
- [x] Methods the app can't show (`preview.*`, `terminal.read`, `window.read`, `tour`) get the `4404` decline when the gateway counts declines (`client.capabilities` → `declines_not_shown`), so Desktop can still answer them and the agent stops waiting once every client declined; never `-32601`, which would settle them for Desktop too
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
- [x] Sessions live in a left sidebar: a swipe-in drawer on phones (swipe right or the panel button; swipe left off the chat rows, tap outside or Back to close), docked and collapsible on wide screens; the open chat is highlighted
- [x] Sidebar layout: title with a round search button, Scheduled / Archived rows, title-only chat rows with a live dot for running sessions, floating "New chat" pill and an initials avatar (with a connection warning dot) that opens the account sheet
- [x] Refetch the list whenever the sidebar opens
- [x] Rename, pin, archive and delete (`PATCH`/`DELETE /api/sessions/{id}`), with optimistic updates that roll back on error
- [x] Swipe a chat row: right pins or unpins, left archives (with Undo); in Archived, right unarchives and left deletes (after asking). A row's swipe wins over the drawer's swipe-left close; screen readers get the same actions
- [x] Chat options (⋮ beside new chat) for the open chat: rename, pin, export as Markdown, copy session ID, archive, delete
- [x] Floating see-through composer over the conversation
- [x] Reading back stays put while a reply streams below; the jump arrow then reads "New reply"
- [x] Live list updates (`sessions.changed`, `session.title` → refetch; also refetch after reconnect)
- [x] Live per-session status in the list, turns started on other clients included: `session.active_list` is asked on connect, after turn and list events, and every 10 s while the list shows; a chat waiting on a request this phone never saw reads "Waiting for you"
- [x] Search sessions by title, session id and message text from the sidebar search button (`GET /api/sessions/search`, debounced, with match snippet)

### 4. Chat
- [x] Send a prompt (`prompt.submit`); prompts sent mid-turn are marked queued; unsent text returns to the composer
- [x] A prompt that lost its reply offers Check delivery (reads the transcript again), Resend (warns first that Hermes may get it twice) and Edit; nothing resends on its own. The chat's title line tells no connection, connecting again and signed out apart
- [x] Streaming assistant text (`message.start`, `message.delta`, `message.complete`), including outcome (complete / stopped / error)
- [x] Interim commentary (`message.interim`), folded into the reply
- [x] Markdown rendering (GFM: lists, tables, links, code blocks with language label and copy button; copy whole reply); only web and mail links open
- [x] Tables in replies size each column by its cells (a column of emoji or numbers stays slim, wordy ones share the rest and wrap, and only a table whose longest words don't fit scrolls sideways), with faint grid lines and every second row tinted
- [x] Pictures from the web load only on a tap, without gateway cookies
- [x] Optional webpage cards beneath completed replies (Settings → Chat → Show link previews, off by default): up to three unique links, with title, description and thumbnail when available; fetched without gateway cookies, never from loopback, LAN, link-local or tailnet addresses (checked again after DNS)
- [x] Syntax highlighting in code blocks (Highlights, cached by content; light and dark palettes); long lines scroll sideways or wrap ("Wrap code lines" setting)
- [x] TeX math: inline `$…$` / `\(…\)` and display `$$…$$` / `\[…\]` typeset with JLaTeXMath (display formulas centered and scrollable); prices like "$5 and $10" stay text, code is left alone, and a formula that won't parse shows its source
- [x] A small time under each prompt and finished reply ("Message timestamps" setting): the stored row's time, or when this device sent or saw it finish
- [x] Reasoning/thinking blocks, collapsible, with compact Markdown rendering (`reasoning.delta`, `thinking.delta`, `reasoning.available`)
- [x] Stop a running turn (`session.interrupt`): the Send button turns into Stop while a turn runs
- [x] Prompts sent from another client (Desktop, CLI, messaging) show up live: a turn this client didn't start refetches the transcript at its start and end, since the gateway streams only the reply
- [x] Send while a reply runs, three ways: Steer (`session.steer`, the agent reads it after its current step; rejected → queued as the next prompt, a gateway without steering → plain `prompt.submit`), Queue (`prompt.submit {queued}`) and Stop & send (`session.interrupt`, wait for the turn to end, then `prompt.submit`). A Send button appears beside Stop; long-press it for a dropdown to pick the way for one message. `/steer <note>` uses `session.steer` too. A steer the turn ended before reading is requeued by the gateway and shows up as the next turn
- [x] Setting "While a reply is running, Send…": Steer (default), Queue or Stop & send
- [x] Voice chat has its own composer button instead of sharing the send button
- [x] Token usage per turn (live turns) and per session with cost, context window and account limits (`session.usage`, `MessageCompletePayload.usage`, `GET /api/sessions/{id}`)
- [x] Error banner and live status line (`error`, `status.update`, failed turns)
- [x] Notices and warnings (`notice`, `MessageCompletePayload.warning`)
- [x] A New messages line where the unread messages start; a chat with unread messages (opened from the list or from a notification) opens there instead of at the bottom. The line stays put while the chat is open, so it does not jump while you read, and is gone the next time it is opened (the last look is kept per chat on the phone). Other bots' messages count as new; when the first new message is more than five pages back, there is no line rather than a misplaced one

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
- [x] Desktop-only requests (`preview.*`, `terminal.read`, `window.read`, `tour`) are left unanswered for another client: an error reply would settle them for every client
- [x] Vault prompts: the password manager's master password (`vault.unlock_prompt`, masked), a sign-in code (`vault.code`, with one-time-code autofill) and a login to save for the page the agent is on (`vault.save_login`, with username and password autofill); Skip and Don't save send an empty value, and the notification opens the chat instead of answering inline

### 7. Settings (MVP)
- [x] Model picker in the composer (`model.options`), switched per chat (`config.set model … --session`; picks before the first send go into `session.create`), with a confirm for expensive models
- [x] Star the models you use most (kept on the device): the picker opens on Your models, starred and recently picked, with chips for All and for each provider
- [x] One row per model: the same model from several providers shows once, with a pill per provider (and its price) to pick which
- [x] Ranked model search: name starts first, then words, then anywhere; "op55" finds Opus 5.5; the start of a provider's name lists its models after the name matches
- [x] Thinking level and fast mode per chat (`config.set reasoning` / `fast`), offered when the model supports them
- [x] Profile picker (account sheet → Profile; `GET /api/profiles`, chats and sessions scoped with `profile`)
- [x] Settings screen, opened from the account sheet
- [x] Theme: system, light, dark, plus pure black; window and system bars follow it
- [x] Text size on top of the system font scale
- [x] Chat background: a photo behind the conversation (frosted under the composer), with how strongly it shows
- [x] Show or hide reasoning and tool activity in the chat
- [x] Starting instruction (Settings › Chat): text every new chat started in Herald (chats, threads, the watch, the assistant) tells the agent first, such as "I'm on my phone: keep replies short". It goes into `session.create` as a hidden seed row (`display_kind: "hidden"`), so no client shows it
- [x] Account (sign out, change gateway) and about (app and gateway versions, update check)
- [x] Manage several saved gateways: add, rename, remove (signs out of it), choose a primary (listed first, taken over when the gateway in use is removed). Switching keeps each one signed in and reopens where you left off on it; the app opens on the last one used

---

## Later

### Attachments & media
- [x] Image attachments from the photo picker or camera (`image.attach_bytes`), upright and scaled to 2048 px; a failed send detaches them (`image.detach`)
- [x] File and PDF attachments (`file.attach` as `@file:` refs, `pdf.attach` with a `file.attach` fallback when the gateway can't render pages)
- [x] Images in history: stored `@image:` refs fetched through `GET /api/media`, `@file:` refs as file cards, the attached-context block hidden
- [x] Pictures and files the agent delivers (`MEDIA:` paths, Markdown images, `::preview` widget files) shown inline or as cards, fetched through `GET /api/files/download`
- [x] Links to files on the gateway (`[name](/path)`, `file://`, `/src/App.kt:42`) open full screen: Markdown rendered, JSON indented, CSV/TSV as a table, code with line numbers at the linked line; Save and Share fetch the whole file (`GET /api/fs/read-text`, `GET /api/fs/download`)
- [x] Full-screen image viewer with pinch-zoom; Save (Pictures / Download `Herald`) and Share for pictures and files
- [x] Chats open as the `desktop` surface (`source` on `session.create` / `session.resume`), so the agent knows Markdown and files render
- [x] Run self-contained HTML `::preview` widgets inline (an isolated WebView with Desktop-compatible theme colors and Herald's font; a scrollable panel, with Save and Share; requires Android System WebView 152+). External resources and widget-to-agent messages aren't supported yet
- [x] Share sheet: text, links, pictures and files shared from other apps open a new chat with them as its draft (up to 10 files, through the composer's size limits); nothing sends until you do, and before sign-in the draft waits

### Voice
- [x] Dictation into the composer (phone mic → `POST /api/audio/transcribe`)
- [x] Dictation with the phone's own speech recognizer (Settings → Voice → Dictate with: Device or Gateway), words appear as you talk; falls back to the gateway when no recognizer can start
- [x] Dictation switch (Settings → Voice): off, the mic leaves the composer and the empty chat's pills, so the model name has room on a narrow phone; a dictation already running (the New chat with voice shortcut) keeps its mic to finish it
- [x] An empty chat offers "Attach" and "Dictate" under the wordmark, as one split capsule as wide as the lettering
- [x] Voice chat: listen, transcribe, send, read the reply aloud (`/api/audio/speak`, `tts-lease`), say "stop" to end; `/voice`
- [x] Send now in the voice chat: once it hears you, an arrow sends what's been said without waiting for the pause, for a car or wind where the pause never comes; the end of speech also lets brief noise (a gust, a fan) pass without starting the pause over
- [x] Speak while the reply streams (`/api/audio/speak-stream`): in the voice chat, the reply's text goes to the gateway as it's written and its audio plays as it comes back (an `AudioTrack` stream), so the first sentence is heard long before the reply ends; code blocks and attached files aren't sent. The orb shows Speaking while sound plays and Thinking in the pauses while the agent works. A gateway without the socket, or one that made no audio, reads the finished reply as before; Skip stops it without reading it again
- [x] Read aloud: a speaker under a finished reply reads it in the gateway's voice (`/api/audio/speak`), without its code, links, pictures or files; Stop takes its place while it plays, and the screen stays on until it ends. Read again, it plays the same take from its start, kept for the last few replies for a day, rather than asking the gateway for a new one. Another reply, a voice chat, dictation, another chat, the reply leaving the chat (regenerated, say) or leaving the app stops it; a gateway that can't speak says why in one line
- [x] Talk over the reply to interrupt it (barge-in): while the agent works and while it speaks, the call microphone (with the phone's echo cancelling) listens with Desktop's phase-aware trigger, which also learns how loud the voice comes back so a loudspeaker doesn't cut in on itself. Talking over it holds the voice at once; someone who goes on talking stops it and the turn, and what's said, from its first syllable, is the next prompt. When nobody does, it was the phone hearing its own voice (a speaker turned right up), and the voice goes on where it held. `voice.barge_in` off disarms it, `voice.barge_in_threshold_multiplier` sets how readily it cuts in, `voice.barge_in_grace_seconds` how long the voice's first sound can't
- [x] Live voice mode, Desktop's GPT-Live (`voice.voice_chat_mode: gpt-live`): the voice button opens a WebRTC call with OpenAI's voice model through the gateway (`/api/audio/voice-live/status`, `/api/audio/voice-live/session`); each request it hands over (`session.delegation.created`) is a Hermes turn in the chat (`prompt.submit` with `surface: voice-live` and the spoken context), and the reply streams back a sentence at a time (`session.commentary.append`) with the running tool as quiet progress; talk over it to cut in, Mute, say "stop" to end. Another mode, no OpenAI key on the gateway, or an older gateway keeps the voice chat above
- [ ] Wake word (phone mic → `wake.feed`, `wake.detected`)

### Slash commands & composer
- [x] Slash command catalog and autocomplete (`commands.catalog`, `complete.slash`, `slash.exec`, `command.dispatch`; skills, plus Desktop's own `/new`, `/model`, `/resume`, `/stop`, `/compress`, `/status`, `/btw`, `/reasoning`, `/yolo`, `/title`, `/branch`, `/profile`, `/handoff`, `/skin`)
- [x] Pet: Petdex gallery, adopt or put away, animated on the composer by the agent's activity (`pet.gallery`, `pet.info`, `pet.select`, `pet.disable`; `/pet`)
- [x] Journey: learned skills and memories by month, with their text (`/api/learning/graph`, `/api/learning/node`; `/journey`)
- [x] BTW side questions (`/btw` → `prompt.btw`, answered in place by `btw.complete`)
- [x] Background prompts: `/bg <prompt>` (or `/background`) runs it on a fresh agent beside the chat (`prompt.background`), and the answer fills its card when `background.complete` arrives, also for one started on Desktop; a crashed task shows as failed. A gateway without the method runs `/bg` on the slash worker as before
- [ ] Message reactions (`message.react`)
- [x] Drafts: each chat keeps its unsent text (stored, survives a restart) and picked files (in memory) when you switch chats; the session list marks it "Draft"
- [x] Suggested replies: with the community Next Prompt plugin on the gateway (`GET /api/plugins/next-prompt/suggestion`, `POST …/dismiss`), the reply the agent expects next sits as a chip above an empty composer; a tap puts it in the composer without sending, × dismisses it for every client. Settings → Chat → Suggested replies turns it off, shows whether the plugin is there, and offers to install it at a pinned commit (`plugins.manage`); a gateway that only serves new plugins' routes after a restart says so
- [ ] Suggested replies as quick-reply choices in reply notifications
- [x] Undo and branch from the composer (`/undo` hands the last prompt back; `/branch` → `session.branch_whole` / `session.branch`)
- [x] Undo and branch from a message: a ⋯ menu on each message with Copy, Edit last prompt (`/undo`, then the transcript reloads) and Branch from here (`session.branch` with the message's position), each change explained before it happens
- [x] Regenerate a reply and edit any prompt: one `prompt.submit` with `truncate_before_row_id` (the prompt's stored row), `confirm_truncate` and `confirm_empty_truncate` cuts the transcript and sends in its place; asks before discarding later messages; off mid-turn and for prompts the live agent no longer holds
- [x] A finished reply's Copy, Regenerate and Branch sit on the right, under the thumb, with Copy last; its token usage moves to the left
- [x] Comment on part of a reply: select text and pick Comment; a numbered card with a note waits above the composer and the text stays highlighted. Each comment names its place (the reply, list item, code block line, tool or command output) and quotes the sentence or line around the selection, so a repeated word is still found; they go out together in one message, shown as cards in the sent bubble. Also on a prompt (its long-press menu), tool and command output and subagent results
- [x] Explain (asks at once when nothing else is waiting) and Ask aside (a `/btw` about the selection) in the same selection menu
- [x] Context compression (`/compress` → `session.compress`)
- [x] Context breakdown in the usage sheet (`session.context_breakdown`): the window split by system prompt, tools, skills, memory, conversation and the rest, plus the files read in

### Agents & processes
- [x] Subagent tree and live progress (`subagent.*`, `subagent.list`, `subagent.interrupt`; saved `spawn_tree.*` snapshots not used)
- [x] Background process list and kill, from the chat menu (`process.list`, `process.kill`; polled while open, with each one's output tail). Live `agent.terminal.output` streaming not used
- [x] Rollback / checkpoints (`rollback.list`, `rollback.diff`, `rollback.restore`): Checkpoints in the chat menu, or `/rollback`, lists the snapshots Hermes took before the agent changed files; open one for what changed since (added lines green, removed red), and Restore it after a confirm, which also takes back the chat's last turn and reads the transcript again. Says when checkpoints are off in the profile
- [x] Goal / loop / heartbeat panel (`session.control.read`, `session.control`, `session.control.update`): a strip above the composer shows the chat's goal, loop and heartbeat with their state and progress; a sheet (the strip, or "Goal and loops" in the chat menu) pauses, resumes, unwaits, stops and clears, edits sub-goals, and sends the kickoff prompt a goal resume returns (queued while a turn runs)

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
- [x] Model per bot: the editor's Model row (`profiles.configure` model + provider, the expensive-model confirmation), and a model picked in a Bot Chat's composer becomes the bot's own instead of pinning the chat (#129460)
- [x] Skills, toolsets and MCP servers per bot: the editor opens the Capabilities page on the bot's profile, saying a running Bot Chat may keep its old tools (#124211)
- [ ] Avatar pictures (upload, `image.generate`), Desktop's blob faces
- [x] Bot notifications: while out of sight, a bot writing in its chat arrives as an Android conversation notification from it (face, name, inline Reply into its chat); driven by `sessions.changed` on the open socket (Notifications anywhere or a running turn), only for the bot's own words, never hidden bots
- [x] Each bot a launcher conversation shortcut; `hermes://bot/<profile>` opens its chat
- [x] @mentions: `@` in the composer offers the bots with their faces; picking inserts `@handle`
- [x] Bot exchanges drawn as a conversation: the other bot speaking in its colour, this bot's messages to it as "To X" notes
- [x] Needs-you inbox across bots: at the top of the roster, bots held up on an answer (approval, question, secret) first, then bots with a ⚠, then failing routines, one row each, tapping to the chat or the routines; the count on the Bots tab; a held-up bot's own row says so
- [x] Share-to-bot: bots show in the share sheet; a share opens the bot's chat with the content after what was typed there (nothing sends until you do). Shortcuts follow the roster, so a removed or renamed bot leaves the share sheet, and a share to one that's gone opens a new chat instead
- [ ] "Ask <bot>" by voice, a Settings switch for bot notifications
- [x] Rooms: Desktop rooms read-only from the `hermes-bots-groups` mirror; hosted rooms (`groups.*`) to take part
- [x] Rooms: attach photos and files on gateways advertising `groups.attachment.upload` (8 files, 15 MB each, 25 MB per message)
- [x] Rooms: select message text to copy a passage, or copy a bot's whole message with its Markdown; also in read-only Desktop rooms
- [x] Rooms: gateway file links open in the existing viewer, with Save and Share through the speaking bot's profile
- [x] Rooms: scroll up for older messages (`groups.log` read back a window at a time, the reader's place kept)
- [x] Rooms: notifications for bots' lines while Herald is out of sight (one conversation per room, a tap opens it), and an unread dot on room rows
- [x] Rooms: @mention a member (`@` lists Everyone and the members with their faces; the composer says who answers first, by the gateway's own rule)
- [x] Rooms: who's replying and who's next, read off the room's log; a member that passes says so

### Automation & configuration
- [x] Scheduled jobs in the sidebar (`GET /api/cron/jobs`): schedule in plain words, next run, state; a job page with its prompt, last error, Run now, Pause / Resume, and its runs (`/api/cron/jobs/{id}/runs`), each opening as a chat
- [x] Create, edit and delete cron jobs (`POST`/`PUT`/`DELETE /api/cron/jobs`): prompt, free-text schedule with presets, name, and delivery target (`/api/cron/delivery-targets`); refetch on `cron.changed`
- [x] Skills: a Capabilities page in the sidebar, after Desktop's, lists the profile's skills by category with search and an on/off switch (`GET /api/skills`, `PUT /api/skills/toggle`). Skills hub (Capabilities → Skills → Browse hub): featured and official catalog, search, preview with `SKILL.md`, a security scan before installing anything not official, install, uninstall hub skills, Update all (`/api/skills/hub/*`, actions polled through `/api/actions/{name}/status`)
- [x] Toolsets on the Capabilities page with their switches and a "needs setup" tag (`GET /api/tools/toolsets`, `PUT /api/tools/toolsets/{name}`)
- [x] MCP servers on the Capabilities page: switch, and a connection test listing its tools (`/api/mcp/servers`, `…/{name}/enabled`, `…/{name}/test`). Adding servers and OAuth not yet
- [x] Config page in the sidebar, after Desktop's Settings (`GET /api/config/schema`, `GET`/`PUT /api/config`): Desktop's sections (Model, Chat, Workspace, Safety, Browser, Memory and context, Voice, Advanced) with its labels, Voice showing only the picked providers' fields; a search over every field in the schema; All settings by the gateway's own categories. A switch, a picker sheet (searchable when long, "Other…" for voices and models off the list) or a text field per type; each change saved at once as a patch of just that key and put back if refused; emptying the toolsets asks first. Edit config.yaml as a whole (`/api/config/raw`) behind a confirm. Each bot's config from its editor
- [x] Projects in the chat list (`projects.tree`, `projects.project_sessions`): a chip per project with chats, as Desktop groups them by folder; a new chat started under one runs in its folder (`session.create` `cwd`)
- [x] A pinned chat's row names its project after the title, lightly, except inside that project's own list (`ProjectIndex` maps a chat, or any id in its compression lineage, to its project)
- [x] Threads: permanent chats in a section above Pinned, in an order you set. Start fresh archives the old chat as "<name> · date time" and opens a new one in the same slot; renaming a thread retitles its chat. Kept in the profile's `ui_meta`, so every phone sees them
- [x] Projects as a list (Settings → Appearance → Projects as a list): one button where the chips were, naming the picked project, that opens a menu of every project with its chat count and a tick on the picked one; long names keep their room, and a long list scrolls with its cut edge faded. Off by default, the chips as before. Every menu now scrolls when the screen is too short for it, where its last rows used to be cut off
- [ ] A chat's working folder: show it, and change it with a folder picker (`complete.path`, `session.cwd.set`, `session.workspace.move`)
- [x] Create, rename and delete projects (`projects.create`, `projects.update`, `projects.delete`): a New project button beside the project chips (name and an optional folder on the gateway, typed or picked by browsing the gateway's folders with `complete.path`), and the picked project's options: Rename and Delete for one you made, "Save as project" for a folder the gateway only grouped chats by. A project you made shows before it has chats
- [x] Insights page in the sidebar, after Desktop's (`GET /api/analytics/usage`): cost, sessions, tokens and cache share over 7/30/90 days, tokens by day (tap a day), top models, tools and skills. `usage.bars` (subscription limits) not yet

### Notifications & background
- [x] Inbox: finished runs of the cron jobs you follow, newest first and grouped by day, each read in full as Markdown; per job Off, Inbox (quiet) or Notify, plus a notification for any failed run; an unread count on the sidebar tile. Chips along the top show one job's history (a chip per job, with its unread count) or only the failed runs; or, switched to My groups, the user's own groups of jobs (such as RUBIX and Personal, with Other for the rest), made, renamed and filled on a Chips and groups page and kept on the phone. Runs come from the herald-push plugin (0.4.0, `/herald-push cron`), which reads the gateway's cron output files, so a job keeps its own delivery (Telegram and so on). Pushed runs are kept on the phone and read offline
- [x] Foreground service that keeps the WS alive during long runs
- [x] One constant, quiet ongoing notification ("Herald · Connected to Hermes") in place of a Live Update; what a turn does shows in the app
- [x] Local notifications: approval waiting, run finished; tapping one opens its chat
- [x] Session list filters All / Running / Needs attention, with text labels for a waiting approval or question (tracked across chats this phone has opened), "Running" and "New reply" (read state kept per gateway and profile)
- [x] Answer from the notification: approve/deny, clarify answers, inline reply to a finished turn (each asks for an unlock first)
- [x] Notification toggles in Settings; permission asked on the first turn
- [x] Notifications anywhere is the one background mode: it keeps Herald up (quiet notification) and comes back after a reboot or update. Stay connected was removed
- [x] The gateway's notices (`notification.show`, `notification.clear`): credits and "still starting the agent" show as banners above the composer, one per key, by level; timed ones go by themselves
- [x] Follow all sessions: approvals, questions and finished replies notify for chats other than the open one, turns started on Desktop or the CLI included. Each chat that `session.active_list` shows running is attached with `session.activate` (never `session.close`, which would end it for every client). Approve, answer and reply from the notification. Needs the socket up: in sight, during a turn, or with Notifications anywhere
- [x] Notifications anywhere: bot messages reach the phone off the gateway's network, end-to-end encrypted and signed through ntfy, without Google services. The herald-push plugin is installed from Settings in one tap ([protocol](push-protocol.md))
- [ ] Reply and approve from a push notification when the gateway is out of reach (the `p2g` direction) ⛔
- [ ] UnifiedPush distributor support, so the ntfy app holds the one connection instead of Herald's foreground service

### Phone abilities for the agent
Agent tools that run on the phone: a gateway plugin registers them, Herald answers over the dashboard socket (woken by push when it's away), and a tool says "phone offline" after a timeout. Every tool below, the incoming call included, has its own switch in Settings, plus one switch for all of them; all are off until the user turns them on. A tool that is off is not offered to the agent at all (the phone tells the plugin which tools it has, and the plugin offers only those), so the agent can't even ask. Turning a tool off takes effect at once, and a request already waiting for it is refused. Anything that changes the phone or your data asks on the phone first. Notifications, photos and files are left out on purpose: they would send one-time codes and private messages to the gateway, and text from strangers would reach the agent.
- [x] Settings → Phone tools: one switch for all of them and one per tool, all off; the herald-push plugin (0.2) offers the agent only the tools switched on, in new chats (Hermes fixes a chat's tools when it starts; an open chat calling a tool that's off is refused). Calls are fetched when a watched chat starts a `phone_*` tool, on a push, and every 15 s; each phone answers with its own secret key; turns from messaging platforms are refused. The page lists what the agent used lately
- [x] Battery and charging state
- [x] Network state (Wi-Fi or mobile, offline)
- [x] Do Not Disturb, ringer and volume (read and set). A change asks first in a notification (Allow does it from the tap, Deny or a swipe refuses, no answer in 110 s refuses), and the result is read back. From Android 15 an app only turns off a Do Not Disturb it turned on, so the agent is told that up front
- [x] Changes ask on the phone first; the page asks for what a tool needs (notifications, Do Not Disturb access) when it's switched on
- [x] Set alarms and timers (in the phone's clock app, after Allow; the user sees and cancels them there)
- [x] App screen time (Usage Access): per app, screen-on time and unlocks, today or up to a week by day
- [x] Open URLs, maps, apps or the share sheet (after Allow; a link's real site is shown first, lookalike and hidden-character links refused)
- [x] Write to the clipboard (after Allow; up to 300 characters, all shown in the question; hidden characters refused; the clipboard is never read)
- [x] Location (Android's own location service, no Google services; precise or approximate as the user allows; while Herald is open, or also while closed if the user allows that from its own row)
- [x] Geofences ("when I get home…"): watched places on Android's own proximity alerts; adding asks first; arriving or leaving sends the message to the chat it was added from, marked as coming from the place; listed and removable in Settings → Phone tools
- [x] Calendar (the phone's calendar provider: list events in a range; adding or moving asks first, showing when, where and which calendar; repeating events and invitations the user doesn't organize are left to the calendar app)
- [x] Contacts lookup (by name from the start of a word, by at least 5 digits of a number, or by email; only names, numbers and emails of contacts the Contacts app shows, at most 20 people a call; never writes, and never calls them: the agent can't place calls from the phone)
- [x] Health data (Health Connect, read only: steps, sleep sessions with stages, heart rate min/avg/max, each as the user allows there; totals per day, at most 31 days; while Herald is open, unless the user also allows reading while it's closed)
- The agent calls you when it needs an answer. It only ever rings you: the agent never places calls from the phone (Herald has no CALL_PHONE)
  - [x] The phone rings (Settings → Phone tools → Calls, off until turned on): an approval or a clarify question from a chat out of sight rings as an incoming call, once; over the lock screen or with the screen off it takes the whole screen, naming only the chat and whether it's an approval or a question. Answer unlocks and opens the chat; Decline, or 45 s without an answer, leaves the usual notification. Passwords and codes never ring
  - [x] Answer by voice: picking up puts Hermes on the line, a call screen with no chat behind it (timer, captions, Mute, End): it asks in its own words, takes the spoken answer, and you can talk on ("Talk when I answer", on with the calls; off, Answer opens the chat). Hung up unanswered, the usual notification comes back. Any voice chat does the same for an approval or a question Hermes stops on: the chained one reads it out and listens, asking again when it isn't clear; a GPT-Live call has the voice ask it and takes the next thing said as the answer, without stopping the turn. Passwords and codes stay on the screen; several questions at once are left there too in a live call
  - [x] The watch rings too, and answers from the wrist ("Ring my watch too", on with the calls; only when the gateway talks live): Herald on the watch rings at once, over the watch face or a dark screen, naming only the chat and whether it's an approval or a question. Answer unlocks the watch and starts a live call from it in that chat, where the voice asks; the phone stops ringing and keeps a quiet notification. Decline, on either, stops both and leaves the usual notification on the phone. One call with Hermes at a time, on the phone or the watch: nothing rings over one, and a second is refused with why
  - [x] The agent calls you on demand ("Hermes can call me", Calls, off until turned on): `phone_call_me(reason, details?)` from any chat or a scheduled job rings the phone and the watch like the calls above. The ring travels in the push too, so it reaches the phone off the gateway's network. Answering starts a voice call in that chat that opens with why Hermes called; the tool waits for the call and tells the agent whether it was answered, declined, not answered in about 45 s or busy, and to reach you another way if not. It takes no number and only ever rings your own devices
  - [ ] Off the gateway's network, the herald-push plugin carries the question

### Auth & connectivity extras
- [x] Browser sign-in for SSO/OIDC gateways (RFC 8252 PKCE via Custom Tab and a loopback redirect; `/auth/native/*`). Bearer tokens kept encrypted and refreshed before they expire
- [ ] QR-code pairing
- [ ] Tailscale / MagicDNS setup guide
- [ ] Gateway API server "lite" mode (`:8642`, static bearer key, Runs API + SSE)

### Platforms & polish
- [x] 48dp touch targets (`MinTouchTarget`) on icon buttons, the composer, the top bar and the sidebar rows, without enlarging the icons
- [x] Launcher shortcuts New chat, New chat with voice (starts dictating) and Call Hermes (a voice chat in a new chat, once the gateway is connected); links `hermes://new-chat`, `hermes://new-chat-voice`, `hermes://voice-chat` and `hermes://session/<id>`
- [x] Web links in replies open in a Custom Tab in the app's colors, or the browser when there's none
- [x] App lock (Settings → Privacy): fingerprint, face or screen lock on launch and after a minute away; content hidden in Recents
- [ ] Tablet and foldable adaptive layout (list and detail side by side)
- [ ] Home-screen widget and Quick Settings tile
- [x] Offline cache of sessions and messages: the chat list and the newest rows of the last 50 chats read are kept on the device (Room, rows encrypted with a Keystore-held key) and shown while the gateway can't be reached, marked "Saved copy"; signing out of a gateway forgets its chats
- [ ] Send prompts typed offline once the gateway is back (an outbox)
- [ ] iOS app
- [ ] Desktop (JVM) app
- [x] Wear OS companion (`wearApp`, `shared/wearlink`): talk to Hermes from the watch through Herald on the phone (Data Layer relay, its own side chat); the watch's speech recognizer, or a recording the gateway transcribes; live progress, the reply and the chat's title; the gateway's voice played on the watch, the watch's own voice when there is none; pick, start or continue a chat; an "Ask Hermes" tile and complication
- [x] Wear OS live voice: with GPT-Live on the gateway, the watch holds the voice call itself (WebRTC, its microphone and speaker, over Wi-Fi or mobile data when it has them) while Herald on the phone gets the model's answer and runs each request as a Hermes turn in the watch's chat; captions, Hermes at work, mute and hang up on the watch; "Talk live" in the watch's settings
- [x] Wear OS assistant: the watch's assist entry opens Herald and it listens at once (an `ASSIST` filter on the talk screen, the same listening the tile and complication start), and Herald can be picked under Settings → Digital assistant app — a `VoiceInteractionService` with a session, ported from the phone's, shows the orb over the watch face instead of bringing the app up; "Assistant" in the watch's settings asks the system for the role
- [x] watch follows phone accent colors
- [ ] Wear OS direct mode: the watch talks to the gateway itself, without the phone
- [ ] Localization (`i18n.catalog`)
