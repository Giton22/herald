import test from 'node:test';
import assert from 'node:assert/strict';
import { RpcClient } from './client.js';
import { observeScreen } from './open_screen.js';

const state = running => ({running, placement:'gateway'});
const ticket = {...state(true), viewer_id:'v', ticket:'secret', path:'/display'};
class Socket {
  readyState = 1; events = {}; calls = [];
  addEventListener(type, fn) { (this.events[type] ||= []).push(fn); }
  emit(type, event = {}) { for (const fn of this.events[type] || []) fn(event); }
  send(frame) { const request = JSON.parse(frame); this.calls.push(request); this.handler(request); }
  reply(request, result) { this.emit('message', {data:JSON.stringify({id:request.id, result})}); }
  close() { this.readyState = 3; this.emit('close'); }
}
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const opts = {explicit:true, current:() => true, viewerId:''};

test('start over 10 seconds and event before reply retain the long deadline', async t => {
  t.mock.timers.enable({apis:['setTimeout']});
  const socket = new Socket(), rpc = new RpcClient(socket); let start;
  socket.handler = req => { if (req.method === 'display.status') socket.reply(req, state(false)); else if (req.method === 'display.start') start = req; else socket.reply(req, ticket); };
  const result = observeScreen(rpc, opts); await flush();
  t.mock.timers.tick(15000); await flush();
  assert.equal(rpc.pending.size, 1);
  socket.emit('message', {data:JSON.stringify({method:'event', params:{type:'display.status', payload:state(true)}})});
  socket.reply(start, state(true)); await flush();
  assert.deepEqual(await result, ticket);
  assert.deepEqual(socket.calls.map(x => x.method), ['display.status','display.start','display.observe']);
});

test('per-request timeout preserves default for other requests', async t => {
  t.mock.timers.enable({apis:['setTimeout']});
  const s = new Socket(), rpc = new RpcClient(s); s.handler = () => {};
  const normal = rpc.request('normal'), long = rpc.request('long', {}, {timeoutMs:90000});
  const normalRejected = assert.rejects(normal, e => e.code === 'RPC_TIMEOUT');
  t.mock.timers.tick(10000); await normalRejected;
  assert.equal(rpc.pending.size, 1); s.reply(s.calls[1], 42); assert.equal(await long, 42);
});

for (const outcome of ['running','stopped','status-fail','lost','rejected']) test(`ambiguous start: ${outcome}`, async t => {
  t.mock.timers.enable({apis:['setTimeout']});
  const s = new Socket(), rpc = new RpcClient(s); let statuses = 0;
  s.handler = req => {
    if (req.method === 'display.status') {
      statuses++;
      if (statuses === 1) s.reply(req, state(false));
      else if (outcome === 'status-fail') s.emit('message', {data:JSON.stringify({id:req.id, error:{message:'secret-status'}})});
      else s.reply(req, state(outcome === 'running'));
    } else if (req.method === 'display.start' && outcome === 'rejected') {
      s.emit('message', {data:JSON.stringify({id:req.id, error:{message:'secret-server'}})});
    } else if (req.method === 'display.observe') s.reply(req, ticket);
  };
  const result = observeScreen(rpc, opts);
  const captured = result.then(value => ({value}), error => ({error}));
  await flush();
  if (outcome === 'lost') s.close();
  else if (outcome !== 'rejected') t.mock.timers.tick(90000);
  await flush();
  if (!['running','lost'].includes(outcome)) { t.mock.timers.tick(1000); await flush(); t.mock.timers.tick(1000); await flush(); }
  const settled = await captured;
  if (outcome === 'running') assert.deepEqual(settled.value, ticket);
  else {
    assert.match(settled.error.message, /unknown/);
    assert.doesNotMatch(settled.error.message, /secret/);
    if (outcome === 'stopped' || outcome === 'status-fail') assert.match(settled.error.message, /timed out/);
    if (outcome === 'rejected') assert.match(settled.error.message, /could not be confirmed/);
    if (outcome === 'lost') assert.match(settled.error.message, /disconnect/);
  }
  assert.equal(s.calls.filter(r => r.method === 'display.start').length, 1);
  assert.equal(s.calls.filter(r => r.method === 'display.observe').length, outcome === 'running' ? 1 : 0);
});
