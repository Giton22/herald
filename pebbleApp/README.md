# Kyllene for Pebble

Kyllene's Pebble watchapp: talk to Hermes from the watch, read the reply as it comes, answer an approval or a
question, and pick up when Hermes calls. It works through Kyllene on the phone, which holds the gateway connection
and the sign-in, the way the Wear OS app does. The watch never talks to the gateway itself.

It runs on every Pebble with a microphone: Pebble Time, Time Round, Pebble 2, Pebble 2 Duo, Pebble Time 2 and
Pebble Round 2. The plan behind it, what was tested on a device, and the known limits are in
[docs/pebble.md](../docs/pebble.md).

## Screenshots

From the emulator (Pebble Time 2, and Round 2 at the end), with sample chats.

| Talking | At work | Hold Select | Prompts |
|:---:|:---:|:---:|:---:|
| <img src="screenshots/talk.png" width="200" alt="A reply on the talk screen, under what was said"> | <img src="screenshots/working.png" width="200" alt="A turn at work, saying what it's doing"> | <img src="screenshots/menu.png" width="200" alt="The menu: Stop, New chat, Chats, Check phone"> | <img src="screenshots/prompts.png" width="200" alt="Prompts to ask with a press"> |
| **Chats** | **A question** | **Answering it** | **An approval** |
| <img src="screenshots/chats.png" width="200" alt="Recent chats, the current one marked"> | <img src="screenshots/question.png" width="200" alt="A question from Hermes, with the chat it's in"> | <img src="screenshots/answer.png" width="200" alt="The question's choices, and Dictate"> | <img src="screenshots/approval.png" width="200" alt="A command waiting for approval"> |
| **Hermes calling** | **Launcher glance** | **Switched off** | **Round 2** |
| <img src="screenshots/ring.png" width="200" alt="The ring screen with Answer and Decline"> | <img src="screenshots/glance.png" width="200" alt="Kyllene in the app list, the last reply under it"> | <img src="screenshots/off.png" width="200" alt="The watch saying Kyllene on the Pebble is off"> | <img src="screenshots/round-talk.png" width="200" alt="A reply on a round screen, following its edge"> |

On the phone, **Settings → Connected devices** (in the Phone section) holds what the watchapp answers to:

| Light | Dark |
|:---:|:---:|
| <img src="screenshots/connected-devices-light.jpg" width="280" alt="Connected devices: the Pebble switches and prompts, light theme"> | <img src="screenshots/connected-devices-dark.jpg" width="280" alt="Connected devices: the Pebble switches and prompts, dark theme"> |

- **Kyllene on the Pebble** (on): off, the watchapp says so and Kyllene sends it nothing: no turns, no rings, no
  questions.
- **Answer on the Pebble** (off): approvals and questions can be answered on the watch, as they come or when it
  rings. It's off by default because the watch doesn't ask for the phone's unlock: anyone wearing it can answer.
- **Pebble prompts:** what the watch's hold-Select menu offers to ask with one press. Add, edit or remove up to six;
  they reach the watch the next time it checks in with the phone, and stay there.

Rings are turned on in **Settings → Phone tools → Calls**, the same as for a Wear OS watch: ringing for answers,
and **Ring my watch too**.

## Install

1. Install Kyllene on the phone and sign in to your gateway.
2. Download `kyllene-pebble-<version>.pbw` from the same [release](https://github.com/Giton22/kyllene/releases/latest)
   and open it on the phone. The Pebble app installs it on the watch.
3. Open Kyllene on the watch once. The phone only rings watches it has heard from.

## Using it

| Button | Talk screen |
|---|---|
| **Select**, or a tap on touch models | Dictate and send to Hermes |
| **Up / Down**, or a drag | Scroll the reply |
| **Hold Select** | Stop the turn, Prompts (set on the phone), New chat, Chats, Check phone |
| **Back** | Close Kyllene |

- **Chats:** each chat opens on its last exchange. The watch keeps the chat it talks in across launches.
- **A finished reply while Kyllene is closed** comes as the phone's usual reply notification, which the Pebble app
  shows on the watch.
- **Approvals and questions** in the watch's chat show on the watch when answering there is on. A single question
  is answered by picking a choice or dictating. Multi-select and batches of questions, passwords and logins stay
  on the phone.
- **Rings:** when Hermes calls for an answer, the phone opens Kyllene on the watch, which buzzes and, on models with
  a speaker (Time 2, Pebble 2 Duo, Round 2), plays a ringtone.
  - **Select** answers. With answering on the watch, the question opens there and the phone stops. Otherwise the
    watch goes quiet and says to answer on the phone, which keeps ringing.
  - **Down** declines, and the phone stops too.
  - **Back** quiets the watch only.
- **Quick launch** (the watch's Settings → Quick Launch) opens straight into dictation.
- **Glance:** the app list shows the last reply's first line under Kyllene, or "Working on it..." when Kyllene was
  closed mid-turn.

## Build

The watchapp is C, built with Pebble's own tools, which need Python 3.13 or older:

```bash
uv tool install --python 3.13 pebble-tool
```

```bash
pebble sdk install 4.33.1
```

```bash
pebble build
```

`build/pebbleApp.pbw` holds every model. 4.33.1 is the SDK CI uses; pebble-tool 5.0.40 can't install 4.44.
Installing the SDK prints Pebble's [developer terms](https://developer.repebble.com/legal/sdk-license/). CI builds
the watchapp on every pull request, and a release attaches it as `kyllene-pebble-<version>.pbw` (see
`.github/workflows`).

For Kyllene on the phone to take the watch's messages, its package has to be in `companionApp` in
`package.json`. `dev.herald.android` and `dev.herald.android.dev` (Kyllene Dev) are.

## Pebble appstore

Beside the `.pbw` on each release, the watchapp can be in the Pebble appstore, which the Pebble app installs from and
updates. After the setup below, `.github/workflows/pebble-appstore.yml` does it: when a release is published on
GitHub, it sends that release's `kyllene-pebble-<version>.pbw` to the store, checked against the SHA-256 the release
carries, and the store's version is the tag's. Prereleases (and tags like `v1.2.3-rc1`) are skipped. To send a release
that was published earlier, run the workflow by hand from the Actions tab with its tag.

Setting it up takes a maintainer once:

1. **Decide whose account owns the listing.** The app's UUID in `package.json` can be listed once, and a new release
   has to come from the account that did.
2. **Sign in** to the [Developer Dashboard](https://developer.repebble.com/dashboard) with Google, GitHub or Apple, and
   always the same one. The first sign-in makes the developer account.
3. **Make the listing by hand** from a release's `.pbw`: **New**, upload it, then:
   - a name and description, and the category (Notifications or Tools & Utilities);
   - the website and source code URLs, and visibility (Listed, Unlisted or Hidden);
   - up to five screenshots per model, each the model's own size with no watch frame: 144×168 for basalt, diorite and
     flint, 180×180 for chalk, 200×228 for emery and 260×260 for gabbro. `screenshots/` has emery's and gabbro's;
     `pebble screenshot --all-platforms` takes the others. Sample data only, as for any screenshot here;
   - the optional 80×80 and 144×144 icons and a 720×320 banner. Without icons the store makes them from the name.

   An app goes live as soon as it's submitted; there's no review.
4. **Let the workflow sign in**, one of:
   - *A refresh token.* Run `pebble login` on your computer, then put the `refresh_token` from the file the tool
     keeps into the repository secret `PEBBLE_FIREBASE_REFRESH_TOKEN`. The workflow trades it for a one-hour sign-in
     each run. On Linux the file is `~/.local/share/pebble-sdk/firebase_oauth_storage.json` (`~/.pebble-sdk/` if that
     exists; on macOS `~/Library/Application Support/Pebble SDK/`):

     ```bash
     jq -r .refresh_token ~/.local/share/pebble-sdk/firebase_oauth_storage.json | gh secret set PEBBLE_FIREBASE_REFRESH_TOKEN
     ```
   - *A trusted publisher.* In the dashboard, **Settings → Publish from GitHub Actions**: register this repository and
     `pebble-appstore.yml`. This needs no secret, but it's newer, and whether the dashboard matches a workflow that
     runs on a release tag (the others on a branch) isn't known; try it before relying on it.
5. **Turn it on** with the repository variable `PEBBLE_APPSTORE` set to `true` (Settings → Secrets and variables →
   Actions → Variables). Until then the workflow does nothing, and it never makes a listing itself: a release whose
   UUID isn't listed fails there.

After that a release needs nothing more than publishing its draft. If a release drops a model from `targetPlatforms`,
the store stops offering the app on it, because it serves only the newest release.

## Try it

On the emulator, the phone's side is played with `pebble send-app-message`, the keys being the numbers in
`package.json`'s `messageKeys`. Give each type (`--int`, `--string`) once, followed by all its keys:

```bash
pebble install --emulator emery
```

```bash
pebble send-app-message --emulator emery --int 1=64 3=1 4=4 6=0 7=5 --string 2=p1 5=Hello 11=c1 9="A chat"
```

That's a finished reply (type 64, phase 4). A question is type 69, a ring type 67, the phone's status type 66.
`pebble emu-button`, `pebble transcribe` (for dictation) and `pebble screenshot` drive the rest.

On a phone over USB, `pebble install --adb` installs on the watch, and `adb logcat -s HeraldPebble` shows each
message Kyllene sends and takes.

## How it fits together

The phone keeps speaking WearLink's JSON to `WatchRelay`, as for a Wear OS watch. For a Pebble,
`PebbleOutbox` turns each message into a small AppMessage dictionary, and `PebbleBridgeService` reads what the
watch sends back. Both go through PebbleKit 2 and the Pebble app, which wakes Kyllene when it's closed. The
dictionaries, their keys and types are in
[`PebbleLink.kt`](../shared/wearlink/src/commonMain/kotlin/dev/hermeskotlin/wearlink/PebbleLink.kt), and a test
keeps them in step with `package.json`.

| File | What it is |
|---|---|
| `src/c/main.c` | Opens the link and hands each message to its screen |
| `src/c/link.c` | The messages to and from the phone, read into plain structs |
| `src/c/talk.c` | The talk screen, the hold-Select menu, quick launch, the glance and touch |
| `src/c/chats.c` | The chat list |
| `src/c/request.c` | An approval or a question, answered on the watch |
| `src/c/ring.c` | Ringing for a call from Hermes |
| `src/c/dictate.c` | Dictation for the whole app: one session, its text going to whoever asked |
| `src/c/layout.c` | Round and rectangular screens |
