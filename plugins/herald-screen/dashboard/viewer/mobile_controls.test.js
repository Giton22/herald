import test from 'node:test';
import assert from 'node:assert/strict';
import { RemoteScroll, ViewerGestures, viewerTap, gestureKind } from './mobile_controls.js';

function setup() {
  let session = { display: {}, generation: 1, epoch: 1 };
  const events = [], taps = [];
  const win = { dispatchEvent: e => events.push(e) };
  const canvas = { ownerDocument: { defaultView: win }, focus() {},
    getBoundingClientRect: () => ({left:0, top:0, right:200, bottom:100, width:200, height:100}),
    dispatchEvent: e => events.push(e) };
  const viewport = {clientWidth:200, clientHeight:100, scrollLeft:0, scrollTop:0};
  const target = {style:{}, querySelector: () => canvas};
  const display = {};
  const get = () => session;
  const scroll = new RemoteScroll(get, () => canvas, options => ({type:'wheel', ...options}));
  const gestures = new ViewerGestures(get, scroll, viewport, target, () => display, (x,y) => taps.push([x,y]));
  const point = (pointerId,x,y) => ({pointerId,clientX:x,clientY:y,preventDefault(){},stopPropagation(){}});
  return {events,taps,canvas,viewport,target,display,scroll,gestures,point,get, set: value => session=value};
}

test('remote scroll requires live control and rejects stale display, epoch and reconnect generation', () => {
  const f = setup(), old = f.get();
  assert.equal(f.scroll.setEnabled(true), true);
  assert.equal(f.scroll.scroll(3, old), true);
  for (const key of ['display','epoch','generation']) {
    f.set({...old, [key]: key === 'display' ? {} : 2});
    assert.equal(f.scroll.scroll(3, old), false);
  }
  f.set(null);
  assert.equal(f.scroll.scroll(3), false);
  assert.equal(f.scroll.setEnabled(true), false);
  assert.equal(f.events.length, 3);
});

test('tap is bounded and rechecks control after focus', () => {
  const f = setup(), factory = (type, opts) => ({type,...opts});
  assert.equal(viewerTap(f.get, f.canvas, 20, 30, factory), true);
  assert.deepEqual(f.events.map(e=>e.type), ['mousedown','mouseup']);
  assert.equal(viewerTap(f.get, f.canvas, 200, 30, factory), false);
  f.canvas.focus = () => f.set(null);
  assert.equal(viewerTap(f.get, f.canvas, 20, 30, factory), false);
  assert.equal(f.events.length, 2);
});

test('single touch pans locally, taps with control, and scroll lock sends remote wheel', () => {
  const f = setup(), {gestures:g, point:p} = f;
  g.down(p(1,50,50)); g.up(p(1,50,50));
  assert.deepEqual(f.taps, [[50,50]]);
  g.toggleFit(); const before = f.viewport.scrollTop;
  g.down(p(1,50,70)); g.move(p(1,50,40)); g.up(p(1,50,40));
  assert.equal(f.viewport.scrollTop, before+30);
  assert.equal(f.events.length, 0);
  f.scroll.setEnabled(true);
  g.down(p(1,50,70)); g.move(p(1,50,30)); g.up(p(1,50,30));
  assert.equal(f.events.length, 1); assert.equal(f.events[0].deltaY, 3);
});

test('two-finger parallel swipe scrolls remotely; pinch changes local zoom only', () => {
  const f = setup(), {gestures:g, point:p} = f;
  assert.equal(gestureKind({x:0,y:30},{x:50,y:30},{x:0,y:0},{x:50,y:0}), 'scroll');
  g.down(p(1,30,50)); g.down(p(2,80,50));
  g.move(p(1,30,20)); g.move(p(2,80,20)); g.up(p(1,30,20)); g.up(p(2,80,20));
  assert.equal(f.events.length, 1); assert.equal(f.events[0].type, 'wheel');
  g.down(p(1,50,50)); g.down(p(2,100,50));
  g.move(p(1,30,50)); g.move(p(2,120,50));
  assert.ok(g.factor > 1); assert.equal(f.display.scaleViewport, true);
  assert.equal(f.display.resizeSession, false);
  g.up(p(1,30,50)); g.up(p(2,120,50));
  assert.equal(f.events.length, 1);
});

test('fit toggles saved zoom and position, and fit resets on reconnect', () => {
  const f = setup(), g = f.gestures;
  g.toggleFit(); assert.equal(g.factor, 2);
  f.viewport.scrollLeft=45; f.viewport.scrollTop=23;
  g.toggleFit(); assert.equal(g.factor, 1); assert.equal(f.target.style.width, '100%');
  g.toggleFit(); assert.equal(g.factor, 2); assert.equal(f.viewport.scrollLeft, 45); assert.equal(f.viewport.scrollTop, 23);
  g.fit(); assert.equal(g.factor, 1); assert.equal(f.viewport.scrollTop, 0);
  g.toggleFit(); assert.equal(g.factor, 2);
});

test('control loss, reconnect, cancellation and pointer loss fence pending gestures', () => {
  for (const action of ['lost','generation','epoch','display','cancel','pointer']) {
    const f = setup(), {gestures:g, point:p} = f, old = f.get();
    g.down(p(1,40,50)); g.down(p(2,90,50));
    g.move(p(1,40,20)); g.move(p(2,90,20));
    if (action === 'lost') f.set(null);
    else if (action === 'cancel') g.cancel();
    else if (action === 'pointer') g.lose(1);
    else f.set({...old, [action]: action === 'display' ? {} : 2});
    g.up(p(1,40,20)); g.up(p(2,90,20));
    assert.equal(f.events.length, 0, action);
    assert.equal(f.taps.length, 0, action);
  }
});

test('watchers can zoom but never tap or scroll', () => {
  const f = setup(), {gestures:g, point:p} = f; f.set(null);
  assert.equal(f.scroll.setEnabled(true), false);
  g.down(p(1,40,50)); g.up(p(1,40,50));
  g.down(p(1,50,50)); g.down(p(2,100,50));
  g.move(p(1,30,50)); g.move(p(2,120,50));
  assert.ok(g.factor > 1);
  g.up(p(1,30,50)); g.up(p(2,120,50));
  assert.deepEqual(f.events, []); assert.deepEqual(f.taps, []);
});
