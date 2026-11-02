#!/bin/sh
# Our Flatpak repository end to end, served from this machine instead of flatpak.kyllene.app:
#   1. a release bundle that names the repository installs, and `flatpak update` takes the next release from it;
#   2. the .flatpakref installs from the repository, and `flatpak update` takes the next release.
# Each release is published as flatpak-repo.yml does it: publish-repo.sh into an empty directory, then copied over
# the served one. Uses a throwaway signing key and the user installation; the app must not be installed already.
#
#   repo_smoke.sh BUILD_REPO APP BRANCH     (BUILD_REPO: flatpak-builder's --repo, holding app/APP/x86_64/BRANCH)
set -eu
build="$(realpath "$1")" app="$2" branch="$3"
here="$(dirname "$(realpath "$0")")"
publish="$here/../publish-repo.sh"
ref="app/$app/x86_64/$branch"
work="$(mktemp -d)"
port=8765
url="http://127.0.0.1:$port/"

if flatpak info --user "$app" >/dev/null 2>&1; then
  echo "Refusing to replace an installed $app" >&2; exit 1
fi

export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --passphrase '' --quick-gen-key "Kyllene repo smoke" rsa2048 sign never
key="$(gpg --list-keys --with-colons | awk -F: '/^fpr/ { print $10; exit }')"
gpg --export "$key" > "$work/key.gpg"

served="$work/served"
mkdir "$served"
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$served" >/dev/null 2>&1 &
server=$!
cleanup() {
  kill "$server" 2>/dev/null || true
  flatpak uninstall --user --noninteractive -y "$app" >/dev/null 2>&1 || true
  flatpak remote-delete --user --force kyllene-smoke >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# A release: a new signed commit of the app, bundled as release.yml bundles it, published into the served repo.
n=0
release() {
  n=$((n + 1))
  flatpak build-commit-from --force --src-repo="$build" --src-ref="$ref" --subject="release $n" \
    --gpg-sign="$key" --gpg-homedir="$GNUPGHOME" "$work/build" "$ref" >/dev/null
  flatpak build-bundle --repo-url="$url" --gpg-keys="$work/key.gpg" \
    --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo "$work/build" "$work/release-$n.flatpak" "$app" "$branch"
  rm -rf "$work/publish"
  sh "$publish" "$work/release-$n.flatpak" "$work/publish" "$GNUPGHOME" "$key"
  cp -R "$work/publish/." "$served/"
}
installed() { flatpak info --user --show-commit "$app"; }
published() { ostree --repo="$served" rev-parse "$ref"; }

ostree --repo="$work/build" init --mode=archive-z2
release
echo "1. Bundle that names the repository"
flatpak install --user --noninteractive -y "$work/release-1.flatpak"
flatpak remotes --user -d
test "$(installed)" = "$(published)"
release
flatpak update --user --noninteractive -y "$app"
test "$(installed)" = "$(published)"
echo "   flatpak update took release 2 from the repository"
flatpak uninstall --user --noninteractive -y "$app"
flatpak remote-delete --user --force "$(flatpak remotes --user --columns=name | grep -i origin | head -1)" 2>/dev/null || true

echo "2. The .flatpakref"
printf '[Flatpak Ref]\nName=%s\nBranch=%s\nTitle=Kyllene\nUrl=%s\nSuggestRemoteName=kyllene-smoke\nRuntimeRepo=https://dl.flathub.org/repo/flathub.flatpakrepo\nIsRuntime=false\nGPGKey=%s\n' \
  "$app" "$branch" "$url" "$(base64 -w0 "$work/key.gpg")" > "$work/kyllene.flatpakref"
flatpak install --user --noninteractive -y --from "$work/kyllene.flatpakref"
test "$(installed)" = "$(published)"
release
flatpak update --user --noninteractive -y "$app"
test "$(installed)" = "$(published)"
echo "   flatpak update took release 3 from the repository"

# Signatures are checked: a release signed by another key (its commit and an unsigned summary) is refused.
other="$work/other"
mkdir -m 700 "$other"
GNUPGHOME="$other" gpg --batch --quiet --passphrase '' --quick-gen-key "Not Kyllene" rsa2048 sign never
before="$(installed)"
flatpak build-commit-from --force --src-repo="$build" --src-ref="$ref" --subject="forged" \
  --gpg-sign="$(GNUPGHOME="$other" gpg --list-keys --with-colons | awk -F: '/^fpr/ { print $10; exit }')" \
  --gpg-homedir="$other" "$served" "$ref" >/dev/null
flatpak build-update-repo "$served" >/dev/null
if flatpak update --user --noninteractive -y "$app"; then echo "An update without our signature was accepted" >&2; exit 1; fi
test "$(installed)" = "$before"
echo "   a release signed by another key was refused"
echo "Repository: bundle and .flatpakref installs update from it"
