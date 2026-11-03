"""Offline relay and lease tests: synthetic profile, native reader, and gateway frames."""
import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / 'herald-screen' / 'herald_screen_backend'))
from selection_relay import SelectionRelay
from selection_backend import SelectionService


def frame(method, identifier, params=None):
    return json.dumps({'jsonrpc': '2.0', 'id': identifier, 'method': method, 'params': params or {}})


class SelectionRelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        base = self.home / 'bot-desktop'
        base.mkdir()
        auth = base / 'Xauthority'
        auth.write_text('synthetic')
        auth.chmod(0o600)
        (base / 'env').write_text(f'DISPLAY=:123\nXAUTHORITY={auth}\n')
        self.lease = base / 'lease.json'
        self.set_lease('holder', 7)
        self.sent = []
        self.started, self.release, self.cancelled = asyncio.Event(), asyncio.Event(), asyncio.Event()
        self.block = False
        self.calls = 0
        async def native(env):
            self.calls += 1
            self.started.set()
            if self.block:
                try:
                    await self.release.wait()
                except asyncio.CancelledError:
                    self.cancelled.set()
                    raise
            return 'synthetic text'
        async def send(value):
            self.sent.append(value)
        self.relay = SelectionRelay(SelectionService(self.home, _native_read=native), send, str(self.home))

    async def asyncTearDown(self):
        await self.relay.close()

    def set_lease(self, viewer, epoch, holder='human'):
        self.lease.write_text(json.dumps({'viewer_id': viewer, 'epoch': epoch, 'holder': holder}))

    async def observe(self, viewer='holder', **changes):
        await self.relay.client_text(frame('display.observe', 1))
        result = {'ticket': 'synthetic-ticket', 'viewer_id': viewer, 'profile_key': str(self.home), 'path': '/api/display/ws', 'placement': 'gateway', 'running': True, 'lease': {'epoch': 7, 'holder': 'human', 'viewer_hash': None}}
        result.update(changes)
        self.relay.server_text(json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': result}))

    async def copy(self, params=None, identifier=2):
        await self.relay.client_text(frame('viewer.selection.read', identifier, {'epoch': 7} if params is None else params))
        if self.relay.task:
            await self.relay.task
        return self.sent[-1]

    async def test_unobserved_and_forged_client_result_cannot_bind(self):
        await self.relay.client_text(json.dumps({'id': 1, 'result': {'viewer_id': 'holder'}}))
        self.assertIn('error', await self.copy())
        self.assertEqual(self.calls, 0)

    async def test_out_of_order_observe_and_duplicate_replies_are_fenced(self):
        await self.relay.client_text(frame('display.observe', 10))
        await self.relay.client_text(frame('display.observe', 11))
        result = {'ticket': 'synthetic-ticket', 'viewer_id': 'holder', 'profile_key': str(self.home), 'path': '/api/display/ws', 'placement': 'gateway', 'running': True, 'lease': {'epoch': 7, 'holder': 'human', 'viewer_hash': None}}
        def response(identifier):
            return json.dumps({'jsonrpc': '2.0', 'id': identifier, 'result': result})
        self.assertIn('error', self.relay.server_text(response(10)))
        self.assertIsNone(self.relay.viewer)
        self.assertEqual(self.relay.server_text(response(11))['result']['ticket'], 'synthetic-ticket')
        self.assertEqual(self.relay.viewer, 'holder')
        self.assertIsNone(self.relay.server_text(response(11)))
        self.assertIsNone(self.relay.server_text(response(10)))

    async def test_mismatched_and_malformed_observe_returns_only_safe_error(self):
        for identifier, changes in enumerate(({'profile_key': 'other'}, {'placement': 'sandbox'}, {'path': '/wrong'}, {'running': False}, {'viewer_id': ''}, {'ticket': ''}), 10):
            await self.relay.client_text(frame('display.observe', identifier))
            result = {'ticket': 'secret-ticket', 'viewer_id': 'holder', 'profile_key': str(self.home), 'path': '/api/display/ws', 'placement': 'gateway', 'running': True, 'lease': {'epoch': 7, 'holder': 'human', 'viewer_hash': None}}
            result.update(changes)
            response = self.relay.server_text(json.dumps({'jsonrpc': '2.0', 'id': identifier, 'result': result}))
            self.assertEqual(response['id'], identifier)
            self.assertIn('error', response)
            self.assertNotIn('secret-ticket', json.dumps(response))
            self.assertIsNone(self.relay.viewer)

    async def test_holder_read_and_authoritative_watcher_denial(self):
        await self.observe()
        self.assertEqual((await self.copy())['result']['text'], 'synthetic text')
        await self.observe('watcher')
        self.assertIn('error', await self.copy())
        self.assertEqual(self.calls, 1)

    async def test_wrong_profile_placement_path_and_running_never_bind(self):
        for change in ({'profile_key': 'other'}, {'placement': 'sandbox'}, {'path': '/wrong'}, {'running': False}, {'viewer_id': ''}):
            with self.subTest(change=change):
                await self.observe(**change)
                self.assertIn('error', await self.copy())
        self.assertEqual(self.calls, 0)

    async def test_invalid_copy_parameters_cannot_reach_backend(self):
        await self.observe()
        for params in ({}, {'epoch': True}, {'epoch': -1}, {'epoch': '7'}, {'epoch': 7, 'viewer_id': 'holder'}, {'epoch': 7, 'profile': 'other'}):
            with self.subTest(params=params):
                self.assertEqual((await self.copy(params))['error']['code'], -32602)
        self.assertEqual(self.calls, 0)

    async def test_stale_lease_and_pre_read_revocation(self):
        await self.observe()
        self.set_lease('holder', 8)
        self.assertIn('error', await self.copy())
        self.assertEqual(self.calls, 0)

    async def test_post_read_lease_change_discards_delayed_text(self):
        await self.observe()
        self.block = True
        await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
        await asyncio.wait_for(self.started.wait(), 2)
        task = self.relay.task
        self.set_lease('holder', 8)
        self.release.set()
        await asyncio.wait_for(task, 2)
        self.assertIn('error', self.sent[-1])
        self.assertNotIn('synthetic text', str(self.sent))

    async def test_release_reobserve_and_lease_event_cancel_delayed_reads(self):
        for trigger in ('release', 'observe', 'event'):
            with self.subTest(trigger=trigger):
                await self.observe()
                self.block = True
                self.started.clear(); self.cancelled.clear()
                await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
                await asyncio.wait_for(self.started.wait(), 2)
                task = self.relay.task
                if trigger == 'event':
                    self.relay.server_text(json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'display.lease', 'payload': {'profile_key': str(self.home), 'lease': {'epoch': 8, 'holder': 'agent', 'viewer_hash': None}}}}))
                else:
                    await self.relay.client_text(frame('display.lease.release' if trigger == 'release' else 'display.observe', 3))
                await asyncio.wait_for(task, 2)
                self.assertTrue(self.cancelled.is_set())
                self.assertIn('error', self.sent[-1])
                self.assertNotIn('synthetic text', str(self.sent))

    async def test_close_cancels_pending_read_without_reply(self):
        await self.observe()
        self.block = True
        await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
        await asyncio.wait_for(self.started.wait(), 2)
        await self.relay.close()
        self.assertTrue(self.cancelled.is_set())
        self.assertEqual(self.sent, [])

    async def test_sandbox_status_cancels_pending_gateway_copy(self):
        await self.observe()
        self.block = True
        await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
        await asyncio.wait_for(self.started.wait(), 2)
        task = self.relay.task
        self.relay.server_text(json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {
            'type': 'display.status', 'payload': {'profile_key': str(self.home), 'running': True,
                'placement': 'terminal:docker', 'lease': {'epoch': 7, 'holder': 'human', 'viewer_hash': None}}}}))
        await asyncio.wait_for(task, 2)
        self.assertTrue(self.cancelled.is_set())
        self.assertIsNone(self.relay.viewer)
        self.assertIn('error', self.sent[-1])
        self.assertNotIn('synthetic text', str(self.sent))

    async def test_immediate_invalidation_denies_once_and_allows_later_copy(self):
        for trigger in ('release', 'observe', 'event'):
            with self.subTest(trigger=trigger):
                self.sent.clear()
                await self.observe()
                await self.relay.client_text(frame('viewer.selection.read', 20, {'epoch': 7}))
                task = self.relay.task
                # No event-loop turn between creating the copy and revocation.
                if trigger == 'event':
                    self.relay.server_text(json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'display.lease', 'payload': {'profile_key': str(self.home), 'lease': {'epoch': 8, 'holder': 'agent', 'viewer_hash': None}}}}))
                else:
                    await self.relay.client_text(frame('display.lease.release' if trigger == 'release' else 'display.observe', 21))
                await asyncio.wait_for(task, 2)
                self.assertIsNone(self.relay.task)
                self.assertEqual(len(self.sent), 1)
                self.assertEqual(self.sent[0]['id'], 20)
                self.assertIn('error', self.sent[0])
                self.assertEqual(self.calls, 0 if trigger == 'release' else (1 if trigger == 'observe' else 2))
                await self.observe()
                self.assertEqual((await self.copy(identifier=22))['result']['text'], 'synthetic text')
                self.assertEqual([item['id'] for item in self.sent], [20, 22])

    async def test_immediate_close_neither_replies_nor_deadlocks(self):
        await self.observe()
        await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
        await asyncio.wait_for(self.relay.close(), 2)
        self.assertIsNone(self.relay.task)
        self.assertEqual(self.calls, 0)
        self.assertEqual(self.sent, [])

    async def test_repeated_invalidation_during_denial_preserves_task_and_reply(self):
        await self.observe()
        started, release = asyncio.Event(), asyncio.Event()
        async def delayed_send(value):
            started.set()
            await release.wait()
            self.sent.append(value)
        self.relay.send = delayed_send
        await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
        old_task = self.relay.task
        await self.relay.client_text(frame('display.lease.release', 3))
        await asyncio.wait_for(started.wait(), 2)
        await self.observe()
        self.assertIs(self.relay.task, old_task)
        release.set()
        await asyncio.wait_for(old_task, 2)
        self.assertEqual([item['id'] for item in self.sent], [2])
        self.assertIn('error', self.sent[0])
        self.assertEqual((await self.copy(identifier=4))['result']['text'], 'synthetic text')
        self.assertEqual([item['id'] for item in self.sent], [2, 4])

    async def test_revocation_cancels_blocked_result_send(self):
        await self.observe()
        started = asyncio.Event()
        async def delayed_send(value):
            if 'result' in value:
                started.set()
                await asyncio.Event().wait()
            self.sent.append(value)
        self.relay.send = delayed_send
        await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
        task = self.relay.task
        await asyncio.wait_for(started.wait(), 2)
        await self.relay.client_text(frame('display.lease.release', 3))
        await asyncio.wait_for(task, 2)
        self.assertEqual(len(self.sent), 1)
        self.assertIn('error', self.sent[0])
        self.assertNotIn('synthetic text', str(self.sent))

    async def test_overlapping_copy_is_rejected(self):
        await self.observe()
        self.block = True
        await self.relay.client_text(frame('viewer.selection.read', 2, {'epoch': 7}))
        await asyncio.wait_for(self.started.wait(), 2)
        task = self.relay.task
        await self.relay.client_text(frame('viewer.selection.read', 3, {'epoch': 7}))
        self.assertIn('error', self.sent[-1])
        self.assertEqual(self.calls, 1)
        self.release.set()
        await task
        self.assertEqual(self.sent[-1]['result']['text'], 'synthetic text')

    async def test_backend_exception_is_not_reflected(self):
        class Broken:
            async def read(self, viewer, epoch):
                raise RuntimeError('synthetic-sensitive-marker')
        self.relay.service = Broken()
        await self.observe()
        self.assertIn('error', await self.copy())
        self.assertNotIn('synthetic-sensitive-marker', str(self.sent))
