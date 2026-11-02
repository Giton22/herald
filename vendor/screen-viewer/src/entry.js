// The page Kyllene loads to show a bot's screen.
//
// The app holds the session: it asks the gateway for a ticket, and hands the page the address of the
// display stream. The page draws the screen and sends what the user does; the gateway decides what
// reaches the bot. The page never sees the gateway's credentials, only the short-lived ticket.
//
// The app and the page talk in two directions:
//   - app to page: window.kylleneScreen.setViewOnly / sendKey / focus / disconnect
//   - page to app: a navigation to herald://screen?state=..., which the app intercepts and blocks.
//
// The page is served from a file:// address, so it has no origin of its own: the gateway accepts a
// viewer without one, and a file:// document is a secure context, which noVNC needs.

import RFB from './core/rfb.js';
import { initLogging } from './core/util/logging.js';

// Warnings and errors only. The app logs what the page prints, and a screen that works has nothing
// to say.
initLogging('warn');

const params = new URLSearchParams(window.location.search);
if (!params.get('ws')) {
    // The app passes the address in the fragment when the page is served from a file: address, where a
    // query would be read as part of the file's name.
    const fragment = window.location.hash.replace(/^#/, '');
    if (fragment) new URLSearchParams(fragment).forEach((value, key) => params.set(key, value));
}
const socketUrl = params.get('ws');
const viewOnly = params.get('viewOnly') === '1';

// noVNC draws into a canvas of its own and mounts it inside the element it is given, so that element
// has to be a container: a canvas inside a canvas is never painted, and the screen would stay blank
// while every frame arrived and decoded.
const container = document.getElementById('screen');
let rfb = null;
let connected = false;

/**
 * Tells the app how the screen is doing. The page cannot talk to the app directly, so it asks for an
 * address of its own and the app reads the answer out of it. The reason is one of a few codes the app
 * knows, not a sentence: nothing has to be decoded.
 */
function report(state, reason) {
    const target = 'herald://screen?state=' + encodeURIComponent(state);
    window.location.href = reason ? target + '&reason=' + encodeURIComponent(reason) : target;
}

window.kylleneScreen = {
    /** Whether the user may drive the screen. The gateway decides; this keeps the page honest. */
    setViewOnly(value) {
        if (rfb) rfb.viewOnly = !!value;
    },
    /** A key on the bot's screen: the app maps what the user typed to a keysym. */
    sendKey(keysym, down) {
        if (rfb) rfb.sendKey(keysym, 0, !!down);
    },
    /** Gives the keyboard to the screen, so the page takes what the user types. */
    focus() {
        if (rfb) rfb.focus();
    },
    /** Closes the stream to the screen. */
    disconnect() {
        if (rfb) rfb.disconnect();
    },
};

// A page that breaks before it connects would leave the app waiting for a screen that is never
// coming: say so instead.
window.addEventListener('error', () => {
    if (!connected) report('failed', 'error');
});

if (socketUrl) {
    rfb = new RFB(container, socketUrl, { shared: true });
    rfb.viewOnly = viewOnly;
    // The screen is a fixed size on the host; the page shows all of it, scaled to fit.
    rfb.scaleViewport = true;
    rfb.resizeSession = false;
    rfb.addEventListener('connect', () => {
        connected = true;
        report('connected');
    });
    rfb.addEventListener('disconnect', (event) => {
        const clean = !!(event.detail && event.detail.clean);
        report(clean ? 'closed' : 'failed', 'closed');
    });
    rfb.addEventListener('securityfailure', () => report('failed', 'refused'));
} else {
    report('failed', 'no-address');
}
