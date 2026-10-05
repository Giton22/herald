# Bot Mode: integration design

Built from five research passes (2026-10-05) over `NousResearch/hermes-agent` at tag `v2026.9.24` (v0.21.5, which
is what the test gateway runs) and `main` @ 6cd2817. They covered Desktop's Bot Mode plugin
(`apps/desktop/src/plugins/hermes-bots/`), the gateway contract (`tui_gateway/`), group chats, how bot messages
appear in a transcript, and upstream issues and PRs. Paths are upstream and line numbers are at the tag unless
marked. The emulator check of 2026-10-05 is in §11.

## 1. Why this matters

- A maintainer wrote in PR #128992: "Bot Mode is the #2 community complaint, behind a mobile app." Herald covers
  both.
- Users ask for Bot Mode without Desktop: #105317 (a remote-only client), #113695 (Bot Mode on the web dashboard)
  and #89995 (rooms "from mobile/remote without the desktop app running"). As far as we found, no other Hermes
  mobile client (hermes-android, HermesPilot, hermes-relay, and others) does per-bot rosters.
- The worst bug class upstream is a **forked or lost Bot Chat**. In #129518 one install lost a 930-message chat to
  7 forks in one morning; #126272, #130980, #131014 and #122063 are the same class. Phones hit the cold-start race
  more than Desktop does.

## 2. The model

**A Bot is a Hermes profile** (`~/.hermes/profiles/<name>/`), not a separate object. Bot Mode adds the following
on top of profiles:

| Concept | On the wire |
|---|---|
| **Bot Chat** | The one permanent chat per bot. Its **identity is a name**: the profile's session titled exactly `Bot Chat`. A UNIQUE title allows only one. Clients store no pointer to it. |
| **Look** | `ui_meta["hermes-bots"]` in `profile.yaml` (title, shape, color, pinned, hidden, section…), plus the avatar asset. Desktop keeps some looks in its local storage only. |
| **Teammate protocol** | The server adds the roster and the `message_agent` tool, but **only** in a session titled `Bot Chat`, and only once any profile has a `hermes-bots` block. An empty `{}` counts (`agent/system_prompt.py:349-355`, `tools/bot_mode_probe.py:117-129`). |
| **Routines** | Cron jobs named `[bot:<profile>] <title>`. `deliver: bot-chat` posts their output into the Bot Chat. |
| **Group chats** | Two separate engines, which never share a room (§8). |

## 3. Rules Herald must keep

1. **Never fork a Bot Chat.** Look up the chat before opening it. If the lookup fails, or comes back empty for a
   bot known to have a chat, show "try again" and **never create one**. Allow only one open per bot at a time.
   Open `resolved_id || id`, which is the live end of the compression lineage. *(Done: `BotChats`.)*
2. **Create a Bot Chat like this:**
   - `session.create {profile, title:"Bot Chat", hidden:true, follow_profile_config:true, source, cols}`.
   - Then `session.title` straight away, which creates the row hidden and claims the name.
   - If the title is taken, the error is code **4022** `Title 'Bot Chat' is already in use by session <id>`
     (`hermes_state_titles.py:119`). Then `session.close` our runtime, `session.delete` our row (the gateway
     never prunes a non-`tui` row), and adopt the existing chat. *(Done.)*
3. **Never touch the chat's identity.** No rename, no `/title`, no unhide (`session.set_hidden false` disables the
   rename guard), and no archive unless the user means to retire the chat (archiving frees the title).
   - In a Bot Chat, `/new` and `/reset` compress instead.
   - Don't pin a model in a Bot Chat (`follow_profile_config`). Edit the profile's model instead (#129460).
   - *(Mostly done; model pinning is still possible from the composer.)*
4. **Unknown params are rejected.** Any param not in a method's contract fails with **4000**
   (`rpc_dispatch.py:27-31`). Send only the keys listed in §10.
5. **Don't call `bot_relay.*`.** `outbox.drain` would take Desktop's deliveries. Don't send `_turn_author`
   (error 4124). Don't `prompt.submit` into `Group: <room>` sessions, which are fenced.
6. **Owning a Bot Chat brings duties.**
   - While Herald's runtime holds the chat's lease, other bots' DMs and routine deliveries run as turns **inside
     our session**, polled every 5 s (`session_notifications.py:602-657`). They start with a bare
     `message.start`, and the "Message from…" row is never pushed, so **reload history on `message.complete`**
     for such turns.
   - Their approval requests come to us.
   - If another surface owns the chat, `prompt.submit` returns **4090** with `data.reason` set to
     `SESSION_NOT_OWNED`, `MAX_CONCURRENT_SESSIONS` or `SESSION_COORDINATION_UNAVAILABLE`. Show "open elsewhere"
     with a retry, not an error.
   - Never resend a DM whose delivery is queued.
7. **`source` sets the model's platform hint.** It is fixed for a Bot Chat once the first turn has built the
   system prompt (`conversation_loop.py:609-651`).
   - `desktop` makes the model emit `MEDIA:/path` and `::preview{file=…}`, so we must render both.
   - Desktop's own kickoff ran through the CLI (`tui`), which is why test2 thinks it's in a terminal.
8. **Parse leniently and feature-detect everything.**
   - Roster fields are changing upstream: #131849 adds a declarative roster with `bot.enabled`, #104876 adds
     per-agent `private`.
   - There is no capability negotiation (#130702).
   - Fields that exist only on `main`: `live_message_count`, `install_id`, `session.archive`, the
     `approval.cancelled` event.
9. **Treat names as plain text** (#131562 was a stored XSS through a bot name). Treat the avatar PNG as a hint
   (§5).

## 4. The experience on a phone

The pattern is **bots as contacts**: Telegram's chat list, plus Character.AI's one thread per persona, plus Slack's
Activity tab for what needs you, plus Android's conversation notifications and shortcuts.

- **Chats | Bots at the top of the sidebar**, remembered per gateway. *(Done.)*
- **Roster row:**
  - face; name, with `@handle` only when two bots share a name;
  - preview from the Bot Chat (else the last session) with Markdown stripped; a bot-to-bot preview without its
    "Message from" prefix, in italics;
  - age from the newest of chat and worker activity;
  - unread dot;
  - mood: *thinking* while its turn runs, *working* when a worker heartbeat is under 150 s old;
  - an amber ⚠ for a lasting failure (`provider_auth_or_access`, `provider_quota_limit`, `missing_config`,
    `agent_blocked`) with a one-line hint. Never for rate limits, 5xx errors or timeouts.
- **Order:** pinned first, then the newest of activity and creation. *(Done.)* Hidden bots go to a collapsed
  "Hidden N" section at the bottom.
- **Long-press menu** (an anchored dropdown, not a sheet): Pin, Hide, Edit, Duplicate, New chat with this bot,
  Open recent session, Delete (with a destructive confirm; not offered for `default`).
- **Inside a Bot Chat:**
  - the bot's face and name in the header;
  - an empty state with a large face and "Say something to get started.";
  - transcript rendering per §6;
  - Reset context instead of New chat (#110229);
  - `@mention` completion of other bots.
- **Needs-you inbox across bots:** pending approvals and clarify questions, `@user` lines in rooms, failed routine
  deliveries (#102653 §6, #124417). This is the phone's natural job.
- **Android:**
  - per-bot conversation notifications (`MessagingStyle` with a `Person` using the bot's face, direct reply, tap
    to open its chat);
  - per-bot shortcuts;
  - a `hermes://bot/<profile>` deep link, compatible with PR #115195;
  - share-to-bot from the share sheet;
  - "ask <bot>" by voice from the assistant panel.
- **Create a bot (quick path):** Name, Title, Description, face. Advanced: model, custom SOUL, clone source,
  skills/toolsets/MCP (§7).
- **Edit a bot:** look, title and description; send **only the fields that changed**, so a stale form can't revert
  an edit made on Desktop.

## 5. Faces

Desktop's options (`avatar.tsx`, `avatar-picker.tsx`):

- **Blob faces ("blobatar")** are the default for new bots: `blobatar[:seed[:kind]]` with 10 kinds (round,
  organic, boxy, capsule, nub, cloud, droplet, hexagon, sun, triangle), drawn by the SDK's `blobatarSvg`. Not
  ported yet; we fall back to the stock shape for the name.
- **Classic shapes:** circle, squircle, pill, triangle, hexagon, cloud, drop. Colour comes from a hash of the name
  (`hsl(h 68% 58%)`) or a picked swatch. *(Done; the hash ports match Desktop's drawing of test1.)*
- The default bot, until customized, is a violet `#8b5cf6` squircle. *(Done.)*
- Generate: `image.generate {probe:true}` first, then `{prompt, aspect_ratio:"square"}` (90 s).
- Upload: centre-cropped to 256 px, at most 2,000,000 bytes, PNG/JPEG/WebP (`profiles.set_asset`).
- Pet: `pet.gallery` / `pet.thumb` used as a picture.
- **Desktop uploads a 160×160 PNG snapshot of every drawn face, and the snapshot goes stale.** Skip it when the
  metadata says how to draw the bot. When it doesn't (Desktop kept the look locally), the snapshot is the only
  record. *(Done: `showsPicture`.)*

## 6. Transcript: rows not written by the user

Classify a row **before** picking a bubble, in this order:

| Row | How to recognise it | Show |
|---|---|---|
| hidden / `[System:` / heartbeat | `display_kind:"hidden"`; user row starting `[System:`; `^\[Background process \S+ heartbeat #\d+ ` | nothing |
| failed turn | `display_kind:"failed_turn"` (legacy rows are typed on read) | muted system notice |
| process / delivery result | `display_kind` `process_complete` or `async_delegation_complete`; or an untyped user row matching `^\[IMPORTANT: Background process [\s\S]*\]$` | **Delivery outcome** when the Command has `bot_mode_dm.py --run-delivery` or `--wait-reply`, or the `proc_…` id matches an earlier `message_agent` ack's `process_id`. Otherwise one collapsed line with `display_metadata.display_text`, output in monospace on tap. Batches are split on `\n\n(?=\[IMPORTANT: )`. |
| message from another bot | user row matching AGENT_MESSAGE_RE (`AgentMessage.kt`) | a small centred note: the sender's face (from the handle; `hermes` means default) and "Message from X", body collapsed, no edit. *(Done as a card; make it collapsed and add the face.)* |
| reply to that message | the next settled assistant row after an agent note | collapse to "↗ Replied to X · show reply", except while streaming and except when this bot itself messaged X earlier in the same exchange (`agent-delivery.tsx:48-83`) |
| routine output | user row starting `[Cronjob "<name>" output — scheduled job, not the user…]\n\n` | "⏰ <name> ran", content collapsed (Desktop has no renderer for this) |
| cron mirror | `[Cron delivery: <name>]\n…` | same treatment |
| silence | a settled assistant reply that is exactly `[SILENT]`, `SILENT`, `NO_REPLY`, `NO REPLY`, `[静默]`, `静默`, `[沉默]` or `沉默`, after normalising (port `is_intentional_silence_response`, `gateway/response_filters.py:40-75`) | nothing; while streaming, hold back a prefix of a marker |
| sender's `message_agent` call | tool call `message_agent{target,message}`; the ack is `{status:"queued", delivery_id, process_id, reply_delivery}` or `{error, reason}` | "Messaging @x…", then "Messaged @x"; "Couldn't message @x: …" on an error; hide the ack JSON |
| other kinds | `model_switch`, `auto_continue`, `steer`, … | a centred one-line label |

**Delivery outcome** (parse the process `Output:`):
- plain text, `{"reply",…,"status":"settled"}` or `Reply from X:` → "💬 Reply from @x", collapsed. Strip
  `session_id:` lines. An empty reply means "@x chose not to reply".
- `queued` / `claimed` / `ambiguous` → "Still waiting on @x · don't resend" (amber).
- `failed`, `error` or `[reason: c]` → "Couldn't reach @x · <label>".

Reason labels come from `tools/bot_failure_reasons.py:17-31` plus `target_busy` ("chat is open elsewhere"):
`runtime_offline`, `queued_expired`, `delivery_timeout`, `agent_blocked`, `cancelled`, `provider_auth_or_access`,
`provider_quota_limit`, `provider_rate_limit`, `provider_server_error`, `context_overflow`, `missing_config`,
`model_unavailable`, `unknown`.

No sender metadata reaches the wire, so agent messages must be detected with the regex.

## 7. Creating and editing

- **Name → profile id** (`labels.ts:82`): NFC; accents folded; other letters become `u<hex>`; `[a-z0-9_-]`, at most
  64. Must match `^[a-z0-9][a-z0-9_-]{0,63}$` and not be taken. A non-ASCII name becomes the title when the title
  is empty.
- `profiles.create {name, description:"Title — Description", clone_from ("default" | null = fresh), no_skills, share_auth (default on), soul, model+provider (only both)}`.
  Errors: 4061 (name required), 4062 (with the message). Result:
  `{ok, name, path, soul_written, model_set, mirrored{env, auth, model_inherited, voice}}`.
- **SOUL** (`soul.ts:160`), unless the user gives a custom one:
  ```
  # {DisplayName}

  **Role:** {title}
  **Mission:** {description}

  You are {DisplayName}, a persistent named agent (profile `{slug}`) on this machine.
  You keep your own memory, skills, and conversation history across sessions.
  ```
  Leave out the Role and Mission lines when empty. **Don't** append the messaging section when `profiles.list`
  says `bot_mode_protocol: true`.
- Then `profiles.configure {name, ui_meta:{"hermes-bots":{shape, color, imageKind, title, created}}}`, which marks
  the profile as a bot, and `profiles.set_asset` for a picture.
- Then `setup.runtime_check {profile}`. It is never an RPC error; it returns `{ok, provider, model, source, error?}`.
  If `ok:false`, say "created, needs a model" and **skip the intro**.
- Then create the Bot Chat (§3.2) and, **only on creation**, `prompt.submit` the kickoff "Hey, tell me about
  yourself!". Consider showing it as a system line (#91827).
- **Edit:**
  - `profiles.configure` merges `ui_meta` **per top-level key**, so send the whole `hermes-bots` block.
  - Use CAS: once `ui_meta_expected_revisions` is sent, every incoming key needs its exact int revision (0 when
    new). A conflict returns `applied.ui_meta_conflicts` with the current revisions; re-read and retry.
  - Over 64 KB returns `applied.ui_meta:false` and `ok:false` with no error.
  - A model change may come back with `confirm_required` / `confirm_message`; resend with
    `confirm_expensive_model:true`. The REST `PUT …/model` skips that guard, so don't use it.
- **Duplicate:** `profiles.create {name:"<base>-N", clone_from: base, description}`, plus the look with title
  "<title> (copy)".
- **Delete:** `DELETE /api/profiles/{name}`. Not for `default` (400). It can take up to 10 s and may return
  `settlement_pending`.
- `PATCH /api/profiles/{name} {new_name}` **renames the profile id** (on `default` it only sets the display name).
  That is not a title edit.

## 8. Group chats

There are **two engines that never share a room** (confirmed by #131723 and PR #126652):

- **Desktop rooms** are run by Desktop's renderer (`group-rounds.ts:574`, `group-turns.ts:869`) and call no
  `groups.*` method, at the tag or on `main`. They stop when Desktop closes.
- **Hosted rooms** are run by the gateway (`HostedRoomService`) through `groups.*`. They keep going without any
  client.

**3a. Desktop rooms, read-only.** Read the `default` row's `ui_meta["hermes-bots-groups"]` (v3, `group-chat.ts:59-80`):

```
{ version:3, updatedAt, deleted?:{roomKey:revision},
  rooms:{ "id:<roomId>"|"name:<name>": { name, roomId?, log:Msg[], members:[{name, handle?, …}]≤6,
                                          holdDetection, revision, omitted?, image? } } }
Msg = { id?, from:{kind:"member"|"user", name, source?}, text≤1200 (+"… [truncated]"), at(ms), thread?, truncated? }
```

- Bounds: the last 16 messages per room, 48 KB in total. There are **no images or `from.gateway` per message**.
- Merge rules:
  - Key rooms by room key.
  - A missing room or message is **not** a deletion.
  - An `id:` tombstone always deletes; a `name:` tombstone deletes only when its revision is at least the room's.
  - Union messages by `id` (or by content when there is no id), then sort by `at`.
  - Take the name and members from the copy with the higher revision.
- Show "N earlier messages" from `omitted`. "Needs you" is when the latest member message matches `/@user\b/i`.
- **Never write to the mirror.** Nothing runs turns for it, it can shift Desktop's watermarks, and upstream has no
  writer contract.
- Label these rooms "Continue on Desktop".
- Risk: open PR #122321 makes the mirror opt-in, so it may go empty.

**3b. Hosted rooms, the way to join in.** Gate on `groups.capabilities` returning `driver:true` and the methods
we need.

- `groups.list` lists the rooms.
- `groups.create {room_id, name, members:[{member_id, profile, handle, display_name?}]}`: 2–6 local profiles,
  unique handles, `all`/`everyone` reserved. **Always a fresh UUID, never a Desktop roomId** (the fence returns
  4122).
- `groups.send {room_id, event_id:<client uuid>, payload:{text, thread_id}}` sends a message. It is idempotent, so
  retry with the same id.
- `groups.log {room_id, since_seq, limit≤500}` reads from a saved cursor. Event kinds:
  - render `message.user` and `message.member`;
  - show `turn.failed`, `turn.cancelled`, `room.stop_requested` and `room.renamed` as system lines;
  - hide the rest.
  - A member reply counts once a `turn.settled` references it.
- `groups.state` returns `driver_status.pending_actions` (retry or approval).
- `groups.approve` takes **`once` or `deny` only**. `groups.stop`, `groups.retry`, `groups.rename` and
  `groups.disband` complete the set.
- No push events. Poll `groups.state` every 2 s while working and every 10 s when idle, and read the log when
  `latest_seq` moves.
- Hosted rounds: round 0 goes to the mentioned members (or everyone); rounds 1–2 go only to members a bot cited.
  At most 10 member messages. No `@user`, no stop-word holds, no clarify.
- Risk: hosted rooms don't appear in Desktop until #131723 lands.
- **Rejected:** porting Desktop's engine (about 5k lines of TypeScript; the phone would have to stay awake through
  every drive, and it would race Desktop).

## 9. Liveness: events vs polling

- **There is no event for the profile list, roster or ui_meta.**
- `sessions.changed` (payload `{}`, at most once every 2 s) covers the launch home **plus profiles we've made a
  profile-scoped call for** (`change_watcher.py:118-129`; `server.py:535-542`). `profiles.list` doesn't count.
- Plan: make one cheap profile-scoped call per bot (e.g. `session.list {profile, title:"Bot Chat"}`) so each store
  is watched. Refresh the roster on `sessions.changed`. Poll slowly (30–60 s, plus on foreground) for new or
  deleted bots and look changes. *Today: a 5 s poll while the roster is visible.*
- `session.reclaimed {session_id, stored_session_id, reason}`: re-resume if it's the open chat.
- `cron.changed` only covers the launch home's jobs, so poll per-bot routines.

## 10. Method reference (tag)

- `profiles.list {profile?, include_sessions=true}` returns `{profiles:[Row], bot_mode_protocol:true}`.
  - Row fields: `name, path, is_default, model, provider, description, display_name, skill_count, previous_names, role, last_session, worker_session, canonical_session{id, resolved_id, root_title, title, preview, started_at, last_active, message_count}, ui_meta_revisions, ui_meta?, has_avatar`.
  - Caching is keyed on state.db and profile.yaml (`profile_roster_cache.py:65-93`).
- `profiles.describe {name}` returns
  `{name, description, soul, model{provider, default}, skills[], toolsets[], toolsets_pinned, mcp_servers[]}`.
- `profiles.get_asset {name, asset:"avatar"}` returns `{found, mime, size, data}`.
- `profiles.set_asset {name, asset, data | clear}`. Errors 4066–4070.
- `session.list {profile, title, limit, include_hidden}` does an exact title lookup. It resolves hidden chats and
  un-archives an accidentally reaped Bot Chat. Returns `{sessions:[{id, resolved_id, title, preview, started_at, message_count, source}]}`.
- `session.create`: `profile, cols, source, cwd, messages, parent_session_id, title, model, provider, reasoning_effort, fast, close_on_disconnect, hidden, room_plumbing, follow_profile_config`.
- `session.title {session_id, profile?, title}`: errors 4021, 4022 and 5007.
- `cron.manage {action: list|add|remove|pause|resume, name, schedule, prompt, repeat, continuity, deliver ("bot-chat[:profile]"), include_disabled, profile}`.
- `image.generate {prompt, aspect_ratio, probe, max_bytes}` returns `{available, success, image, image_data?, error?}`.
- `agents.list` lists **background processes**, not bots.

## 11. Seen on the test gateway (v0.21.5, 2026-10-05)

- `profiles.list` matches §10. Only `test2` had a `hermes-bots` block (`{shape:"squircle", color:"hsl(210 68% 58%)", imageKind:"shape", title:"", created}`).
- Every bot had `has_avatar:true`. These are face snapshots; test2's is a stale yellow hexagon.
- Bot Chats don't appear in `GET /api/sessions`.
- Test1's chat has the raw `[IMPORTANT: Background process … bot_mode_dm.py --run-delivery … {"reply":…, "status":"settled"}]`
  row, which §6 turns into "💬 Reply from @hermes".
- No rooms existed, so the mirror is unchecked.

## 12. Plan

| Phase | Scope |
|---|---|
| **1 (done)** | Chats \| Bots switch; roster (face, name, preview, age, unread, working); open or start a Bot Chat safely; `/new` → compress; identity actions kept away; agent-message card; reopen on launch; Desktop's order; race cleanup |
| **2: transcript** | §6 classification: delivery outcomes, process rows, collapsed agent notes with faces, replies, silence, routine notes, `message_agent` calls; reload history after turns started by deliveries; "open elsewhere" (4090) state; render `::preview{}` |
| **3: roster polish** | mood and ⚠ attention badge; italic agent previews; `@handle` on duplicate names; long-press menu (Pin, Hide with a "Hidden" section, Open recent session, New chat with this bot); bot header and empty state; event-driven refresh (§9) |
| **4: create and edit** | §7 quick path plus face picker (classic shapes, upload, generate); edit look, title and description with CAS, sending only changed fields; duplicate; delete; advanced model and SOUL |
| **5: phone features** | per-bot notifications with direct reply; Needs-you inbox; shortcuts and `hermes://bot/` deep link; share-to-bot; voice "ask <bot>"; @mention completion |
| **6: rooms** | 3a read-only Desktop rooms; 3b hosted rooms (create, send, read, stop, approve) |
| later | blob faces; per-bot routines; sections; search and filters (useful past 8 bots); QR pairing compatible with PR #103766 |

Not planned: Bot Screen (VNC takeover), multi-connection relay, Desktop's room engine.
