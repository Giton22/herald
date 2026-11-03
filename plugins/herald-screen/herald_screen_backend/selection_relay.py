"""Project-only selection RPC on an upstream-authenticated gateway connection.

Viewer identity comes exclusively from that connection's trusted observe reply.
The native service independently checks the authoritative on-disk human lease.
No clipboard data is broadcast or forwarded through the RFB stream.
"""
import asyncio
import json
import re

METHOD = 'viewer.selection.read'

# Built-in stream-capable Hermes backends; unknown plugin backends fail closed.
PLACEMENTS = ('gateway', 'terminal:docker', 'terminal:ssh')


def valid_placement(value):
    return isinstance(value, str) and value in PLACEMENTS


class SelectionDenied(Exception):
    """An allowlisted, user-safe backend failure (never arbitrary stderr)."""


def message(text):
    if len(text) > 65536:
        return None
    try:
        value = json.loads(text)
    except (ValueError, TypeError):
        return None
    return value if isinstance(value, dict) else None


def valid_id(value):
    return (type(value) is int and value >= 0) or (isinstance(value, str) and 0 < len(value) <= 128)


def clean_lease(lease):
    if (not isinstance(lease, dict) or type(lease.get('epoch')) is not int or lease['epoch'] < 0
            or lease.get('holder') not in ('human', 'agent') or 'viewer_hash' not in lease):
        return None
    value = lease.get('viewer_hash')
    # Match the 12-character fingerprint emitted by Hermes and used by the viewer.
    if value is not None and (not isinstance(value, str) or re.fullmatch(r'[0-9a-f]{12}', value) is None):
        return None
    return {'epoch': lease['epoch'], 'holder': lease['holder'], 'viewer_hash': value}


class SelectionRelay:
    def __init__(self, service, send, profile_key):
        self.service = service
        self.send = send
        self.profile_key = profile_key
        self.viewer = None
        self.placement = None
        # Most recently received valid status, independent of observation.
        self.status_gateway = None
        self.revision = 0
        self.observe = None
        self.pending = {}
        self.closed = False
        self.task = None
        self.read_epoch = None
        self.read_id = None
        self.read_started = False
        self.read_replying = False

    async def reply(self, identifier, *, result=None, error=None):
        if self.closed:
            return
        response = {'jsonrpc': '2.0', 'id': identifier}
        response.update({'error': error} if error is not None else {'result': result})
        try:
            await self.send(response)
        except (ConnectionError, RuntimeError):
            # The transport owns disconnect cleanup. Never log response bodies.
            pass

    async def deny(self, identifier, message, code=-32001):
        await self.reply(identifier, error={'code': code, 'message': message})

    def invalidate(self, *, unbind=False):
        self.revision += 1
        if unbind:
            self.viewer = None
            self.placement = None
        # A task cancelled before its first turn never enters read's finally.
        # Let an unstarted task run its revision check instead. Do not cancel
        # twice: a second cancellation could interrupt its denial reply.
        if (self.task is not None and self.read_started and not self.read_replying
                and not self.task.cancelling()):
            self.task.cancel()

    async def client_text(self, text):
        """Return True only for a consumed local RPC; all other frames relay."""
        request = message(text)
        if request is None:
            return False
        method, identifier = request.get('method'), request.get('id')
        if method == METHOD:
            params = request.get('params')
            if (request.get('jsonrpc') != '2.0' or not valid_id(identifier) or
                    not isinstance(params, dict) or set(params) != {'epoch'} or
                    type(params['epoch']) is not int or params['epoch'] < 0):
                await self.deny(identifier if valid_id(identifier) else None, 'Invalid Copy request.', -32602)
            elif self.closed or self.viewer is None:
                await self.deny(identifier, 'Take control of a connected screen before copying.')
            elif self.placement != 'gateway' or self.status_gateway is False:
                await self.deny(identifier, 'Copy is unavailable for sandbox screens.')
            elif self.task is not None:
                await self.deny(identifier, 'A Copy operation is already in progress.')
            else:
                self.read_epoch, self.read_id = params['epoch'], identifier
                self.task = asyncio.create_task(self.read(identifier, self.viewer, params['epoch'], self.revision))
            return True
        if method == 'display.observe' and valid_id(identifier):
            self.invalidate(unbind=True)
            self.observe = (identifier, self.revision)
        elif method == 'display.lease.release':
            self.invalidate()
        if valid_id(identifier):
            self.pending[identifier] = (method, self.revision)
        return False

    def server_text(self, text):
        """Return a sanitized permitted frame, or None to suppress it."""
        response = message(text)
        if response is None or response.get('jsonrpc') != '2.0' or self.closed:
            return None
        if response.get('method') == 'event' and 'id' not in response:
            params = response.get('params')
            if not isinstance(params, dict) or not isinstance(params.get('payload'), dict):
                return None
            kind, payload = params.get('type'), params['payload']
            if kind == 'gateway.ready':
                return {'jsonrpc': '2.0', 'method': 'event', 'params': {'type': kind, 'payload': {}}}
            if kind not in ('display.status', 'display.lease') or payload.get('profile_key') != self.profile_key:
                return None
            if kind == 'display.status':
                if type(payload.get('running')) is not bool or not valid_placement(payload.get('placement')):
                    self.status_gateway = False
                    self.invalidate(unbind=True)
                    return None
                self.status_gateway = payload['running'] and payload['placement'] == 'gateway'
                if not self.status_gateway or (self.viewer is not None and payload['placement'] != self.placement):
                    self.invalidate(unbind=True)
            lease = clean_lease(payload.get('lease'))
            if lease is None:
                return None
            if kind == 'display.lease' and self.task is not None and lease['epoch'] != self.read_epoch:
                self.invalidate()
            clean = {'lease': lease}
            if kind == 'display.status':
                clean.update(running=payload['running'], placement=payload['placement'])
            return {'jsonrpc': '2.0', 'method': 'event', 'params': {'type': kind, 'payload': clean}}
        identifier = response.get('id')
        if 'method' in response or not valid_id(identifier) or identifier not in self.pending:
            return None
        method, revision = self.pending.pop(identifier)
        safe_error = {'jsonrpc': '2.0', 'id': identifier, 'error': {'code': -32001, 'message': 'Screen gateway request failed.'}}
        if ('error' in response) == ('result' in response) or 'error' in response:
            return safe_error
        result = response['result']
        if not isinstance(result, dict):
            return safe_error
        if method == 'display.observe':
            if not (self.observe == (identifier, revision) and revision == self.revision and
                    result.get('profile_key') == self.profile_key and result.get('path') == '/api/display/ws' and
                    valid_placement(result.get('placement')) and result.get('running') is True and
                    isinstance(result.get('viewer_id'), str) and 0 < len(result['viewer_id']) <= 512 and
                    isinstance(result.get('ticket'), str) and 0 < len(result['ticket']) <= 8192):
                return safe_error
            self.observe = None
            self.viewer = result['viewer_id']
            self.placement = result['placement']
        elif method in ('display.status', 'display.start') and (result.get('profile_key') != self.profile_key or
                type(result.get('running')) is not bool or not valid_placement(result.get('placement'))):
            if result.get('profile_key') == self.profile_key:
                if method == 'display.status':
                    self.status_gateway = False
                self.invalidate(unbind=True)
            return safe_error
        if method == 'display.status':
            gateway = result['running'] and result['placement'] == 'gateway'
            # An older request must not clear a more recent invalidation.
            if not gateway or revision == self.revision:
                self.status_gateway = gateway
            if not gateway:
                self.invalidate(unbind=True)
        if method in ('display.status', 'display.start') and self.viewer is not None and (
                not result['running'] or result['placement'] != self.placement):
            self.invalidate(unbind=True)
        clean_result = {}
        if method in ('display.observe', 'display.status', 'display.start'):
            clean_result.update(running=result['running'], placement=result['placement'])
        if method == 'display.observe':
            clean_result.update({key: result[key] for key in ('ticket', 'viewer_id', 'path')})
        if 'lease' in result:
            lease = clean_lease(result['lease'])
            if lease is None:
                if method == 'display.observe':
                    self.viewer = None
                    self.placement = None
                return safe_error
            clean_result['lease'] = lease
        elif method in ('display.observe', 'display.status', 'display.start', 'display.lease.acquire', 'display.lease.release'):
            if method == 'display.observe':
                self.viewer = None
                self.placement = None
            return safe_error
        return {'jsonrpc': '2.0', 'id': identifier, 'result': clean_result}

    async def read(self, identifier, viewer, epoch, revision):
        self.read_started = True
        result_started = False
        try:
            if self.closed or revision != self.revision or self.viewer != viewer:
                raise SelectionDenied('Control changed. Copy cancelled.')
            async with asyncio.timeout(5):
                text = await self.service.read(viewer, epoch)
            if self.closed or revision != self.revision or self.viewer != viewer:
                raise SelectionDenied('Control changed. Copy cancelled.')
            if not isinstance(text, str) or not text:
                raise SelectionDenied('No text selection is available in this app.')
            if len(text.encode('utf-8')) > 65536:
                raise SelectionDenied('Selected text is too large for this viewer.')
            result_started = True
            await self.reply(identifier, result={'text': text})
        except asyncio.CancelledError:
            self.read_replying = True
            await self.deny(identifier, 'Control changed. Copy cancelled.')
        except SelectionDenied as error:
            self.read_replying = True
            await self.deny(identifier, str(error))
        except Exception:
            # No exception repr/traceback: native owners and stdout are private.
            if not result_started:
                self.read_replying = True
                await self.deny(identifier, 'Selected text is unavailable or control changed.')
        finally:
            if self.task is asyncio.current_task():
                self.task = None
                self.read_epoch = self.read_id = None
                self.read_started = False
                self.read_replying = False

    async def close(self):
        self.closed = True
        self.observe = None
        self.invalidate(unbind=True)
        if self.task is not None:
            await asyncio.gather(self.task, return_exceptions=True)
