import RFB from './vendor/core/rfb.js';
import { RpcClient, ScreenState, gatewaySocketUrl, gatewayProtocols, displaySocketUrl, handoffButtonState, viewModeState } from './client.js';
import { TextKeyboard, ClipboardBridge } from './input.js';
import { nativeScreenHost } from './native_host.js';
import { RemoteScroll, ViewerGestures, viewerTap } from './mobile_controls.js';
import { observeScreen, ScreenOpenError, clipboardControls } from './open_screen.js';
import { displayChannel } from './display_channel.js';

const $ = id => document.getElementById(id);
const status = text => { $('state').textContent = text; };
const recovery = 'Control state unknown: the old lease may remain. Inspect or hand it back using Hermes Screen controls, then refresh the viewer.';
// Close the channel before noVNC teardown so Hermes can hand back the lease.
function closeDisplay(display, socket) {
  if (!display) return;
  display.viewOnly = true; display.focusOnClick = false;
  if (socket && socket.readyState < 2) socket.close();
  display.disconnect();
}
const screenState = new ScreenState();
let rpc = null, rfb = null, displaySocket = null, viewerId = '';
let otherHuman = false;
let generation = 0, retryTimer = null, attempt = 0, userClosed = false, zoom = false;
let rfbConnected = false, observed = false;
let connectionStage = 'initializing';
let streamGeneration = 0, takeoverPending = null, leaseUnknown = false, reconciling = false, leaseAtRisk = false;
let leaseOwnerClient = null;
let controlGranted = false;
let closing = false;
let placement = null, startingDesktop = false, retryAllowed = true;
const fitOnly = matchMedia('(pointer: coarse), (max-width: 600px)');
const mobileBrowser = fitOnly;
const remoteScroll = new RemoteScroll(inputSession, () => $('screen').querySelector('canvas'));
const gestures = new ViewerGestures(inputSession, remoteScroll, $('viewport'), $('screen'), () => rfb, (x,y) => {
  viewerTap(inputSession, $('screen').querySelector('canvas'), x, y);
});
gestures.onChange = factor => $('viewer-fit').setAttribute('aria-pressed', String(factor > 1));
function setScrollMode(on) {
  const enabled = remoteScroll.setEnabled(on && mobileBrowser.matches);
  $('scroll-mode').setAttribute('aria-pressed', String(enabled));
  $('scroll-mode').title = enabled ? 'Scroll lock on' : 'Scroll lock off';
  $('screen').classList.toggle('scroll-mode', enabled);
}
mobileBrowser.addEventListener('change', event => {
  document.body.classList.toggle('mobile-browser', event.matches);
  if (rfb) rfb.focusOnClick = !!inputSession() && !event.matches;
  setScrollMode(false);
  gestures.fit();
});
const textKeyboard = new TextKeyboard(inputSession);
const nativeHost = nativeScreenHost();
const phoneClipboard = nativeHost || navigator.clipboard;
const clipboardBridge = new ClipboardBridge(inputSession, phoneClipboard, {
  readSelection: async session => {
    const result = await rpc.request('viewer.selection.read', { epoch: session.epoch });
    return result?.text;
  },
});
let composing = false;
function inputSession() {
  if (userClosed || reconciling || takeoverPending || leaseUnknown || screenState.owned !== true || !observed || !rfbConnected ||
      !screenState.connected || !rfb || !rpc || rpc.closed) return null;
  return { display: rfb, generation, epoch: screenState.epoch };
}
function resetInput() {
  setScrollMode(false);
  gestures.cancel();
  composing = false;
  clipboardBridge.reset();
  nativeHost?.cancelPending();
  $('keyboard-input').value = textKeyboard.reset();
  $('keyboard-input').blur();
  $('keyboard').setAttribute('aria-pressed', 'false');
}
function connectionFailed(error) {
  if (userClosed) return;
  if (error instanceof ScreenOpenError) {
    clearTimeout(retryTimer); retryAllowed = false; startingDesktop = false; fence();
    $('connect').hidden = false; status(leaseUnknown ? `${error.message} ${recovery}` : error.message); return;
  }
  fence();
  $('connect').hidden = rfbConnected;
  const known = ['Hermes session not accepted at viewer origin', 'Gateway ticket request failed', 'Gateway returned no connection ticket', 'Gateway connection timeout', 'Gateway readiness timeout', 'Gateway unavailable'];
  const message = known.includes(error?.message) ? error.message : `Connection failed during ${connectionStage}`;
  status(leaseUnknown ? `${message} · ${recovery}` : message);
  if (!userClosed && error?.message !== 'Hermes session not accepted at viewer origin') scheduleRetry();
}

function fence() {
  screenState.owned = false;
  resetInput();
  if (rfb) { rfb.viewOnly = true; rfb.focusOnClick = false; }
  updateButtons();
}
function setViewMode(nativeSize) {
  const mode = viewModeState(nativeSize, fitOnly.matches);
  zoom = mode.zoom;
  if (rfb) { rfb.clipViewport = false; rfb.scaleViewport = mode.scaleViewport; }
  $('fit').setAttribute('aria-pressed', String(!zoom));
  $('zoom').setAttribute('aria-pressed', String(zoom));
  $('fit').hidden = mode.fitHidden;
  $('zoom').hidden = mode.zoomHidden;
}
function updateButtons() {
  const connected = !!rpc && !rpc.closed && rfbConnected && screenState.connected;
  const buttons = handoffButtonState(screenState.owned, connected && observed);
  Object.assign($('take'), buttons.take);
  if (takeoverPending || leaseUnknown || reconciling || otherHuman) $('take').disabled = true;
  Object.assign($('back'), buttons.back);
  const ready = !!inputSession();
  $('scroll-mode').disabled = !ready;
  $('keyboard').disabled = !ready || clipboardBridge.busy;
  $('keyboard-input').disabled = !ready || clipboardBridge.busy;
  const clipboard = clipboardControls(placement, ready, clipboardBridge.busy, phoneClipboard);
  $('copy').disabled = clipboard.copyDisabled;
  $('paste').disabled = clipboard.pasteDisabled;
  $('copy-explanation').hidden = !clipboard.sandbox;
  $('connect').disabled = false;
}
function setOwned(owned, text) {
  owned = !userClosed && owned === true && observed && rfbConnected && screenState.connected && !!rpc && !rpc.closed;
  screenState.owned = owned;
  if (owned) { leaseAtRisk = true; leaseOwnerClient = rpc; }
  if (!owned) resetInput();
  if (rfb) { rfb.viewOnly = !owned; rfb.focusOnClick = !!owned && !mobileBrowser.matches; }
  status(text);
  updateButtons();
}
function waitReady(client, gen) {
  if (client.ready) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { off(); reject(Error('Gateway readiness timeout')); }, 10000);
    const off = client.onEvent(msg => {
      if (gen !== generation || client !== rpc) { clearTimeout(timer); off(); reject(Error('Stale connection')); }
      else if (msg.params?.type === 'gateway.ready') { clearTimeout(timer); off(); resolve(); }
      else if (msg.type === 'rpc.closed') { clearTimeout(timer); off(); reject(msg.error); }
    });
  });
}
async function connect(explicit = false) {
  clearTimeout(retryTimer); userClosed = false;
  $('connect').textContent = 'Retry connection';
  $('connect').hidden = true;
  generation = screenState.beginConnection();
  streamGeneration++; reconciling = false; controlGranted = false; otherHuman = false; viewerId = ''; takeoverPending = null;
  const gen = generation; placement = null; startingDesktop = false; retryAllowed = true; fence(); observed = false; rfbConnected = false;
  if (rpc && !rpc.closed) rpc.socket.close();
  if (rfb) { const old = rfb, socket = displaySocket; rfb = null; displaySocket = null; closeDisplay(old, socket); }
  status('Connecting…');
  status('Requesting authenticated connection…');
  connectionStage = 'authentication ticket';
  const protocols = await gatewayProtocols();
  if (gen !== generation) throw Error('Stale connection');
  status('Opening gateway connection…');
  connectionStage = 'gateway WebSocket';
  const ws = new WebSocket(gatewaySocketUrl(location), protocols);
  // A cold, idle-stopped desktop can take longer than an ordinary RPC to start.
  const client = new RpcClient(ws, nativeHost ? 30000 : 10000); rpc = client;
  client.onEvent(msg => {
    if (gen !== generation || rpc !== client) return;
    if (msg.type === 'rpc.closed') {
      const startLost = startingDesktop;
      leaseUnknown ||= !!takeoverPending || leaseAtRisk || screenState.owned;
      takeoverPending = null; streamGeneration++; reconciling = false; fence();
      const old = rfb, socket = displaySocket; rfb = null; displaySocket = null; rfbConnected = false;
      closeDisplay(old, socket); gestures.fit(); screenState.disconnected(); observed = false;
      if (startLost) {
        startingDesktop = false; retryAllowed = false; clearTimeout(retryTimer);
        $('connect').hidden = false;
        status(leaseUnknown ? `Desktop start state unknown after gateway disconnect. Select Retry to check again. ${recovery}` : 'Desktop start state unknown after gateway disconnect. Select Retry to check again.');
      } else if (retryAllowed) status(leaseUnknown ? recovery : 'Gateway disconnected · watch only');
      if (!userClosed && retryAllowed && !startingDesktop) scheduleRetry(); return;
    }
    if (msg.params?.type === 'display.lease') {
      const lease = msg.params.payload?.lease;
      if (lease && observed && rfbConnected && Number.isInteger(lease.epoch) && lease.epoch >= screenState.epoch && ['agent', 'human'].includes(lease.holder)) {
        const owned = screenState.applyLease(lease, gen);
        otherHuman = lease.holder === 'human' && !owned;
        if (!owned) controlGranted = false;
        if (takeoverPending || reconciling || leaseUnknown) fence();
        else setOwned(owned && controlGranted, owned && controlGranted ? 'Connected · control' : otherHuman ? 'Connected · another viewer has control' : 'Connected · watch only');
      }
    }
  });
  const opened = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Gateway connection timeout')), 12000);
    ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
    ws.addEventListener('error', () => { clearTimeout(timer); reject(Error('Gateway unavailable')); }, { once: true });
  });
  await opened;
  connectionStage = 'gateway readiness';
  await waitReady(client, gen);
  if (gen !== generation || rpc !== client) throw Error('Stale connection');
  await observeAndAttach(client, gen, explicit);
}
async function observeAndAttach(client, gen, explicit) {
  const stream = ++streamGeneration;
  const current = () => !userClosed && gen === generation && rpc === client && stream === streamGeneration && !client.closed;
  connectionStage = 'desktop status';
  const result = await observeScreen(client, { explicit, current,
    starting: () => { startingDesktop = true; status('Starting desktop…'); },
  });
  if (!current()) return;
  startingDesktop = false;
  placement = result.placement; updateButtons();
  viewerId = result.viewer_id;
  await screenState.prepareViewer(viewerId);
  if (!current()) return;
  screenState.observe(result, gen); observed = true;
  // Observe includes a lease snapshot, but it is not authoritative for input yet.
  fence(); reconciling = true; updateButtons();
  connectionStage = 'desktop stream';
  await attach(result, client, gen, stream, explicit);
  if (current()) attempt = 0;
}
async function reconcile(client, gen, stream, explicit) {
  if (!observed || !rfbConnected || gen !== generation || client !== rpc || stream !== streamGeneration) return;
  reconciling = true; fence();
  try {
    // A status read before an outstanding acquire completes can falsely report agent ownership.
    if (takeoverPending) await takeoverPending;
    if (gen !== generation || client !== rpc || stream !== streamGeneration || !rfbConnected) return;
    let result = await client.request('display.status');
    if (gen !== generation || client !== rpc || stream !== streamGeneration || !rfbConnected) return;
    if (!Number.isInteger(result.lease?.epoch) || result.lease.epoch < screenState.epoch) throw Error('Invalid lease');
    // Only the original authenticated binding may recover its own stale lease.
    if (result.lease.holder === 'human' && client === leaseOwnerClient &&
        result.lease.viewer_hash === screenState.viewerHash && screenState.viewerHash) {
      result = await client.request('display.lease.release', { viewer_id: viewerId });
      if (gen !== generation || client !== rpc || stream !== streamGeneration || !rfbConnected) return;
    }
    if (!['agent', 'human'].includes(result.lease?.holder) || !Number.isInteger(result.lease.epoch) || result.lease.epoch < screenState.epoch || (result.lease.holder === 'human' && !result.lease.viewer_hash)) {
      leaseUnknown = true; fence(); status(recovery); return;
    }
    leaseUnknown = false; leaseAtRisk = false; leaseOwnerClient = null;
    $('notice').textContent = '';
    const ours = screenState.applyLease(result.lease, gen);
    otherHuman = result.lease.holder === 'human' && !ours;
    if (ours) { leaseUnknown = true; fence(); status(recovery); return; }
    reconciling = false;
    setOwned(false, otherHuman ? 'Connected · another viewer has control' : 'Connected · watch only');
  } catch {
    if (gen === generation && client === rpc && stream === streamGeneration) {
      leaseUnknown = true; fence(); status(recovery);
    }
  } finally { if (stream === streamGeneration) { reconciling = false; updateButtons(); } }
}
async function attach(result, client, gen, stream, explicit) {
  $('placeholder').hidden = true;
  const socket = displayChannel(new WebSocket(displaySocketUrl(location, result.path, result.ticket)));
  const instance = new RFB($('screen'), socket);
  displaySocket = socket; rfb = instance; instance.viewOnly = true; instance.focusOnClick = false;
  instance.resizeSession = false; gestures.fit(); setViewMode(zoom);

  instance.addEventListener('connect', () => {
    if (gen !== generation || stream !== streamGeneration || instance !== rfb) { closeDisplay(instance, socket); return; }
    rfbConnected = true; $('connect').hidden = true; $('notice').textContent = ''; status(leaseUnknown ? recovery : 'Connected · checking control'); updateButtons();
    void reconcile(client, gen, stream, explicit);
  });
  instance.addEventListener('disconnect', () => {
    if (gen !== generation || stream !== streamGeneration || instance !== rfb) return;
    controlGranted = false; leaseUnknown ||= !!takeoverPending || leaseAtRisk || screenState.owned;
    rfbConnected = false; observed = false; streamGeneration++; reconciling = false; fence(); gestures.fit();
    if (socket.readyState < 2) socket.close();
    screenState.connected = false; screenState.epoch = null;
    // Retire the RPC binding immediately; a fresh connection must mint a new id.
    rpc = null; if (!client.closed) client.socket.close(1000, 'Display disconnected');
    status(leaseUnknown ? 'Display disconnected · control state unknown; reconnecting' : 'Display disconnected · reconnecting');
    if (!userClosed && retryAllowed) scheduleRetry();
  });
}
function scheduleRetry() {
  clearTimeout(retryTimer);
  retryTimer = setTimeout(() => {
    runConnect(false);
  }, Math.min(30000, 1000 * 2 ** Math.min(attempt++, 5)));
}
function runConnect(explicit) {
  const pending = connect(explicit), gen = generation;
  void pending.catch(error => { if (gen === generation && !userClosed) connectionFailed(error); });
}
function disconnectViewer(showClosed = true) {
  userClosed = true; controlGranted = false;
  clearTimeout(retryTimer);
  fence();
  gestures.fit();
  generation = screenState.beginConnection();
  streamGeneration++; takeoverPending = null; reconciling = false;
  observed = false; rfbConnected = false;
  const display = rfb, socket = displaySocket, client = rpc;
  rfb = null; displaySocket = null; rpc = null;
  closeDisplay(display, socket);
  if (client?.socket) client.socket.close(1000, 'Viewer closed');
  updateButtons();
  if (showClosed) {
    $('notice').textContent = '';
    $('placeholder').hidden = false;
    $('placeholder').textContent = 'Viewer disconnected. The desktop keeps running.';
    $('connect').textContent = 'Reopen screen';
    $('connect').hidden = false;
    status('Disconnected');
  }
}
async function requestClose() {
  if (userClosed || closing) return;
  closing = true; userClosed = true; clearTimeout(retryTimer);
  const client = rpc, pending = takeoverPending, id = viewerId, gen = generation;
  const watchOnly = !pending && !leaseAtRisk && !leaseUnknown && !leaseOwnerClient && !screenState.owned;
  fence();
  status(watchOnly ? 'Closing…' : 'Closing · checking handback…');
  let confirmed = false;
  // Both acquire and release have deadlines; do not release while an acquire
  // may still complete later, or after this RPC binding becomes stale.
  const bounded = promise => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Close timeout')), 3000);
    Promise.resolve(promise).then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
  });
  try {
    if (!watchOnly) {
      if (pending) await bounded(pending);
      if (!client || client.closed || client !== rpc || client !== leaseOwnerClient || gen !== generation || !id) throw Error('No bound owner');
      const result = await bounded(client.request('display.lease.release', { viewer_id: id }));
      confirmed = client === rpc && gen === generation && result?.lease?.holder === 'agent' && Number.isInteger(result.lease.epoch) && result.lease.epoch >= (screenState.epoch ?? 0);
    }
  } catch { /* Ambiguous outcomes must remain unknown. */ }
  if (gen !== generation || client !== rpc) { closing = false; return; }
  if (confirmed) { leaseAtRisk = false; leaseUnknown = false; leaseOwnerClient = null; }
  else if (!watchOnly) leaseUnknown = true;
  disconnectViewer();
  closing = false;
  if (leaseUnknown) {
    status(`Disconnected · ${recovery}`);
    $('notice').textContent = '';
    return;
  }
  status(confirmed ? 'Disconnected · control handed back' : 'Disconnected');
  if (!nativeHost) return;
  try { await nativeHost.close(); }
  catch { $('notice').textContent = 'Screen disconnected. Use Herald Back to close the page.'; }
}
if (nativeHost) window.heraldScreenRequestClose = requestClose;
$('scroll-mode').onclick = () => setScrollMode(!remoteScroll.enabled);

$('viewer-fit').onclick = () => gestures.toggleFit();
for (const [type, method] of [['pointerdown','down'],['pointermove','move'],['pointerup','up']]) {
  $('viewport').addEventListener(type, event => {
    if (!mobileBrowser.matches || event.pointerType === 'mouse') return;
    gestures[method](event);
    if (type === 'pointerdown') $('viewport').setPointerCapture?.(event.pointerId);
  }, {capture:true});
}
// Block noVNC's native pinch-to-Ctrl+wheel recognizer on phones.
for (const type of ['touchstart','touchmove','touchend','touchcancel']) $('viewport').addEventListener(type, event => {
  if (mobileBrowser.matches) { event.preventDefault(); event.stopImmediatePropagation(); }
}, {capture:true,passive:false});
for (const type of ['pointercancel', 'lostpointercapture']) $('viewport').addEventListener(type, event => gestures.lose(event.pointerId));
window.addEventListener('resize', () => mobileBrowser.matches ? gestures.fit() : gestures.cancel());
window.addEventListener('orientationchange', () => mobileBrowser.matches ? gestures.fit() : gestures.cancel());
function fitKeyboardViewport() {
  if (mobileBrowser.matches) gestures.fit(); else gestures.cancel();
  if (mobileBrowser.matches && window.visualViewport?.scale === 1) {
    document.body.style.height = `${Math.round(window.visualViewport.height)}px`;
  } else document.body.style.height = '';
}
window.visualViewport?.addEventListener('resize', fitKeyboardViewport);
fitKeyboardViewport();
document.body.classList.toggle('mobile-browser',mobileBrowser.matches);
$('close').onclick = () => void requestClose();
$('refresh').onclick = () => location.reload();
$('keyboard').addEventListener('pointerdown', event => event.preventDefault());
$('keyboard').onclick = () => {
  if (!inputSession()) return;
  if (document.activeElement === $('keyboard-input')) {
    $('keyboard-input').blur();
    composing = false;
    $('keyboard').setAttribute('aria-pressed', 'false');
    return;
  }
  rfb.blur();
  $('keyboard-input').value = textKeyboard.reset();
  $('keyboard-input').focus({ preventScroll: true });
  $('keyboard').setAttribute('aria-pressed', 'true');
};
$('keyboard-input').addEventListener('blur', () => {
  composing = false;
  $('keyboard').setAttribute('aria-pressed', 'false');
});
$('keyboard-input').addEventListener('keydown', event => {
  if (event.isComposing || composing) return;
  if (textKeyboard.special(event.key)) {
    event.preventDefault();
    $('keyboard-input').value = textKeyboard.reset();
  }
});
$('keyboard-input').addEventListener('compositionstart', () => { composing = true; });
function forwardKeyboard(isComposing = false) {
  const field = $('keyboard-input');
  if (document.activeElement !== field || !inputSession()) return;
  const value = textKeyboard.accept(field.value, isComposing);
  // Do not rewrite the native composing buffer or disturb its selection.
  if (!isComposing && field.value !== value) field.value = value;
}
$('keyboard-input').addEventListener('compositionend', () => {
  composing = false;
  forwardKeyboard();
});
$('keyboard-input').addEventListener('input', event => {
  forwardKeyboard(event.isComposing || composing);
});
async function transferClipboard(action, success) {
  try {
    $('keyboard-input').blur();
    composing = false;
    $('keyboard').setAttribute('aria-pressed', 'false');
    $('keyboard-input').value = textKeyboard.reset();
    const pending = clipboardBridge[action]();
    updateButtons();
    await pending;
    if (!userClosed) $('notice').textContent = success;
  } catch (error) {
    if (!userClosed) $('notice').textContent = error.name === 'NotAllowedError' ? 'Clipboard permission denied. Check browser permissions.' : error.message;
  } finally { updateButtons(); }
}
$('copy').onclick = () => { if (!$('copy').disabled) void transferClipboard('copy', 'Selected text copied to phone.'); };
$('paste').onclick = () => void transferClipboard('paste', 'Paste sent to the focused app.');
$('connect').onclick = () => { if (!closing && !startingDesktop) runConnect(true); };
$('take').onclick = async () => {
  if (!rpc || !rfbConnected || !observed || rpc.closed || takeoverPending || leaseUnknown || reconciling || otherHuman) return;
  const client = rpc, gen = generation, stream = streamGeneration; fence();
  status('Takeover pending · control unknown');
  leaseAtRisk = true;
  leaseOwnerClient = client;
  const pending = client.request('display.lease.acquire', { viewer_id: viewerId, reason: 'mobile viewer takeover' });
  takeoverPending = pending; updateButtons();
  try {
    const result = await pending;
    if (gen !== generation || client !== rpc) return;
    takeoverPending = null;
    if (stream !== streamGeneration || !rfbConnected) return;
    const owned = screenState.reconcile(result.lease, gen);
    controlGranted = owned;
    setOwned(owned, owned ? 'Connected · control' : 'Takeover not confirmed');
  } catch {
    if (gen === generation && client === rpc) {
      takeoverPending = null; leaseUnknown = true; fence();
      $('connect').hidden = rfbConnected; status(recovery);
    }
  } finally { updateButtons(); }
};
$('back').onclick = async () => {
  if (!rpc || !rfbConnected || !screenState.owned || rpc.closed) return;
  const client = rpc, gen = generation; fence();
  try {
    const result = await client.request('display.lease.release', { viewer_id: viewerId });
    if (gen !== generation || client !== rpc || !rfbConnected) return;
    if (result?.lease?.holder !== 'agent' || !Number.isInteger(result.lease.epoch) || result.lease.epoch < screenState.epoch) throw Error('Handback not confirmed');
    screenState.reconcile(result.lease, gen);
    leaseAtRisk = false;
    leaseOwnerClient = null;
    setOwned(false, 'Connected · watch only');
  } catch { if (gen === generation && client === rpc) { leaseUnknown = true; fence(); $('connect').hidden = rfbConnected; status(recovery); } }
};
$('fit').onclick = () => setViewMode(false);
$('zoom').onclick = () => setViewMode(true);
fitOnly.addEventListener('change', () => setViewMode(zoom));
resetInput();
setViewMode(false);
addEventListener('pagehide', () => disconnectViewer(false));
runConnect(true);
