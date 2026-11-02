import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

// Exercise the real app's click handlers with a minimal browser and RPC transport.
test('open, failed start, Retry, Close and Reopen are explicit user actions', async t => {
  const elements = new Map();
  class Element {
    constructor() { this.hidden = false; this.disabled = false; this.style = {}; this.classList = {toggle(){}}; this.events = {}; this.value = ''; }
    addEventListener(type, fn) { (this.events[type] ||= []).push(fn); }
    appendChild(child) { child.parentNode=this; } removeChild() {}
    setAttribute() {} querySelector() { return null; } blur() {} focus() {} setPointerCapture() {}
    click() { if (!this.disabled && !this.hidden) this.onclick?.(); }
  }
  const $ = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  globalThis.document = {getElementById: $, body: new Element(), documentElement:new Element(), createElement:() => new Element(), activeElement: null};
  globalThis.MutationObserver = class { observe() {} disconnect() {} };
  const windowEvents = {}; globalThis.window = globalThis; globalThis.addEventListener = (type, fn) => { (windowEvents[type] ||= []).push(fn); };
  const nativeCalls = [];
  globalThis.heraldScreenHost = { postMessage(message) {
    const request = JSON.parse(message); nativeCalls.push(request.operation);
    queueMicrotask(() => this.onmessage({data:JSON.stringify({id:request.id, ok:true})}));
  } };
  globalThis.matchMedia = () => ({matches:false, addEventListener(){}});
  globalThis.location = {protocol:'https:', host:'example.test', pathname:'/', reload(){}};
  globalThis.sessionStorage = {};
  Object.defineProperty(globalThis, 'navigator', {value: {clipboard:{writeText(){}, readText(){}}}, configurable:true});
  globalThis.fetch = async () => ({ok:true, json:async () => ({ticket:'gateway-ticket'})});
  let delayedClose = null, deferClose = false;
  let running = false, failStart = 'pending', failRelease = false, lease = {epoch:1, holder:'agent', viewer_hash:null}, pendingAcquire, pendingStart; const methods = [], sockets = [], displays = [], displaySockets = [];
  class Socket {
    constructor(url) { this.isDisplay = url.includes('display_ticket='); this.viewer = this.isDisplay ? sockets.at(-1).viewer : `viewer-${sockets.length}`; (this.isDisplay ? displaySockets : sockets).push(this); this.readyState = 0; this.events = {}; this.binaryType = ''; this.protocol = ''; this.onopen = this.onmessage = this.onerror = null; this.closes = []; queueMicrotask(() => { if (this.readyState !== 0) return; this.readyState = 1; if (!this.isDisplay) { this.emit('open', {}); this.emit('message', {data:JSON.stringify({method:'event', params:{type:'gateway.ready'}})}); } }); }
    addEventListener(type, fn) { (this.events[type] ||= []).push(fn); }
    emit(type, data) { for (const fn of this.events[type] || []) fn(data); }
    send(data) {
      const req = JSON.parse(data); methods.push(req.method);
      let response;
      if (req.method === 'display.lease.acquire') { pendingAcquire = () => { lease = {epoch:lease.epoch+1, holder:'human', viewer_hash:createHash('sha256').update(req.params.viewer_id).digest('hex').slice(0,12)}; this.emit('message', {data:JSON.stringify({id:req.id, result:{lease}})}); }; return; }
      if (req.method === 'display.start' && failStart === 'pending') { pendingStart = () => this.emit('message', {data:JSON.stringify({id:req.id, error:{message:'private failure'}})}); return; }
      if (req.method === 'display.start' && failStart) response = {error:{message:'failed'}};
      else if (req.method === 'display.start') { running = true; response = {result:{running, placement:'gateway', lease}}; }
      else if (req.method === 'display.status') response = {result:{running, placement:'gateway', lease}};
      else if (req.method === 'display.observe') response = {result:{running:true, placement:'gateway', lease, viewer_id:this.viewer, ticket:'ticket', path:'/api/display/ws'}};
      else if (req.method === 'display.lease.release') { if (failRelease === 'timeout') return; if (failRelease === 'ambiguous') response = {result:{lease}}; else if (failRelease || (lease.holder === 'human' && lease.viewer_hash !== createHash('sha256').update(req.params.viewer_id).digest('hex').slice(0,12))) response = {error:{message:'not owner'}}; else { lease = {epoch:lease.epoch+1, holder:'agent'}; response = {result:{lease}}; } }
      else response = {result:{lease}};
      queueMicrotask(() => this.emit('message', {data:JSON.stringify({id:req.id, ...response})}));
    }
    close(code) { if (this.readyState >= 2) return; this.closes.push(code ?? 1000); this.readyState = 3; if (this.isDisplay && (code === undefined || code === 1000 || code === 1001)) { const release = () => { if (lease.holder === 'human' && lease.viewer_hash === createHash('sha256').update(this.viewer).digest('hex').slice(0,12)) lease = {epoch:lease.epoch+1, holder:'agent'}; }; if (deferClose) { delayedClose = release; deferClose = false; } else release(); } this.emit('close', {}); }
  }
  Socket.CONNECTING=0; Socket.OPEN=1; Socket.CLOSING=2; Socket.CLOSED=3;
  globalThis.WebSocket = Socket;
  const {default:Websock} = await import('./vendor/core/websock.js');
  const {default:RFB} = await import('./vendor/core/rfb.js');
  globalThis.__FakeRFB = class { constructor(target, socket) { this.socket=socket; this.transport=new Websock(); this.transport.attach(socket); this._sock=this.transport; this._cursor={detach(){}}; this._canvas={removeEventListener(){}}; this._eventHandlers={}; this._resizeObserver={disconnect(){}}; this._keyboard={ungrab(){}}; this._gestures={detach(){}}; this._target={removeChild(){}}; displays.push(this); this.events={}; } addEventListener(type, fn) { this.events[type]=fn; if (type === 'connect') queueMicrotask(fn); } _updateConnectionState() { RFB.prototype._disconnect.call(this); } disconnect() { RFB.prototype.disconnect.call(this); this.events.disconnect?.(); } };
  let source = await readFile(new URL('./app.js', import.meta.url), 'utf8');
  source = source.replace("import RFB from './vendor/core/rfb.js';", 'const RFB = globalThis.__FakeRFB;');
  source = source.replaceAll(/from '\.\/(.*?)'/g, (_, file) => `from '${new URL(file, import.meta.url).href}'`);
  await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
  const settle = async () => { for (let i=0; i<10; i++) await new Promise(r => setTimeout(r, 2)); };
  await settle(); $('connect').onclick(); $('connect').onclick();
  assert.equal(methods.filter(m => m === 'display.start').length, 1);
  pendingStart(); await new Promise(r => setTimeout(r, 2100)); await settle();
  assert.deepEqual(methods, ['display.status','display.start','display.status','display.status','display.status']);
  assert.match($('state').textContent, /could not be confirmed/); assert.equal($('connect').hidden, false);
  failStart = false; $('connect').click(); await settle();
  assert.deepEqual(methods.slice(5,8), ['display.status','display.start','display.observe']);
  assert.equal($('take').disabled, false);
  // Delay old server-side RFB close until after reconnect and a new takeover.
  $('take').click(); pendingAcquire(); await settle();
  const oldDisplay = displays.at(-1), oldSocket = sockets.at(-1);
  deferClose = true; oldDisplay.events.disconnect();
  assert.deepEqual(displaySockets.at(-1).closes, [1000]);
  // The old holder is still visible until its delayed close completes.
  $('connect').hidden = false; $('connect').click(); await settle();
  assert.notEqual(sockets.at(-1), oldSocket);
  assert.match($('state').textContent, /another viewer has control/);
  assert.equal($('take').disabled, true);
  // Simulate the old owner handing back by another path before delayed close.
  lease = {epoch:lease.epoch+1, holder:'agent'};
  displays.at(-1).disconnect(); await new Promise(r => setTimeout(r, 1100)); await settle();
  $('take').click(); pendingAcquire(); await settle();
  const newLease = {...lease};
  delayedClose();
  assert.deepEqual(lease, newLease);
  assert.equal($('keyboard').disabled, false);
  $('back').click(); await settle();

  // A normal disconnect while holding control sends 1000 and reconnects watch-only.
  $('take').click(); pendingAcquire(); await settle();
  assert.equal($('keyboard').disabled, false);
  displays.at(-1).disconnect(); await settle();
  assert.equal(lease.holder, 'agent');
  assert.deepEqual(displaySockets.at(-1).closes, [1000]);
  await new Promise(r => setTimeout(r, 1100)); await settle();
  assert.equal($('keyboard').disabled, true); assert.equal($('take').disabled, false);
  assert.equal($('connect').hidden, true);

  // RPC failure closes the display normally and reconnect remains watch-only.
  $('take').click(); pendingAcquire(); await settle();
  sockets.at(-1).close(); await settle();
  assert.equal(lease.holder, 'agent'); assert.equal($('keyboard').disabled, true);
  await new Promise(r => setTimeout(r, 1100)); await settle();
  assert.equal($('connect').hidden, true); assert.equal($('take').disabled, false);
  assert.equal($('keyboard').disabled, true);

  // If a server-side acquire completes after the owning RPC and display died,
  // a browser cannot cancel it or safely release it from the new RPC binding.
  $('take').click();
  const lostRpc = sockets.at(-1);
  lostRpc.close();
  pendingAcquire();
  assert.equal(lease.holder, 'human');
  const lateReleases = methods.filter(m => m === 'display.lease.release').length;
  await new Promise(r => setTimeout(r, 1100)); await settle();
  assert.equal(lease.holder, 'human'); assert.equal($('keyboard').disabled, true);
  assert.equal(methods.filter(m => m === 'display.lease.release').length, lateReleases);
  assert.equal($('connect').hidden, true);

  // A different viewer's human lease cannot be forcibly released and no
  // retry overlay or duplicate warning obscures the connected video.
  const releases = methods.filter(m => m === 'display.lease.release').length;
  lease = {epoch:lease.epoch+1, holder:'human', viewer_hash:'someone-else'};
  displays.at(-1).disconnect(); await new Promise(r => setTimeout(r, 1100)); await settle();
  assert.equal(lease.viewer_hash, 'someone-else');
  assert.equal(methods.filter(m => m === 'display.lease.release').length, releases);
  assert.equal($('connect').hidden, true); assert.equal($('notice').textContent, '');
  assert.equal($('keyboard').disabled, true); assert.equal($('take').disabled, true);
  assert.match($('state').textContent, /another viewer has control/);

  // Restore agent ownership, reconnect, then refresh/pagehide while controlling.
  lease = {epoch:lease.epoch+1, holder:'agent'};
  displays.at(-1).disconnect(); await new Promise(r => setTimeout(r, 1100)); await settle();
  $('take').click(); pendingAcquire(); await settle();
  assert.equal($('keyboard').disabled, false);
  windowEvents.pagehide[0]();
  assert.deepEqual(displaySockets.at(-1).closes, [1000]);
  assert.equal(lease.holder, 'agent');
  assert.equal($('keyboard').disabled, true);
});
