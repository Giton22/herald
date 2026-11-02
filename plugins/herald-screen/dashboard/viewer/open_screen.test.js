import test from 'node:test';
import assert from 'node:assert/strict';
import { observeScreen, ScreenOpenError, clipboardControls } from './open_screen.js';

const state = (running, placement = 'gateway') => ({running, placement});
const ticket = {running: true, placement: 'gateway', viewer_id: 'viewer', ticket: 'one-use', path: '/api/display/ws'};
function client(responses) {
  return {calls: [], closed: false, async request(method, params) {
    this.calls.push([method, params]);
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return await response;
  }};
}
const opts = (overrides = {}) => ({explicit: true, current: () => true, viewerId: '', ...overrides});
function deferred() { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; }

test('cold explicit open starts once before fetching a fresh observation ticket', async () => {
  const c = client([state(false), state(true), ticket]); let starts = 0;
  assert.equal(await observeScreen(c, opts({starting: () => starts++})), ticket);
  assert.deepEqual(c.calls, [['display.status', undefined], ['display.start', {}], ['display.observe', {}]]);
  assert.equal(starts, 1);
});
test('running open and retry attach without starting or reusing viewer identity', async () => {
  for (const explicit of [true, false]) {
    const c = client([state(true), ticket]);
    await observeScreen(c, opts({explicit, viewerId: 'existing'}));
    assert.deepEqual(c.calls, [['display.status', undefined], ['display.observe', {}]]);
  }
});
test('background reconnect to a stopped desktop never starts or observes', async () => {
  const c = client([state(false)]);
  await assert.rejects(observeScreen(c, opts({explicit: false})), /stopped/);
  assert.deepEqual(c.calls.map(c => c[0]), ['display.status']);
});
test('failed, timed out, or unconfirmed start does not observe or repeat', async () => {
  for (const failure of [Error('RPC timeout'), Error('closed'), state(false), {running: 'yes', placement:'gateway'}]) {
    const c = client([state(false), failure]);
    await assert.rejects(observeScreen(c, opts()), ScreenOpenError);
    assert.deepEqual(c.calls.map(c => c[0]), failure instanceof Error ? ['display.status', 'display.start', 'display.status', 'display.status', 'display.status'] : ['display.status', 'display.start']);
  }
});
test('explicit retry checks status afresh after ambiguous start', async () => {
  const first = client([state(false), Error('RPC timeout')]);
  await assert.rejects(observeScreen(first, opts()), ScreenOpenError);
  const retry = client([state(true), ticket]);
  await observeScreen(retry, opts());
  assert.deepEqual(retry.calls.map(c => c[0]), ['display.status', 'display.observe']);
});
test('close or newer generation during status, start, or observe prevents subsequent actions', async () => {
  for (const stage of ['status','start','observe']) {
    const d = deferred(); let current = true;
    const responses = stage === 'status' ? [d.promise] : stage === 'start' ? [state(false), d.promise] : [state(true), d.promise];
    const c = client(responses), pending = observeScreen(c, opts({current: () => current}));
    await new Promise(resolve => setImmediate(resolve));
    current = false; d.resolve(stage === 'observe' ? ticket : state(stage !== 'status'));
    await assert.rejects(pending, /Stale connection/);
    assert.deepEqual(c.calls.map(c => c[0]), stage === 'status' ? ['display.status'] : stage === 'start' ? ['display.status','display.start'] : ['display.status','display.observe']);
  }
});
test('invalid status and closed connection cannot trigger start', async () => {
  for (const s of [{running: false, placement: 'unknown'}, {placement:'gateway'}, null]) {
    const c = client([s]); await assert.rejects(observeScreen(c, opts()), ScreenOpenError);
    assert.equal(c.calls.length, 1);
  }
  const c = client([]); c.closed = true;
  await assert.rejects(observeScreen(c, opts()), /Stale/); assert.equal(c.calls.length, 0);
});
test('local protected copy and lease-only paste; sandbox copy disabled with explanation', () => {
  const clipboard = {writeText(){}, readText(){}};
  assert.deepEqual(clipboardControls('gateway', true, false, clipboard), {sandbox:false, copyDisabled:false, pasteDisabled:false});
  for (const placement of ['terminal:docker','terminal:ssh']) {
    assert.deepEqual(clipboardControls(placement, true, false, clipboard), {sandbox:true, copyDisabled:true, pasteDisabled:false});
    assert.equal(clipboardControls(placement, false, false, clipboard).pasteDisabled, true);
  }
  assert.equal(clipboardControls('gateway', false, false, clipboard).copyDisabled, true);
  assert.equal(clipboardControls('gateway', true, true, clipboard).copyDisabled, true);
  assert.equal(clipboardControls('gateway', true, true, clipboard).pasteDisabled, true);
});
