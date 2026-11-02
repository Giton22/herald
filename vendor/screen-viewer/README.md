# The viewer page

The page Kyllene loads to show a bot's screen: [noVNC](https://novnc.com/) (the RFB client) plus the
glue in `src/entry.js`, bundled into one file.

## Why it is built here and committed

noVNC is published as ES modules, and a browser will not load a module script from a `file://` address
— which is where the app has to serve it from (see below). So the modules are bundled into one
classic script, and the result is committed: the app's build needs neither Node nor a network.

Run `./build.sh` after upgrading noVNC, and commit what it writes. It fetches the pinned release from
npm, patches one thing, bundles the page and copies noVNC's licence next to it.

## The one patch

`core/util/browser.js` ends with a top-level `await`, and a classic script cannot have one. The await
guards the H.264 encoding, which the gateway's screen does not offer, so the check is replaced with
`false`. The patch is applied while building and fails loudly if noVNC's file changes shape.

## The one thing to get right

noVNC draws into a canvas **of its own** and mounts it inside the element it is given. That element is
a container, not a canvas: given a `<canvas>`, noVNC appends a `<div>` and a `<canvas>` inside it, and a
canvas inside a canvas is never painted. The screen then stays blank while every frame arrives and
decodes without an error — the frames are fine, the picture is simply somewhere that cannot be seen.

So the page has a `<div id="screen">`, and the glue hands that to `new RFB(...)`.

## Why the page is served from a file

The gateway refuses a display stream to a page on another origin before the upgrade is even accepted.
It trusts a page that has no origin of its own, so the page is served from a `file://` address: on
Android from the app's assets, on the desktop from a temporary file. A `file://` document is also a
secure context, which noVNC needs.

## The contract

The app and the page talk in two directions, and neither side trusts the other with credentials:

- **app to page**: `window.kylleneScreen.setViewOnly(bool)`, `.sendKey(keysym, down)`, `.focus()`,
  `.disconnect()`.
- **page to app**: a navigation to `herald://screen?state=...`, which the app intercepts and blocks.
  The states are `connected`, `failed` and `closed`.

The page's parameters are the address of the display stream (`ws`, with the gateway's single-use
ticket already in it) and whether the user may drive the screen (`viewOnly`).

## Licence

noVNC is MPL-2.0; the bundled page is covered by it. `LICENSE.noVNC.txt` and `AUTHORS.noVNC.txt` are
noVNC's own, and travel with the page.
