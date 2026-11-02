import test from 'node:test';
import assert from 'node:assert/strict';
import { gatewayProtocols } from './client.js';

test('requests authenticated single-use websocket ticket and uses documented protocols', async () => {
  const protocols = await gatewayProtocols(async (path, options) => {
    assert.equal(path, '/api/auth/ws-ticket');
    assert.equal(options.method, 'POST');
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.cache, 'no-store');
    return { ok: true, json: async () => ({ ticket: 'fixture-only' }) };
  });
  assert.deepEqual(protocols, ['hermes-gateway-v1', 'hermes-gateway-ticket.fixture-only']);
});

test('rejected authentication cannot open an unticketed websocket', async () => {
  await assert.rejects(gatewayProtocols(async () => ({ ok: false, status: 401 })), /session not accepted/);
});

test('missing ticket cannot open a websocket', async () => {
  await assert.rejects(gatewayProtocols(async () => ({ ok: true, json: async () => ({}) })), /no connection ticket/);
});
