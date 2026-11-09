#!/bin/sh
# Exercise publish-repo.sh and both installation routes against the SAME two releases.
# --fixture uses a tiny payload for PRs; BUILD_REPO APP BRANCH uses the real release payload.
# No app is launched: installed_smoke.py separately qualifies real application behavior.
set -eu
export LC_ALL=C
[ "$(uname -s)" = Linux ] || { echo 'This installed-Flatpak test requires Linux.' >&2; exit 1; }
here="$(dirname "$(realpath "$0")")"
publish="$here/../publish-repo.sh"
work="$(mktemp -d)"
server=""
cleanup() {
  if [ -n "$server" ]; then kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; fi
  for home in "$work/gnupg" "$work/other"; do
    [ ! -d "$home" ] || gpgconf --homedir "$home" --kill all
  done
  rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$#" = 1 ] && [ "$1" = --fixture ]; then
  app=app.kyllene.RepositoryTest branch=stable build="$work/source"
  mkdir -p "$work/app/files" "$work/app/export"
  printf '[Application]\nname=%s\nruntime=org.freedesktop.Platform/x86_64/26.08\n' "$app" > "$work/app/metadata"
  printf 'repository fixture\n' > "$work/app/files/payload"
  flatpak build-export --arch=x86_64 "$build" "$work/app" "$branch" >/dev/null
elif [ "$#" = 3 ]; then
  build="$(realpath "$1")" app="$2" branch="$3"
else
  echo 'Usage: repo_smoke.sh --fixture | BUILD_REPO APP BRANCH' >&2; exit 1
fi
ref="app/$app/x86_64/$branch"

export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --passphrase '' --quick-gen-key "Kyllene repo smoke" rsa2048 sign never
key="$(gpg --list-keys --with-colons | awk -F: '/^fpr/ { print $10; exit }')"
gpg --export "$key" > "$work/key.gpg"
served="$work/served"
mkdir "$served"
# Bind before reporting the port, avoiding collisions with other tests or local services.
python3 - "$served" "$work/port" <<'PY' >/dev/null 2>&1 &
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
server = ThreadingHTTPServer(('127.0.0.1', 0), partial(SimpleHTTPRequestHandler, directory=sys.argv[1]))
Path(sys.argv[2]).write_text(str(server.server_port))
server.serve_forever()
PY
server=$!
for _ in $(seq 50); do
  [ ! -s "$work/port" ] || break
  kill -0 "$server"
  sleep 0.1
done
[ -s "$work/port" ] || { echo 'The local HTTP server did not start' >&2; exit 1; }
url="http://127.0.0.1:$(cat "$work/port")/"

# An independent user installation per route: never touch the caller's apps, remotes or data.
fp() { FLATPAK_USER_DIR="$work/install-$route" flatpak "$@"; }
installed() { fp info --user --show-commit "$app"; }
published() { ostree --repo="$served" rev-parse "$ref"; }
same() {
  a="$(installed)" b="$(published)"
  [ -n "$a" ] && [ "$a" = "$b" ] || { echo "Installed '$a', published '$b'" >&2; exit 1; }
}
# Fresh output still exercises the production publication contract; only the HTTP store accumulates objects.
release() {
  flatpak build-commit-from --force --src-repo="$build" --src-ref="$ref" --subject="release $1" \
    --gpg-sign="$key" --gpg-homedir="$GNUPGHOME" "$work/build" "$ref" >/dev/null
  flatpak build-bundle --repo-url="$url" --gpg-keys="$work/key.gpg" \
    "$work/build" "$work/release-$1.flatpak" "$app" "$branch"
  rm -rf "$work/publish"
  sh "$publish" "$work/release-$1.flatpak" "$work/publish" "$GNUPGHOME" "$key"
  cp -R "$work/publish/." "$served/"
}
ostree --repo="$work/build" init --mode=archive-z2
release 1
route=bundle
fp install --user --noninteractive --no-deps --no-related -y "$work/release-1.flatpak"
same
printf '[Flatpak Ref]\nName=%s\nBranch=%s\nTitle=Kyllene\nUrl=%s\nSuggestRemoteName=kyllene-smoke\nIsRuntime=false\nGPGKey=%s\n' \
  "$app" "$branch" "$url" "$(base64 -w0 "$work/key.gpg")" > "$work/kyllene.flatpakref"
route=ref
fp install --user --noninteractive --no-deps --no-related -y --from "$work/kyllene.flatpakref"
same
before="$(published)"
release 2
test "$before" != "$(published)"
for route in bundle ref; do
  fp update --user --noninteractive --no-deps --no-related -y "$app"
  same
  echo "$route: updated from release 1 to release 2"
done

# Wrong-key commit in a correctly signed summary must fail for BOTH origins, retaining release 2.
other="$work/other"
mkdir -m 700 "$other"
GNUPGHOME="$other" gpg --batch --quiet --passphrase '' --quick-gen-key "Not Kyllene" rsa2048 sign never
before="$(published)"
flatpak build-commit-from --force --src-repo="$build" --src-ref="$ref" --subject=forged \
  --gpg-sign="$(GNUPGHOME="$other" gpg --list-keys --with-colons | awk -F: '/^fpr/ { print $10; exit }')" \
  --gpg-homedir="$other" "$served" "$ref" >/dev/null
flatpak build-update-repo --gpg-sign="$key" --gpg-homedir="$GNUPGHOME" "$served" >/dev/null
for route in bundle ref; do
  if fp update --user --noninteractive --no-deps --no-related -y "$app" >"$work/rejected" 2>&1; then
    echo 'A commit signed by another key was accepted' >&2; exit 1
  fi
  cat "$work/rejected"
  grep -Ei 'signature|GPG|public key' "$work/rejected" >/dev/null
  test "$(installed)" = "$before"
  echo "$route: refused wrong-key update and retained release 2"
done
echo 'Repository: both routes passed with two prepared releases'
