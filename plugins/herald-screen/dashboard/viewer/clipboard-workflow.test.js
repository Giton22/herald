import test from 'node:test';
import assert from 'node:assert/strict';
import { ClipboardBridge } from './input.js';

function fixture(clipboard, options = {}) {
  const calls = [], writes = [];
  let selected = 'selected word', session, bridge;
  const display = {
    clipboardPasteAndWait: async text => { calls.push(['payload', text]); },
    sendKey(...args) {
      calls.push(['key', ...args]);

    },
  };
  session = { display, generation: 1, epoch: 1 };
  bridge = new ClipboardBridge(() => session,
    clipboard || { writeText: async text => writes.push(text) },
    { ClipboardItemType: null, readSelection: async current => {
      calls.push(['selection', current.epoch]);
      return selected;
    }, ...options });
  return { display, bridge, calls, writes, getSession: () => session,
    setSession: value => { session = value; }, setSelected: value => { selected = value; } };
}

test('Copy reads selected text through the protected backend, never RFB shortcuts or cache', async () => {
  const state = fixture();

  await state.bridge.copy();
  assert.deepEqual(state.writes, ['selected word']);
  assert.deepEqual(state.calls, [['selection', 1]]);
  assert.equal(state.bridge.busy, false);
});

test('Copy preserves selection and never writes an empty desktop clipboard first', async () => {
  const state = fixture(null, { timeoutMs: 20 });
  state.display.clipboardPasteAndWait = async text => {
    state.calls.push(['payload', text]);
    // X11 selection ownership loss can remove the highlighted text.
    if (text === '') state.setSelected('');
  };
  await state.bridge.copy();
  assert.deepEqual(state.writes, ['selected word']);
  assert.equal(state.calls.some(([kind]) => kind === 'payload'), false);
});

test('Copying the same selection twice never changes clipboard ownership', async () => {
  const state = fixture();
  await state.bridge.copy();
  await state.bridge.copy();
  assert.deepEqual(state.writes, ['selected word', 'selected word']);
  assert.equal(state.calls.filter(([kind]) => kind === 'payload').length, 0);
});

test('unchanged Unicode selection is read explicitly each time and whitespace is preserved', async () => {
  const state = fixture();
  state.setSelected('  café 🐾\n');
  await state.bridge.copy();
  await state.bridge.copy();
  assert.deepEqual(state.writes, ['  café 🐾\n', '  café 🐾\n']);
  assert.deepEqual(state.calls, [['selection', 1], ['selection', 1]]);
});

test('control loss during protected read cannot write its response to phone', async () => {
  const state = fixture();
  state.bridge.readSelection = async () => {
    state.setSession(null);
    return 'late selection';
  };
  await assert.rejects(state.bridge.copy(), /Control changed/);
  assert.deepEqual(state.writes, []);
});

test('promise-backed ClipboardItem write starts during tap, before remote clipboard work', async () => {
  const writes = [];
  class Item { constructor(data) { this.data = data; } }
  let started = false;
  const state = fixture({ write: async ([item]) => {
    started = true;
    const blob = await item.data['text/plain'];
    writes.push(await blob.text());
  } }, { ClipboardItemType: Item });
  const pending = state.bridge.copy();
  assert.equal(started, true);
  assert.deepEqual(state.calls, []);
  await pending;
  assert.deepEqual(writes, ['selected word']);
});

test('missing protected backend response times out without touching phone clipboard', async () => {
  const state = fixture(null, { timeoutMs: 20, readSelection: () => new Promise(() => {}) });
  await assert.rejects(state.bridge.copy(), /timed out/);
  assert.deepEqual(state.writes, []);
  assert.equal(state.bridge.busy, false);
});

test('Copy does not depend on or invoke the Paste transport API', async () => {
  const state = fixture();
  state.display.clipboardPasteAndWait = async () => { throw Error('Transport rejected'); };
  await state.bridge.copy();
  assert.equal(state.calls.some(([kind]) => kind === 'payload'), false);
  assert.deepEqual(state.writes, ['selected word']);
});

test('control change during clipboard send prevents subsequent Paste shortcut', async () => {
  const state = fixture({ readText: async () => 'phone' });
  state.display.clipboardPasteAndWait = async text => {
    state.calls.push(['payload', text]);
    state.setSession({ ...state.getSession(), epoch: 2 });
  };
  await assert.rejects(state.bridge.paste(), /Control changed/);
  assert.deepEqual(state.calls, [['payload', 'phone']]);
});

test('transport Unicode validation failure is reported without sending Paste', async () => {
  const state = fixture({ readText: async () => '🐾' });
  state.display.clipboardPasteAndWait = async () => { throw Error('Unicode not negotiated'); };
  await assert.rejects(state.bridge.paste(), /Unicode not negotiated/);
  assert.deepEqual(state.calls, []);
});

test('overlapping operations are rejected and cannot race clipboard state', async () => {
  let read;
  const state = fixture({ readText: () => new Promise(resolve => { read = resolve; }) });
  const pending = state.bridge.paste();
  assert.equal(state.bridge.busy, true);
  await assert.rejects(state.bridge.copy(), /already in progress/);
  read('phone');
  await pending;
  assert.equal(state.bridge.busy, false);
});

test('empty selection and UTF8 byte overflow are rejected without clipboard writes', async () => {
  for (const selected of ['', '🐾'.repeat(20000)]) {
    const state = fixture();
    state.setSelected(selected);
    await assert.rejects(state.bridge.copy(), /selection|too large/);
    assert.deepEqual(state.writes, []);
    assert.equal(state.calls.some(([kind]) => kind === 'key' || kind === 'payload'), false);
  }
});
