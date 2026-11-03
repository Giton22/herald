#!/usr/bin/env bash
# Print the verified revision, or nothing for a non-upstream build. No network access.
set -euo pipefail

if [[ ${GITHUB_SERVER_URL:-} != https://github.com || ${GITHUB_REPOSITORY:-} != Giton22/kyllene ]]; then
  exit 0
fi
fail() { printf '::error::Screen release pin: %s\n' "$1" >&2; exit 1; }
sha=${GITHUB_SHA:-}
[[ $sha =~ ^[0-9a-f]{40}$ ]] || fail 'GITHUB_SHA must be a full lowercase commit SHA'
[[ ${GITHUB_REF_TYPE:-} == tag && ${GITHUB_REF:-} == refs/tags/* ]] || fail 'expected a tag push'
[[ $(git rev-parse --is-shallow-repository) == false ]] || fail 'full checkout required'
[[ $(git cat-file -t "$sha" 2>/dev/null) == commit ]] || fail 'GITHUB_SHA is not a commit'
[[ $(git rev-parse HEAD) == "$sha" ]] || fail 'GITHUB_SHA does not match HEAD'
tag_commit=$(git rev-parse --verify "${GITHUB_REF}^{commit}" 2>/dev/null) || fail 'tag does not resolve to a commit'
[[ $tag_commit == "$sha" ]] || fail 'tag does not match HEAD'
git rev-parse --verify 'refs/remotes/origin/master^{commit}' >/dev/null 2>&1 || fail 'upstream master is unavailable'
git merge-base --is-ancestor "$sha" refs/remotes/origin/master || fail 'release commit is not an ancestor of upstream master'
manifest=plugins/herald-screen/plugin.yaml
[[ -f $manifest && ! -L $manifest ]] || fail 'plugin.yaml missing or not a regular file'
[[ $(git ls-tree HEAD -- "$manifest") == 100644\ blob* || $(git ls-tree HEAD -- "$manifest") == 100755\ blob* ]] || fail 'plugin.yaml is not a tracked regular file'
# Include ignored files: they can otherwise hide accidental local/private data.
[[ -z $(git status --porcelain=v1 --untracked-files=all --ignored -- plugins/herald-screen) ]] || fail 'plugin subtree has tracked changes or untracked/ignored files'
printf '%s\n' "$sha"
