#!/usr/bin/env bash
# Rebuilds the viewer page Kyllene loads to show a bot's screen.
#
# The page is noVNC (the RFB client) plus the glue in src/entry.js, bundled into one file: the app
# serves it from a file:// address, and a browser will not load a module script from there.
#
# The result is committed (dist/screen-viewer.html) so the app's build needs neither Node nor a
# network. Run this when noVNC is upgraded, and commit what it writes.
#
# Usage: ./build.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VERSION="1.7.0"   # The noVNC release this page is built from.
ESBUILD="0.25.0" # The bundler.

WORK="$(mktemp -d)"
echo "Working in $WORK"

echo "Fetching noVNC $VERSION"
cd "$WORK"
npm pack "@novnc/novnc@$VERSION" >/dev/null
tar -xzf "novnc-novnc-$VERSION.tgz"

# noVNC's own licence travels with the code it covers.
cp package/LICENSE.txt "$HERE/LICENSE.noVNC.txt"
cp package/AUTHORS "$HERE/AUTHORS.noVNC.txt"

echo "Installing esbuild $ESBUILD"
npm install --no-audit --no-fund --silent "esbuild@$ESBUILD" >/dev/null

# The glue imports noVNC's core by a relative path, so the core sits beside it, and noVNC's core
# imports the pako it ships with, so that sits beside the core.
mkdir -p novnc
cp -R package/core novnc/core
cp -R package/vendor novnc/vendor
cp "$HERE/src/entry.js" novnc/entry.js

echo "Patching noVNC's browser checks"
node - <<'NODE'
const fs = require('fs');
const path = require('path');
const file = path.join(process.cwd(), 'novnc', 'core', 'util', 'browser.js');
const before = fs.readFileSync(file, 'utf8');
const needle = 'supportsWebCodecsH264Decode = await _checkWebCodecsH264DecodeSupport();';
if (!before.includes(needle)) {
    throw new Error("noVNC's browser checks changed: the patch below no longer applies.");
}
// The check needs a top-level await, and a classic script cannot have one. It guards the H.264
// encoding, which the gateway's screen does not offer.
const after = before.replace(needle,
    "// Kyllene's patch: no top-level await in a classic script, and the gateway's screen\n" +
    "// offers no H.264 encoding for this to guard.\n" +
    "supportsWebCodecsH264Decode = false;");
fs.writeFileSync(file, after);
NODE

echo "Bundling"
./node_modules/.bin/esbuild novnc/entry.js \
    --bundle --format=iife --target=es2022 --minify \
    --outfile=bundle.js

echo "Writing the page"
node - "$HERE" <<'NODE'
const fs = require('fs');
const path = require('path');
const here = process.argv[2];
const bundle = fs.readFileSync(path.join(process.cwd(), 'bundle.js'), 'utf8');
const page = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>Bot screen</title>
<style>
  html, body { margin: 0; padding: 0; height: 100%; background: #0b0b0d; overflow: hidden; }
  #screen { width: 100%; height: 100%; overflow: hidden; }
</style>
</head>
<body>
<!-- noVNC draws into a canvas of its own and mounts it inside the element it is given, so this has
     to be a container: a canvas inside a canvas is never painted. -->
<div id="screen"></div>
<script>
// If the viewer below never starts, say so. A browser too old to parse it would otherwise leave the
// app waiting for a screen that was never opened.
window.setTimeout(function () {
  if (!window.kylleneScreen) {
    window.location.href = 'herald://screen?state=failed&reason=too-old';
  }
}, 8000);
</script>
<script>${bundle}</script>
</body>
</html>
`;
fs.mkdirSync(path.join(here, 'dist'), { recursive: true });
fs.writeFileSync(path.join(here, 'dist', 'screen-viewer.html'), page);
NODE

echo "Wrote $HERE/dist/screen-viewer.html"
