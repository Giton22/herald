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
