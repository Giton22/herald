"""Gateway-origin Screen page and authenticated, lease-fenced selection relay.

HTTP authentication/consent is supplied by Hermes's plugin API host. The socket
is accepted only AFTER the stock gateway redeems its normal one-use browser
WebSocket ticket; this plugin never mints or bypasses gateway authentication.
"""
from pathlib import Path
from urllib.parse import urlsplit
import asyncio
import importlib.util
import json
import logging
import os
import secrets
import sys

import aiohttp
import anyio
from fastapi import APIRouter, HTTPException, Request, WebSocket
from fastapi.responses import FileResponse
from hermes_constants import get_hermes_home

ROOT = Path(__file__).resolve().parent
HOME = Path(get_hermes_home()).resolve()

def upstream_url():
    # Explicit operator configuration; never derive a credential target from Host.
    port = os.environ.get('HERMES_SCREEN_GATEWAY_PORT', '')
    if not port.isascii() or not port.isdecimal() or len(port) > 5 or not 1 <= int(port) <= 65535:
        return None
    return f'ws://127.0.0.1:{int(port)}/api/ws'
ASSETS = ROOT / 'viewer'
PACKAGE = ROOT.parent / 'herald_screen_backend'
_spec = importlib.util.spec_from_file_location('herald_screen_backend', PACKAGE / '__init__.py', submodule_search_locations=[str(PACKAGE)])
if _spec is None or _spec.loader is None:
    raise RuntimeError('Screen backend is unavailable')
_backend = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = _backend
_spec.loader.exec_module(_backend)
SelectionService = _backend.SelectionService
SelectionError = _backend.SelectionError
SelectionRelay = _backend.SelectionRelay
SelectionDenied = _backend.SelectionDenied
router = APIRouter()
_log = logging.getLogger(__name__)
ALLOWED = {
    'display.start': set(),
    'display.observe': {'viewer_id'},
    'display.status': set(),
    'display.lease.acquire': {'viewer_id', 'reason'},
    'display.lease.release': {'viewer_id'},
    'viewer.selection.read': {'epoch'},
}


def active_profile():
    return Path(get_hermes_home()).resolve() == HOME


def browser_admission(scope, headers, protocols):
    origin = headers.get('origin', '')
    try:
        value = urlsplit(origin)
        # ASGI server/middleware supplies the effective scheme; never interpret
        # forwarding headers here. TLS proxies must be explicitly trusted and
        # must overwrite client-supplied forwarding headers.
        expected_scheme = {'ws': 'http', 'wss': 'https'}.get(scope.get('scheme'))
        valid_origin = expected_scheme is not None and value.scheme == expected_scheme and value.netloc.lower() == headers.get('host', '').lower() and value.path in ('', '/') and not value.query and not value.fragment and not value.username and not value.password
    except ValueError:
        return False
    return bool(valid_origin and len(protocols) == 2 and protocols[0] == 'hermes-gateway-v1' and protocols[1].startswith('hermes-gateway-ticket.') and 0 < len(protocols[1]) < 8192)


def allowed_request(text):
    if len(text) > 65536:
        return False
    try:
        value = json.loads(text)
    except (ValueError, TypeError):
        return False
    if not isinstance(value, dict) or value.get('jsonrpc') != '2.0':
        return False
    method, params = value.get('method'), value.get('params', {})
    return isinstance(method, str) and method in ALLOWED and isinstance(params, dict) and set(params) <= ALLOWED[method]


class LiveSelection:
    async def read(self, viewer, epoch):
        # Placement is observed by the relay, not atomically locked to this read.
        return await SelectionService(HOME).read(viewer, epoch)


async def _close_rpc(upstream, socket, relay, tasks, code, viewers=()):
    """Stop forwarding, then enqueue owner-bound releases on the same RPC leg.

    Gateway controls are serial: releases follow every forwarded acquisition,
    including one still executing. This is best effort if the leg is lost.
    """
    for task in tasks:
        if not task.done():
            task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    for viewer in viewers:
        try:
            identifier = 'screen-cleanup-' + secrets.token_hex(24)
            await upstream.send_json({'jsonrpc': '2.0', 'id': identifier,
                                      'method': 'display.lease.release',
                                      'params': {'viewer_id': viewer}})
            while True:
                msg = await upstream.receive()
                if msg.type != aiohttp.WSMsgType.TEXT:
                    raise ConnectionError('gateway closed during lease cleanup')
                try:
                    response = json.loads(msg.data)
                except (ValueError, TypeError):
                    continue
                if isinstance(response, dict) and response.get('id') == identifier:
                    if 'error' in response or not isinstance(response.get('result'), dict):
                        raise ConnectionError('gateway refused lease cleanup')
                    break
        except Exception:
            _log.warning('Screen owner-bound lease cleanup could not be confirmed')
            break
    try:
        await upstream.close(code=code)
    except Exception:
        # Prevent aiohttp's context manager from sending a later clean close.
        upstream._response.close()
    try:
        await relay.close()
    finally:
        try:
            await socket.close(code=code)
        except RuntimeError:
            pass


async def _finish_rpc(upstream, socket, relay, tasks, code, viewers=()):
    # The task owns cleanup independently of cancellation of the request task.
    cleanup = asyncio.create_task(_close_rpc(upstream, socket, relay, tasks, code, viewers))
    deadline = asyncio.get_running_loop().time() + 2
    cancelled = False
    with anyio.CancelScope(shield=True):
        while not cleanup.done():
            try:
                await asyncio.wait_for(asyncio.shield(cleanup), max(0, deadline - asyncio.get_running_loop().time()))
            except asyncio.CancelledError:
                cancelled = True
            except asyncio.TimeoutError:
                _log.warning('Screen RPC cleanup timed out; lease handback is unconfirmed')
                # Abort the transport before aiohttp's context can default to 1000.
                upstream._response.close()
                cleanup.cancel()
                break
    if cleanup.done():
        cleanup.result()
    else:
        # Retrieve a later exception without keeping the request open indefinitely.
        cleanup.add_done_callback(lambda task: task.exception() if not task.cancelled() else None)
    if cancelled:
        raise asyncio.CancelledError


@router.get('/health')
async def health():
    if not active_profile():
        raise HTTPException(404)
    configured = bool(upstream_url())
    return {'status': 'ok' if configured else 'unconfigured', 'copy': 'protected-primary' if configured else 'unavailable', 'rpc': 'stock-gateway-ticket'}


@router.websocket('/rpc')
async def rpc_socket(socket: WebSocket):
    protocols = socket.scope.get('subprotocols', [])
    upstream_url_value = upstream_url()
    if not active_profile() or not upstream_url_value or not browser_admission(socket.scope, socket.headers, protocols):
        await socket.close(code=1008)
        return
    headers = {key: socket.headers[key] for key in ('cookie', 'origin', 'host') if key in socket.headers}
    # No query target, credential persistence, session cookie jar or compression
    # header forwarding: each WebSocket leg negotiates independently.
    try:
        async with aiohttp.ClientSession(cookie_jar=aiohttp.DummyCookieJar()) as session:
            async with session.ws_connect(upstream_url_value, headers=headers, protocols=protocols, compress=0, max_msg_size=1048576) as upstream:
                if upstream.protocol not in (None, 'hermes-gateway-v1'):
                    await socket.close(code=1008)
                    return
                await socket.accept(subprotocol=upstream.protocol)
                relay = SelectionRelay(LiveSelection(), socket.send_json, str(HOME))
                seen_ids = set()
                owned_viewers = set()
                acquired_viewers = set()

                async def from_phone():
                    while True:
                        event = await socket.receive()
                        if event['type'] == 'websocket.disconnect':
                            return event.get('code', 1006)
                        text = event.get('text')
                        if text is None:
                            return 1003
                        if not allowed_request(text):
                            # No reflected body/identifier, clipboard text or credentials.
                            await socket.send_json({'jsonrpc': '2.0', 'id': None, 'error': {'code': -32600, 'message': 'Only Screen display requests are allowed.'}})
                            continue
                        request = json.loads(text)
                        identifier = request.get('id')
                        if not _backend.valid_id(identifier) or identifier in seen_ids or len(seen_ids) >= 8192:
                            return 1008
                        seen_ids.add(identifier)
                        if not await relay.client_text(text):
                            if request['method'] == 'display.lease.acquire':
                                viewer = request.get('params', {}).get('viewer_id')
                                if isinstance(viewer, str) and viewer in owned_viewers:
                                    # Record before send: cancellation may interrupt a write after delivery.
                                    acquired_viewers.add(viewer)
                            await upstream.send_str(text)

                async def from_gateway():
                    async for message in upstream:
                        if message.type == aiohttp.WSMsgType.TEXT:
                            permitted = relay.server_text(message.data)
                            if permitted is not None:
                                result = permitted.get('result')
                                if isinstance(result, dict) and isinstance(result.get('viewer_id'), str):
                                    owned_viewers.add(result['viewer_id'])
                                await socket.send_json(permitted)
                        elif message.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                            break
                    return upstream.close_code or 1011

                phone = asyncio.create_task(from_phone())
                gateway = asyncio.create_task(from_gateway())
                code = 1011
                try:
                    done, _ = await asyncio.wait((phone, gateway), return_when=asyncio.FIRST_COMPLETED)
                    # If both legs finish together, an abnormal result wins. A
                    # raised task exception must not become a clean close either.
                    results = await asyncio.gather(*done, return_exceptions=True)
                    if results and all(result == 1000 for result in results):
                        code = 1000
                finally:
                    await _finish_rpc(upstream, socket, relay, (phone, gateway), code, acquired_viewers)
    except (aiohttp.ClientError, asyncio.TimeoutError, OSError):
        try:
            await socket.close(code=1008)
        except RuntimeError:
            pass


@router.get('/{asset:path}')
async def asset_file(asset: str, request: Request):
    if not active_profile():
        raise HTTPException(404)
    target = (ASSETS / (asset or 'index.html')).resolve()
    if not target.is_relative_to(ASSETS.resolve()) or not target.is_file() or target.suffix not in ('.html', '.js', '.css', '.svg', '.png', '.json'):
        raise HTTPException(404)
    headers = {
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        'Referrer-Policy': 'no-referrer',
        'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'",
    }
    return FileResponse(target, headers=headers)
