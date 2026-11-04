# Kyllene subscription voice host

Experimental, standalone Hermes dashboard plugin for **voice-session creation only**, included as source in this repository. It can serve a compatible client's outgoing or incoming voice conversation, but it does not initiate calls or replace the stock voice route. Inclusion does not imply a release or deployment. Kyllene Push remains responsible for phone tools, notifications and incoming-call delivery; this plugin does not provide those capabilities.

## Installation (after review)

Once a reviewed commit containing this directory is available to users, an operator may install it explicitly with a full 40-character commit SHA:

```sh
hermes plugins install 'https://github.com/Giton22/kyllene.git#plugins/kyllene_voice' --ref <FULL_40_CHARACTER_COMMIT_SHA>
```

Replace the placeholder with a stable, accessible commit agreed with the maintainer; no such release pin is established here. Review the source, dependencies, permissions and risks before installation. Do not force-replace an existing plugin installation. Installation is optional and is not performed by the tests.

### Release build pin

The one-click installer requires a build-time commit pin, explicit profile selection and consent, and evidence that the connected gateway supports profile-scoped pinned installs. These checks are compatibility evidence, not proof of gateway provenance or plugin integrity. Existing copies are not replaced automatically. The app's generated `VoicePluginRelease.revision` defaults to null; malformed nonempty values fail the build. A syntactically valid SHA alone does not establish trust. No runtime URL or ref override is offered.

The proposed release workflow checks that the tag's commit is reachable from the maintainer-controlled `Giton22/kyllene` upstream and that this plugin subtree matches the checked-out commit, then supplies that commit to the Android and desktop builds. The commit must already be available upstream to users; a PR cannot safely pin its own not-yet-known merge commit. This workflow change still requires maintainer review and a real release-run verification.

## Contract and operation

An authenticated client selects a profile and sends `POST /api/plugins/kyllene_voice/gpt-live/session?profile=<profile>` with exactly `sdp` (WebRTC offer), `billing: "subscription"`, and `history` (ordered user/assistant text messages). The host obtains voice instructions and voice from that profile's `tools.voice_live.build_session_config()`, and sends them with the offer, selected history and client delegation to the subscription service. The response contains a WebRTC answer, not credentials. There is no API-key billing fallback. The code does not implement the earlier manual-briefing host contract.

Dashboard authentication is owner-level, not per-user authorization. The plugin relies on Hermes' request guard and private `_config_profile_scope`. An explicit profile is required on the direct route; an absent query is accepted only when Hermes marks the request as hosted-authenticated, to support forwarding to a selected secondary profile host. The plugin cannot independently distinguish a forwarded explicit selection from a native-ticket request lacking a query; it relies on gateway routing and client behavior. Credentials may be shared across profiles; profile scoping does not imply separate billing. Sessions may refresh or adopt existing Codex credentials; authorization and credential store access must be reviewed by the operator.

Voice selection uses `voice.gpt_live.voice` in the selected profile without substitution. The integration accepts Hermes' default `marin` and the Codex built-in v3 IDs from [6ff1e1ca](https://github.com/openai/codex/blob/6ff1e1ca3b30fc8b914720681fcf5fe62e95f856/codex-rs/protocol/src/protocol.rs): `juniper`, `maple`, `spruce`, `ember`, `vale`, `breeze`, `arbor`, `sol`, `cove`, `aube`, `bubbie`, `haetsal`, `hanul`, `himari`, `leher`, `miko`, `neer`, `porto`, `rindo`, `rio`, `rivage`, `selva`, `sonna`, `tinta`, `viola`. Provider acceptance of `marin` on this subscription endpoint is unverified, and the local list does not guarantee provider availability of any voice.

The toggle performs `GET /api/plugins/kyllene_voice/gpt-live/status` for the selected profile, or the launch profile only when safely identified as default. It checks the mounted versioned status contract, profile voice configuration against a local allowlist, and read-only resolution of a structurally valid Codex token and account ID; it does not refresh or adopt credentials, contact the provider, create a call, or verify token validity, provider entitlement, real voice availability, gateway/plugin provenance, WebRTC, or future success. It does not check other profiles. The global opt-in remains on even if the check fails; calls fail closed without API fallback. Use Check subscription voice setup after installation, restart, sign-in, or profile changes. Dashboard authentication is separate from ChatGPT/Codex provider credentials. An older plugin without this route must be updated before it can be checked. A successful check is not a guarantee of compatibility with future private Hermes/provider APIs.

## Data, dependencies and risk

The host sends the SDP offer, profile voice instructions, selected voice, bounded selected-chat history, and client-delegation setting to `https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas`, model `gpt-live-1-codex`, using ChatGPT/Codex subscription credentials and account ID in headers. The resulting WebRTC connection carries live audio and associated session data to the provider. Review provider retention, privacy and account terms before use. Delegated Hermes chat turns continue through their selected chat provider and can incur separate API charges; subscription-only voice does not enforce subscription-only chat.

The exchange is adapted from MIT-licensed Hermes Conduit Notifier snapshot **cdbf0acd0a9d416749a6fceaf42fcb28a16e4037**; the included `LICENSE` preserves its notice. The endpoint, `quicksilver=v2` header, request structure, model, and Hermes private hosting, authentication and profile APIs are version-sensitive, unsupported integration points and may change or violate provider terms. Plugin metadata version is `0.1.0`; no general Hermes compatibility range has been established. The local synthetic tests were run against a Hermes checkout at commit `bf0203a794a07aac3ff764972ab9087baa4e4f4e` with local modifications, not a verified release. Requires Python and a compatible Hermes runtime with FastAPI and Starlette, including `hermes_cli` and `tools.voice_live`.

A nonstandard resolved Codex base URL is refused and redirects are disabled. Requests, responses, history and concurrent sessions are bounded and most exchange failures are sanitized, but these measures do not replace a security review. A failed or invalid response can follow creation of an upstream session: do not blindly retry. Provider acceptance of delegation, initial history, instructions, model, voice and audio behavior remains unverified. Review gateway admission, profile isolation, installation and enable/disable behavior before use.

## Tests

From this directory, using the target Hermes environment and dependencies:

```sh
HERMES_REPO_ROOT=/path/to/hermes-agent PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s tests -v
```

Alternatively omit `HERMES_REPO_ROOT` if the compatible Hermes packages are already importable. Tests exercise the actual Hermes hosted ASGI plugin loader, with synthetic authentication, profile scope, settings, credentials and/or provider exchange as specified in each test. They make no provider calls. They are not an end-to-end gateway, separate-process, real-account, entitlement, audio or device test. No installation, restart, live provider call or deployment is performed by these tests.
