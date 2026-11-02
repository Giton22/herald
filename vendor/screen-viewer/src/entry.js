// The page Kyllene loads to show a bot's screen.
//
// The app holds the session: it asks the gateway for a ticket, and hands the page the address of the
// display stream. The page draws the screen and sends what the user does; the gateway decides what
// reaches the bot. The page never sees the gateway's credentials, only the short-lived ticket.
//
// The app and the page talk in two directions:
//   - app to page: window.kylleneScreen.setViewOnly / sendKey / focus / disconnect / setZoom
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

/** How much bigger the screen is than the page: 1 is the whole screen. */
let zoom = 1;
/** The zoom the app asked for, kept for the moment the screen is not there to show it in. */
let wantedZoom = 1;
/**
 * Whether the user may drive the screen, as the app last said: kept for the moment the screen is not there.
 *
 * The lease changes while the page is open, and the app tells the page what the user may do. A page that
 * was not listening yet keeps the answer until the screen is there to apply it in.
 */
let wantedViewOnly = viewOnly;
/** The furthest the view may be zoomed in. */
const MAX_ZOOM = 8;

/**
 * Tells the app how the screen is doing. The page cannot talk to the app directly, so it asks for an
 * address of its own and the app reads the answer out of it. The reason is one of a few codes the app
 * knows, not a sentence: nothing has to be decoded.
 */
function report(state, reason) {
    const target = 'herald://screen?state=' + encodeURIComponent(state);
    window.location.href = reason ? target + '&reason=' + encodeURIComponent(reason) : target;
}

/** Tells the app how far the screen is zoomed, so that its buttons say the same. */
function reportZoom() {
    window.location.href = 'herald://screen?state=zoom&zoom=' + encodeURIComponent(zoom.toFixed(2));
}

/**
 * Looks at a part of the screen and draws it larger.
 *
 * The screen itself is not scaled up: the viewer looks at part of it, so the pixel under the user's
 * finger is still the pixel the bot's screen has, and what the user does to the bot's screen does not
 * change by looking closer.
 */
function applyZoom(factor) {
    const value = Math.min(Math.max(factor, 1), MAX_ZOOM);
    wantedZoom = value;
    if (!rfb) return;
    rfb.zoom = value;
    zoom = rfb.zoom;
    // A drag moves the picture while the view is closer than the whole screen, and the bot's own
    // mouse while it is not.
    rfb.dragViewport = zoom > 1;
}

window.kylleneScreen = {
    /** Whether the user may drive the screen. The gateway decides; this keeps the page honest. */
    setViewOnly(value) {
        wantedViewOnly = !!value;
        if (rfb) rfb.viewOnly = wantedViewOnly;
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
    /** How much of the screen there is to look at: 1 is the whole screen. */
    setZoom(factor) {
        applyZoom(factor);
    },
};

// The pinch.
//
// noVNC turns a pinch into Ctrl and the mouse wheel, which zooms the program on the other end and
// not the picture the user is looking at. The gesture arrives as an event on the canvas, and the
// container sees it first: it takes the one it wants and stops it from reaching the canvas at all.
const gesture = {
    /** How far apart the fingers were when the pinch began. */
    pinch: 0,
    /** The zoom when the pinch began. */
    zoom: 1,
};

/** The kind of gesture an event carries. */
function gestureType(event) {
    return (event.detail && event.detail.type) || '';
}

container.addEventListener('gesturestart', (event) => {
    if (gestureType(event) !== 'pinch') return;
    gesture.pinch = Math.hypot(event.detail.magnitudeX, event.detail.magnitudeY);
    gesture.zoom = zoom;
    event.stopPropagation();
}, true);

container.addEventListener('gesturemove', (event) => {
    if (gestureType(event) !== 'pinch') return;
    // The fingers are further apart than they were: the screen is larger.
    const distance = Math.hypot(event.detail.magnitudeX, event.detail.magnitudeY);
    if (gesture.pinch > 0) applyZoom(gesture.zoom * distance / gesture.pinch);
    event.stopPropagation();
}, true);

container.addEventListener('gestureend', (event) => {
    if (gestureType(event) !== 'pinch') return;
    event.stopPropagation();
    // The app shows how far the screen is zoomed, and is told once the fingers are off.
    reportZoom();
}, true);

// A page that breaks before it connects would leave the app waiting for a screen that is never
// coming: say so instead.
window.addEventListener('error', () => {
    if (!connected) report('failed', 'error');
});

if (socketUrl) {
    rfb = new RFB(container, socketUrl, { shared: true });
    rfb.viewOnly = wantedViewOnly;
    // The screen is a fixed size on the host; the page shows all of it, scaled to fit.
    rfb.scaleViewport = true;
    rfb.resizeSession = false;
    // The app may have asked for a zoom before the screen was there to show it in.
    applyZoom(wantedZoom);
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
