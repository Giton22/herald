# Herald push: wire protocol v1

Notifications that reach the phone when it can't reach the gateway (off the tailnet, on mobile data). The
gateway runs the **herald-push** Hermes plugin. The gateway and the phone talk only through an **ntfy** server
(public `https://ntfy.sh` by default, or the user's own), and both of them connect to it **outbound**.
Everything is end-to-end encrypted and signed, so the ntfy server only carries opaque envelopes.

## Keys and topics

At registration each side holds two P-256 key pairs:

| Key | Use |
|---|---|
| `enc` | ECDH: the other side encrypts to it |
| `sig` | ECDSA P-256/SHA-256: this side signs what it sends |

- The **phone** generates its pairs once. Its private keys never leave the phone; on Android they are wrapped
  by an AndroidKeyStore AES key.
- The **gateway** (plugin) generates its pairs once and keeps them in its data directory, file mode 0600.
- **Topics** are per phone: `push_topic` (gateway → phone) and `reply_topic` (phone → gateway). Each is
  `hp-` followed by 32 random hex characters (128 bits), and is unguessable. A topic name alone gives nothing:
  every message is encrypted and signed.

Public keys travel as base64url (no padding) of the **uncompressed** SEC1 point: 65 bytes, starting with `0x04`.

## Registration

This happens over the authenticated dashboard connection, while the phone can reach the gateway, so the keys
are exchanged on a trusted channel. Hermes plugins can't add JSON-RPC methods, so the plugin registers a
slash command, and Herald calls it without a session: `command.dispatch {name: "herald-push", arg: "register
<json>"}`. The command refuses to run on a messaging platform (Telegram, Discord, …) or outside the dashboard
process, so a person allowed to chat with a bot can't register their own phone. Other operations:
`info`, `unregister <device_id>` and `test <device_id>` (sends a `ping`). Each answers with one JSON object.

If the gateway lacks the plugin, Herald installs it with `plugins.manage {action: "install", identifier:
"https://github.com/Giton22/hermes-herald-push", ref: <commit>, enable: true}`. That is the same call
Desktop's Plugins hub makes, and the plugin is live without a restart. `ref` pins the exact commit Herald was
built against, so a later change to the repository never reaches a gateway unreviewed. A copy that is
present but switched off is switched on (`plugins.manage {action: "toggle"}`) rather than reinstalled, and
one older than Herald's minimum version is reinstalled at the pinned commit (`force: true`).

One phone identity serves one gateway. Moving to another gateway, or turning the feature off, retires the
identity: the keys and topics are dropped, so the old gateway's pushes land on a topic nobody reads, and that
gateway is sent `unregister` as soon as it can be reached. The phone only accepts envelopes signed by the
gateway it is signed in to now.

The phone sends:

```json
{ "device_id": "<uuid>", "name": "Pixel 9", "server": "https://ntfy.sh",
  "push_topic": "hp-…", "reply_topic": "hp-…",
  "enc_pub": "<b64u>", "sig_pub": "<b64u>" }
```

The gateway stores the record, replacing any earlier one with the same `device_id`, and answers:

```json
{ "gateway_id": "<uuid>", "enc_pub": "<b64u>", "sig_pub": "<b64u>", "version": 1 }
```

The phone pins the gateway's keys. Unregistering removes the record, and the topics are then never used again.

## Envelope

One ntfy message body, at most 4096 bytes. Bodies above that become an attachment on ntfy, so senders keep
the plaintext text to about 2000 characters.

```json
{ "v": 1, "epk": "<b64u ephemeral P-256 pub>", "salt": "<b64u 16 bytes>",
  "iv": "<b64u 12 bytes>", "ct": "<b64u AES-256-GCM ciphertext||tag>", "sig": "<b64u DER ECDSA>" }
```

**Encrypting to a recipient's `enc` key:**

1. Generate an ephemeral P-256 key pair `e`. Compute `z = ECDH(e.priv, recipient.enc_pub)`, the 32-byte x coordinate.
2. Derive `key = HKDF-SHA256(ikm = z, salt = salt, info = "herald-push/v1/" + dir, L = 32)`, where `dir` is
   `g2p` (gateway → phone) or `p2g` (phone → gateway).
3. Encrypt `ct = AES-256-GCM(key, iv, plaintext, aad = topic)`, where `topic` is the ntfy topic the envelope is
   published to. Binding to the topic stops an envelope being replayed into another phone's topic.
4. Sign `sig = ECDSA-P256-SHA256(sender.sig_priv, M)` with
   `M = "herald-push/v1|" + dir + "|" + topic + "|" + epk + "|" + salt + "|" + iv + "|" + ct`. `M` is the
   b64u strings joined with `|`, as UTF-8.

**Receiving:**

1. Verify `sig` against the pinned sender `sig_pub` **before** decrypting. Drop the envelope on failure.
2. Decrypt with the derived key and `aad = topic`. Drop it on failure.
3. Check the plaintext's `ts` and `id` (below).

## Plaintext

UTF-8 JSON. Every message has:

- `id`: a uuid, unique per message. The receiver remembers the ids it has seen for 24 h and drops repeats.
- `ts`: unix seconds. The receiver drops messages older than 24 h (g2p) or 10 minutes (p2g, since those are
  actions), and messages more than 5 minutes in the future.
- `type`

### Gateway → phone (`g2p`)

| `type` | Fields |
|---|---|
| `bot_message` | `bot` (profile), `label`, `session` (stored id), `text` |
| `approval` | `bot`, `label`, `session`, `request_id`, `command`, `description`, `choices` (subset of `once`, `session`, `deny`; never `always`), `expires` (unix) |
| `ping` | none. Sent by the plugin's "Send a test" action |

### Where the gateway's messages come from

The plugin runs one watcher. Only one process holds a file lock under `<root>/plugin-data/herald-push/`,
and it is the dashboard or the messaging gateway, never a one-shot chat. The watcher polls every profile's
`state.db` read-only, about every 3 seconds, for new final assistant replies in the profile's Bot Chat, and
follows compression to the newest continuation. This catches replies whichever process wrote them. Replies
older than 15 minutes (after a restart) and silence tokens are not pushed.

### Phone → gateway (`p2g`): reserved

**Not accepted in plugin 0.1.** The plugin doesn't read reply topics yet. A Reply or Approve from a
notification still goes over the direct connection. The format below is fixed so that a later version can
add it without changing the phone's keys.

| `type` | Fields |
|---|---|
| `reply` | `bot`, `session`, `text`: post `text` into the bot's Bot Chat as the user |
| `approval_answer` | `request_id`, `choice` (`once`, `session` or `deny`). Accepted only for a request the gateway pushed to **this** device that is still pending and not expired. Each request id is answered at most once. |

A phone's `reply_topic` is only ever read by the gateway, so a `p2g` envelope is attributed to the device
that owns that topic, and its signature must verify against **that device's** `sig_pub`.
