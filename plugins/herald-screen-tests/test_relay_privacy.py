"""Offline privacy regressions; all gateway values here are synthetic."""
import hashlib
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / 'herald-screen' / 'herald_screen_backend'))
from selection_relay import SelectionRelay

PROFILE = '/synthetic/private/profile-secret'
LEAK = '/synthetic/private/token-secret'
HASH = hashlib.sha256(b'viewer').hexdigest()
LEASE = {'epoch': 3, 'holder': 'human', 'viewer_hash': HASH[:12], 'reason': LEAK}


class PrivacyTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.sent = []
        async def send(value):
            self.sent.append(value)
        class Service:
            async def read(self, viewer, epoch):
                assert (viewer, epoch) == ('viewer', 3)
                return 'synthetic selection'
        self.relay = SelectionRelay(Service(), send, PROFILE)

    async def asyncTearDown(self):
        await self.relay.close()

    async def response(self, method, result):
        await self.relay.client_text(json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': method}))
        return self.relay.server_text(json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': result}))

    def event(self, kind, payload):
        return self.relay.server_text(json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': kind, 'payload': payload}}))

    def private(self, value):
        self.assertNotIn(PROFILE, json.dumps(value))
        self.assertNotIn(LEAK, json.dumps(value))
        self.assertNotIn('profile_key', json.dumps(value))

    def status(self):
        return {'profile_key': PROFILE, 'running': True, 'placement': 'gateway', 'lease': LEASE, 'other': LEAK}

    async def test_legitimate_flow_and_copy(self):
        ready = self.event('gateway.ready', {'secret': LEAK})
        self.private(ready)
        status = self.status()
        observed = await self.response('display.observe', {**status, 'ticket': 'ticket', 'viewer_id': 'viewer', 'path': '/api/display/ws'})
        self.private(observed)
        self.assertEqual(observed['result']['lease']['viewer_hash'], HASH[:12])
        self.assertEqual(observed['result']['placement'], 'gateway')
        await self.relay.client_text(json.dumps({'jsonrpc': '2.0', 'id': 2, 'method': 'viewer.selection.read', 'params': {'epoch': 3}}))
        await self.relay.task
        self.assertEqual(self.sent[-1]['result']['text'], 'synthetic selection')
        for method in ('display.status', 'display.lease.acquire', 'display.lease.release'):
            result = await self.response(method, status)
            self.private(result)
            self.assertEqual(result['result']['lease']['viewer_hash'], HASH[:12])
        for kind in ('display.status', 'display.lease'):
            result = self.event(kind, status)
            self.private(result)
            self.assertEqual(result['params']['payload']['lease']['viewer_hash'], HASH[:12])
        null = await self.response('display.status', {**status, 'lease': {**LEASE, 'viewer_hash': None}})
        self.assertIsNone(null['result']['lease']['viewer_hash'])

    async def test_bad_hashes_are_never_forwarded(self):
        for value in (LEAK, '', 'a' * 11, 'a' * 13, HASH, 'A' * 64, 'g' * 64, 'a' * 64 + '\n', 3, [], {}):
            with self.subTest(value=value):
                payload = {**self.status(), 'lease': {**LEASE, 'viewer_hash': value}}
                result = await self.response('display.status', payload)
                self.assertIn('error', result)
                self.private(result)
                self.assertIsNone(self.event('display.lease', payload))
                self.assertIsNone(self.event('display.status', payload))

    async def test_supported_sandbox_and_cross_profile_status(self):
        for placement in ('terminal:docker', 'terminal:ssh'):
            status = {**self.status(), 'placement': placement}
            started = await self.response('display.start', status)
            self.private(started)
            self.assertEqual(started['result']['placement'], placement)
            observed = await self.response('display.observe', {**status, 'ticket': 'ticket', 'viewer_id': 'viewer', 'path': '/api/display/ws'})
            self.private(observed)
            self.assertEqual(observed['result']['placement'], placement)
            self.assertIsNone(self.event('display.status', {**status, 'profile_key': 'other', 'placement': 'gateway'}))
            self.assertEqual(self.relay.placement, placement)
            await self.relay.client_text(json.dumps({'jsonrpc': '2.0', 'id': 2, 'method': 'viewer.selection.read', 'params': {'epoch': 3}}))
            self.assertIn('error', self.sent[-1])
            self.assertIsNone(self.relay.task)
            self.assertIsNotNone(self.event('display.status', status))
        self.assertIsNone(self.event('display.status', {**status, 'placement': 'terminal:unknown'}))
        self.assertIsNone(self.relay.viewer)

    async def test_start_rejects_foreign_and_malformed_results(self):
        for changes in ({'profile_key': 'other'}, {'placement': 'terminal:unknown'}, {'running': 1}, {'lease': None}):
            result = await self.response('display.start', {**self.status(), **changes})
            self.assertIn('error', result)
            self.private(result)

    async def test_missing_and_malformed_fields_fail_closed(self):
        base = self.status()
        for key in ('profile_key', 'running', 'placement', 'lease'):
            bad = dict(base); del bad[key]
            result = await self.response('display.status', bad)
            self.assertIn('error', result)
            self.private(result)
        for key in ('epoch', 'holder', 'viewer_hash'):
            lease = dict(LEASE); del lease[key]
            result = await self.response('display.lease.acquire', {'lease': lease})
            self.assertIn('error', result)
            self.assertIsNone(self.event('display.lease', {**base, 'lease': lease}))
        for placement in (LEAK, 'terminal:unknown', 'terminal:modal', '', None, ['gateway']):
            bad = {**base, 'placement': placement}
            result = await self.response('display.status', bad)
            self.assertIn('error', result)
            self.private(result)
            self.assertIsNone(self.event('display.status', bad))
        self.assertIsNone(self.event('display.lease', {**base, 'profile_key': LEAK}))
        observe = {**base, 'ticket': 'ticket', 'viewer_id': 'viewer', 'path': '/api/display/ws'}
        del observe['lease']
        self.assertIn('error', await self.response('display.observe', observe))
        self.assertIsNone(self.relay.viewer)
