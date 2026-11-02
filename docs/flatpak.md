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

### Secret portal adapter

The JVM core includes a Linux `org.freedesktop.portal.Secret` adapter for a later
storage integration. This alone does not encrypt existing desktop settings or
migrate credentials. It uses FileKit's dbus-java binding with the FD-capable
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
