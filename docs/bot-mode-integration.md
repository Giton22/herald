# Bot Mode — integration design

Source: `NousResearch/hermes-agent` at tag `v2026.9.24` (v0.21.5, what the test gateway runs), checked against
`main` (2026-10-05). Desktop side: `apps/desktop/src/plugins/hermes-bots/` (bundled, default-on). Gateway side:
`tui_gateway/methods_profiles.py`, `tui_gateway/methods_groups.py`. User guide:
`website/docs/user-guide/bot-mode.md`.

## 1. What a Bot is

**A Bot is a Hermes profile.** It is not a new kind of object. Desktop's Bots tab is a UI over `profiles.list`. Each
profile is one roster row, the `default` profile included (it shows as "Hermes"). **New Agent** creates a profile.
`hermes -p <bot> chat` opens the same agent from the CLI.

Bot Mode adds three things on top of profiles:

| Concept | What it is on the wire |
|---|---|
| **Bot Chat** | The one permanent chat per bot. Its identity is a **name**: the profile's session titled exactly `Bot Chat` (the core `UNIQUE(title)` index allows only one). No session-id pointer is stored anywhere. The gateway resolves it on every `profiles.list` row. |
| **Bot look** | `ui_meta["hermes-bots"]` in the profile's `profile.yaml` (title, shape, color, hidden, section, …), plus an avatar image in the profile's asset store. |
| **Teammate protocol** | The backend adds a roster of teammates and a `message_agent` tool to the system prompt, **only** in canonical Bot Chat sessions. This applies once any profile on the install carries a `hermes-bots` ui_meta block. It all happens server-side; clients do nothing. |

Group chats are mostly a Desktop feature (§6).

## 2. Roster: `profiles.list` (WS)

We already call REST `GET /api/profiles`. The WS method returns the same rows plus the Bot Mode fields. Desktop
polls it every 5 s, and the server caches results per profile until the profile's store changes, so polling is
cheap.

```jsonc
// result
{ "profiles": [ Row… ], "bot_mode_protocol": true }      // install_id: main only, not in v0.21.5
// Row
{
  "name": "test2", "path": "…", "is_default": false, "model": "…", "provider": "…",
  "description": "…", "display_name": "", "skill_count": 3, "previous_names": [], "role": null,
  "last_session":      { "id", "title", "preview", "started_at", "last_active", "message_count", "live_message_count" } | null,
  "worker_session":    { "id", "source", "title", "last_active" } | null,      // newest kanban/tool worker
  "canonical_session": { "id", "resolved_id", "root_title", "title", "preview",
                         "started_at", "last_active", "message_count", "live_message_count" } | null,
  "ui_meta_revisions": { "hermes-bots": 3 },            // always present, may be {}
  "ui_meta": { "hermes-bots": BotMeta, … },             // only when non-empty
  "has_avatar": true
}
```

Field notes:

- `canonical_session.id` is the registry row and stays stable. `resolved_id` is the **compression-lineage tip**, the
  live session. **Always open `resolved_id || id`.** The two differ after `/compress`.
- `canonical_session` is null when there is no Bot Chat yet, or when the user archived it on purpose (the chat is
  retired and the next open creates a new one). The server undoes archives made by its own reaper.
- `last_active` is in Unix **seconds**. The preview is the **latest** message: whitespace collapsed, capped at 80
  characters, and possibly still containing markdown. Desktop strips markdown with `stripPreviewMarkdown`.
- A row preview should come from `canonical_session` so the preview and the tap target are the same chat. Desktop
  uses `last_session` only for "Open recent session".
- "Working" state: `worker_session.last_active` within about 60 s (workers heartbeat at least every 60 s).

`BotMeta` (`ui_meta["hermes-bots"]`). There is no schema, so parse every field as optional and keep unknown keys:

```ts
title?: string            // display name; wins over display_name
description?: string
shape?: string            // 'circle'|'squircle'|'pill'|'triangle'|'hexagon'|'cloud'|'drop'|'blobatar:…'|'sigil-N'…
color?: string            // '#rrggbb'
custom?: boolean          // user customized the look
imageKind?: 'photo'|'shape'
hidden?: boolean          // display-only: hide from roster
pinned?: boolean
sectionId?: string|null; sectionName?: string|null
groups?: string[]; group?: string|null   // group-chat membership
created?: number          // ms
// legacy `chat` pointer: ignore
```

**Display name** (`labels.ts displayName`, in this order): `meta.title`, then `display_name`, then "Hermes" for
`default`, then `name` title-cased with `-`/`_` turned into spaces.

**Avatar** (`avatar.tsx botAppearance`):
- If `has_avatar` is set, fetch `profiles.get_asset {name, asset:"avatar"}`, which returns
  `{found, mime, size, data:"data:…;base64,…"}`. Cache it per gateway and profile.
- Desktop backfills a 160 px PNG of the vector face for agent-message cards. Desktop skips it when it detects that
  case (`isBackfilledFacePng`); we can just show it.
- Without an image:
  - `default` (not customized) is a violet `#8b5cf6` squircle.
  - Any other bot uses `shape ?: defaultShapeFor(name)` and `color ?: profileColor(name)`, drawn as a shape with
    two eyes.
- `defaultShapeFor` is `h = h*31 + charCode` (uint32) over `[circle, squircle, pill, triangle, hexagon, cloud,
  drop]`. `profileColor` lives in the plugin SDK. We can use our own name-hash hue until we port it.

## 3. Opening a Bot Chat

Port of `canonical-chat.ts`, which has regressed upstream many times. The invariants:

1. Get the **registry** from a fresh roster row: `canonical_session`. Desktop runs a separate
   `session.list {profile, title:"Bot Chat", include_hidden:true}`. `canonical_session` is the same lookup
   resolved server-side, so Herald can use the roster row.
2. **Fail closed.** If the lookup errored, **or** came back empty while we had seen a `canonical_session` for this
   bot before, show "try again" and **never create**. A backend that is still warming up can return an empty answer,
   and creating then forks the forever-chat. Upstream calls this "my bot lost all context".
3. If it exists, resume `resolved_id || id` through our existing `ChatSession` with `profile = <bot name>`. This
   must **not** change the profile picked for the Sessions view; the bot's profile belongs to that chat.
4. If it doesn't exist, create it:
   ```
   session.create { profile, title:"Bot Chat", hidden:true, follow_profile_config:true, source:"desktop", cols }
   session.title  { session_id:<runtime>, title:"Bot Chat" }   // creates the stored row now and claims the name
   ```
   - If `session.title` fails with `/already in use/i`, someone else just created it. Re-read the roster and
     **adopt** the winner; leave our lazy session alone (the gateway prunes it).
   - If it fails any other way, the gateway is old: send a first prompt so the lazy row persists.
   - Allow one creation per bot at a time, so a double tap can't create two chats.
5. `hidden:true` keeps Bot Chats out of the normal session list. `follow_profile_config:true` makes the chat follow
   the profile's **current** model on every resume. **So the bot chat composer should not pin a model the way a
   new chat does.**
6. In a Bot Chat, `/new` and `/reset` become `/compress`: same conversation, fresh context. Archiving a Bot Chat
   retires it, so ask for confirmation, or don't offer archive.

## 4. Creating a Bot

`create-dialog.tsx` → `ensureAgentCreated` + `submit`:

1. Turn the name into a profile id with `slugifyProfileName`: NFC normalization, accents folded, other letters
   become `u<hex>`, `[a-z0-9_-]`, at most 64 characters, matching `^[a-z0-9][a-z0-9_-]{0,63}$`. If the name had
   non-ASCII characters and no title was given, the typed name becomes the title.
2. `profiles.create { name, description, clone_from?: "default"|null, no_skills?, share_auth?, soul?, model?+provider? }`
   returns `{ok, name, path, soul_written, model_set, mirrored}`.
   - `mirror_credentials` defaults to true, which copies the launch profile's provider keys. Without that the bot
     can't answer.
   - Desktop's `soul` comes from `composeSoul({name, title, description, roster, customSoul})`. On a backend
     reporting `bot_mode_protocol: true` the protocol section is **not** added to SOUL; send only the persona.
3. `profiles.configure { name, ui_meta:{ "hermes-bots": {shape, color, imageKind:"shape", title, created} } }`.
   This is what makes the profile a Bot-Mode bot. An image goes through
   `profiles.set_asset {name, asset:"avatar", data}`.
4. `setup.runtime_check { profile }`. If it returns `ok:false`, the bot was created but needs a model: show that,
   and skip the intro turn.
5. Create the Bot Chat (§3) **and** send the kickoff prompt `"Hey, tell me about yourself!"`. This is the **only**
   path that sends the kickoff. A normal open never sends it.

Desktop's own intro ran over the CLI path, so Test2 believed it was in "the terminal UI". Herald creates the chat
with `source:"desktop"`, so the agent knows Markdown and images work.

**Edit**: read-modify-write with CAS. Read the row, merge, then send
`profiles.configure {name, ui_meta:{"hermes-bots": merged}, ui_meta_expected_revisions:{"hermes-bots": rev}}`.
If `applied.ui_meta_conflicts` comes back, re-read and retry (Desktop tries a few times). Keep data-URL images out
of ui_meta, which has a 64 KB cap and is sent on every roster poll.

**Delete**: `DELETE /api/profiles/{name}` (dashboard REST, present at v0.21.5). `default` can't be deleted. The
dashboard also has REST routes we may prefer for single fields: `PUT /api/profiles/{name}/soul`, `…/description`,
`…/model`.

## 5. Bot-to-bot messages in a transcript

Messages a bot sends to another bot arrive on the **user** role. Desktop's regex
(`assistant-ui/thread/user-message.tsx`) is:

```
^(?:Message from (?:🤖\s*)?([^:\n(]{1,64}?)(?:\s*\(@([a-z0-9][a-z0-9_-]{0,63})(?:@[a-zA-Z0-9][a-zA-Z0-9_-]{0,63})?\))?:\s*|\[Message from agent '([^']{1,64})'\]\s*)([\s\S]*)$
```

Group 1 or 3 is the sender's name, group 2 its handle (for the avatar), group 4 the body. Show these as an
attributed notice, not as the user's bubble. This applies in **every** chat, Sessions mode included, because cron
`deliver: bot-chat` and `hermes peer` write the same form.

Empty replies: a turn that ends in `[SILENT]` / `NO_REPLY` and similar is kept in the transcript but should show
nothing.

## 6. Group chats: read-only at first

At v0.21.5, **Desktop runs group rooms itself** (`group-turns.ts` / `group-rounds.ts`). Each member speaks through
its own hidden session titled `Group: <room> · <thread>`. The room log lives in Desktop's plugin storage.

For other clients, Desktop publishes a **bounded mirror** into the **default** profile's ui_meta under
`hermes-bots-groups`. The code comment says it is there "so mobile can show the same messages":

```ts
{ version: 3, updatedAt?, deleted?: { [roomKey]: revision },
  rooms: { [roomKey]: { roomId?, name?, image?, members?: GroupMember[], log: GroupMessage[],
                        omitted?: number, revision?, holdDetection? } } }
// GroupMessage: { at(ms), from:{kind:'member'|'user', name, source?, gateway?}, id?, text, thread?, truncated?, images? }
// at most 16 messages per room, 1200 chars each, ~48 KB total
```

So Herald can **show** rooms (read-only, latest 16 messages, needs-you hints from `@user`) with no extra calls.
**Posting** into a Desktop room would mean porting Desktop's whole turn engine, so it's out of scope.

The gateway also exposes a separate **hosted rooms** protocol (`groups.capabilities/list/create/state/send/log/…`,
with a server-side driver when `driver:true`). That is the right long-term path for a phone client. But the Desktop
plugin files read here don't use it, so rooms created in Desktop won't show up in `groups.list`. Revisit once
upstream moves Desktop onto hosted rooms.

## 7. Herald design

### Top-level modes: Chats | Bots

A segmented switch at the top of the sidebar under the wordmark, like Codex's Chat/Work. Remembered per gateway.

- **Chats**: today's sidebar, unchanged (profile-scoped session list, filters, Scheduled, Capabilities, …).
- **Bots**: the roster.
  - One row per bot: avatar, display name, preview, age, unread dot, and a working dot from `worker_session`.
  - Hidden bots are left out.
  - The default profile comes first, the rest follow by `canonical_session.last_active`.
  - A **Group chats** section shows the mirrored rooms, read-only.
  - A **New bot** button and a search field.
- Tapping a bot opens its Bot Chat in the chat pane. The chat header shows the bot's avatar and title instead of
  the session title. A long press gives Edit / Hide / Open recent session.
- Show **Bots** only when `profiles.list` over WS works. Use `bot_mode_protocol` to decide whether to send `soul`
  without the protocol section.
- The profile picker in the account sheet stays a Chats-mode setting. In Bots mode every profile is a bot.

### Where it fits in the code

| Piece | Where |
|---|---|
| `BotsApi`: `profiles.list` / `get_asset` / `create` / `configure` / `set_asset`, `setup.runtime_check` over WS | `shared/core/.../bots/` (new), using `JsonRpcClient` |
| `Bot`, `BotMeta`, `CanonicalSession`, `GroupMirror` models (lenient; keep unknown `ui_meta` keys) | same |
| `openBotChat` registry logic (§3) with single-flight and fail-closed | `shared/core/.../bots/BotChats.kt` |
| Opening a chat with an explicit profile without changing `ProfileStore` | `ChatSession` already takes `profile`; route it from the bot rather than from the store |
| `/new`→`/compress` guard and bot header | `ChatScreen` / composer, keyed on "this is a Bot Chat" |
| Agent-message card | the transcript renderer, all chats |
| Unread state | `SeenStore` keyed on `canonical_session.id`, compared against `last_active` |
| Mode switch, roster, create sheet | `shared/ui/.../bots/` (new) + `SessionsSidebar` header |

### Phases

1. **Roster + Bot Chat**: WS roster, mode switch, open/create the canonical chat, avatars (image or shape), unread
   state, `/new` guard, agent-message cards.
2. **Create/Edit bot**: create sheet (name, title, description, optional model), CAS edits, hide/unhide, avatar
   upload.
3. **Group chats, read-only**: from the `hermes-bots-groups` mirror.
4. **Later**: per-bot routines (cron jobs named `[bot:<name>] …`, `deliver: bot-chat:<profile>`), hosted rooms
   (`groups.*`), @mention autocomplete.

## 8. Seen on the test gateway (v0.21.5, 2026-10-05)

- `profiles.list` returns everything in §2. Only `test2` had a `hermes-bots` block
  (`{shape:"squircle", color:"hsl(210 68% 58%)", imageKind:"shape", title:"", created}`). Desktop kept the look
  of the other bots in its own storage, so `ui_meta` is not a full record of how Desktop draws a bot.
- **Every bot had `has_avatar: true`.** These are Desktop's 160×160 PNG snapshots of the drawn face, and they go
  stale: test2's showed a yellow hexagon while its metadata says a blue squircle. Herald follows Desktop's rule:
  it skips a 160×160 PNG when the metadata says how to draw the bot, and shows the PNG otherwise, because then
  it's the only record of Desktop's look (`showsPicture` in `BotLook.kt`).
- Our port of `defaultShapeFor` picks a triangle for `test1`, the same shape Desktop's snapshot shows.
- Bot Chats don't appear in `GET /api/sessions`, so Chats mode doesn't list them twice.
- No group chats existed, so the `hermes-bots-groups` mirror is still unchecked.

Still open:
- That `session.create` takes `title`/`hidden`/`follow_profile_config` from our WS client, and that `session.title`
  works on a lazy session. Source at the tag supports both (`pending_hidden` handling in
  `tui_gateway/methods_session.py`); confirm live.
