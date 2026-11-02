import test from 'node:test';
import assert from 'node:assert/strict';
import { RemoteScroll, ViewerGestures } from './mobile_controls.js';

// Run the bundled noVNC event handlers and its real window capture proxy on a
// small DOM adapter. Event cloning, focus, button masks and viewOnly matter here.
class Mouse extends Event {
  constructor(type, options = {}) {
    super(type, {bubbles: options.bubbles, cancelable: true});
    for (const k of ['clientX','clientY','button','buttons','deltaX','deltaY','deltaMode']) this[k] = options[k] ?? 0;
  }
}
class Element extends EventTarget {
  constructor() { super(); this.style = {}; this.children = []; this.offsetWidth = 200; this.offsetHeight = 200; }
  appendChild(e) { e.parentNode = this; this.children.push(e); }
  removeChild(e) { this.children = this.children.filter(x => x !== e); }
  getBoundingClientRect() { return {left:0,top:0,right:200,bottom:200,width:200,height:200}; }
  focus() { this.onfocus?.(); }
}
const win = new EventTarget(); win.devicePixelRatio = 1; win.getComputedStyle = () => ({cursor:'default'});
const body = new Element(), document = {body, documentElement:new Element(), createElement:() => new Element(), getElementById: id => body.children.find(e => e.id === id) || null};
globalThis.window = win; globalThis.document = document;
Object.defineProperty(globalThis, 'navigator', {value:{platform:'Linux', userAgent:'test', maxTouchPoints:1}, configurable:true});
globalThis.MutationObserver = class { observe() {} disconnect() {} };
globalThis.MouseEvent = Mouse;
const {default:RFB} = await import('./vendor/core/rfb.js');

function fixture() {
  const canvas = new Element(); canvas.ownerDocument = {defaultView:win};
  const sent = [], viewport = {clientWidth:200,clientHeight:200,scrollLeft:0,scrollTop:0};
  const rfb = Object.create(RFB.prototype);
  Object.assign(rfb, {_canvas:canvas, _rfbConnectionState:'connected', _viewOnly:false, _mouseButtonMask:0,
    _keyboard:{grab(){},ungrab(){}}, _mouseMoveTimer:null, _mouseLastMoveTime:0, _viewportDragging:false, _accumulatedWheelDeltaX:0, _accumulatedWheelDeltaY:0});
  rfb._sendMouse = (x,y,mask) => { if (!rfb._viewOnly) sent.push({x,y,mask}); };
  for (const type of ['mousedown','mouseup','mousemove']) canvas.addEventListener(type, e => rfb._handleMouse(e));
  canvas.addEventListener('wheel', e => rfb._handleWheel(e));
  let session = {display:rfb, epoch:1, generation:1};
  const get = () => session, target = {style:{},querySelector:() => canvas};
  const scroll = new RemoteScroll(get, () => canvas, o => new Mouse('wheel',o));
  const g = new ViewerGestures(get,scroll,viewport,target,() => null,() => {},(t,o) => new Mouse(t,o));
  const p = (id,x,y) => ({pointerId:id,clientX:x,clientY:y,preventDefault(){},stopPropagation(){}});
  return {canvas,rfb,sent,g,p,scroll,viewport,get,set:v => session=v};
}
const hold = async () => new Promise(r => setTimeout(r, 530));
test('long press uses real noVNC capture and button mask for selection', async () => {
  const f = fixture(), {g,p} = f;
  g.down(p(1,20,30)); await hold();
  assert.equal(document.captureElement, f.canvas);
  g.move(p(1,70,60)); g.up(p(1,80,65));
  assert.equal(document.captureElement, null);
  assert.deepEqual(f.sent.map(e => e.mask), [1,1,0]);
  assert.deepEqual(f.sent.at(-1), {x:80,y:65,mask:0});
});
test('release and capture cleanup on cancellation and ownership races', async () => {
  for (const action of ['cancel','pointer','lease','epoch','generation','display','second']) {
    const f = fixture(), {g,p} = f, old = f.get();
    g.down(p(1,20,30)); await hold();
    if (action === 'cancel') g.cancel();
    else if (action === 'pointer') g.lose(1);
    else if (action === 'second') g.down(p(2,60,30));
    else { f.set(action === 'lease' ? null : {...old,[action]:action === 'display' ? {} : 2}); g.move(p(1,50,30)); }
    assert.equal(document.captureElement,null,action);
    assert.equal(f.rfb._mouseButtonMask,0,action);
    assert.equal(f.sent.at(-1).mask, action === 'cancel' || action === 'pointer' || action === 'second' ? 0 : 1, action);
    g.cancel(); g.up(p(1,50,30)); g.up(p(2,60,30));
  }
});
test('wheel gestures produce discrete proportional noVNC steps and stop on loss', () => {
  const f = fixture(), {g,p} = f;
  g.down(p(1,30,180)); g.down(p(2,80,180));
  g.move(p(1,30,60)); g.move(p(2,80,60)); g.up(p(1,30,60)); g.up(p(2,80,60));
  assert.deepEqual(f.sent.map(e=>e.mask),[16,0,16,0,16,0]);
  assert.ok(f.sent.every(e=>e.x===55 && e.y===60));
  f.sent.length = 0;
  assert.equal(f.scroll.scroll(-100,f.get(),{x:25,y:35},true), true);
  assert.equal(f.sent.length,24); assert.ok(f.sent.every(e=>e.x===25 && e.y===35));
  f.sent.length=0; const original = f.rfb._sendMouse;
  f.rfb._sendMouse = (...args) => { original(...args); if (f.sent.length===2) f.set(null); };
  f.scroll.scroll(10,f.get(),null,true); assert.equal(f.sent.length,2);
});
test('watcher and movement before hold never generate remote input', async () => {
  const f = fixture(), {g,p} = f; f.set(null);
  g.down(p(1,20,30)); await hold(); g.move(p(1,20,80)); g.up(p(1,20,80));
  g.down(p(1,30,150)); g.down(p(2,80,150)); g.move(p(1,30,80)); g.move(p(2,80,80)); g.up(p(1,30,80)); g.up(p(2,80,80));
  assert.deepEqual(f.sent,[]);
  f.set({display:f.rfb,epoch:2,generation:1}); g.toggleFit();
  g.down(p(1,20,30)); g.move(p(1,20,60)); await hold(); g.up(p(1,20,60));
  assert.deepEqual(f.sent,[]); assert.notEqual(f.viewport.scrollTop,0);
});
test('focus ownership race and release-before-timeout cannot press', async () => {
  const f=fixture(), {g,p}=f;
  f.canvas.onfocus = () => f.set(null);
  g.down(p(1,20,30)); await hold(); g.up(p(1,20,30));
  assert.deepEqual(f.sent,[]);
  f.canvas.onfocus = null; f.set({display:f.rfb,epoch:1,generation:1});
  g.down(p(1,20,30)); g.up(p(1,20,30)); await hold();
  assert.deepEqual(f.sent,[]);
});
