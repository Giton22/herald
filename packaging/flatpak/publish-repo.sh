#!/bin/sh
# Puts a release's Flatpak bundle into a signed repository directory, ready to upload over the published one.
#
#   publish-repo.sh BUNDLE REPO GPG_HOMEDIR KEY_ID
#
# REPO starts empty on every release: the published repository keeps the objects of earlier releases, and an
# upload adds this one's objects, then replaces its refs and summary (see flatpak-repo.yml). The bundle's own
# commit is imported, not rebuilt, so a bundle installed by hand and the repository hold the same commit.
set -eu
bundle="$1" repo="$2" home="$3" key="$4"

[ -f "$repo/config" ] || ostree --repo="$repo" init --mode=archive-z2
flatpak build-import-bundle --gpg-sign="$key" --gpg-homedir="$home" "$repo" "$bundle"
# The summary lists the refs and carries the app's AppStream data, which software centres show.
flatpak build-update-repo --gpg-sign="$key" --gpg-homedir="$home" \
  --title=Kyllene --default-branch=stable "$repo"
