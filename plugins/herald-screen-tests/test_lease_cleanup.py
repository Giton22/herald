"""Synthetic gateway tests for same-connection, owner-bound cleanup."""
import asyncio
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest

import aiohttp

spec = importlib.util.spec_from_file_location('screen_cleanup_api', Path(__file__).parent.parent / 'herald-screen/dashboard/plugin_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class Gateway:
    def __init__(self, holder=None):
        self.holder = holder
        self.requests = []
        self.responses = asyncio.Queue()
        self.closed = []
        self.gate = asyncio.Event()
        self._response = SimpleNamespace(close=lambda: self.closed.append('abort'))

    async def delayed_acquire(self, viewer):
        await self.gate.wait()
        self.holder = viewer
        await self.responses.put(SimpleNamespace(type=aiohttp.WSMsgType.TEXT, data=json.dumps({'id': 4, 'result': {}})))

    async def send_json(self, value):
        self.requests.append(value)
        # Model the gateway's serial control lane: acquisition finishes first.
        await self.acquire
        if self.holder == value['params']['viewer_id']:
            self.holder = None
        await self.responses.put(SimpleNamespace(type=aiohttp.WSMsgType.TEXT, data=json.dumps({'id': value['id'], 'result': {'lease': {}}})))

    async def receive(self):
        return await self.responses.get()

    async def close(self, code):
        self.closed.append(code)


class Relay:
    async def close(self):
        pass


class Socket:
    async def close(self, code):
        pass


class CleanupTests(unittest.IsolatedAsyncioTestCase):
    async def test_delayed_acquire_finishes_before_owner_release(self):
        gateway = Gateway()
        gateway.acquire = asyncio.create_task(gateway.delayed_acquire('ours'))
        cleanup = asyncio.create_task(api._finish_rpc(gateway, Socket(), Relay(), (), 1011, {'ours'}))
        await asyncio.sleep(0.02)
        self.assertFalse(cleanup.done())
        gateway.gate.set()
        await cleanup
        self.assertIsNone(gateway.holder)
        self.assertEqual(gateway.requests[0]['method'], 'display.lease.release')
        self.assertEqual(gateway.requests[0]['params'], {'viewer_id': 'ours'})
        self.assertEqual(gateway.closed, [1011])

    async def test_other_viewer_takeover_and_duplicate_close_are_safe(self):
        gateway = Gateway('another')
        gateway.acquire = asyncio.create_task(asyncio.sleep(0))
        await api._finish_rpc(gateway, Socket(), Relay(), (), 1000, {'ours'})
        await api._finish_rpc(gateway, Socket(), Relay(), (), 1000, {'ours'})
        self.assertEqual(gateway.holder, 'another')
        self.assertEqual([r['params'] for r in gateway.requests], [{'viewer_id': 'ours'}] * 2)

    async def test_failed_rpc_cannot_claim_handback(self):
        gateway = Gateway('ours')
        gateway.acquire = asyncio.create_task(asyncio.sleep(0))
        async def fail(value):
            raise ConnectionError('synthetic partition')
        gateway.send_json = fail
        with self.assertLogs(api._log, level='WARNING'):
            await api._finish_rpc(gateway, Socket(), Relay(), (), 1011, {'ours'})
        self.assertEqual(gateway.holder, 'ours')
        self.assertEqual(gateway.closed, [1011])

    async def test_cleanup_survives_outer_cancellation(self):
        gateway = Gateway()
        gateway.acquire = asyncio.create_task(gateway.delayed_acquire('ours'))
        cleanup = asyncio.create_task(api._finish_rpc(gateway, Socket(), Relay(), (), 1011, {'ours'}))
        await asyncio.sleep(0.02)
        cleanup.cancel()
        gateway.gate.set()
        with self.assertRaises(asyncio.CancelledError):
            await cleanup
        self.assertIsNone(gateway.holder)


if __name__ == '__main__':
    unittest.main()
