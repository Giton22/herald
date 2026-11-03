import test from 'node:test';
import assert from 'node:assert/strict';
import { RpcClient, ScreenState, gatewaySocketUrl, displaySocketUrl } from './client.js';

class FakeSocket {
  readyState = 1; listeners = {}; sent = [];
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  emit(type, data = {}) { for (const fn of this.listeners[type] || []) fn(data); }
  send(frame) { this.sent.push(JSON.parse(frame)); }
  close() { this.readyState = 3; this.emit('close'); }
  message(value) { this.emit('message', { data: JSON.stringify(value) }); }
}
const publicLease = (epoch, holder = 'agent', viewer_hash = null) => ({ epoch, holder, viewer_hash });

test('accepts actual gateway.ready event envelope and dispatches event', () => {
  const socket = new FakeSocket(), rpc = new RpcClient(socket); let event;
  rpc.onEvent(msg => { event = msg; });
  socket.message({ jsonrpc: '2.0', method: 'event', params: { type: 'gateway.ready', payload: { replay_epoch: 'x' } } });
  assert.equal(rpc.ready, true); assert.equal(event.params.payload.replay_epoch, 'x');
});

test('ownership checks are synchronous; unresolved hash promise never grants input', async () => {
  const state = new ScreenState(); const generation = state.beginConnection();
  await state.prepareViewer('viewer-secret');
  state.observe({ viewer_id: 'viewer-secret', lease: publicLease(7, 'human', state.viewerHash) }, generation);
  assert.equal(state.reconcile(publicLease(7, 'human', state.viewerHash), generation), true);
  assert.equal(state.reconcile(publicLease(7, 'human', Promise.resolve(state.viewerHash)), generation), false);
  assert.equal(typeof state.applyLease(publicLease(7, 'human', state.viewerHash), generation), 'boolean');
});

test('older epochs cannot override a newer lease', async () => {
  const state = new ScreenState(); const gen = state.beginConnection(); await state.prepareViewer('v');
  state.observe({ lease: publicLease(10, 'human', state.viewerHash) }, gen);
  assert.equal(state.applyLease(publicLease(9), gen), false);
  assert.equal(state.epoch, 10); assert.equal(state.owned, true);
});

test('socket close rejects and clears pending RPC requests', async () => {
  const socket = new FakeSocket(), rpc = new RpcClient(socket);
  const pending = rpc.request('display.observe'); assert.equal(rpc.pending.size, 1);
  socket.close(); await assert.rejects(pending, /Connection closed/);
  assert.equal(rpc.pending.size, 0); assert.equal(rpc.closed, true);
});

test('disconnect invalidates in-flight operations and old generations across reconnect', async () => {
  const state = new ScreenState(); const old = state.beginConnection();
  await state.prepareViewer('viewer'); state.observe({ lease: publicLease(3, 'human', state.viewerHash) }, old);
  const next = state.beginConnection();
  assert.equal(state.connected, false); assert.equal(state.owned, false);
  assert.equal(state.reconcile(publicLease(4, 'human', state.viewerHash), old), false);
  await state.prepareViewer('viewer');
  assert.equal(state.observe({ lease: publicLease(2, 'human', state.viewerHash) }, old), false);
  assert.equal(state.observe({ lease: publicLease(2, 'agent') }, next), false);
});

test('URLs stay same-origin and preserve URL encoding', () => {
  assert.equal(gatewaySocketUrl({ protocol: 'https:', host: 'screen.example' }), 'wss://screen.example/api/ws');
  assert.equal(displaySocketUrl({ protocol: 'http:', host: 'localhost:8080' }, '/api/display/ws', 'a b&c'), 'ws://localhost:8080/api/display/ws?display_ticket=a%20b%26c');
});

test('installed plugin uses its relay path while standalone viewer is unchanged', () => {
  assert.equal(gatewaySocketUrl({ protocol: 'http:', host: 'gateway:8765', pathname: '/api/plugins/herald-screen/index.html' }), 'ws://gateway:8765/api/plugins/herald-screen/rpc');
  assert.equal(gatewaySocketUrl({ protocol: 'https:', host: 'gateway', pathname: '/p/default/api/plugins/herald-screen/index.html' }), 'wss://gateway/p/default/api/plugins/herald-screen/rpc');
  assert.equal(gatewaySocketUrl({ protocol: 'https:', host: 'gateway', pathname: '/other/index.html' }), 'wss://gateway/api/ws');
});
