import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { hashViewerId } from './viewer_hash.js';
import { NativeScreenHost } from './native_host.js';

test('viewer identity matches gateway SHA256 in HTTP without WebCrypto', async () => {
  for (const value of ['', 'viewer', 'é 😀 中文', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), 'v'.repeat(512)]) {
    const expected = createHash('sha256').update(value).digest('hex').slice(0, 12);
    assert.equal(await hashViewerId(value, null), expected);
  }
});
test('native host is idle until explicit operation and correlates bounded replies', async () => {
  const sent = [], port = { postMessage: text => sent.push(JSON.parse(text)) };
  const host = new NativeScreenHost(port, 50);
  assert.deepEqual(sent, []);
  const read = host.readText();
  assert.equal(sent[0].operation, 'clipboard.readText');
  port.onmessage({ data: JSON.stringify({ id: sent[0].id, ok: true, text: 'synthetic phone text' }) });
  assert.equal(await read, 'synthetic phone text');
  const write = host.writeText('synthetic desktop text');
  assert.equal(sent[1].text, 'synthetic desktop text');
  port.onmessage({ data: JSON.stringify({ id: sent[1].id, ok: true }) });
  await write;
  host.dispose();
});
test('native host rejects malformed, oversize, unknown and post-close operations', async () => {
  const sent = [], port = { postMessage: text => sent.push(JSON.parse(text)) };
  const host = new NativeScreenHost(port, 50);
  await assert.rejects(host.writeText('é'.repeat(32769)), /too large/);
  assert.equal(sent.length, 0);
  const read = host.readText();
  port.onmessage({ data: 'not JSON' });
  port.onmessage({ data: JSON.stringify({ id: 'unrelated', ok: true, text: 'ignored' }) });
  port.onmessage({ data: JSON.stringify({ id: sent[0].id, ok: true, text: 'é'.repeat(32769) }) });
  await assert.rejects(read, /too large/);
  const pending = host.readText();
  host.dispose();
  await assert.rejects(pending, /closed/);
  await assert.rejects(host.readText(), /closed/);
});
test('native failure, timeout and cancellation cannot report clipboard success', async () => {
  const sent = [], port = { postMessage: text => sent.push(JSON.parse(text)) };
  const host = new NativeScreenHost(port, 15);
  const denied = host.readText();
  port.onmessage({ data: JSON.stringify({ id: sent[0].id, ok: false, error: 'Clipboard access denied.' }) });
  await assert.rejects(denied, /denied/);
  await assert.rejects(host.readText(), /timed out/);
  const cancelled = host.writeText('test');
  host.cancelPending();
  await assert.rejects(cancelled, /cancelled/);
  host.dispose();
});
