# Experimental Flatpak

The x86_64 development recipe builds the existing desktop application from a
pinned upstream revision, using Freedesktop 26.08 and JBRSDK 25 with JCEF. It does
not extract a DEB or use host Java. Its identity, `app.kyllene.Kyllene.Devel`, is
separate from other installations. The stable app is `app.kyllene.Kyllene`, after
the project's domain, kyllene.app.

Flatpak updates come from the installed app's signed remote, through the Flatpak
update portal (version 2 or newer), software centres or `flatpak update`.
With update checking enabled, the app offers **Update** when the portal reports a
new commit. Polling can be delayed; silence does not mean the app is up to date.
The banner shows operation progress; 100% alone does not confirm installation.
Completion offers a separate **Restart**, with a warning to finish calls and save
work first. Restart can be deferred. Saved profiles, drafts and settings remain
in the same app identity; unsaved work and active calls do not migrate.
Denial and failure stay in the app. Updates requiring new permissions need system
software tools. There is no GitHub download or browser fallback inside Flatpak.
A standalone bundle without an origin cannot obtain updates through the portal.

Portal wire tests require Linux and `dbus-daemon`; state tests are portable.
Run `./gradlew :desktopApp:jvmTest --tests '*FlatpakUpdate*Test'`; Windows/macOS
contributors can use the Ubuntu workflow for the Linux-only cases.

Each release also carries the stable app as a bundle,
`kyllene-desktop-<version>_x86_64.flatpak`, for a manual install. The release
workflow runs `prepare-ci.py --release <version>`, which makes the Devel manifest
into `release.json`: `app.kyllene.Kyllene` on the `stable` branch, built as that
version, with its own `.desktop` file and metainfo. The bundle names Flathub as its
runtime's repository, so installing it fetches the Freedesktop runtime. Installing
the next release's bundle replaces it in place and keeps the app's data.

### Our repository

Kyllene's own Flatpak repository, `https://flatpak.kyllene.app/repo/`, delivers
updates. Users install from it once:

```sh
flatpak install --user https://kyllene.app/kyllene.flatpakref
```

and `flatpak update` or a software centre takes each release from then on. A release
bundle's commit is signed and names this repository as its remote, so a bundle
installed by hand updates from it too.

- **Publishing:** publishing a GitHub release (never a draft or a pre-release) runs
  `flatpak-repo.yml`. It downloads the release's bundle, and `publish-repo.sh` signs
  it into an empty repository. The upload adds that repository's objects to the R2
  bucket behind `flatpak.kyllene.app`, then replaces its refs and summary. Objects
  are named by their hash and never change, so earlier releases' objects stay, and an
  update downloads only what changed. Only GitHub's latest release is published,
  because Flatpak refuses a commit older than the installed one, so an older tag
  would stop every update. The workflow waits up to an hour for the release job to
  attach the bundle. To publish the latest release again, run the workflow by hand
  with its tag.
- **Signing key:** the public half is `packaging/flatpak/kyllene-repo.asc`.
  `website/kyllene.flatpakref` and `website/kyllene.flatpakrepo` carry the same key
  (`test_repo_files.py` checks this). The private half is the
  `FLATPAK_GPG_PRIVATE_KEY` secret, which both the release and the publish workflows
  use. Replacing the key breaks updates for every existing install, so keep a backup
  of it outside GitHub.
- **Storage:** the R2 bucket `kyllene-flatpak` (or the `FLATPAK_R2_BUCKET`
  variable), with the custom domain `flatpak.kyllene.app` and an Object Read & Write
  token in the `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` secrets.
- **Tests:** Linux advisory runs JVM/desktop and portable packaging checks on every PR
  that touches what the desktop app is built from (Android-only PRs skip it),
  alongside a tiny repository fixture. Full offline builds and installed-app smoke run
  for packaging, dependency, desktop entry-point and workflow PRs, relevant master
  pushes, weekly, and via **Run workflow** on any branch. Ordinary shared UI PRs
  receive package qualification after merge; Linux remains advisory for Android.
  The SDK font test stays in the full package job.
- **Release qualification:** the Flatpak build starts alongside Android. Its bundle
  is attached only after Android's draft exists and JVM/desktop tests, Flatpak contracts, real installed
  UI smoke, and repository tests pass. Publication reuses that qualified bundle.
  Repository tests use the real candidate payload on tags and a tiny fixture on PRs.
  Two isolated user installations share two signed test releases: bundle and
  `.flatpakref` both update, then both reject a wrong-key commit and retain the
  previous commit. The two releases use the same payload; this tests delivery/trust,
  not application-data migration from a historical version. Temporary keys/origins
  never appear in the downloadable candidate bundle.

Repository tests require Linux, Flatpak, OSTree, GnuPG and Python 3. They do not
launch the app, download a runtime, or change the caller's installation. Run
`timeout 300 sh packaging/flatpak/tests/repo_smoke.sh --fixture`, or replace
`--fixture` with `BUILD_REPO APP BRANCH` to test a built app payload. Windows/macOS
contributors can run the Ubuntu workflow instead; direct unsupported invocation
fails before making changes. Installed UI tests additionally need the SDK/runtime,
Xvfb, xauth, xdotool, Pillow, the desktop portal and an unlocked test keyring.

Keep all selected jobs green when qualifying a Flatpak; a green fast-check job
alone is not package qualification. Tagged releases perform their own checks on
that exact source rather than trusting a potentially skipped advisory run.
Timing targets (4–7 minutes fast, 12–18 package, 15–20 Flatpak release qualification)
need hosted measurements; they are not guarantees. JVM and packaging now overlap,
so runner concurrency can increase even when elapsed time decreases.

Install `org.flatpak.Builder`, `org.freedesktop.Sdk//26.08` and its matching
runtime in the user installation. From the repository root, build and install:

```sh
user_flatpak="$(realpath "${XDG_DATA_HOME:-$HOME/.local/share}/flatpak")"
flatpak run --env=FLATPAK_USER_DIR="$user_flatpak" --filesystem="$user_flatpak" org.flatpak.Builder --user --repo=build/flatpak-repo build/flatpak packaging/flatpak/app.kyllene.Kyllene.Devel.json
flatpak build-bundle build/flatpak-repo build/kyllene.flatpak app.kyllene.Kyllene.Devel devel
flatpak install --user build/kyllene.flatpak
flatpak run app.kyllene.Kyllene.Devel
```

Flatpak-builder downloads checksummed inputs before running Gradle offline in
its build sandbox. Source selection uses a pinned Git revision, excluding local
untracked files. The installed package contains the upstream app image and JVM
licenses. `gradle-sources.json` records the Maven input URLs and hashes; their
POMs provide license declarations. Regenerate after dependency upgrades with
`python3 packaging/flatpak/generate-sources.py`, using JBR 25 on Linux (the
desktop dependencies differ per OS). Generate from the commit the manifest pins and
move the pin with it; `test_catalog.py` fails when the version catalog has plugins
the offline repository lacks. Review the generated diff and rebuild offline. The
generator leaves out BOMs that a plugin's POM imports (AGP 9.5.0's `sdk-common`
imports `org.bouncycastle:bc-jdk18on-bom`). Add any file the offline build reports
missing to `packaging/flatpak/extra-sources.json`, with its sha512; the script merges
that file into every regeneration. Set `JAVA_HOME` to JBR 25 for generation and the Gradle fixture test. Run tests with
`python3 -m unittest discover -s packaging/flatpak/tests -v`.

JBR includes Chromium: security updates require updating its pinned archive and
checksum together, checking the JCEF API version, rebuilding, and testing embedded
content in the installed sandbox. Review native dependencies alongside JVM ones.
The package uses XWayland under KDE Wayland. The entry point selects X11 for
child runtimes, preventing Chromium from choosing an unavailable Wayland socket.
Native Wayland is not qualified.
Voice remains separate work. Qualify secure storage on the target desktop before
using personal credentials. A successful build alone does not
qualify login, clipboard, window movement, portals or embedded content.

The package keeps Compose's app image and generated runtime. Its shell entry point
uses the bundled Java with the classpath, JVM options and main class derived from
Compose's generated configuration, avoiding a native jpackage launcher short-read
crash on small-capacity pipes. User arguments are forwarded literally. Staging also
restores the Chromium helper's executable bit. Its fontconfig
fragment excludes WOFF/WOFF2 entries, which stalled Skia enumeration in installed
tests; ordinary host fonts remain available. Remove this exclusion once those
formats pass startup and rendering checks with the selected Skia/runtime versions.

### Secret portal adapter

The JVM core uses a Linux `org.freedesktop.portal.Secret` adapter to retrieve the
key material for encrypted desktop settings. It uses FileKit's dbus-java binding with the FD-capable
junixsocket transport, replacing its native Unix transport at the FileKit edge.
Requests subscribe before dispatch, accept only the portal owner's matching
response, and send `Request.Close` on cancellation or timeout. Missing services,
denial, transport errors and empty/oversized material fail without creating a key.
The opaque secret is bounded to 4 KiB; callers must derive suitable encryption keys,
retain any returned portal token for the next retrieval, and clear owned bytes.
Linux integration tests start a disposable `dbus-daemon`; other hosts skip them
explicitly. Run `./gradlew :shared:core:jvmTest --tests '*LinuxSecretPortalTest'`.

The Linux Secret transport pins matching junixsocket Java/native libraries. Release
builds analyze those two JARs as ProGuard libraries and package their original bytes,
preserving multi-release classes and JNI resources while shrinking the application.
The Linux runtime includes `jdk.security.auth` for D-Bus EXTERNAL authentication.
Other desktop platforms retain FileKit's existing transport provider.

### Encrypted Linux settings

Linux Flatpak startup unlocks storage before settings, authentication and cache
consumers are created. A failed unlock offers Retry and Quit; no plaintext or
empty-store fallback is used. Unlock the desktop keyring and check Secret portal
availability before retrying. A corrupt file or lost key requires a known-good
backup and its original application identity; a replacement key cannot recover it.
Ordinary non-Flatpak Linux keeps its existing plaintext store; it needs a separate
Secret Service integration before credentials there are encrypted. Windows and
macOS stores are unchanged. Missing metadata with a Flatpak runtime hint, present
invalid metadata or read errors fail closed; environment hints never supply a key
identity. No portal failure can select the legacy store after Flatpak selection.

The app derives an application-scoped AES-256 key with HKDF-SHA256. Its versioned
AES-GCM envelope authenticates the application identity and optional portal token,
with a new random nonce on each write. Token rotation and values commit together.
`settings.secure` is authoritative: invalid content there is never imported as
plaintext. A fresh profile imports only its own `settings.json`, preserving the
existing offline-cache key. It verifies and syncs the encrypted replacement before
removing unchanged legacy data. Interrupted cleanup can resume using an encrypted
migration digest; conflicting legacy data or an unfinished `settings.json.part`
stops startup for manual resolution. Atomic replacement and parent-directory sync
are required. A per-profile file lock serializes version checks and replacement;
a stale writer must reopen. Failed or uncertain writes do not permit further unsafe mutations.

Back up the complete profile before upgrades. The file's format has a version: a
`settings.secure` written by a newer Kyllene stops an older one at startup, with
Quit as the only choice, instead of being read wrongly or replaced. Go back to that
version or newer, or restore the backup taken before the upgrade. Other Flatpak identities are never
searched or imported. Removing old plaintext does not securely erase disk blocks
or backups. Encryption does not protect an unlocked process or prevent a same-user
attacker replacing the entire profile. Run the Linux storage and migration tests
with `./gradlew :shared:core:jvmTest --tests '*Linux*StoreTest' --tests '*LinuxStorage*Test'`
and startup contracts with `./gradlew :desktopApp:jvmTest --tests '*StorageStartupTest'`.

Linux Flatpak push registration uses a per-install UUID, two P-256 key pairs and
independent random topics from the existing signed/encrypted push protocol. Its
private keys, pinned gateway and identities awaiting unregistration share the
encrypted settings store; an unavailable or corrupt identity is preserved and
reported, never silently replaced. It is not available through plaintext desktop
stores.
After an uncertain save, identity reads also stop until the encrypted store is
reopened, so an earlier unbound or retired identity cannot be registered again.

One identity belongs to one gateway. Retirement clears its keys, topics and pin
together, retaining the old device ID only for that gateway's later unregistration.
A subsequent registration creates fresh material. Public gateway keys must be valid
protocol-version-one P-256 points before they are pinned.

This adds registration storage, not push reception or a background service. The
desktop Notifications anywhere control remains hidden until receiving is supported.
The existing authenticated registration coordinator and gateway protocol are reused.

### Gateway client certificates

With encrypted Linux Flatpak storage, the existing Connect and gateway menus can
import a PKCS#12 (`.p12`/`.pfx`) file for an HTTPS gateway. Choose the file through
the desktop file chooser, then enter its password in the app. Imports are limited
to 1 MiB and one RSA or EC private key, with a matching, unexpired client
certificate and a valid supplied chain. Chain checks are offline: no issuer or
revocation URLs are fetched, and import does not assert that the gateway trusts it.
The import password is not saved. Other desktop stores do not offer this import;
Android continues to use its system certificate picker.

Selection matches Android's hostname-only lookup: a pending pick first, otherwise
the first saved gateway with an alias for that hostname. URL paths and ports do
not isolate selections: gateways sharing a hostname cannot independently choose
different certificates. Both REST requests and WebSocket upgrades use this lookup;
another hostname, redirects and embedded browsers do not receive the identity.
Normal server trust and hostname checks remain enabled. The Linux client uses HTTP/1.1
and pools idle connections. Changing a selection drops the idle ones and the cached
identity, so the next request handshakes with the new selection. As on Android, a
request already running keeps its connection, which may be reused once afterwards;
existing WebSockets retain their original identity until they close or reconnect.
An imported identity is decrypted once per hostname and reopened after its
certificate expires.

Clearing a gateway's selection removes its alias; another pending or saved alias
for the same hostname may still apply. It does not delete imported encrypted keys,
recover space from cancelled imports, erase backups or revoke an existing TLS connection. Failed/cancelled imports keep the previous selection. Storage failures
must be resolved before retrying; there is no plaintext import fallback.

Run the JVM core suites with `./gradlew :shared:core:jvmTest --tests '*LinuxClientCertificatesTest' --tests '*LinuxMtlsTest'`.
The synthetic PKCS#12 fixture contains public test-only keys (password `test-only`);
it must never be used for a real gateway. UI tests run with
`./gradlew :shared:ui:jvmTest --tests '*ClientCert*Test'`; headless environments can
set `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`. Imports accept regular files, not
symlinks/devices/pipes, and use interruptible channel reads. The named-pipe rejection
test requires Linux and `mkfifo` from coreutils; it is explicitly skipped elsewhere.
The password and error area scrolls in short windows while Cancel and Import remain visible.
Installed qualification must also exercise the real file chooser, password/cancellation,
restart and both TLS transports.

### Microphone backend

Linux has a recorder behind the existing `VoiceRecorder` contract, using the default
microphone through webrtc-java 0.20.0 and PulseAudio compatibility on PipeWire.
The package grants the PulseAudio socket for this feature. This socket can access
microphone and output devices; it is not a per-recording permission prompt.
Recording begins only when requested, uses bounded PCM buffering, and releases its
native device on completion, cancellation or failure. It changes no device defaults
or system volume and writes no temporary recording files.
The supported Linux desktop exposes the existing voice controls; device dictation
remains unavailable and uses the existing gateway fallback.
Unit tests use synthetic PCM. Installed capture, audio quality and live-provider
behavior require separate qualification; synthetic frames do not prove them.

### Spoken playback backend

Linux playback implements the existing `SpeechPlayer` contract through the same
native audio backend and default output device. Freedesktop 26.08 supplies
`/usr/bin/ffmpeg`; encoded replies and raw PCM are decoded through pipes to
48 kHz mono PCM. Only pipe input and selected audio formats are allowed. The
codec inherits no environment, opens no temporary media files and needs no new
sandbox permission. A host install needs that executable at exactly
`/usr/bin/ffmpeg` (no `PATH` lookup). Without it, dictation and calls still
work, but voice chat and Read aloud are hidden.

Playback buffers are bounded. Hold emits silence without consuming queued audio;
cancellation or failure retires the owned codec and output device. No system
volume, mute or device defaults are changed. Unit tests cover lifecycle and codec
failures. Installed output, physical audio quality and live-provider behavior
require separate qualification. The live-call integration uses the same audio
backend independently of ordinary spoken playback.

### Live transport backend

The internal Linux WebRTC transport reuses the existing offer/answer callback and
`oai-events` channel. The gateway retains provider credentials; this adapter does
not add a provider client or change the signaling payload. SDP is preserved,
including trailing CRLF. Incoming text events and outgoing buffering are bounded;
unsupported or overflowing events end the connection instead of silently losing
part of the conversation.

Each instance owns one connection. Setup cancellation requests native retirement
even while an answer callback is still returning, and a late answer cannot reach
the retired peer. This retires native resources without claiming that arbitrary
non-cancellable callback code can be forced to finish. Native callbacks schedule
one owned cleanup operation; channel, peer, factory and device releases share the
same completion path. Explicit owner close waits for that release.

Transport-only checks can use dummy audio with recording and playout off. The
Linux live-call binding instead owns a default microphone/output pair and a local
track through the native audio engine, with echo cancellation, noise suppression,
gain control and high-pass filtering requested. That configuration does not prove
acoustic echo handling or interruption; both need a real device/provider session.

The existing shared voice UI, signaling, captions and call lifecycle are reused.
Mute disables the outgoing track and clears its displayed level. End/cancellation
retires the connection and its audio resources. Level reports expire rather than
leaving a stale microphone or speaking indicator; late reports cannot restore a
retired call. No system mute, volume or device default is changed. Windows, macOS
and unsupported Linux architectures retain their unavailable voice capability.
Local peer exchange and unit tests do not qualify provider or physical audio behavior.

## PDF previews

Desktop file previews draw PDF pages inside the existing viewer, with its scroll
and zoom controls. Password-protected or damaged documents show an explanation;
Save remains available. PDFBox runs in one child of the bundled Java runtime per
open document. Closing the viewer, cancellation, or a 15-second operation deadline
retires that child. A later render after a cancelled zoom or scroll starts a fresh
child for the same document; closing the viewer is permanent. Input is limited to 64 MiB and 500 pages; each rendered page is
limited to eight million pixels and 16,384 pixels on either side. The child has a
256 MiB Java heap limit, so complex documents can exceed its limits sooner.

This contains parser resource use within the application sandbox; the child is
not a separate security sandbox. It receives document bytes and returns bounded
page shapes and raw pixels over private pipes. The preview does not execute PDF
scripts, follow links or fetch external document content. The JVM regression tests
use synthetic PDFs and worker failure fixtures, without a gateway or credentials.

## Advisory CI

The separate Linux workflow runs JVM/packaging tests and exports an x86_64 bundle.
It runs only when the desktop app's sources, the build or this packaging change.
It builds the exact commit tested by the workflow (a pull request's merge commit)
from a Git clone of the checkout, with the same locked build inputs, so committed
files only: never untracked files in the runner workspace. Only the bundle is uploaded. The
font-filter contract uses the selected SDK's fontconfig, independent of the
runner host's fontconfig version. CI uses the native `flatpak-builder` package;
the Builder application needs a desktop host-command service unavailable there.

Keep these Linux checks non-required in branch rules. Failures stay visibly
failed; Android checks and release publication remain independent. A built CI
bundle still needs installed-sandbox and Bazzite acceptance before publication.

The workflow installs its bundle and exercises the actual sandbox on a private
Xvfb display: gateway address input, synthetic sign-in, local HTML rendering,
JavaScript interaction and window resize. The loopback-only fixture never calls
a provider or reads host files. The runner stops its exact owned instance and
uninstalls its verified app without deleting data after failed checks. If the
installed commit cannot be verified, it reports retained/unknown state and leaves
the installation for inspection instead of deleting an unverified ref.

Run `python3 packaging/flatpak/tests/installed_smoke.py build/kyllene.flatpak`
on Linux with Flatpak, its matching runtime, Xvfb, xauth, xdotool and Pillow
installed. The app reads its settings key from the Secret portal at startup, so
run it as CI does: `dbus-run-session` with a throwaway unlocked gnome-keyring as
the portal's Secret backend (see `.github/workflows/linux.yml`). On a desktop
session it would use, and leave an item in, your own keyring. Direct invocation
on Windows/macOS exits before optional imports or external actions; use a
suitable Linux environment or open/update a PR to trigger the Ubuntu GitHub
Actions job. Portable fixture/platform contracts still run in
normal test discovery; Linux-only integration coverage is explicitly skipped
on unsupported platforms. It refuses to replace an existing
user installation; use a disposable Flatpak user installation with its matching
runtime available. Screenshots and profiles are not uploaded. Xvfb smoke does
not qualify KDE focus, context menus, portals other than the Secret portal
through gnome-keyring, voice or live-provider behavior;
real Bazzite acceptance remains a separate release gate.

### Quick entry shortcut

With **Settings → Desktop → Quick entry** enabled, Linux asks the desktop's
GlobalShortcuts portal to bind the existing composer. The portal's permission
dialog chooses the actual shortcut; Ctrl+Shift+Space is a preference, never a
forced replacement for another app's keys. Settings shows the granted trigger,
refusal or unavailable portal. After refusing, turn the setting off and on to
ask again. When the desktop grants it with no key (KDE does once its dialog is
closed, and remembers that), Settings says so: pick a key in the desktop's own
shortcut settings, and the row follows. Outside Flatpak the app names itself to
the portal as `kyllene-Kyllene`, the .deb's desktop entry; a build run without
that entry installed gets no shortcut. Disabling it or quitting closes the request/session; a portal restart
recreates the session. A held key opens once until released.

The shortcut opens the current profile's existing composer and does not send a
prompt. It needs the app running; launcher actions and login startup are separate.
Window focus follows the existing runtime/compositor rules. The pinned public
JBR API cannot consume the portal's activation token, so native Wayland activation
is not guaranteed. In-app shortcuts remain available without this portal.

## Call launcher action

The Linux application menu's **Call Hermes** action opens the existing voice-chat
route. It calls the destination selected in **Default Call Target** for the
current gateway, using the shared target lookup, cancellation and connection cue.
It routes the request to the existing instance instead of starting a second
application.
An unavailable target reports the existing call error rather than choosing another
destination. If signed out, the request waits for sign-in through the usual flow.
The action starts a call and may use the microphone and configured voice provider.

The equivalent explicit invocation is `flatpak run
app.kyllene.Kyllene.Devel herald://voice-chat`. Native launcher activation
and live-provider behavior require installed Linux validation; packaging tests
and shared routing tests alone do not establish that acceptance.
