# Pebble companion: plan

**Goal:** talk to Hermes from a Pebble (first target: **Pebble Time 2**) through Kyllene on an Android phone. It should work the way the Wear OS companion does wherever the hardware allows, and do something sensible where it doesn't.

**Status:** built and tested on a Pebble Time 2, Phases 0 to 5, with each phase's results below. Planned on 2026-10-27 against `master` @ `687d80e3` and Pebble SDK 4.33.1.

**Evidence labels.** ✅ means verified from source or docs (the link is given). ❓ means inferred or unverified, and the plan says which phase settles it.

---

## 1. What Kyllene does for Wear OS today

The Wear companion is a thin watch UI over a phone-side relay. The phone holds the gateway connection and the sign-in. The watch never sees either.

| Piece | Where | What it does |
|---|---|---|
| Protocol | [`shared/wearlink/WearLink.kt`](../shared/wearlink/src/commonMain/kotlin/dev/hermeskotlin/wearlink/WearLink.kt) | Defines the message paths (`/herald/ask`, `/reply`, `/chats`, `/status`, `/ring`, `/call/*`, …) and their JSON bodies (`WatchAsk`, `ReplyUpdate`, `ChatList`, `PhoneStatus`, `WatchRing`, …) |
| Relay | [`shared/core/wear/WatchRelay.kt`](../shared/core/src/commonMain/kotlin/dev/hermeskotlin/core/wear/WatchRelay.kt) | Platform-agnostic. Runs the watch's ask as its own side `ChatSession`, sends `ReplyUpdate`s every ≤400 ms, and voices the reply in clips. It reaches the watch **only through the `WatchOutbox` interface** (`send(node, path, bytes)` and `stream(...)`) |
| Phone transport | [`androidApp/wear/`](../androidApp/src/main/kotlin/dev/hermeskotlin/android/wear/) | `WearBridgeService` (incoming messages and channels) and `PhoneOutbox` (the Data Layer). `WatchRinger` rings the watch, `WatchAccentSync` pushes colours, and `WearRequests` posts the watch chat's approvals as phone notifications |
| Live calls | [`shared/ui/voice/WatchCalls.kt`](../shared/ui/src/commonMain/kotlin/dev/hermeskotlin/ui/voice/WatchCalls.kt) | The watch holds a WebRTC call. The phone brokers the SDP and runs requests as turns |
| Watch app | [`wearApp/`](../wearApp/src/main/kotlin/dev/hermeskotlin/wear/) | Talk screen (orb, caption, reply), chats list, settings, ring screen, call screen, tile, complication, on-watch speech recognizer or recorder, and on-watch TTS fallback |

**The key seam is `WatchOutbox`.** Most of the Pebble work is a second transport behind it, plus a new watch app. The relay's turn logic, side-chat handling and status logic are reused unchanged.

Gaps in that seam (✅ from reading the code):

- **DI binds one `WatchOutbox`** (`WearModule.kt`), which is the Data Layer one. Pebble needs a **routing outbox** keyed by node id: for example, `pebble:<watchId>` goes to Pebble and everything else goes to the Data Layer.
- **`WatchRinger` and `WatchAccentSync` call Wear APIs directly** (`CapabilityClient`), and `WearBridgeService` *is* a `WearableListenerService`. Each needs a Pebble counterpart, or a small "watch transports" abstraction that both implement.
- **`WatchRinger.ring` only rings when the gateway talks live**, because answering a ring on Wear means starting a WebRTC call. Pebble can't do that (see §3), so ringing needs its own rule.
- **No "replay current turn" message.** If the watch app is closed mid-turn, it misses updates, and the Wear protocol has no way to ask for the current state again.

---

## 2. What the Pebble platform gives us

### Hardware: Pebble Time 2 = platform `emery` ✅

[Hardware information](https://developer.repebble.com/guides/tools-and-resources/hardware-information/):

- **Display:** 200×228, rectangular, 64 colours.
- **Limits:** 128 KB for code plus heap, 256 KB for resources.
- **Hardware:** 4 buttons, touchscreen (watchapps only), heart-rate monitor, **microphone**, **speaker** (flint and emery only), LRA vibration motor.

### Languages

- **C SDK:** full API, runs on every platform. ✅
- **Alloy** (on-watch JavaScript, Moddable/XS): runs on emery and gabbro only. ✅
  - It is unverified whether an Alloy `Message` reaches a PebbleKit Android 2 companion, or only PebbleKit JS. ❓
- **Recommendation: C.** It has the full API (speaker, AppGlance, quick launch), runs on every model, and the PebbleKit Android 2 sample watchapp is in C.

### Phone ↔ watch: PebbleKit Android 2 ✅

The docs say it is the [currently recommended](https://developer.repebble.com/guides/communication/using-pebblekit-android/) way to reach the watch. Library: [`io.rebble.pebblekit2:client`](https://github.com/pebble-dev/PebbleKitAndroid2) (1.3.2), Apache-2.0, Kotlin.

- **Supported phone apps:** the Core Pebble app ≥ 1.0.7.7, and microPebble.
- **Sending:** `DefaultPebbleSender().sendDataToPebble(uuid, dict, watches?)` is a suspend function that returns a `TransmissionResult`:
  - `Success`
  - `FailedWatchNotConnected`
  - `FailedWatchNacked`
  - `FailedTimeout`
  - `FailedDifferentAppOpen`
  - `FailedNoPermissions`
- **Receiving:** subclass `BasePebbleListenerService`. It has `onMessageReceived(uuid, dict, watch)` (returns Ack or Nack), `onAppOpened` and `onAppClosed`. The Pebble app binds it while our watchapp runs.
- **Also available:** `startAppOnTheWatch()`, `stopAppOnTheWatch()`, timeline pins from the phone, and `PebbleInfoRetriever`.
- **The watchapp must name its companion.** Its `package.json` needs a `companionApp.android.apps[].package` entry, or sends fail with `FailedNoPermissions`. List **both** `dev.herald.android` and `dev.herald.android.dev` (the debug suffix).
- **Turn off auto-select.** By default the library trusts any app that claims to be the Pebble app. Set `DefaultPebbleAndroidAppPicker.enableAutoSelect = false` and use the `client-ui` picker. Kyllene sends private agent output.
- **Avoid classic PebbleKit** (`com.getpebble:pebblekit`). It uses broadcasts that any app can read and that can't wake Kyllene, and the Gravel fork turns it off.

### AppMessage limits ✅ ([AppMessage](https://developer.repebble.com/docs/c/Foundation/AppMessage/))

- **Buffers:** the guaranteed minimum is 124 B inbox and 636 B outbox. The watch can ask for `app_message_inbox_size_maximum()`, documented as up to about 8 KB. Both buffers come out of the 128 KB heap.
- **Foreground only:** `FailedDifferentAppOpen` means our watchapp must be the one open. Anything sent while it's closed is lost.

### Voice

- **Input: dictation only.** `dictation_session_create` works on emery ✅. The Pebble phone app transcribes the speech and the watchapp gets **text only**.
  - There is no raw-microphone API ✅ (no capture symbol in the [API index](https://developer.repebble.com/api-index.json)).
  - The phone app's default speech-to-text backend on Android is unverified ❓. The options are WisprFlow cloud, on-device Parakeet or Whisper, Rebble ASR, and ML Kit. Phase 0 settles it.
- **Output: speaker** ([guide](https://developer.repebble.com/guides/events-and-services/speaker/)) ✅. It can play tones, note sequences, and **PCM streaming** at 8/16 kHz, 8/16-bit.
  - That covers a ringtone.
  - It might cover gateway TTS, but even 8 kHz/8-bit is about 8 KB/s, which is likely more than AppMessage over BLE can sustain. This is a spike, not a commitment.

### Glanceables and other APIs ✅

| Feature | What it gives us |
|---|---|
| [AppGlance](https://developer.repebble.com/guides/user-interfaces/appglance-c/) | A subtitle in the launcher; up to 8 slices |
| [Quick launch](https://developer.repebble.com/guides/design-and-interaction/one-click-actions/) | Hold a button to open the app straight into dictation |
| [Custom vibration patterns](https://developer.repebble.com/docs/c/User_Interface/Vibes/) | Our own ring and notification buzzes |
| [Persistent storage](https://developer.repebble.com/guides/events-and-services/persistent-storage/) | 1 MB per app on emery; 256 B per value |
| [Timeline local pins](https://developer.repebble.com/guides/pebble-timeline/timeline-local-pins/) | Pins pushed from the phone through PebbleKit 2. The web timeline API is gone |

### Notification mirroring (from the Core app's source, `libpebble3/.../notification/`)

What the Core app's source shows ✅:

- It mirrors Android notifications.
- It turns `RemoteInput` into a Reply action, with canned replies and dictation.
- It turns other actions into buttons that fire their `PendingIntent`.
- It **prefers `WearableExtender` actions** when a notification has them.
- It **ignores ongoing notifications.** Kyllene's ringing "call" notification is ongoing, so it won't show.
- It skips `setLocalOnly` notifications.
- It **drops actions marked `showsUserInterface`** (the `NotificationCompat` default) unless *Enable showsUserInterface actions* is on in the Pebble app. Kyllene's actions are all marked that way today (see Phase 0 findings).
- *(The earlier claim that it sends nothing while the phone is unlocked was wrong: `alwaysSendNotifications` defaults to `true`.)*

**Never tested on a watch** ❓: whether Kyllene's existing reply and approval notifications already work on a Pebble with no code.

---

## 3. Feature parity: Wear OS vs Pebble

| Wear OS feature | Pebble equivalent | Feasibility |
|---|---|---|
| Ask by voice (watch recognizer, or a recording the gateway transcribes) | Dictation API, giving text that goes into `WatchAsk.text` | ✅ Text only. **The gateway-transcription path isn't possible** (no raw audio). The transcription quality is whatever the Pebble app's speech-to-text gives |
| Ask by typing | Canned prompts on the watch, plus dictation | ✅ (no keyboard) |
| Live progress: phase, activity line, streaming reply | A talk window with a status line and the reply scrolling below | ✅ Needs a byte budget and throttling (§4.2) |
| Read the reply aloud (gateway voice, else watch TTS) | Text only by default. Gateway voice through speaker PCM streaming is a **spike** | ❓ The watch-TTS fallback **isn't possible** (no on-watch TTS) |
| Stop the running turn | Back or Select, sending `WatchStop` | ✅ |
| Recent chats: pick, new chat, continue | A `MenuLayer` of up to 15 chats, with "New chat" first | ✅ |
| Phone status dot (signed in, connected, failed) | A status icon in the header | ✅ |
| Accent colour follows the phone | Map ARGB to the nearest of the 64 `GColor`s; black-and-white on older models | ✅ |
| Approvals and questions in the watch's chat: "answer on your phone" | Same phase (`WaitingOnPhone`), plus the existing phone notification, which may mirror | ✅ Answering on the wrist is a **decision** (§7) |
| Ring for approvals and questions; Answer starts a live call | Vibration and a speaker ringtone, then a ring window. **Answer can't start a live call** | ❓ Needs a new answer behaviour (§7) |
| Live WebRTC voice call | — | ❌ **Not possible** (no WebRTC, no internet on the watch, no raw mic) |
| "Ask Hermes" tile | Quick launch (hold a button, go straight into dictation) | ✅ |
| Complication | AppGlance ("Last: …" or "Working…") | ✅ |
| Works while the app is closed | Rings and finished replies need a fallback: auto-launch, a timeline pin, or a mirrored notification (§4.4) | ❓ Phase 3 |

---

## 4. Architecture

```
 Pebble Time 2 (C watchapp, pebbleApp/)                    Android phone (Kyllene)
 ┌───────────────────────────────┐    AppMessage dicts    ┌──────────────────────────────────────────┐
 │ talk_window   chats_window    │◄──────────────────────►│ PebbleBridgeService : BasePebbleListener │
 │ ring_window   status/glance   │   (via Pebble app,     │        │ decode → WatchAsk/Stop/…          │
 │ link.c  (keys from package.json)│   PebbleKit Android 2)│        ▼                                  │
 └───────────────────────────────┘                        │  WatchRelay  (unchanged, shared/core)    │
                                                          │        │ outbox.send(node, path, json)    │
                                                          │        ▼                                  │
                                                          │  RoutingOutbox ── "pebble:*" ──► PebbleOutbox
                                                          │               └── else ──────► PhoneOutbox (Wear)
                                                          └──────────────────────────────────────────┘
```

### 4.1 Where the code goes

```
shared/wearlink/…/                    Pure Kotlin, tested in commonTest (and androidHostTest for package.json).
  PebbleLink.kt                       Message keys and types, the app UUID, and the codec between WearLink models and
                                      dictionaries.                                                    [Phase 1, built]
  PebbleText.kt                       UTF-8 sizing and cutting at character boundaries; Markdown to plain text. [built]
  PebbleWatch.kt                      One watch's sender: queue, chunked reply streaming, replay, resync.       [built]
androidApp/…/pebble/
  PebbleBridgeService.kt              BasePebbleListenerService: asks, stops, chat and status requests, resyncs;
                                      onAppOpened / onAppClosed.                                         [built]
  PebbleOutbox.kt                     WatchOutbox over DefaultPebbleSender, one PebbleWatch per watch.     [built]
  RoutingOutbox.kt                    Picks PebbleOutbox or the Wear Data Layer by node id (bound in WearModule). [built]
  PebbleApps.kt                       Turns PebbleKit's auto-select off and picks the Core app by name.   [built]
  PebbleRinger.kt                     Rings Pebbles (rules in §4.4), reports back to ChatNotifications.   [Phase 3]
  (Settings → Watch: a "Pebble" switch and a picker for other Pebble apps)                                 [later]
pebbleApp/                            The C watchapp (pebble-tool project; not part of Gradle).
  package.json                        uuid, messageKeys with explicit numbers (the source of truth), companionApp.
  src/c/main.c                        Phase 1's bare test app; split into talk, chats, ring, glance in Phase 2+.
```

### 4.2 Wire protocol (new, compact)

The Wear JSON protocol stays untouched. `PebbleOutbox` **decodes the JSON for each path** and projects it into a small dictionary. `PebbleBridgeService` does the reverse for incoming messages. JSON is never parsed on the watch.

- **One key, `TYPE`, says what the message is:**

  | TYPE | Direction | Carries |
  |---|---|---|
  | `ASK` | Watch → phone | `ASK_ID`, `TEXT`, `CHAT_ID?` (the phone always asks with `speak=false`) |
  | `STOP` | Watch → phone | `ASK_ID` |
  | `CHATS_REQ` | Watch → phone | — |
  | `STATUS_REQ` | Watch → phone | `ACCENT_REV` |
  | `RING_ON` | Watch → phone | `REQUEST_ID` |
  | `RING_END` | Watch → phone | `REQUEST_ID`, `ANSWERED` |
  | `RESYNC` | Watch → phone | `ASK_ID` (told to add past its text: send the reply again from 0) |
  | `REPLY` | Phone → watch | `ASK_ID`, `SEQ`, `PHASE`, `CHAT_ID`, `ACTIVITY`, `TITLE`, `ERROR`, `TEXT`, `TEXT_OFFSET`, `TEXT_TOTAL` |
  | `CHAT_ITEM` | Phone → watch | `INDEX`, `COUNT`, `CHAT_ID`, `TITLE`, `AGO` |
  | `STATUS` | Phone → watch | `VERSION`, `FLAGS`, and the accent (`ACCENT_REV` and 4 RGB colours) when the watch's is out of date |
  | `RING` | Phone → watch | `REQUEST_ID`, `CHAT_ID`, `TITLE`, `APPROVAL`, `AGENT` |
  | `RING_END` | Phone → watch | `REQUEST_ID` |

- **Keys come from `pebbleApp/package.json` `messageKeys`**, written as an object with explicit numbers (`"TYPE": 1`, …), which the SDK keeps as given (verified: `MESSAGE_KEY_TYPE = 1`). A JVM test reads that file and fails if `PebbleKeys`' numbers, the UUID or the companion packages drift from it. It lives in a new `shared/wearlink/src/androidHostTest`, because `commonTest` has no file I/O.
- **Numbers** go as 32-bit integers both ways; the watchapp reads each by its length, and PebbleKit hands the phone every number as `Int32`/`UInt32` anyway.
- **Budget for each message:** the watch opens a 2,048-byte inbox and a 512-byte outbox. A reply chunk is at most 1,024 bytes of text, and a test checks that a reply with every field at its longest still fits the inbox (`dict_calc_buffer_size` rules: 7 bytes per value, text with its NUL).
  - `PebbleOutbox` clips `TEXT` by **UTF-8 bytes** at a code-point boundary. Wear's `MAX_WATCH_TEXT` is 24,000 *characters*, which is useless here.
  - The whole reply kept on the watch is capped at about 6 KB, with an ellipsis.
- **Streaming:** `WatchRelay` resends the whole reply every 400 ms. `PebbleWatch` turns that into **placed pieces**:
  - Each piece is computed against what the watch **acknowledged** (PebbleKit's `Success`), never against the last update built. A lost piece is just part of the next one.
  - The watch sets its text's length to `TEXT_OFFSET`, then appends the piece. That makes a piece sent twice harmless, and offset 0 a replace. (A C inbox handler can't NACK; the ACK is automatic.)
  - Only an offset past the watch's length is wrong (it restarted unseen): it sends `RESYNC`, and the phone sends the reply again from 0.
  - A text that changed other than by growing (a mark closing later in the stream rewrote what came before) goes again from where it changed, as pieces that each fit the inbox: the first cuts the watch's text there.
  - One message is in flight at a time; while streaming, at most one every 800 ms. Only *unsent* updates are dropped, the latest `SEQ` winning. An update that changes nothing the watch shows (only its `SEQ`) isn't sent.
  - Failures: `FailedTimeout`/`FailedWatchNacked` are tried 3 times; `FailedDifferentAppOpen`/`FailedWatchNotConnected` stop all sending until the app opens again, and queued messages (status, chats) are dropped, since the watch asks again on opening.
- **Plain text:** strip markdown (code fences, headings, links) before sending. The watch draws plain text in a `ScrollLayer`.
- **Speech:** the watch always sends `speak=false` for now, so the relay never voices. `PebbleOutbox.stream()` returns `false`.
- **Replay:** `PebbleWatch` keeps the newest `REPLY` for its watch. `onAppOpened` sends it again from 0, so reopening the app mid-turn (or after it) shows where things stand. Verified on the device (Phase 1 results).
- **Who may send:** the bridge is exported for the Pebble app to bind. PebbleKit's `BasePebbleListenerService` drops any call whose caller (by binder uid) isn't the Pebble app picked in `DefaultPebbleAndroidAppPicker`; Kyllene turns auto-select off and picks only `coredevices.coreapp` (`PebbleApps`). Without that, any app could start Hermes turns through it.
- **Gateway and profile:** a chat belongs to one gateway and profile, as on Wear OS (`WatchScope`).
  - Each `REPLY` and `CHAT_ITEM` carries its scope (`SCOPE`: the gateway, then the profile on its own line). The watch keeps it with the chat it talks in, and sends it back with each `ASK` and `HISTORY_REQ`.
  - The relay refuses an ask whose scope isn't the gateway the phone is on now ("Conversation changed. Choose it again on your watch."), and the phone refuses a history request the same way.
  - A scope longer than 200 bytes, which the watch couldn't keep, isn't sent; the watch then asks without one, in whatever the phone is on.

### 4.3 Node identity

PebbleKit 2 gives a `WatchIdentifier` on every message. Node ids are `pebble:<id>`. The relay already keys everything by node string, so nothing changes there.

### 4.4 Rings and the closed-app problem

**Rings.** A Pebble rings whatever the gateway's live voice, because answering doesn't start a call (see the decision in §7). The ring goes like this:

1. `startAppOnTheWatch()` opens the watchapp.
2. The phone sends `RING`.
3. The watch shows a ring window, with a custom vibration pattern and a speaker ringtone.
4. The watch answers with `RING_ON`, so the existing "phone's call shows on the watch" fallback logic is reused.

**Finished replies while the app is closed.** When a `Done` reply gets `FailedDifferentAppOpen`, Kyllene posts a normal reply notification for that chat, which the Pebble app mirrors (✅ *decided* 2026-10-27, over a timeline pin or reopening the watchapp).

Either way, the AppGlance is updated the next time the app runs.

### 4.5 Background wake (a risk, spiked first)

On Wear, `keepUp()` starts `ChatService` as a foreground service from inside the Data Layer callback, because Play Services grants a temporary allowlist to start one.

**Spiked in Phase 1 ✅: it works on Android 17.** With Kyllene Dev swiped away (`Killing … remove task`), opening the watchapp made Android start Kyllene's process for the Pebble app's binding (`Start proc … for bound-service PebbleBridgeService`). The ask then started `ChatService` from the background: `Background started FGS: Allowed … uidState: FGS`. Being bound by the Pebble app, which runs a foreground service, appears to lend Kyllene that state (inferred from `uidState: FGS`). Kyllene's process stayed up after the watchapp was closed mid-reply.

The fallback, should a later Android or another Pebble app change that: ask the user to exempt Kyllene from battery optimisation, which lifts the background-start ban. A foreground service *type* such as `connectedDevice` doesn't lift it on its own.

---

## 5. Phases

Each phase ends in something testable on the user's phone and Time 2.

### Phase 0: groundwork and baseline (no Kyllene code)

1. **Fork and branch.** Fork `Giton22/kyllene` to `faultoverload/herald`. Set the local git identity to the faultoverload noreply address. Then open a feature-request issue upstream proposing the plan, since CONTRIBUTING asks for an issue before anything big.
2. **Toolchain.** Arch ships Python 3.14, which pebble-tool doesn't support:

   ```
   uv tool install --python 3.13 pebble-tool
   pebble sdk install latest
   ```

   Build and run the PebbleKit 2 sample ([`sample/`](https://github.com/pebble-dev/PebbleKitAndroid2/tree/main/sample)) on the Time 2 with the Core app. That proves a message round trip.
3. **Developer Connection.** Turn it on in the Pebble app. Then `pebble install --phone <ip>` and `pebble logs --phone <ip>` work.
4. **Zero-code baseline.** Install Kyllene's current release and record what the Time 2 shows for each case below. Test each one with the phone screen on and with it off.
   - A finished-reply notification, replying by dictation and by canned reply.
   - An approval notification, using Approve/Deny.
   - A question notification.
   - The ringing "Hermes is calling" notification. It is expected **not** to mirror (ongoing).
   - The watch chat's quiet requests (`setLocalOnly`). These are expected not to mirror.
   - Which speech-to-text backend the Pebble app uses, and whether it needs internet or a subscription.
5. **Security check.** Kyllene sets `setAuthenticationRequired(true)` on approve and reply actions so a locked phone can't approve. `NotificationActionReceiver` doesn't check the lock itself (no `KeyguardManager` use), so the system shade is the only gate. **Find out whether firing them from the Pebble skips the unlock.**
   - It isn't specific to Pebble: any notification listener (Gadgetbridge, other bridges) can fire the `PendingIntent` directly. If the unlock is skipped, it may deserve an upstream issue on its own.
   - It also decides the wrist-approval policy below.

*Exit:* a short findings note appended to this doc, and the decisions in §7 made.

#### Phase 0 findings so far (2026-10-27)

**Setup used:** Pixel 9a on Android 17, Core Pebble app 1.15.0.1, Pebble Time 2, pebble-tool 5.0.40 with SDK 4.33.1.

- **Toolchain** ✅
  - Installed with `uv tool install --python 3.13 pebble-tool`, then `pebble sdk install latest`.
  - The PebbleKit 2 sample watchapp builds for every platform.
  - The sample's Android app (compileSdk 37, AGP 9.4.1) builds against a user-owned SDK in `~/Android/Sdk`. The AUR has no API 37 platform package. Install `android-sdk-cmdline-tools-latest`, accept the licenses with `sdkmanager --sdk_root=$HOME/Android/Sdk --licenses`, and the Gradle build downloads the platform and build-tools itself.
- **Developer Connection** ✅
  - It defaults to the **CloudPebble proxy**, so `pebble install --phone <ip>` is refused until *Settings → Phone → Connectivity → Use LAN developer connection* is on and the Dev Connection toggle is cycled.
  - After that, `pebble install --phone`, `pebble logs --phone` and `pebble screenshot --phone` all work.
  - LAN mode has no sign-in, so turn it off off-network.
- **PebbleKit 2 round trip** ✅
  - Watch → phone: a button press reached `BasePebbleListenerService.onMessageReceived` as `{1=UInt32(2)}`, with the watch's `WatchIdentifier` (its serial number).
  - Phone → watch: `startAppOnTheWatch` returned `Success`, followed by `onAppOpened`. `sendDataToPebble` with a text item returned `Success`, and the text showed on the watch, confirmed by screenshot.
  - `onAppClosed` fires when the app is left.
  - No permission prompt appeared for the sample (its `companionApp` entry was enough).
- **Notification baseline, Test 1 (finished reply)** ✅ with a setting
  - The reply notification reaches the watch. **Its Reply action is dropped by default.**
  - Why: Kyllene builds its actions with `NotificationCompat.Action.Builder`, which defaults `showsUserInterface` to `true`. The Pebble app [drops such actions](https://github.com/coredevices/mobileapp/blob/main/libpebble3/src/androidMain/kotlin/io/rebble/libpebblecommon/notification/LibPebbleNotificationAction.kt) unless `addShowsUserInterfaceActions` is set, and that is off by default.
  - **Requirement today:** in the Pebble app, turn on *Settings → Phone → Notifications → Enable showsUserInterface actions*.
  - With that on, Reply appears. A dictated reply went through the `RemoteInput` (`handleReply() actionIntent result code: 0`), and Kyllene sent it to the chat.
  - **Fix in Kyllene:** mark the Reply, Approve and Answer actions `setShowsUserInterface(false)`, since they go to `NotificationActionReceiver` and open no screen. Tracked in [faultoverload/herald#1](https://github.com/faultoverload/herald/issues/1), to send upstream.
  - Correction to §2: the Pebble app's `alwaysSendNotifications` defaults to `true`, so notifications are sent even while the phone is unlocked with its screen on.
- **Test 2 (approval) couldn't run:** this gateway has command approvals turned off.
  - A question notification (Hermes's clarify tool) arrived with `actions = null`. Kyllene only adds **Answer** for a single, non-multi-select question that isn't in batch form (`qid` set; `ChatNotifications.postRequest`). Otherwise it shows no buttons on the phone either.
  - **Test 3a confirmed it:** a single, single-choice question still arrived with `actions = null`, because current Hermes always sends batch form. See §8.
- **Test 2b, the unlock check, using Reply instead** ❌ **The unlock is skipped.** This is an **upstream risk** in Kyllene, not something the Pebble work introduced; it is listed in §8 for a future patch.
  - Reply carries the same `setAuthenticationRequired(true)` as Approve and Answer.
  - The phone was locked by the power button at 16:15:18 (`locking without delay due to "power button"`, `isDeviceUnlocked=false`).
  - A dictated reply from the watch fired at 16:16:10 (`handleReply() actionIntent result code: 0`), with the screen off until 16:16:38. Kyllene sent it to the chat.
  - **So `setAuthenticationRequired` is only enforced by the system notification shade.** A notification listener (the Pebble app, Gadgetbridge, any app granted notification access) fires the `PendingIntent` directly, and `NotificationActionReceiver` doesn't check the lock itself.
  - On a gateway with approvals on, Approve would very likely go through from a locked phone the same way. Today that only happens with the Pebble "showsUserInterface" setting on, and the [#1](https://github.com/faultoverload/herald/issues/1) fix would make it the default.
  - **Consequence for #1:** don't ship `setShowsUserInterface(false)` on the Approve actions alone. Either pair it with a receiver-side lock check (`KeyguardManager.isDeviceLocked`, refusing Approve or opening an unlock prompt), or leave Approve as it is so it stays off the watch. This feeds decision 2 in §7.
- **Test 3b, the ringing call** ❌ **It doesn't reach the watch.**
  - Kyllene's ring is a `CallStyle`, `CATEGORY_CALL`, ongoing notification with a full-screen intent.
  - The Pebble app's [`NotificationCallDetector`](https://github.com/coredevices/mobileapp/blob/main/libpebble3/src/androidMain/kotlin/io/rebble/libpebblecommon/calls/NotificationCallDetector.kt) did see it (`Call notification from dev.herald.android (answer=true, decline=true)`). But by design it is a *passive store* that "does not drive call state": only telephony calls ring the watch, and the detector only lends them answer and decline actions. Nothing was sent to the watch for the 10 s it rang.
  - After the call, Kyllene posted the question again: once quietly (`NotSentLocalOnly`, as designed), then as a normal notification. The normal one reached the watch at 16:26:18 (BlobDB insert succeeded), again with no actions (see §8).
  - **Confirms the plan:** Pebble rings have to come from Kyllene's own watchapp (`PebbleRinger`, §4.4), not from mirroring.
- **Phase 0 notification baseline: done.** Summary:

  | Case | On the Pebble today, no code |
  |---|---|
  | Finished reply | Shows. Reply works only with *Enable showsUserInterface actions* on ([#1](https://github.com/faultoverload/herald/issues/1)) |
  | Reply from the watch with the phone locked | Goes through; the unlock is skipped (§8) |
  | Approval | Not tested: approvals are off on this gateway |
  | Question | Shows, never answerable (§8) |
  | Quiet request (`setLocalOnly`) | Not sent, as designed |
  | Ringing call | Not shown |

### Phase 1: phone transport ✅ done (2026-10-27)

1. Add `io.rebble.pebblekit2:client` to `libs.versions.toml` and `androidApp` (`client-ui` waits for the Settings picker).
2. `PebbleLink` codec in `shared/wearlink`, with tests:
   - Key table versus `package.json`.
   - UTF-8 clipping, including emoji at the cut.
   - Delta and replace.
   - Markdown stripping.
   - Round-trip of every type.
3. `RoutingOutbox`, `PebbleOutbox` (in-flight queue, latest wins, replay cache) and `PebbleBridgeService` (manifest entry with the `RECEIVE_DATA_FROM_WATCH` action).
4. **Spike: background wake (§4.5)** with Kyllene swiped away, on Android 14+. **Gate:** don't build the watchapp until a turn completes from a closed phone app.
5. ~~Settings → Watch: a "Pebble" switch and the companion-app picker~~ **Moved to Phase 4.** For now `PebbleApps` turns auto-select off and picks the Core app by name.

*Exit:* a stub watchapp sends an ask and gets the streamed reply through to `Done`.

#### Phase 1 results

**Setup:** Kyllene Dev (`./gradlew :androidApp:assembleDev`, `dev.herald.android.dev`, R8-minified) on the Pixel 9a, signed in to the gateway, and the bare watchapp in `pebbleApp/` on the Time 2.

- **Tests:** 26 new tests in `shared/wearlink`, all passing with the 22 existing ones:
  - codec and size limits (`PebbleLinkTest`);
  - UTF-8 and Markdown (`PebbleTextTest`);
  - streaming, replace, chunking, latest-wins, closed app, reopen and resync (`PebbleWatchTest`);
  - the `package.json` keys, UUID and packages (`PebbleAppTest`).

  `:androidApp:lintDebug` has no new issues.
- **Round trip on the device** ✅
  - Dictated asks (the Core app transcribed them with `LocalFirst`, on the phone) reached the bridge.
  - Each reply streamed back in about 5 pieces, every one ACKed and none NACKed. *(From the logs; the outbox log didn't record the phase, so reaching `Done` is confirmed only where the user saw it on the watch.)*
  - The PebbleKit library works under R8 with no keep rules of Kyllene's own.
- **Background wake** ✅ (§4.5).
- **Closed mid-reply** ✅ *(per the logs)*
  - Closing the watchapp right after the first update (`AppRunStateStop`) stopped the sending.
  - Reopening sent the reply again (181 bytes) before the status, and the watch showed the whole reply with `Done` (confirmed by the user on the watch).
- **Gap found, fixed in Phase 2:** if the final `Done`/`Failed` update failed all 3 tries while the app stayed open, nothing tried again, and the watch showed the turn running until reopened. `PebbleWatch` now retries a lost final update up to 5 more rounds, 5 s apart.
- **Still the watch's job (Phase 2):** a real talk screen; the bare app only shows the status, the activity line and the reply. Buttons:
  - Select dictates;
  - a long Select sends a test ask;
  - Down starts a new chat;
  - Up asks for the status;
  - a long Up stops the turn;
  - Back leaves.

### Phase 2: watchapp MVP (talk) ✅ done (2026-10-27)

1. `pebbleApp/` scaffold: `targetPlatforms: ["emery"]` first, a `companionApp` entry, `messageKeys`, and the inbox/outbox sizes. *(Done in Phase 1.)*
2. Talk window (`src/c/talk.c`, with the messages in `src/c/link.c`):
   - Select opens dictation, which sends `ASK`.
   - The header, in the phone's accent, shows the chat title and a phone-status dot.
   - The line under it shows the phase and activity, worded as on the Wear watch.
   - What was said (bold, grey) and the reply stream into a `ScrollLayer`; Up/Down scroll.
   - **Changed from the plan:** Back always leaves; the turn goes on on the phone, and reopening catches up (Phase 1's replay). Stopping is in the hold-Select menu (`ActionMenu`): *Stop* (while a turn runs), *New chat*, *Check phone*. A stop ends the turn as `Done` with what came so far on both sides, as the Wear watch does, so reopening doesn't show it running again.
3. Phone status and error states, in the Wear watch's words:
   - phone not connected (from `connection_service`);
   - Kyllene not answering (no `STATUS` within 5 s);
   - signed out;
   - gateway out of reach;
   - reconnecting.

   The phone is asked again every 30 s while the app is open. A failed turn shows its whole error in the body. "I didn't catch that" never comes from the phone: the Pebble app's dictation shows its own errors.
4. The picked chat (id and title) and the accent persist, so the next ask continues the chat and the header keeps its colour.
5. **Changed from the plan:** no fake-phone code in the watchapp. The emulator is driven from outside instead:
   - `pebble install --emulator emery`;
   - `pebble send-app-message --emulator emery --int 1=64 4=2 … --string 2=p1 5="…"` plays the phone (numeric keys from `package.json`);
   - `pebble emu-button` presses buttons;
   - `pebble transcribe --emulator emery "…"` answers dictation.

*Exit:* on the Time 2, ask by voice, watch the reply stream in, and stop it. The phone's own chat isn't moved.

#### Phase 2 results

- **Emulator** ✅ Checked by screenshot: each phone state, the empty screen, a streaming reply growing by offset, scrolling, the action menu, dictation (confirm screen, then the ask shown and sent), and a failed turn.
- **On the Time 2** ✅
  - The user confirmed the talk screen works.
  - The logs show an ask streaming through `Sending` and `Working`, and the status checked every 30 s.
  - The first Stop left the screen on *Thinking…*, since the relay sends nothing after a stop. Fixed as above, with a test (`aStoppedTurnEndsAsDoneWithWhatCameSoFar`).
- **Installing:** `pebble install --adb` works with the phone on USB and no LAN developer connection. The LAN mode stopped listening after the Pebble app restarted, even with Dev Connection on.
- **Memory:** about 13.5 KB of the watch's 128 KB.

### Phase 3: chats, answers, rings, closed-app delivery

Decided on 2026-10-27 (§7, decisions 2 and 3, and §4.4).

1. **Chats window:** "New chat" plus up to 15 recent chats, with how long ago each was active (`CHATS_REQ`, then `CHAT_ITEM` paging), and the picked chat marked. It opens from the hold-Select menu.
2. **A setting:** *Settings → Connected devices → Answer on the Pebble*, off by default (`AppSettings.pebbleAnswers`). Its text says that answers given on the watch skip the phone's unlock. Off, the watch only says "Waiting for you on your phone", with a double buzz.
3. **Requests on the watch** (with the setting on):
   - New messages:
     - `REQUEST` (phone → watch): `REQUEST_ID`, `CHAT_ID`, `KIND` (approval or question), `TITLE`, the command or question, and `CHOICES` (newline-joined, the approval choices without "Always", as on the phone's notification).
     - `ANSWER` (watch → phone): `REQUEST_ID`, plus the choice's wire name or the text.
   - The phone answers through the same path as `NotificationActionReceiver`, by chat, so it works for the watch's own chat and for rings from any chat.
   - The watch shows an approval with its choices, and a question with its choices plus *Dictate*. Multi-select and batch questions say to answer on the phone.
   - The setting is the gate. The phone checks it again on every `ANSWER` and refuses when it's off, so a watchapp can't answer around it.
4. **`PebbleRinger`:**
   - Rings as in §4.4, whether or not the gateway talks live: `startAppOnTheWatch`, then `RING`.
   - The ring window has a custom vibration pattern and a speaker ringtone on the Time 2. The watch replies with `RING_ON`.
   - *Answer*, with the setting on, opens the request on the watch (3).
   - *Answer*, with the setting off (and always for a call from the agent), stops the watch ringing and says "Answer on your phone"; the phone rings on.
   - *Decline* goes both ways (decline on the watch stops the phone, and the reverse).
5. **Closed-app delivery:** when the turn's `Done` gets `NotOpen`, Kyllene posts its usual reply notification for that chat, which the Pebble app mirrors. Its Reply action works with the Pebble app's *showsUserInterface* setting on, and by default once [#1](https://github.com/faultoverload/herald/issues/1) is fixed.

*Exit:* the full talk → question or approval → reply loop on the device, with the setting on and off, and with the app open and closed.

**Results so far (2026-10-27, Pebble Time 2, Pixel 9a):**

- ✅ Chats (1): the list, picking a chat, and each chat opening on its last exchange (`HISTORY_REQ`).
- ✅ Closed-app delivery (5): a turn that ends while the watchapp is closed posts the reply notification, and its Reply works. This needs Kyllene's notification permission.
- ✅ Setting and requests (2, 3), with a clarify question:
  - With the setting on, the question shows only in the watchapp. Kyllene still posts it on the phone, but silently and phone-only (`setLocalOnly`), so the Pebble app doesn't mirror it on top of the watchapp's prompt (`WearRequests.shownOnWatch`).
  - Both a picked choice and a dictated answer reach Hermes.
  - Bugs found on the device:
    - An `ActionMenu` callback's `context` is the menu's, not the item's, so every pick sent the first choice. Fixed: the choice index now comes from `action_menu_item_get_action_data`.
    - With a dictation session per screen, the question's dictated answer went to the talk screen and was sent as a new message. Fixed: the app now has one session (`dictate.c`), whose result goes to the screen that started it.
  - Approvals were checked only in unit tests and on the emulator. The test gateway has approvals off.
- ✅ Rings (4), with a question from a chat not on screen:
  - The phone opens Kyllene on the watch (`startAppOnTheWatch`), holds the `RING` until it has opened, and the watch says `RING_ON` within a second.
  - *Answer* with the setting on shows the question, and its answer goes through. *Decline* stops the phone, which posts the usual notification, and Kyllene on the watch closes when it was opened for the ring. With the setting off, *Answer* says to answer on the phone, which rings on.
  - Watches stop each other: whichever ends a ring, the phone tells every other Wear OS watch and Pebble (`ChatNotifications.AllRings`). A request picked up from a ring closes on the watch once it's answered anywhere (`AlsoRings.gone`).
  - Only Pebbles that Kyllene has heard from ring, so the watchapp has to be opened once after it's installed.
  - Known limit: with a Wear OS watch and a Pebble both paired, the phone's call can show on a watch that is ringing itself, when the other watch doesn't confirm its ring in time.

### Phase 4: glanceables and polish

0. Settings → Watch: a "Pebble" switch and a picker for Pebble apps other than the Core app (PebbleKit's `client-ui`), moved here from Phase 1.
1. Quick launch straight into dictation (the tile equivalent).
2. AppGlance with the last reply's first line, or "Working…" (the complication equivalent).
3. The accent colour mapped to `GColor`; the status sends only when the accent revision changes.
4. Canned prompts menu (for example "Summarize my day", "What's running?"), configurable later.
5. Touch: tap to dictate, swipe to scroll (emery only).

**Results (2026-10-28, Pebble Time 2), all ✅ on the device:**

- (0) Built as the switch only, by choice: *Settings → Connected devices → Kyllene on the Pebble*, on by default (`AppSettings.pebble`), with *Answer on the Pebble* beside it. Connected devices opens from the Phone section, and also says where the Wear OS settings are. Off, the phone answers every message from the watch with a status flagged `FLAG_OFF`. The watch then says "Off in Kyllene" and fails any ask that's still sending. Nothing rings the watch and no requests go to it. The picker for other Pebble apps waits until someone uses one.
- (1) A quick launch (`APP_LAUNCH_QUICK_LAUNCH`) opens straight into dictation.
- (2) On closing, Kyllene sets its glance:
  - Mid-turn, "Working on it..." for 10 minutes, then "Ask Hermes anything".
  - After a finished turn, the reply's first line, at most 140 bytes and with braces swapped out, since they start template fields.
  - Otherwise, "Ask Hermes anything".
- (3) Already done in Phase 2: the accent arrives with the status, which carries its revision.
- (4) The hold-Select menu has *Prompts*, hidden while a turn runs. They're edited on the phone, in *Settings → Connected devices → Pebble prompts* (`AppSettings.pebblePrompts`), and start as "Summarize my day", "What's running?" and "What needs my attention?".
  - The watch sends the revision of the prompts it has with each status request (`PROMPTS_REV`). When that's out of date, the phone sends the list back (`PROMPTS`, type 71).
  - The list is up to six prompts, one per line and each at most 60 bytes, with blank ones left out.
  - The watch keeps them in persistent storage, so the menu still works with the phone out of reach. With none, the menu has no Prompts entry.
  - Tested on the Time 2: a prompt edited in Kyllene Dev showed up in the watch's menu and stayed there after Kyllene was reopened.
- (5) On the talk screen, a tap dictates and a vertical drag scrolls, using the SDK's tap and pan recognizers with the system touch bridge turned off for that window. The chat list's `MenuLayer` already scrolls by touch. The ring screen stays buttons-only, so a stray tap can't answer.

### Phase 5: other models, CI and release

1. Add `basalt`, `chalk`, `diorite`, `flint` and `gabbro` to `targetPlatforms`. Black-and-white and round layouts use `PBL_COLOR` and `PBL_ROUND`. The ringtone is speaker-only, vibration everywhere else.
2. CI: a job that installs pebble-tool (Python 3.13) and runs `pebble build`, uploading `kyllene-pebble.pbw`. The release workflow attaches `kyllene-pebble-$v.pbw` next to `herald-wear-$v.apk`. `UpdateChecker` must skip it the way it skips the watch APK (see commit `cb98f888`).
3. Docs: a README section ("Pebble watch: open the `.pbw` on your phone") and ticked ROADMAP items.
4. Optionally, publish to the Pebble app store through the [developer dashboard](https://developer.repebble.com/dashboard).

**Results (2026-10-28):**

- (1) The watchapp builds for `basalt`, `chalk`, `diorite`, `emery`, `flint` and `gabbro`, every model with a microphone. `aplite`, the original Pebble, has none, so it's left out. It takes about 27 KB of RAM, leaving about 38 KB of heap on the 64 KB models.
  - `layout.c` handles round screens:
    - Each fixed row starts as far in as the screen's edge needs, and text is centred.
    - The header is taller, and the status dot is left out because the line below it already says the same.
    - Text in a scroll layer follows the screen's edge, a page at a time. The flow takes the layer's position when it's turned on, so it's turned on again each time the layer moves.
  - On a 144-wide screen, the ring screen stacks its lines.
  - The ringtone is played only where the speaker API exists (emery, flint, gabbro). Touch (emery, gabbro) and the glance are guarded by the SDK's API macros.
  - Checked on the emulators for basalt, diorite, chalk, gabbro and emery. Long header titles are cut short on chalk's narrow top.
- (2) CI has a `pebble` job: `pipx install pebble-tool==5.0.40`, `pebble sdk install 4.33.1` (pinned since 4.44 came out and fails to install with that tool), then `pebble build`, uploading `pebbleApp.pbw`. Installing the SDK only prints its terms; it asks nothing. The same recipe was run from scratch, with a clean home directory and Python 3.12. The release job builds the watchapp at the tag's version, without any pre-release suffix, and attaches `kyllene-pebble-$v.pbw` and its SHA-256. `UpdateChecker` only offers `.apk` files; a test makes sure a `.pbw` is never offered.
- (3) The README and ROADMAP cover the Pebble watch.
- (4) The listing is the maintainers' to make: whose account owns it, and so its UUID, is theirs to decide. Once it exists, `pebble-appstore.yml` sends each published release's `.pbw` to it (setup in [the watchapp's README](../pebbleApp/README.md#pebble-appstore)). Tried against a local stand-in for the store: an upload to a listed UUID, the version taken from the tag rather than `package.json`, and a refusal to create a listing for an unlisted one. Not tried against the real store, sign-in included.

### Phase 6: stretch (each a spike first)

- **Gateway voice on the speaker:** measure AppMessage throughput on the Time 2, transcode the TTS to 8 kHz/8-bit PCM on the phone, and stream it. Keep it only if it plays without stutter.
- **Heart rate or health as context** for prompts.

---

## 6. Testing

| Layer | How |
|---|---|
| Codec and protocol | Kotlin `commonTest` in `shared/wearlink`, run in CI with the existing `:shared:wearlink:testAndroidHostTest` |
| Phone transport | Unit tests with a fake `PebbleSender`. The relay already has a fake-outbox pattern (`WatchTurnTest`) |
| Watch UI | The emulator (`emery`) with fake-phone mode. **The emulator can't reach a real PebbleKit 2 companion on the phone**, so it is for UI only |
| End to end | Real hardware only: the user's Android phone and Time 2, Developer Connection, `pebble logs --phone`, and `adb logcat` for Kyllene. Test against a test gateway, never real chats in screenshots (CONTRIBUTING) |

Device test script for each phase:

1. Ask with Kyllene in the foreground.
2. Ask with Kyllene in the background.
3. Ask with Kyllene swiped away.
4. Ask with the phone locked.
5. Ask with Bluetooth dropped mid-turn, then reconnected.
6. Change the accent colour.
7. Sign out of Kyllene.

---

## 7. Decisions for you

1. **Fork only, or upstream too?** *Forked* to `faultoverload/herald` (Phase 0); the upstream issue and PRs are still to do. The suggestion is to fork to `faultoverload/herald`, open an upstream issue first, and later submit as separate PRs:
   - (1) phone transport and protocol;
   - (2) watchapp;
   - (3) CI and release `.pbw`.

   Each PR ticks ROADMAP.
2. **Approving from the wrist.** ✅ *Decided:* (b), both questions and approvals answerable on the Pebble, **behind a setting that is off by default** and says that it skips the phone's unlock. Off, the watch says to answer on the phone. See Phase 3, steps 2–3.
3. **What "Answer" does on a Pebble ring.** ✅ *Decided:* show it on the watch. With the setting on, Answer opens the question or approval there. With it off, the watch stops ringing and says to answer on the phone, which rings on. See Phase 3, step 4.
4. **C or Alloy.** ✅ *C*, as built in Phases 1–2.
5. **Which Pebble models** count as done: Time 2 only, or every model in Phase 5.
6. **Which phone Pebble app** to support: the Core app (≥ 1.0.7.7), plus microPebble if it's easy (PebbleKit 2 supports both).

---

## 8. Upstream risks

Problems found in Kyllene itself while doing this work. The Pebble work doesn't fix them; each is left for a future patch.

| Risk | Where | Effect | Suggested patch |
|---|---|---|---|
| Notification actions skip the unlock when fired by a notification listener | `ChatNotifications` (actions with `setAuthenticationRequired(true)`), `NotificationActionReceiver` | Reply, Answer, and very likely Approve (not tested, since this gateway has approvals off) can be fired with the phone locked by any app with notification access: the Pebble app, Gadgetbridge, others. Confirmed for Reply from a Pebble in Phase 0 Test 2b | In `NotificationActionReceiver`, check `KeyguardManager.isDeviceLocked` for Approve (and decide for Answer and Reply). When locked, refuse, or post an "unlock to approve" prompt. Must land before or with [#1](https://github.com/faultoverload/herald/issues/1), which would put Approve on Pebbles by default |
| Questions never get an Answer action | `ChatNotifications.postRequest` | Current Hermes **always** sends questions as `questions=[{qid: "q0", …}]`, even a single one ([`tools/clarify_tool.py`](https://github.com/NousResearch/hermes-agent/blob/main/tools/clarify_tool.py)). Kyllene's `!request.batch` check then leaves every question notification without buttons, on the phone and the watch. Confirmed in Phase 0 Test 3a | Drop `!request.batch` when there is exactly one question and it isn't multi-select. `NotificationActionReceiver` already answers batch form through `InputAnswers.clarify` (`answers: {q0: …}`). Check how Hermes treats the "(recommended)" label on the first choice when it comes back as the answer |
