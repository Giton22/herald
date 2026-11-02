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
`python3 packaging/flatpak/generate-sources.py`, using JBR 25. Review the generated
diff and rebuild offline. Set `JAVA_HOME` to JBR 25 for generation and the Gradle fixture test. Run tests with
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
