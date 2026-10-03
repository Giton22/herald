# Hermes backend protocol — research notes

Source: `NousResearch/hermes-agent` @ `bfb30051` (2026-10-02). Paths below are relative to that repo.

**Target version:** the app is developed against the latest release, **v0.21.5** (tag `v2026.9.24`), which is
what the test gateway runs. Auth routes (`/auth/password-login`, `/api/auth/ws-ticket`, `/auth/native/*`) and the
WS subprotocols are identical between that tag and `main`; `tui_gateway/ws.py`, `web_server_chat.py` and the
OpenRPC contract differ, so take JSON-RPC shapes from the tag, not `main`.

Hermes exposes **two** client-facing servers. They are separate processes.

| | Dashboard server (`hermes dashboard`) | Gateway API server (`hermes gateway`) |
|---|---|---|
| Default port | 9119 | 8642 |
| Who uses it | Official Desktop app ("Remote gateway"), dashboard `/chat` | Open WebUI, OpenAI-compatible tools, iOS companion apps |
| Transport | REST + **JSON-RPC over WebSocket `/api/ws`** | REST + SSE |
| Auth | Password login / OAuth (cookies) or native PKCE (bearer) → WS ticket | Static `Authorization: Bearer <API_SERVER_KEY>` |
| Surface | ~250 RPC methods, 75 events, 13 server→client requests | ~45 REST routes |
| Code | `hermes_cli/web_server*.py`, `hermes_cli/dashboard_auth/`, `tui_gateway/` | `gateway/platforms/api_server*.py` |
| Spec | **`apps/shared/src/gateway-contract.openrpc.json`** (generated, machine-readable) | `website/docs/user-guide/features/api-server.md` (partial) |

**Decision: target the dashboard server first** ("remote gateway" in Desktop terms). It's what the
official client uses, has the full feature set (approvals, clarify, sessions, profiles, cron, voice,
config), and ships a generated OpenRPC contract we can codegen Kotlin models from. The gateway API
server is a possible later "lite" mode (single static key, simpler).

---

## 1. Dashboard server ("remote gateway")

Docs: `website/docs/user-guide/features/web-dashboard.md` (§ "Connecting Hermes Desktop to a remote
backend", § Authentication), `website/docs/user-guide/multi-connection-desktop.md`,
`website/docs/guides/desktop-native-signin.md`.
Reference client: `apps/desktop/electron/connection-config.ts`, `apps/shared/` (`JsonRpcGatewayClient`).

### Server setup the user must do
```
hermes dashboard --host 0.0.0.0 --port 9119 --no-open   # + username/password provider configured
```
- Non-loopback bind **requires** an auth provider (fails closed otherwise).
- DNS-rebinding guard: the URL the client uses must match the Host the server bound to.
- Probe: `GET /api/status` (public) → check `auth_required`, `auth_providers` (`"basic"` = password).

### Auth flows
1. **Discovery** — `GET /api/status`, `GET /api/auth/providers` (public).
2. **Password login** — `POST /auth/password-login` `{provider, username, password}` → sets cookies
   `hermes_session_at` (~15 min) + `hermes_session_rt` (24 h rotating, reuse-detected). Cookie names may be
   prefixed `__Host-` / `__Secure-`. Expired AT + live RT is rotated transparently on the next authed request.
   429 = rate limited, 401 = bad creds.
3. **Native OAuth (RFC 8252 + PKCE S256)** — `GET /auth/native/authorize?provider&code_challenge&code_challenge_method=S256&redirect_uri&state`
   in the system browser (Custom Tab); `redirect_uri` **must be a loopback IP literal**
   (`http://127.0.0.1:<port>/...`, `localhost` rejected) → app runs a tiny loopback listener →
   `POST /auth/native/token {code, code_verifier}` → `{access_token, refresh_token, token_type:"Bearer", expires_at, provider, user_id}`;
   `POST /auth/native/refresh {refresh_token, provider}` (401 `session_expired` → re-login).
   Advertised via `auth_flows` containing `native_pkce`.
4. **Legacy token mode** — REST header `X-Hermes-Session-Token`, WS `?token=`.
5. **WS ticket** — `POST /api/auth/ws-ticket` (authed) → `{ticket, ttl_seconds: 30}`, single-use, one per WS.

### WebSocket `/api/ws`
- Subprotocols: `Sec-WebSocket-Protocol: hermes-gateway-v1, hermes-gateway-ticket.<ticket>` (or `?ticket=`).
- Close codes: **4401** ticket didn't authenticate, **4403** request guard (Host/peer mismatch).
- Framing: newline-delimited **JSON-RPC 2.0**, peer-to-peer (`tui_gateway/ws.py`, `tui_gateway/AGENTS.md`):
  - client → server: method calls
  - server → client: `event` notifications
  - server → client: **requests** with ids `srq-<n>`; the agent thread blocks until we reply with a
    JSON-RPC response carrying the same id.
- Handshake: server sends `gateway.ready` → client sends `client.capabilities {server_requests: true}`
  (otherwise server requests fail fast).
- Clients ping ~every 15 s (`ping`).
- Reconnect: `session.resume` / `session.events.since` replay events and re-send still-open requests.

### RPC surface (from OpenRPC; 251 methods)
MVP-relevant:
- `session.create | list | resume | history | title | interrupt | steer | delete | archive | branch | usage | status | most_recent | close`
- `prompt.submit {session_id, text, ...}` (+ `prompt.background`, `prompt.btw`)
- `image.attach_bytes {session_id, content_base64, filename, ext}`, `file.attach`, `pdf.attach`
- `approval.respond | pending`, `request.answer`, `clarify.lock`
- `model.options`, `profiles.list`, `config.get`, `commands.catalog`, `complete.slash`, `slash.exec`
Later: `cron.manage`, `skills.manage`, `tools.*`, `toolsets.list`, `mcp.*`, `voice.*`, `subagent.*`,
`process.*`, `rollback.*`, `projects.*`, `insights.get`, `usage.bars`, `vault.*`, `connectors.*`.

Events (75), MVP-relevant: `message.start | delta | interim | complete`, `reasoning.delta`,
`thinking.delta`, `reasoning.available`, `tool.start | generating | complete | output_risk`,
`approval.cancelled`, `request.cancel`, `session.info | title | usage`, `sessions.changed`,
`status.update`, `notice`, `error`, `todo.updated`, `subagent.*`, `notification.show | clear`.

Server→client requests: **`approval`** (`{session_id, request_id, command, description, choices[], allow_permanent, allow_session, smart_denied, tool_name}` → `{choice: once|session|always|deny, all?}`),
**`clarify`** (1–5 questions → answers), **`sudo`**, **`secret`** (masked input), `vault.*`,
desktop-only ones (`preview.*`, `terminal.read`, `window.read`, `tour`) → reply "unsupported".

Key schemas: `PromptSubmitParams`, `MessageCompletePayload` (`text, usage, status, reasoning, warning, ...`),
`ToolStartPayload` (`tool_id, name, context, args, args_text, preview, labels`), `SessionCreateParams`,
`SessionResumeParams` (`lazy, defer_history, omit_messages, inline_images, ...`).

**Codegen:** `components.schemas` is JSON Schema → generate `@Serializable` Kotlin classes rather than
hand-writing ~1 MB of contract. Regenerate when bumping supported Hermes version.

---

## 2. Gateway API server (later / "lite" mode)

All routes (`gateway/platforms/api_server.py:1749`, `api_server_runs.py:236`); `/p/<profile>/...` prefix
for multi-profile. Docs omit several of these (marked *).

- Health/discovery: `GET /health`, `/health/detailed`, `/v1/health`*, `/v1/models`, `/api/model/options`*, `/v1/capabilities`, `/v1/skills`, `/v1/toolsets`
- OpenAI compat: `POST /v1/chat/completions`, `POST /v1/responses`, `GET|DELETE /v1/responses/{id}`
- Runs: `POST /v1/runs` → `GET /v1/runs/{id}` · `GET /v1/runs/{id}/events` (SSE) · `POST .../approval` · `POST .../steer`* · `POST .../stop`
- Sessions: `GET /api/sessions`* (`limit≤200, offset, source, include_children, title, include_hidden`) · `POST /api/sessions`* · `GET|PATCH|DELETE /api/sessions/{id}`* · `GET .../messages` (`limit≤500, offset, order, include_compacted`) · `POST .../fork` · `POST .../chat` · `POST .../chat/stream` · `POST .../model`*
- Artifacts*: `POST /v1/artifacts/upload` (raw body, `Content-Type` allowlisted, `X-Artifact-Filename`; 201 receipt) · `GET /v1/artifacts/download/{id}` (one-shot)
- Jobs: `GET|POST /api/jobs`, `GET|PATCH|DELETE /api/jobs/{id}`, `POST .../pause|resume|run`
- Rooms*: `/v1/room-members/{invitations,capabilities,grants/refresh,grants/revoke}`

Runs API details:
- `POST /v1/runs {input, session_id?, model?, instructions?, previous_response_id?, conversation_history?}`; headers `Idempotency-Key` (1–255 visible ASCII), `X-Hermes-Session-Key`.
- SSE: frames carry `id: <seq>`; resume with `Last-Event-ID` or `?last_seq=`; `replay.truncated` if backlog was dropped; comment frames `: open`, `: keepalive`, `: stream closed`.
- Events: `run.queued|started|completed|failed|cancelled|interrupted|stopping|steered`, `message.delta {delta}`, `message.interim {text, already_streamed}`, `reasoning.available {text}`, `tool.started {tool, preview}`, `tool.completed {tool, duration, error, preview≤500}`, `approval.request`, `approval.responded`, `subagent.start|complete` (goal, child_session_id, tokens, cost_usd, ...). Envelope: `{event, run_id, timestamp, seq, ...}`.
- `run.completed` carries `output, usage{input,output,total,cache_read,cache_write}_tokens, runtime{provider,model}`; failed carries `error`, `turn_exit_reason`; any terminal may carry `pending_steer`.
- Approval: `POST /v1/runs/{id}/approval {choice: once|session|always|deny (aliases approve/allow), request_id?, all?}`; 409 `approval_not_pending|approval_not_active`.
- Steer: `{input|message|text}`; 409 `run_not_accepting_steer`.
- Session stream (`/api/sessions/{id}/chat/stream`) uses different names: `run.started`, `message.started`, `assistant.delta`, `assistant.commentary`, `tool.started|completed|failed`, `tool.progress`, `assistant.completed`, `run.<status>`.
- 429 when `max_concurrent_runs` (default 10) is exceeded.

---

## 3. Push notifications

No FCM/APNs in Hermes. There is an **ntfy platform adapter** (`plugins/platforms/ntfy/adapter.py`;
`NTFY_TOPIC`, `NTFY_SERVER_URL`, `NTFY_TOKEN`) — a candidate for "approval waiting / run finished" pings
without Google services. Otherwise: foreground service holding the WS.

## 4. Prior art
- Official Desktop (Electron/React): `apps/desktop/` — the reference for the remote-gateway flow.
- Android: hermes android client v1.0.8 (Wi-Fi/Tailscale) — https://p.codekk.com/detail/6a720a32564c0c1097d45dfa
- iOS: `andyst-dev/hermes-ios` (uses a dashboard mobile bridge `/api/mobile/chat` — **not present upstream**), Hermex (targets hermes-webui).
