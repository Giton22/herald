# Experimental Flatpak

The x86_64 development recipe builds the existing desktop application from a
pinned upstream revision, using Freedesktop 26.08 and JBRSDK 25 with JCEF. It does
not extract a DEB or use host Java. The development identity is provisional,
separate from other installations; a stable identity needs maintainer agreement.

Install `org.flatpak.Builder`, `org.freedesktop.Sdk//26.08` and its matching
runtime in the user installation. From the repository root, build and install:

```sh
user_flatpak="$(realpath "${XDG_DATA_HOME:-$HOME/.local/share}/flatpak")"
flatpak run --env=FLATPAK_USER_DIR="$user_flatpak" --filesystem="$user_flatpak" org.flatpak.Builder --user --repo=build/flatpak-repo build/flatpak packaging/flatpak/io.github.Giton22.Kyllene.Devel.json
flatpak build-bundle build/flatpak-repo build/kyllene.flatpak io.github.Giton22.Kyllene.Devel devel
flatpak install --user build/kyllene.flatpak
flatpak run io.github.Giton22.Kyllene.Devel
```

Flatpak-builder downloads checksummed inputs before running Gradle offline in
its build sandbox. Source selection uses a pinned Git revision, excluding local
untracked files. The installed package contains the upstream app image and JVM
licenses. `gradle-sources.json` records the Maven input URLs and hashes; their
POMs provide license declarations. Regenerate after dependency upgrades with
`python3 packaging/flatpak/generate-sources.py`, using JBR 25 on Linux (the
desktop dependencies differ per OS). Generate from the commit the manifest pins and
move the pin with it; `test_catalog.py` fails when the version catalog has plugins
the offline repository lacks. Review the generated diff and rebuild offline. Set `JAVA_HOME` to JBR 25 for generation and the Gradle fixture test. Run tests with
`python3 -m unittest discover -s packaging/flatpak/tests -v`.

JBR includes Chromium: security updates require updating its pinned archive and
checksum together, checking the JCEF API version, rebuilding, and testing embedded
content in the installed sandbox. Review native dependencies alongside JVM ones.
The package uses XWayland under KDE Wayland. The entry point selects X11 for
child runtimes, preventing Chromium from choosing an unavailable Wayland socket.
Native Wayland is not qualified.
Voice and Linux credential encryption are separate work. Do not use personal
credentials until secure storage is qualified. A successful build alone does not
qualify login, clipboard, window movement, portals or embedded content.

The package keeps Compose's app image and generated runtime. Its shell entry point
uses the bundled Java with the classpath, JVM options and main class derived from
Compose's generated configuration, avoiding a native jpackage launcher short-read
crash on small-capacity pipes. User arguments are forwarded literally. Staging also
restores the Chromium helper's executable bit. Its fontconfig
fragment excludes WOFF/WOFF2 entries, which stalled Skia enumeration in installed
tests; ordinary host fonts remain available. Remove this exclusion once those
formats pass startup and rendering checks with the selected Skia/runtime versions.

### Microphone backend

Linux has a recorder behind the existing `VoiceRecorder` contract, using the default
microphone through webrtc-java 0.20.0 and PulseAudio compatibility on PipeWire.
The package grants the PulseAudio socket for this feature. This socket can access
microphone and output devices; it is not a per-recording permission prompt.
Recording begins only when requested, uses bounded PCM buffering, and releases its
native device on completion, cancellation or failure. It changes no device defaults
or system volume and writes no temporary recording files.
The existing desktop voice UI remains disabled until playback is implemented;
this backend alone does not enable dictation, spoken replies or live calls.
Unit tests use synthetic PCM. Installed capture, audio quality and live-provider
behavior require separate qualification; synthetic frames do not prove them.

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
