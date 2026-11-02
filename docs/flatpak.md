# Experimental Flatpak

The x86_64 development recipe builds the existing desktop application from a
pinned upstream revision, using Freedesktop 26.08 and JBRSDK 25 with JCEF. It does
not extract a DEB or use host Java. Its identity, `app.kyllene.Kyllene.Devel`, is
separate from other installations. The stable app is `app.kyllene.Kyllene`, after
the project's domain, kyllene.app, so Flathub can verify it.

A Flatpak doesn't update itself from GitHub releases: `flatpak update` and software
centres take new versions from the remote it was installed from. A bundle installed
with `flatpak install build/kyllene.flatpak` has no remote and never updates; the
stable app is meant for Flathub, which delivers its updates. The app's own update
check is off in this build (it sets no `-PreleasesRepo`), and it should stay off.

Each release also carries the stable app as a bundle,
`kyllene-desktop-<version>_x86_64.flatpak`, for a manual install. The release
workflow runs `prepare-ci.py --release <version>`, which makes the Devel manifest
into `release.json`: `app.kyllene.Kyllene` on the `stable` branch, built as that
version, with its own `.desktop` file and metainfo. The bundle names Flathub as its
runtime's repository, so installing it fetches the Freedesktop runtime. Installing
the next release's bundle replaces it in place and keeps the app's data.

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
preserving multi-release classes and JNI resources while optimizing the application.
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

### Microphone backend

Linux has a recorder behind the existing `VoiceRecorder` contract, using the default
microphone through webrtc-java 0.20.0 and PulseAudio compatibility on PipeWire.
The package grants the PulseAudio socket for this feature. This socket can access
microphone and output devices; it is not a per-recording permission prompt.
Recording begins only when requested, uses bounded PCM buffering, and releases its
native device on completion, cancellation or failure. It changes no device defaults
or system volume and writes no temporary recording files.
The existing desktop voice UI remains disabled until capability and UI integration;
this backend alone does not enable dictation, spoken replies or live calls.
Unit tests use synthetic PCM. Installed capture, audio quality and live-provider
behavior require separate qualification; synthetic frames do not prove them.

### Spoken playback backend

Linux playback implements the existing `SpeechPlayer` contract through the same
native audio backend and default output device. Freedesktop 26.08 supplies
`/usr/bin/ffmpeg`; encoded replies and raw PCM are decoded through pipes to
48 kHz mono PCM. Only pipe input and selected audio formats are allowed. The
codec inherits no environment, opens no temporary media files and needs no new
sandbox permission. Other Linux distributions need that executable separately.

Playback buffers are bounded. Hold emits silence without consuming queued audio;
cancellation or failure retires the owned codec and output device. No system
volume, mute or device defaults are changed. Unit tests cover lifecycle and codec
failures. Installed output, physical audio quality and live-provider behavior
require separate qualification. This backend does not enable the desktop voice
UI or live calls; those need their own capability and lifecycle integration.

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

This stage uses dummy audio and no captured track, with recording and playout off.
The desktop live-call binding and voice controls remain disabled. Real capture,
rendering, echo cancellation and capability/UI integration are separate work.
Local peer SDP/event exchange and cleanup do not qualify provider compatibility,
physical audio quality or interruption. Unit tests cover the transport boundary;
optimized installed-runtime and media/provider checks require separate validation.

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
