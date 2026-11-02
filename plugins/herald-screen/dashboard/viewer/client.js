import { hashViewerId } from './viewer_hash.js';

export class RpcClient {
  constructor(socket, timeoutMs = 10000) {
    this.socket = socket; this.timeoutMs = timeoutMs; this.nextId = 1;
    this.pending = new Map(); this.events = new Set(); this.ready = false; this.closed = false;
    socket.addEventListener('message', e => this.receive(e));
    socket.addEventListener('close', () => this.closeAll(Error('Connection closed')));
    socket.addEventListener('error', () => this.closeAll(Error('Connection error')));
  }
  receive(e) {
    let m; try { m = JSON.parse(e.data); } catch { return; }
    if (m.method === 'event' && m.params?.type === 'gateway.ready') this.ready = true;
    if (m.method === 'event') for (const fn of this.events) fn(m);
    if (m.id !== undefined && this.pending.has(m.id)) {
      const p = this.pending.get(m.id); clearTimeout(p.timer); this.pending.delete(m.id);
      m.error ? p.reject(Error(m.error.message || 'RPC failed')) : p.resolve(m.result);
    }
  }
  onEvent(fn) { this.events.add(fn); return () => this.events.delete(fn); }
  request(method, params = {}, { timeoutMs = this.timeoutMs } = {}) {
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) return Promise.reject(Error('Invalid RPC timeout'));
    if (this.closed || this.socket.readyState !== 1) return Promise.reject(Error('Not connected'));
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); const error = Error('RPC timeout'); error.code = 'RPC_TIMEOUT'; reject(error); }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.socket.send(JSON.stringify({ jsonrpc: '2.0', id, method, params })); }
      catch (e) { clearTimeout(timer); this.pending.delete(id); reject(e); }
    });
  }
  closeAll(error = Error('Connection closed')) {
    if (this.closed) return;
    this.closed = true;
    for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(error); }
    this.pending.clear();
    for (const fn of this.events) fn({ type: 'rpc.closed', error });
  }
}

export class ScreenState {
  constructor(viewerId = '') {
    this.viewerId = viewerId; this.viewerHash = null; this.owned = false;
    this.connected = false; this.epoch = null; this.generation = 0;
  }
  async prepareViewer(viewerId) {
    const generation = this.generation;
    this.viewerId = viewerId || ''; this.viewerHash = null;
    if (!this.viewerId) return;
    const digest = await hashViewerId(this.viewerId);
    if (generation !== this.generation) return;
    this.viewerHash = digest;
  }
  disconnected() { this.generation++; this.connected = false; this.owned = false; this.epoch = null; }
  beginConnection() { this.disconnected(); return this.generation; }
  observe(result, generation = this.generation) {
    if (generation !== this.generation || !Number.isInteger(result?.lease?.epoch)) return false;
    this.connected = true; this.epoch = result.lease.epoch; this.owned = false;
    return this.applyLease(result.lease, generation);
  }
  applyLease(lease, generation = this.generation) {
    if (generation !== this.generation || !this.connected || !Number.isInteger(lease?.epoch)) return false;
    if (this.epoch !== null && lease.epoch < this.epoch) return false;
    this.epoch = lease.epoch;
    this.owned = lease.holder === 'human' && !!this.viewerHash && lease.viewer_hash === this.viewerHash;
    return this.owned;
  }
  reconcile(lease, generation = this.generation) { return this.applyLease(lease, generation); }
}
export function handoffButtonState(owned, connected) {
  const controls = owned === true, ready = connected === true;
  return {
    take: { hidden: controls, disabled: !ready || controls },
    back: { hidden: !controls, disabled: !ready || !controls },
  };
}
export function viewModeState(nativeSize, fitOnly = false) {
  const zoom = nativeSize === true && !fitOnly;
  return { zoom, scaleViewport: !zoom, fitHidden: !zoom, zoomHidden: fitOnly || zoom };
}
export function gatewaySocketUrl(location) {
  const page = location.pathname || '';
  const suffix = '/api/plugins/herald-screen/index.html';
  const path = page.endsWith(suffix) ? page.slice(0, -'index.html'.length) + 'rpc' : '/api/ws';
  return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}`;
}
export async function gatewayProtocols(fetcher = fetch) {
  const response = await fetcher('/api/auth/ws-ticket', { method: 'POST', credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw Error(response.status === 401 || response.status === 403 ? 'Hermes session not accepted at viewer origin' : 'Gateway ticket request failed');
  const result = await response.json();
  if (typeof result.ticket !== 'string' || !result.ticket) throw Error('Gateway returned no connection ticket');
  return ['hermes-gateway-v1', `hermes-gateway-ticket.${result.ticket}`];
}
export function displaySocketUrl(location, path, ticket) { return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}?display_ticket=${encodeURIComponent(ticket)}`; }
