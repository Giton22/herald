"""Packaged plugin tests. Socket authentication is a synthetic upstream, not live login."""
from pathlib import Path
import asyncio
import importlib.util
import json
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from unittest.mock import AsyncMock
from types import SimpleNamespace

from aiohttp import web
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect


class ScreenPluginTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(__file__).parent.parent / 'herald-screen'
        name = 'screen_plugin_api'
        spec = importlib.util.spec_from_file_location(name, self.root / 'dashboard/plugin_api.py')
        assert spec is not None and spec.loader is not None
        self.api = importlib.util.module_from_spec(spec)
        sys.modules[name] = self.api
        spec.loader.exec_module(self.api)
        self.events, self.closed, self.reads = [], [], []
        self.upstream_closed = threading.Event()
        self.security_fixture = False
        self.fixture_placement = 'gateway'
        self.start_error = False
        self.delay_acquire = False
        self.acquire_entered = threading.Event()
        self.acquire_continue = threading.Event()
        self.synthetic_holder = None
        async def gateway(request):
            protocols = request.headers.get('Sec-WebSocket-Protocol', '').split(',')
            if 'hermes-gateway-ticket.fixture-valid' not in [p.strip() for p in protocols]:
                return web.Response(status=401)
            socket = web.WebSocketResponse(protocols=('hermes-gateway-v1',), compress=False)
            await socket.prepare(request)
            await socket.send_json({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'gateway.ready', 'payload': {}}})
            async for message in socket:
                if message.type != web.WSMsgType.TEXT:
                    continue
                data = json.loads(message.data)
                self.events.append(data)
                if self.delay_acquire and data['method'] == 'display.lease.acquire':
                    self.acquire_entered.set()
                    await asyncio.to_thread(self.acquire_continue.wait, 3)
                    self.synthetic_holder = data['params']['viewer_id']
                if self.delay_acquire and data['method'] == 'display.lease.release':
                    if self.synthetic_holder == data['params']['viewer_id']:
                        self.synthetic_holder = None
                if data['method'] == 'display.start' and self.start_error:
                    await socket.send_json({'jsonrpc': '2.0', 'id': data['id'], 'error': {'code': 5300, 'message': 'private error marker'}})
                    continue
                if self.security_fixture and data['method'] == 'display.observe':
                    def event(kind, payload):
                        return {'jsonrpc': '2.0', 'method': 'event', 'params': {'type': kind, 'payload': payload}}
                    for value in [
                        event('display.lease', {'profile_key': 'other', 'lease': {'epoch': 9}, 'secret': 'foreign-marker'}),
                        event('display.status', {'profile_key': 'other', 'lease': {'epoch': 9}}),
                        event('display.lease', {'lease': {'epoch': 9}}),
                        event('unknown', {'profile_key': str(self.api.HOME), 'secret': 'unknown-marker'}),
                        {'jsonrpc': '2.0', 'id': 999, 'result': {'ticket': 'unmatched-marker'}},
                        event('display.lease', {'profile_key': str(self.api.HOME), 'lease': {'epoch': 2, 'holder': 'human', 'viewer_hash': '012345abcdef', 'secret': 'nested-marker'}, 'secret': 'extra-marker'}),
                        event('display.status', {'profile_key': str(self.api.HOME), 'running': True, 'placement': 'gateway', 'lease': {'epoch': 2, 'holder': 'human', 'viewer_hash': None}, 'secret': 'extra-marker'}),
                    ]:
                        await socket.send_json(value)
                    await socket.send_str('{malformed')
                    await socket.send_json({'jsonrpc': '2.0', 'id': data['id'], 'result': {'ticket': 'foreign-ticket-marker', 'profile_key': 'other', 'path': '/api/display/ws', 'placement': 'gateway', 'running': True, 'viewer_id': 'foreign'}})
                    continue
                if data['method'] == 'display.observe':
                    result = {'ticket': 'synthetic-ticket', 'viewer_id': 'fixture-viewer', 'profile_key': str(self.api.HOME), 'path': '/api/display/ws', 'placement': self.fixture_placement, 'running': True, 'lease': {'epoch': 2, 'holder': 'human', 'viewer_hash': None}}
                elif data['method'] in ('display.status', 'display.start'):
                    result = {'profile_key': str(self.api.HOME), 'running': True, 'placement': self.fixture_placement, 'lease': {'epoch': 2, 'holder': 'human', 'viewer_hash': None}}
                else:
                    result = {'lease': {'epoch': 2, 'holder': 'human', 'viewer_hash': None}}
                await socket.send_json({'jsonrpc': '2.0', 'id': data['id'], 'result': result})
            self.closed.append(socket.close_code)
            self.upstream_closed.set()
            return socket

        upstream = web.Application()
        upstream.router.add_get('/api/ws', gateway)
        self.runner = web.AppRunner(upstream, access_log=None)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', 0)
        await site.start()
        address = next(item for item in self.runner.addresses if isinstance(item, tuple))
        port = address[1]
        setattr(self.api, 'upstream_url', lambda: f'ws://127.0.0.1:{port}/api/ws')
        self.original_live_selection = self.api.LiveSelection
        owner = self
        class FixtureSelection:
            async def read(self, viewer, epoch):
                owner.reads.append((viewer, epoch))
                return 'fixture selected é 😀'
        setattr(self.api, 'LiveSelection', FixtureSelection)
        self.app = FastAPI()
        self.app.include_router(self.api.router, prefix='/api/plugins/herald-screen')
        self.path = '/api/plugins/herald-screen/rpc'
        self.protocols = ['hermes-gateway-v1', 'hermes-gateway-ticket.fixture-valid']
        self.headers = {'Origin': 'http://testserver'}

    async def asyncTearDown(self):
        await self.runner.cleanup()
        self.temp.cleanup()

    async def test_package_preserves_helpers_and_serves_complete_nested_assets(self):
        for name in ('selection_backend.py', 'selection_relay.py'):
            self.assertTrue((self.root / 'herald_screen_backend' / name).is_file())
        def browser():
            with TestClient(self.app) as client:
                for asset in (self.root / 'dashboard/viewer').rglob('*'):
                    if asset.is_file():
                        response = client.get('/api/plugins/herald-screen/' + str(asset.relative_to(self.root / 'dashboard/viewer')))
                        self.assertEqual(response.status_code, 200)
                        self.assertEqual(response.content, asset.read_bytes())
                        self.assertEqual(response.headers['cache-control'], 'no-store')
                for value in ('../plugin_api.py', '%2e%2e%2fplugin_api.py'):
                    self.assertEqual(client.get('/api/plugins/herald-screen/' + value).status_code, 404)
                with patch.object(self.api, 'active_profile', return_value=False):
                    self.assertEqual(client.get('/api/plugins/herald-screen/index.html').status_code, 404)
        await asyncio.to_thread(browser)

    async def test_origin_protocol_and_upstream_auth_refusals_never_accept(self):
        def browser():
            with TestClient(self.app) as client:
                cases = [({}, []), ({'Origin': 'http://foreign.invalid'}, self.protocols), (self.headers, ['hermes-gateway-v1', 'hermes-gateway-ticket.expired'])]
                for headers, protocols in cases:
                    with self.assertRaises(WebSocketDisconnect):
                        with client.websocket_connect(self.path, headers=headers, subprotocols=protocols):
                            self.fail('Unauthenticated/refused socket admitted')
        await asyncio.to_thread(browser)
        self.assertEqual(self.events, [])
        self.assertEqual(self.reads, [])

    async def test_effective_origin_scheme_and_authority(self):
        cases = [
            ('ws', 'http://testserver', 'testserver', True),
            ('wss', 'https://TESTSERVER', 'testserver', True),
            ('ws', 'https://testserver', 'testserver', False),
            ('wss', 'http://testserver', 'testserver', False),
            ('wss', 'https://testserver:8443', 'testserver:8443', True),
            ('wss', 'https://testserver:8444', 'testserver:8443', False),
            ('ws', 'http://[::1]:9119', '[::1]:9119', True),
            ('ws', 'http://[::1]:9120', '[::1]:9119', False),
            (None, 'http://testserver', 'testserver', False),
            ('https', 'https://testserver', 'testserver', False),
        ]
        for scheme, origin, host, expected in cases:
            with self.subTest(scheme=scheme, origin=origin, host=host):
                headers = {'origin': origin, 'host': host, 'x-forwarded-proto': 'https',
                           'forwarded': 'proto=https;host=attacker.invalid'}
                self.assertEqual(self.api.browser_admission({'scheme': scheme}, headers, self.protocols), expected)

    async def test_proxy_middleware_applies_only_trusted_forwarded_scheme(self):
        from uvicorn.middleware.proxy_headers import ProxyHeadersMiddleware

        def browser():
            app = ProxyHeadersMiddleware(self.app, trusted_hosts=['127.0.0.1'])
            for peer, origin, forwarded, accepted in [
                ('192.0.2.2', 'https://testserver', 'https', False),
                ('192.0.2.2', 'http://testserver', 'https', True),
                ('127.0.0.1', 'https://testserver', 'https', True),
                ('127.0.0.1', 'http://testserver', 'https', False),
            ]:
                with self.subTest(peer=peer, origin=origin):
                    with TestClient(app, client=(peer, 50000)) as client:
                        headers = {'origin': origin, 'x-forwarded-proto': forwarded}
                        if accepted:
                            with client.websocket_connect(self.path, headers=headers, subprotocols=self.protocols) as socket:
                                self.assertEqual(socket.receive_json()['params']['type'], 'gateway.ready')
                        else:
                            with self.assertRaises(WebSocketDisconnect):
                                with client.websocket_connect(self.path, headers=headers, subprotocols=self.protocols):
                                    self.fail('cross-scheme socket admitted')
        await asyncio.to_thread(browser)

    async def test_real_relay_binds_copy_to_observe_and_limits_rpc_scope(self):
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    self.assertEqual(socket.receive_json()['params']['type'], 'gateway.ready')
                    socket.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'viewer.selection.read', 'params': {'epoch': 2}})
                    self.assertIn('error', socket.receive_json())
                    socket.send_json({'jsonrpc': '2.0', 'id': 2, 'method': 'display.observe', 'params': {}})
                    self.assertEqual(socket.receive_json()['result']['viewer_id'], 'fixture-viewer')
                    socket.send_json({'jsonrpc': '2.0', 'id': 3, 'method': 'viewer.selection.read', 'params': {'epoch': 2}})
                    self.assertEqual(socket.receive_json()['result']['text'], 'fixture selected é 😀')
                    for method, params in [('chat.send', {}), ('display.observe', {'profile': 'other-profile'})]:
                        socket.send_json({'jsonrpc': '2.0', 'id': 4, 'method': method, 'params': params})
                        self.assertIn('error', socket.receive_json())
                    socket.close(code=1000)
                    self.assertTrue(self.upstream_closed.wait(3), 'upstream did not observe clean close')
        await asyncio.to_thread(browser)
        self.assertEqual(self.reads, [('fixture-viewer', 2)])
        self.assertEqual([event['method'] for event in self.events], ['display.observe'])
        self.assertEqual(self.closed, [1000])

    async def test_disconnect_during_delayed_acquire_releases_on_same_connection(self):
        self.delay_acquire = True
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    socket.receive_json()
                    socket.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'display.observe', 'params': {}})
                    self.assertEqual(socket.receive_json()['result']['viewer_id'], 'fixture-viewer')
                    socket.send_json({'jsonrpc': '2.0', 'id': 2, 'method': 'display.lease.acquire', 'params': {'viewer_id': 'fixture-viewer'}})
                    self.assertTrue(self.acquire_entered.wait(2))
                    socket.close(code=1006)
                    self.acquire_continue.set()
                    self.assertTrue(self.upstream_closed.wait(3))
        await asyncio.to_thread(browser)
        self.assertIsNone(self.synthetic_holder)
        self.assertEqual([item['method'] for item in self.events], ['display.observe', 'display.lease.acquire', 'display.lease.release'])
        self.assertEqual(self.events[-1]['params'], {'viewer_id': 'fixture-viewer'})

    async def test_explicit_sandbox_start_observe_and_copy_refusal(self):
        self.fixture_placement = 'terminal:docker'
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    socket.receive_json()
                    for identifier in (1, 2):
                        socket.send_json({'jsonrpc': '2.0', 'id': identifier, 'method': 'display.start', 'params': {}})
                        self.assertEqual(socket.receive_json()['result']['placement'], 'terminal:docker')
                    socket.send_json({'jsonrpc': '2.0', 'id': 3, 'method': 'display.observe', 'params': {}})
                    self.assertEqual(socket.receive_json()['result']['path'], '/api/display/ws')
                    socket.send_json({'jsonrpc': '2.0', 'id': 4, 'method': 'viewer.selection.read', 'params': {'epoch': 2}})
                    self.assertIn('error', socket.receive_json())
                    socket.send_json({'jsonrpc': '2.0', 'id': 5, 'method': 'display.start', 'params': {'profile': 'other'}})
                    self.assertIn('error', socket.receive_json())
        await asyncio.to_thread(browser)
        self.assertEqual([event['method'] for event in self.events], ['display.start', 'display.start', 'display.observe'])
        self.assertEqual(self.reads, [])

    async def test_start_error_is_redacted(self):
        self.start_error = True
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    socket.receive_json()
                    socket.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'display.start', 'params': {}})
                    result = socket.receive_json()
                    self.assertIn('error', result)
                    self.assertNotIn('private error marker', str(result))
        await asyncio.to_thread(browser)

    async def test_end_to_end_real_backend_enforces_lease(self):
        home = Path(self.temp.name)
        base = home / 'bot-desktop'
        base.mkdir()
        auth = base / 'Xauthority'
        auth.write_text('synthetic')
        auth.chmod(0o600)
        (base / 'env').write_text(f'DISPLAY=:123\nXAUTHORITY={auth}\n')
        lease = base / 'lease.json'
        lease.write_text(json.dumps({'holder': 'human', 'viewer_id': 'fixture-viewer', 'epoch': 2}))
        reads = []
        async def native(service, env):
            reads.append(True)
            return 'synthetic selection'
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    socket.receive_json()
                    socket.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'display.observe', 'params': {}})
                    self.assertEqual(socket.receive_json()['result']['viewer_id'], 'fixture-viewer')
                    socket.send_json({'jsonrpc': '2.0', 'id': 2, 'method': 'viewer.selection.read', 'params': {'epoch': 2}})
                    self.assertEqual(socket.receive_json()['result']['text'], 'synthetic selection')
                    lease.write_text(json.dumps({'holder': 'agent', 'viewer_id': 'fixture-viewer', 'epoch': 3}))
                    socket.send_json({'jsonrpc': '2.0', 'id': 3, 'method': 'viewer.selection.read', 'params': {'epoch': 2}})
                    self.assertIn('error', socket.receive_json())
        with patch.object(self.api, 'HOME', home), patch.object(self.api, 'active_profile', return_value=True), patch.object(self.api, 'LiveSelection', self.original_live_selection), patch.object(self.api.SelectionService, '_read_native', native):
            await asyncio.to_thread(browser)
        self.assertEqual(len(reads), 1)
        self.assertEqual([event['method'] for event in self.events], ['display.observe'])

    async def test_unreported_sandbox_transition_before_or_during_pending_copy(self):
        for transition in ('before', 'during'):
            with self.subTest(transition=transition):
                sent, entered, proceed = [], asyncio.Event(), asyncio.Event()
                actual_placement = 'gateway'
                async def send(value):
                    sent.append(value)
                original = self.original_live_selection
                class DelayedSelection(original):
                    async def read(self, viewer, epoch):
                        entered.set()
                        await proceed.wait()
                        return await super().read(viewer, epoch)
                relay = self.api.SelectionRelay(DelayedSelection(), send, 'synthetic-profile')
                with patch.object(self.api, 'SelectionService') as host_service:
                    host_service.return_value.read = AsyncMock(return_value='synthetic selection')
                    await relay.client_text(json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'display.observe', 'params': {}}))
                    relay.server_text(json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': {'profile_key': 'synthetic-profile', 'path': '/api/display/ws', 'placement': 'gateway', 'running': True, 'viewer_id': 'holder', 'ticket': 'ticket', 'lease': {'epoch': 7, 'holder': 'human', 'viewer_hash': None}}}))
                    if transition == 'before':
                        actual_placement = 'terminal:sandbox'
                    await relay.client_text(json.dumps({'jsonrpc': '2.0', 'id': 2, 'method': 'viewer.selection.read', 'params': {'epoch': 7}}))
                    task = relay.task
                    await asyncio.wait_for(entered.wait(), 2)
                    if transition == 'during':
                        actual_placement = 'terminal:sandbox'
                    # No status event is delivered; the relay still sees gateway.
                    self.assertEqual(relay.placement, 'gateway')
                    self.assertEqual(actual_placement, 'terminal:sandbox')
                    proceed.set()
                    await asyncio.wait_for(task, 2)
                    host_service.assert_called_once_with(self.api.HOME)
                    host_service.return_value.read.assert_awaited_once_with('holder', 7)
                    self.assertEqual(len(sent), 1)
                    self.assertEqual(sent[0]['result']['text'], 'synthetic selection')
                await relay.close()

    async def test_status_change_clears_binding_and_stale_status_cannot_restore(self):
        sent = []
        async def send(value):
            sent.append(value)
        service = SimpleNamespace(read=AsyncMock(return_value='synthetic selection'))
        relay = self.api.SelectionRelay(service, send, 'profile')
        def request(method, identifier, params=None):
            return json.dumps({'jsonrpc': '2.0', 'id': identifier, 'method': method, 'params': params or {}})
        lease = {'epoch': 7, 'holder': 'human', 'viewer_hash': None}
        def status(profile, placement):
            return {'profile_key': profile, 'running': True, 'placement': placement, 'lease': lease}
        def event(payload):
            return json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'display.status', 'payload': payload}})
        await relay.client_text(request('display.observe', 1))
        relay.server_text(json.dumps({'jsonrpc': '2.0', 'id': 1, 'result': dict(status('profile', 'gateway'), path='/api/display/ws', viewer_id='holder', ticket='fixture')}))
        await relay.client_text(request('display.status', 2))
        self.assertIsNone(relay.server_text(event(status('other', 'terminal:docker'))))
        self.assertEqual(relay.viewer, 'holder')
        relay.server_text(event(status('profile', 'terminal:docker')))
        self.assertIsNone(relay.viewer)
        relay.server_text(json.dumps({'jsonrpc': '2.0', 'id': 2, 'result': status('profile', 'gateway')}))
        await relay.client_text(request('display.observe', 3))
        relay.server_text(json.dumps({'jsonrpc': '2.0', 'id': 3, 'result': dict(status('profile', 'gateway'), path='/api/display/ws', viewer_id='holder', ticket='fixture')}))
        await relay.client_text(request('viewer.selection.read', 4, {'epoch': 7}))
        self.assertIn('error', sent[-1])
        service.read.assert_not_awaited()
        await relay.close()

    async def test_cross_profile_and_unmatched_frames_are_suppressed_end_to_end(self):
        self.security_fixture = True
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    self.assertEqual(socket.receive_json()['params']['type'], 'gateway.ready')
                    socket.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'display.observe', 'params': {}})
                    lease, status, failure = socket.receive_json(), socket.receive_json(), socket.receive_json()
                    self.assertEqual(lease['params']['type'], 'display.lease')
                    self.assertEqual(status['params']['type'], 'display.status')
                    self.assertEqual(failure['id'], 1)
                    self.assertIn('error', failure)
                    self.assertNotIn('marker', json.dumps([lease, status, failure]))
                    socket.send_json({'jsonrpc': '2.0', 'id': 2, 'method': 'viewer.selection.read', 'params': {'epoch': 2}})
                    self.assertIn('error', socket.receive_json())
        await asyncio.to_thread(browser)
        self.assertEqual(self.reads, [])

    async def test_status_lease_replies_and_duplicate_request_id(self):
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    socket.receive_json()
                    socket.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'display.status', 'params': {}})
                    self.assertTrue(socket.receive_json()['result']['running'])
                    socket.send_json({'jsonrpc': '2.0', 'id': 2, 'method': 'display.lease.acquire', 'params': {'viewer_id': 'fixture-viewer'}})
                    self.assertEqual(socket.receive_json()['result']['lease']['epoch'], 2)
                    socket.send_json({'jsonrpc': '2.0', 'id': 1, 'method': 'display.observe', 'params': {}})
                    with self.assertRaises(WebSocketDisconnect):
                        socket.receive_json()
        await asyncio.to_thread(browser)
        self.assertEqual([item['method'] for item in self.events], ['display.status', 'display.lease.acquire'])

    async def test_unconfigured_socket_and_health_fail_closed(self):
        def browser():
            with TestClient(self.app) as client:
                self.assertEqual(client.get('/api/plugins/herald-screen/health').json()['status'], 'unconfigured')
                self.assertEqual(client.get('/api/plugins/herald-screen/health').json()['copy'], 'unavailable')
                with self.assertRaises(WebSocketDisconnect):
                    with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols):
                        self.fail('Unconfigured socket admitted')
        with patch.object(self.api, 'upstream_url', return_value=None):
            await asyncio.to_thread(browser)
        self.assertEqual(self.events, [])
        self.assertEqual(self.reads, [])

    async def test_configured_health_reports_gateway_primary_capability(self):
        def browser():
            with TestClient(self.app) as client:
                self.assertEqual(client.get('/api/plugins/herald-screen/health').json()['copy'], 'protected-primary')
        await asyncio.to_thread(browser)

    async def test_unexpected_link_loss_closes_rpc_abnormally(self):
        def browser():
            with TestClient(self.app) as client:
                with client.websocket_connect(self.path, headers=self.headers, subprotocols=self.protocols) as socket:
                    socket.receive_json()
                    socket.close(code=1006)
                    self.assertTrue(self.upstream_closed.wait(3), 'upstream did not observe disconnect')
        await asyncio.to_thread(browser)
        self.assertEqual(self.closed, [1011])

    async def test_relay_cancellation_and_task_failure_close_upstream_abnormally(self):
        for failure in ('cancel', 'phone_exception'):
            with self.subTest(failure=failure):
                started = asyncio.Event()
                closes = []
                class Upstream:
                    protocol = 'hermes-gateway-v1'
                    close_code = None
                    def __aiter__(self):
                        return self
                    async def __anext__(self):
                        await asyncio.Event().wait()
                    async def close(self, code=1000):
                        closes.append(code)
                    async def __aenter__(self):
                        return self
                    async def __aexit__(self, *args):
                        if not closes:
                            await self.close()
                upstream = Upstream()
                class Session:
                    def __init__(self, **kwargs):
                        pass
                    async def __aenter__(self):
                        return self
                    async def __aexit__(self, *args):
                        pass
                    def ws_connect(self, *args, **kwargs):
                        return upstream
                class Socket:
                    scope = {'subprotocols': self.protocols, 'scheme': 'ws'}
                    headers = {'host': 'testserver', 'origin': 'http://testserver'}
                    async def accept(self, **kwargs):
                        pass
                    async def receive(self):
                        started.set()
                        if failure == 'phone_exception':
                            raise RuntimeError('synthetic transport failure')
                        await asyncio.Event().wait()
                    async def close(self, code):
                        pass
                    async def send_json(self, value):
                        pass
                with patch.object(self.api.aiohttp, 'ClientSession', Session):
                    task = asyncio.create_task(self.api.rpc_socket(Socket()))
                    await asyncio.wait_for(started.wait(), 2)
                    if failure == 'cancel':
                        task.cancel()
                        with self.assertRaises(asyncio.CancelledError):
                            await task
                    else:
                        await asyncio.wait_for(task, 2)
                self.assertEqual(closes, [1011])

    async def test_malformed_or_cross_profile_requests_fail_closed(self):
        invalid = ['[]', 'null', '{}', '{bad', json.dumps({'jsonrpc': '2.0', 'method': [], 'params': {}}), json.dumps({'jsonrpc': '2.0', 'method': 'display.status', 'params': {'profile': 'other-profile'}})]
        for text in invalid:
            self.assertFalse(self.api.allowed_request(text))
        self.assertFalse(self.api.browser_admission({'scheme': 'ws'}, {'host': 'testserver', 'origin': 'http://testserver@foreign.invalid'}, self.protocols))
        self.assertFalse(self.api.browser_admission({'scheme': 'ws'}, {'host': 'testserver', 'origin': 'null'}, self.protocols))

    async def test_cleanup_real_upstream_before_failed_or_hung_relay(self):
        import aiohttp
        for behavior in ('fail', 'hang'):
            with self.subTest(behavior=behavior):
                self.closed.clear()
                self.upstream_closed.clear()
                order = []
                class Relay:
                    async def close(self):
                        order.append('relay')
                        if behavior == 'fail':
                            raise ValueError('relay failure')
                        await asyncio.Event().wait()
                class Socket:
                    async def close(self, code):
                        order.append(('phone', code))
                async with aiohttp.ClientSession() as session:
                    async with session.ws_connect(self.api.upstream_url(), protocols=self.protocols) as upstream:
                        original_close = upstream.close
                        async def close(*args, **kwargs):
                            order.append(('upstream', kwargs.get('code')))
                            return await original_close(*args, **kwargs)
                        upstream.close = close
                        if behavior == 'fail':
                            with self.assertRaises(ValueError):
                                await self.api._finish_rpc(upstream, Socket(), Relay(), (), 1011)
                        else:
                            await asyncio.wait_for(self.api._finish_rpc(upstream, Socket(), Relay(), (), 1011), 3)
                            await asyncio.sleep(0)
                self.assertTrue(await asyncio.to_thread(self.upstream_closed.wait, 2))
                self.assertEqual(self.closed, [1011])
                self.assertEqual(order[:2], [('upstream', 1011), 'relay'])
                self.assertIn(('phone', 1011), order)

    async def test_repeated_direct_cancellation_does_not_cancel_owned_close(self):
        entered, release = asyncio.Event(), asyncio.Event()
        order = []
        class Upstream:
            _response = SimpleNamespace(close=lambda: order.append('abort'))
            async def close(self, code):
                order.append(('upstream-start', code))
                entered.set()
                await release.wait()
                order.append(('upstream-end', code))
        class Relay:
            async def close(self):
                order.append('relay')
        class Socket:
            async def close(self, code):
                order.append(('phone', code))
        task = asyncio.create_task(self.api._finish_rpc(Upstream(), Socket(), Relay(), (), 1011))
        await asyncio.wait_for(entered.wait(), 1)
        task.cancel()
        await asyncio.sleep(0)
        task.cancel()
        await asyncio.sleep(0)
        self.assertFalse(task.done())
        release.set()
        with self.assertRaises(asyncio.CancelledError):
            await asyncio.wait_for(task, 1)
        self.assertEqual(order, [('upstream-start', 1011), ('upstream-end', 1011), 'relay', ('phone', 1011)])

    async def test_upstream_close_timeout_aborts_before_return(self):
        order = []
        class Upstream:
            _response = SimpleNamespace(close=lambda: order.append('abort'))
            async def close(self, code):
                order.append(('upstream', code))
                await asyncio.Event().wait()
        class Relay:
            async def close(self):
                order.append('relay')
        class Socket:
            async def close(self, code):
                order.append(('phone', code))
        await asyncio.wait_for(self.api._finish_rpc(Upstream(), Socket(), Relay(), (), 1011), 3)
        self.assertEqual(order, [('upstream', 1011), 'abort'])

    async def test_upstream_close_error_aborts_before_relay(self):
        order = []
        class Upstream:
            _response = SimpleNamespace(close=lambda: order.append('abort'))
            async def close(self, code):
                order.append(('upstream', code))
                raise OSError('synthetic close failure')
        class Relay:
            async def close(self):
                order.append('relay')
        class Socket:
            async def close(self, code):
                order.append(('phone', code))
        await self.api._finish_rpc(Upstream(), Socket(), Relay(), (), 1011)
        self.assertEqual(order, [('upstream', 1011), 'abort', 'relay', ('phone', 1011)])



if __name__ == '__main__':
    unittest.main()
