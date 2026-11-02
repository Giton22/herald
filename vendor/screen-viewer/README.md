# The viewer page

The page Kyllene loads to show a bot's screen: [noVNC](https://novnc.com/) (the RFB client) plus the
glue in `src/entry.js`, bundled into one file.

## Why it is built here and committed

noVNC is published as ES modules, and a browser will not load a module script from a `file://` address
— which is where the app has to serve it from (see below). So the modules are bundled into one
classic script, and the result is committed: the app's build needs neither Node nor a network.

Run `./build.sh` after upgrading noVNC, and commit what it writes. It fetches the pinned release from
npm, patches it, bundles the page and copies noVNC's licence next to it.

## The patches

Two, both applied while building, and both failing loudly if noVNC's files change shape.

`core/util/browser.js` ends with a top-level `await`, and a classic script cannot have one. The await
guards the H.264 encoding, which the gateway's screen does not offer, so the check is replaced with
`false`.

`patches/novnc-viewport-zoom.patch` adds a zoom of the view. noVNC can only scale the screen to fit,
and its own pinch gesture zooms the **remote** desktop: it sends Ctrl and the mouse wheel, and the
program on the other end is what zooms. To magnify what the user is looking at, the viewer has to look
at a part of the screen and draw that part larger — which is noVNC's *viewport*, and there is no public
way of asking for one. The patch gives the display a `zoom` (the part of the screen that is looked at,
the middle of it staying where it was) and the connection a `dragView` (a finger that slid across the
page).

The zoom is deliberately **not** a CSS transform on the canvas: the pointer would then be off by the
scale, and a tap would reach the wrong pixel of the bot's screen. The display's own scale keeps the
pixel under the user's finger the pixel the bot's screen has.

## The gestures

- **tap** → a click on the bot's screen. **two taps** → a double click: noVNC sends the second click
  at the spot of the first, so the bot's screen sees a double click and not two clicks.
- **pinch** → the zoom of the view, in the page and not on the bot's screen. The gesture is taken from
  noVNC before it reaches the canvas, so no Ctrl+wheel is sent to the remote.
- **drag** → the remote mouse while the whole screen is shown, and the view itself once it is zoomed
  in, so that a finger can move the picture around.
- **two fingers** → the remote's scroll. **long press** → the remote's right click.

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
  `.disconnect()`, `.setZoom(factor)`.
- **page to app**: a navigation to `herald://screen?state=...`, which the app intercepts and blocks.
  The states are `connected`, `failed`, `closed` and `zoom` — the last one carries how far the screen
  is zoomed, which is how a pinch reaches the app's buttons.

The page's parameters are the address of the display stream (`ws`, with the gateway's single-use
ticket already in it) and whether the user may drive the screen (`viewOnly`).

## Licence

noVNC is MPL-2.0; the bundled page is covered by it. `LICENSE.noVNC.txt` and `AUTHORS.noVNC.txt` are
noVNC's own, and travel with the page.
