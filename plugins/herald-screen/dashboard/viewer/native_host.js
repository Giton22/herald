// The app injects this origin-scoped port only into its trusted Screen page.
// Creating the adapter never reads or writes either clipboard.
const tooLarge = text => typeof text !== 'string' || new TextEncoder().encode(text).byteLength > 65536;
export class NativeScreenHost {
  constructor(port, timeoutMs = 6000) {
    this.port = port; this.timeoutMs = timeoutMs; this.nextId = 1;
    this.pending = new Map(); this.closed = false;
    this.receiver = event => this.receive(event);
    port.onmessage = this.receiver;
  }
  receive(event) {
    let value;
    try { value = JSON.parse(event.data); } catch { return; }
    if (!value || typeof value.id !== 'string' || !this.pending.has(value.id)) return;
    const entry = this.pending.get(value.id);
    this.pending.delete(value.id); clearTimeout(entry.timer);
    if (value.ok !== true) { entry.reject(Error('Native clipboard or Close request denied.')); return; }
    if (entry.operation === 'clipboard.readText') {
      if (tooLarge(value.text)) entry.reject(Error('Clipboard text is invalid or too large.'));
      else entry.resolve(value.text);
    } else entry.resolve();
  }
  request(operation, text) {
    if (this.closed) return Promise.reject(Error('Screen host is closed.'));
    if (this.pending.size) return Promise.reject(Error('A native operation is already in progress.'));
    if (operation === 'clipboard.writeText' && tooLarge(text)) return Promise.reject(Error('Clipboard text is invalid or too large.'));
    const id = `screen-${this.nextId++}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id); reject(Error('Native screen request timed out.'));
      }, this.timeoutMs);
      this.pending.set(id, { operation, resolve, reject, timer });
      try { this.port.postMessage(JSON.stringify({ id, operation, ...(text === undefined ? {} : { text }) })); }
      catch { clearTimeout(timer); this.pending.delete(id); reject(Error('Native screen host unavailable.')); }
    });
  }
  readText() { return this.request('clipboard.readText'); }
  writeText(text) { return this.request('clipboard.writeText', text); }
  close() { return this.request('viewer.close'); }
  cancelPending(error = Error('Screen operation cancelled.')) {
    for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(error); }
    this.pending.clear();
  }
  dispose() {
    this.closed = true;
    this.cancelPending(Error('Screen host is closed.'));
    if (this.port.onmessage === this.receiver) this.port.onmessage = null;
  }
}
export function nativeScreenHost(target = globalThis) {
  const port = target.heraldScreenHost;
  return port && typeof port.postMessage === 'function' ? new NativeScreenHost(port) : null;
}
