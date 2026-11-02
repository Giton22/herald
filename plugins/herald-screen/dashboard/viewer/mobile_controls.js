const sameSession = (a, b) => !!a && !!b && ['display','epoch','generation'].every(k => a[k] === b[k]);

// Remote actions use the same live ownership fence as keyboard and clipboard.
export class RemoteScroll {
  constructor(session, canvas, eventFactory = options => new WheelEvent('wheel', options)) {
    this.session = session;
    this.canvas = canvas;
    this.eventFactory = eventFactory;
    this.enabled = false;
    this.gesture = null;
  }
  reset() { this.enabled = false; this.gesture = null; }
  cancel() { this.gesture = null; }
  setEnabled(enabled) {
    this.cancel();
    this.enabled = Boolean(enabled && this.session());
    return this.enabled;
  }
  scroll(delta, expected = null, point = null, gesture = false) {
    const current = this.session(), canvas = this.canvas();
    if ((!this.enabled && !gesture) || !current || !canvas || !Number.isFinite(delta) || delta === 0) return false;
    if (expected && (current.display !== expected.display || current.epoch !== expected.epoch || current.generation !== expected.generation)) return false;
    const rect = canvas.getBoundingClientRect();
    // noVNC clamps each wheel event to one VNC step (50px threshold).
    const steps = Math.min(12, Math.max(1, Math.round(Math.abs(delta))));
    let sent = false;
    for (let i = 0; i < steps; i++) {
      if (!sameSession(this.session(), current) || this.canvas() !== canvas) break;
      canvas.dispatchEvent(this.eventFactory({
        deltaX: 0, deltaY: Math.sign(delta) * 3, deltaMode: 1, bubbles: true, cancelable: true,
        clientX: point?.x ?? rect.left + rect.width / 2,
        clientY: point?.y ?? rect.top + rect.height / 2,
      }));
      sent = true;
    }
    return sent;
  }
  down(event) {
    const session = this.session();
    if (!this.enabled || !session || event.isPrimary === false) return false;
    this.gesture = { session, id: event.pointerId, x: event.clientX, y: event.clientY };
    event.preventDefault();
    event.stopPropagation();
    return true;
  }
  up(event) {
    const gesture = this.gesture;
    if (!gesture || gesture.id !== event.pointerId) return false;
    this.cancel();
    event.preventDefault();
    event.stopPropagation();
    const delta = gesture.y - event.clientY;
    if (Math.abs(delta) <= 12) return false;
    return this.scroll(Math.sign(delta) * Math.max(1, Math.round(Math.abs(delta) / 40)), gesture.session, gesture);
  }
}

export function gestureKind(a, b, sa, sb) {
  const spread = Math.abs(Math.hypot(a.x-b.x,a.y-b.y)-Math.hypot(sa.x-sb.x,sa.y-sb.y));
  const shift = Math.hypot((a.x+b.x-sa.x-sb.x)/2,(a.y+b.y-sa.y-sb.y)/2);
  if (spread >= 14 && spread > shift * 0.8) return 'pinch';
  const dot = (a.x-sa.x)*(b.x-sb.x)+(a.y-sa.y)*(b.y-sb.y);
  if (dot > 0 && shift >= 18 && spread < 14) return 'scroll';
  return null;
}

export function viewerTap(session, canvas, x, y, eventFactory = (type, options) => new MouseEvent(type, options)) {
  const expected = session();
  if (!expected || !canvas) return false;
  const inside = () => { const r = canvas.getBoundingClientRect(); return x >= r.left && x < r.right && y >= r.top && y < r.bottom; };
  if (!inside()) return false;
  canvas.focus({ preventScroll: true });
  const current = session();
  if (!current || !['display','epoch','generation'].every(k => current[k] === expected[k]) || !inside()) return false;
  const options = {clientX:x,clientY:y,button:0,bubbles:true,cancelable:true};
  canvas.dispatchEvent(eventFactory('mousedown', options));
  // Complete through the normal window capture proxy, which releases noVNC's
  // mouse-capture overlay. Direct canvas mouseup leaves that overlay active.
  (canvas.ownerDocument?.defaultView || canvas).dispatchEvent(eventFactory('mouseup', options));
  return true;
}

export class ViewerGestures {
  constructor(session, scroll, viewport, target, display, tap = () => {}, mouseEvent = (type, options) => new MouseEvent(type, options)) {
    Object.assign(this, { session, scroll, viewport, target, display, tap, mouseEvent });
    this.points = new Map(); this.factor = 1; this.cancelled = false;
  }
  clearHold() { clearTimeout(this.holdTimer); this.holdTimer = null; }
  releaseDrag() {
    const drag = this.drag;
    if (!drag) return;
    this.drag = null;
    // Always unwind noVNC's capture/mask, but never send after ownership changes.
    const stale = !sameSession(this.session(), drag.session);
    const oldViewOnly = drag.session.display.viewOnly;
    if (stale) drag.session.display.viewOnly = true;
    try {
      (drag.canvas.ownerDocument?.defaultView || drag.canvas).dispatchEvent(this.mouseEvent('mouseup', {
        clientX:drag.last.x, clientY:drag.last.y, button:0, buttons:0, bubbles:true, cancelable:true,
      }));
    } finally { if (stale) drag.session.display.viewOnly = oldViewOnly; }
  }
  cancel() { this.clearHold(); this.releaseDrag(); this.cancelled = this.points.size > 0; this.gesture = null; this.scroll.cancel(); }
  beginDrag(g) {
    this.holdTimer = null;
    if (this.cancelled || this.gesture !== g || this.points.size !== 1 || g.moved || this.scroll.enabled || !sameSession(this.session(), g.session)) return;
    const canvas = this.target.querySelector?.('canvas'), p = g.start;
    if (!canvas) return;
    const inside = () => { const r = canvas.getBoundingClientRect(); return p.x >= r.left && p.x < r.right && p.y >= r.top && p.y < r.bottom; };
    if (!inside()) return;
    canvas.focus({preventScroll:true});
    if (!sameSession(this.session(), g.session) || !inside()) return;
    this.drag = {canvas, session:g.session, last:p}; g.mode = 'drag';
    canvas.dispatchEvent(this.mouseEvent('mousedown', {clientX:p.x,clientY:p.y,button:0,buttons:1,bubbles:true,cancelable:true}));
    if (!sameSession(this.session(), g.session)) this.releaseDrag();
  }
  lose(id) { this.cancel(); this.points.delete(id); if (!this.points.size) this.cancelled = false; }
  fit() { this.cancel(); this.savedView = null; this.factor = 1; this.apply(); this.viewport.scrollLeft = this.viewport.scrollTop = 0; }
  toggleFit() {
    this.cancel();
    if (this.factor > 1) {
      const saved = {factor:this.factor, x:this.viewport.scrollLeft, y:this.viewport.scrollTop};
      this.fit(); this.savedView = saved;
    } else {
      const saved = this.savedView;
      this.factor = saved?.factor ?? 2;
      this.apply();
      this.viewport.scrollLeft = saved?.x ?? this.viewport.clientWidth / 2;
      this.viewport.scrollTop = saved?.y ?? this.viewport.clientHeight / 2;
    }
  }
  apply() {
    // Scale the RFB target, never transform its canvas. noVNC updates both
    // Display.scale and absX/absY through its public scaleViewport setter.
    this.target.style.width = `${this.factor * 100}%`;
    this.target.style.height = `${this.factor * 100}%`;
    const display = this.display();
    if (display) { display.resizeSession = false; display.scaleViewport = true; }
    this.onChange?.(this.factor);
  }
  down(e) {
    e.preventDefault(); e.stopPropagation();
    const p = { x:e.clientX, y:e.clientY };
    this.points.set(e.pointerId,p);
    if (this.cancelled) return;
    if (this.points.size === 1) {
      this.gesture = {session:this.session(),start:p,last:p,moved:false,mode:null};
      const g = this.gesture;
      if (g.session && !this.scroll.enabled) this.holdTimer = setTimeout(() => this.beginDrag(g), 500);
    }
    else if (this.points.size === 2) {
      this.clearHold(); this.releaseDrag();
      const starts = [...this.points.values()].map(p=>({...p}));
      const rect = this.target.querySelector?.('canvas')?.getBoundingClientRect();
      const midpoint = {x:(starts[0].x+starts[1].x)/2,y:(starts[0].y+starts[1].y)/2};
      const anchor = rect?.width && rect?.height ? {x:(midpoint.x-rect.left)/rect.width,y:(midpoint.y-rect.top)/rect.height} : null;
      this.gesture = {session:this.session(),starts,factor:this.factor,mode:null,anchor};
    }
    else this.cancel();
  }
  move(e) {
    if (!this.points.has(e.pointerId)) return;
    e.preventDefault(); e.stopPropagation();
    this.points.set(e.pointerId,{x:e.clientX,y:e.clientY});
    const g = this.gesture;
    if (!g || this.cancelled) return;
    const points = [...this.points.values()];
    if (this.drag) {
      if (!sameSession(this.session(), this.drag.session)) { this.cancel(); return; }
      this.drag.last = points[0];
      (this.drag.canvas.ownerDocument?.defaultView || this.drag.canvas).dispatchEvent(this.mouseEvent('mousemove', {
        clientX:e.clientX, clientY:e.clientY, button:0, buttons:1, bubbles:true, cancelable:true,
      }));
      return;
    }
    if (points.length === 2) {
      const [a,b] = points, [sa,sb] = g.starts;
      // Wait for both contacts: browsers deliver their pointer moves in order.
      // Classifying the first moved finger alone mistakes parallel swipes for pinch.
      if (Math.hypot(a.x-sa.x,a.y-sa.y)>6 && Math.hypot(b.x-sb.x,b.y-sb.y)>6) g.mode ||= gestureKind(a,b,sa,sb);
      if (g.mode === 'pinch') {
        this.factor = Math.max(1,Math.min(4,g.factor*Math.hypot(a.x-b.x,a.y-b.y)/Math.max(1,Math.hypot(sa.x-sb.x,sa.y-sb.y))));
        this.apply();
        const rect = this.target.querySelector?.('canvas')?.getBoundingClientRect();
        if (g.anchor && rect?.width && rect?.height) {
          this.viewport.scrollLeft += rect.left + g.anchor.x * rect.width - (a.x+b.x)/2;
          this.viewport.scrollTop += rect.top + g.anchor.y * rect.height - (a.y+b.y)/2;
        }
      }
    } else if (points.length === 1 && g.start) {
      const p = points[0];
      if (Math.hypot(p.x-g.start.x,p.y-g.start.y)>10) { g.moved = true; this.clearHold(); }
      if (g.moved && !this.scroll.enabled) {
        this.viewport.scrollLeft -= p.x-g.last.x; this.viewport.scrollTop -= p.y-g.last.y;
      }
      g.last = p;
    }
  }
  up(e) {
    if (!this.points.has(e.pointerId)) return;
    e.preventDefault(); e.stopPropagation();
    this.clearHold();
    if (this.drag) { this.drag.last = {x:e.clientX,y:e.clientY}; this.releaseDrag(); this.points.delete(e.pointerId); this.gesture = null; this.cancelled = this.points.size > 0; return; }
    const g = this.gesture, current = this.session();
    const valid = g?.session && current && ['display','generation','epoch'].every(k=>current[k]===g.session[k]);
    if (!this.cancelled && g && valid) {
      if (g.mode === 'scroll') {
        const v = [...this.points.values()];
        const delta = (g.starts[0].y+g.starts[1].y-v[0].y-v[1].y)/2;
        this.scroll.scroll(Math.sign(delta)*Math.max(1,Math.round(Math.abs(delta)/40)),g.session,{x:(v[0].x+v[1].x)/2,y:(v[0].y+v[1].y)/2},true);
      } else if (this.points.size === 1 && g.start) {
        const delta = g.start.y-e.clientY;
        if (g.moved && this.scroll.enabled) this.scroll.scroll(Math.sign(delta)*Math.max(1,Math.round(Math.abs(delta)/40)),g.session,g.start);
        else if (!g.moved && !this.scroll.enabled) this.tap(e.clientX,e.clientY);
      }
    }
    this.points.delete(e.pointerId); this.gesture = null; this.cancelled = this.points.size > 0;
  }
}
