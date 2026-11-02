import test from 'node:test';
import assert from 'node:assert/strict';
import { TextKeyboard, ClipboardBridge } from './input.js';

function fixture(clipboard = {}) {
  const calls = [];
  const display = {
    sendKey: (...args) => calls.push(['key', ...args]),
    clipboardPasteAndWait: async text => { calls.push(['clipboard', text]); },
  };
  let session = { display, generation: 1, epoch: 2 };
  return { calls, display, getSession: () => session, setSession: value => { session = value; },
    bridge: new ClipboardBridge(() => session, clipboard) };
}

test('keyboard sends text edits, backspace and autocorrection through public key API', () => {
  const state = fixture(), keyboard = new TextKeyboard(state.getSession);
  const guard = keyboard.value;
  keyboard.accept(guard + 'hi');
  assert.deepEqual(state.calls, [['key', 104], ['key', 105]]);
  state.calls.length = 0;
  keyboard.accept(guard + 'ho');
  assert.deepEqual(state.calls, [['key', 0xff08, 'Backspace'], ['key', 111]]);
  state.calls.length = 0;
  keyboard.accept(guard + 'h');
  assert.deepEqual(state.calls, [['key', 0xff08, 'Backspace']]);
});

test('keyboard sends a Unicode codepoint once rather than surrogate halves', () => {
  const state = fixture(), keyboard = new TextKeyboard(state.getSession);
  keyboard.accept(keyboard.value + '🐾');
  assert.deepEqual(state.calls, [['key', 0x01000000 | '🐾'.codePointAt(0)]]);
});

test('live composing edits arrive before Space, and final commit is not duplicated', () => {
  const state = fixture(), keyboard = new TextKeyboard(state.getSession);
  const guard = keyboard.value;
  keyboard.accept(guard + 'h', true);
  assert.deepEqual(state.calls, [['key', 104]]);
  keyboard.accept(guard + 'hi', true);
  keyboard.accept(guard + 'hi', false);
  assert.deepEqual(state.calls, [['key', 104], ['key', 105]]);
});

test('composition buffer is not reset until native composition commits', () => {
  const state = fixture(), keyboard = new TextKeyboard(state.getSession);
  const longValue = keyboard.value + 'a'.repeat(1001);
  assert.equal(keyboard.accept(longValue, true), longValue);
  const count = state.calls.length;
  assert.notEqual(keyboard.accept(longValue, false), longValue);
  assert.equal(state.calls.length, count);
});

test('keyboard special keys and input newlines preserve key semantics', () => {
  const state = fixture(), keyboard = new TextKeyboard(state.getSession);
  assert.equal(keyboard.special('Enter'), true);
  assert.equal(keyboard.special('Unrecognized'), false);
  keyboard.accept(keyboard.value + '\t\n');
  assert.deepEqual(state.calls, [['key', 0xff0d, 'Enter'], ['key', 0xff09, 'Tab'], ['key', 0xff0d, 'Enter']]);
});

test('watchers cannot send keys or clipboard and ownership is rechecked during text edits', async () => {
  const state = fixture(), keyboard = new TextKeyboard(state.getSession);
  state.setSession(null);
  keyboard.accept(keyboard.value + 'test');
  assert.equal(keyboard.special('Enter'), false);
  await assert.rejects(state.bridge.paste(), /Take control/);
  await assert.rejects(state.bridge.copy(), /Take control/);
  assert.deepEqual(state.calls, []);
  let reads = 0;
  const owned = { display: state.display, generation: 1, epoch: 2 };
  const transient = new TextKeyboard(() => ++reads < 3 ? owned : null);
  transient.accept(transient.value + 'abc');
  assert.deepEqual(state.calls, [['key', 97]]);
});

test('protected selection is never read or written automatically', () => {
  const writes = [], state = fixture({ writeText: async text => writes.push(text) });
  state.bridge.readSelection = () => assert.fail('Automatic selection read');
  assert.deepEqual(writes, []);
});

test('selection copy cancels when display, generation or lease epoch changes', async () => {
  for (const changed of ['display', 'generation', 'epoch']) {
    const state = fixture({ writeText: async () => assert.fail('stale clipboard written') });
    let answer;
    state.bridge.readSelection = () => new Promise(resolve => { answer = resolve; });
    const pending = state.bridge.copy();
    await new Promise(setImmediate);
    state.setSession({ ...state.getSession(), [changed]: changed === 'display' ? {} : 9 });
    answer('stale selection');
    state.bridge.reset();
    await assert.rejects(pending, /Control changed/);
  }
});

test('explicit paste queues clipboard payload before sending Ctrl+V, never Enter', async () => {
  let reads = 0;
  const state = fixture({ readText: async () => { reads++; return 'phone text'; } });
  let delivered;
  state.display.clipboardPasteAndWait = text => {
    state.calls.push(['clipboard', text]);
    return new Promise(resolve => { delivered = resolve; });
  };
  assert.equal(reads, 0);
  const pending = state.bridge.paste();
  await new Promise(setImmediate);
  assert.deepEqual(state.calls, [['clipboard', 'phone text']]);
  delivered();
  await pending;
  assert.equal(reads, 1);
  assert.deepEqual(state.calls, [['clipboard', 'phone text'], ['key', 0xffe3, 'ControlLeft', true], ['key', 0x76, 'KeyV'], ['key', 0xffe3, 'ControlLeft', false]]);
});

test('clipboard permission wait cannot paste into a lost or replacement control session', async () => {
  for (const changed of ['lost', 'display', 'generation', 'epoch']) {
    let resolve;
    const state = fixture({ readText: () => new Promise(done => { resolve = done; }) });
    const pending = state.bridge.paste();
    state.setSession(changed === 'lost' ? null : { ...state.getSession(), [changed]: changed === 'display' ? {} : 10 });
    resolve('pending text');
    await assert.rejects(pending, /Control changed/);
    assert.deepEqual(state.calls, []);
  }
});

test('clipboard refuses oversized payloads', async () => {
  const state = fixture({ readText: async () => 'x'.repeat(65537) });
  await assert.rejects(state.bridge.paste(), /too large/);
  assert.deepEqual(state.calls, []);
});

test('missing or denied browser clipboard access reports an error, not success', async () => {
  const absent = fixture();
  await assert.rejects(absent.bridge.paste(), /unavailable/);
  await assert.rejects(absent.bridge.copy(), /unavailable/);
  const denied = fixture({ readText: async () => { throw Error('Permission denied'); } });
  await assert.rejects(denied.bridge.paste(), /Permission denied/);
  assert.deepEqual(denied.calls, []);
});
