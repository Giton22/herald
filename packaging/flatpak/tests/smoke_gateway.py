"""Loopback-only installed-smoke fixture; never reads files or forwards provider requests."""
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import struct
from urllib.parse import parse_qs, urlsplit

# Each click gives a colour not seen before (green, magenta, orange), so a page reloaded by a resize can't pass the
# second click by toggling back to a colour it already had.
HTML = '''<!doctype html><html><body style="margin:0;background:#19c37d;height:100vh">
<button style="background:#1245ab;color:white;border:0;width:240px;height:112px" onclick="this.dataset.n=(+(this.dataset.n||0))+1;document.body.style.background=['#19c37d','#c319e0','#e0a019'][Math.min(this.dataset.n,2)]">Widget fixture</button>
<p>LOCAL_WIDGET_READY</p></body></html>'''
SESSION = {'id': 'smoke-session', 'title': 'Flatpak fixture', 'source': 'desktop', 'message_count': 1}


class Gateway(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        pass

    def reply(self, value, status=200, cookie=False):
        if status == 200:
            self.server.served.append((self.command, urlsplit(self.path).path))
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        if cookie:
            self.send_header('Set-Cookie', 'hermes_session_at=synthetic-fixture; Path=/; HttpOnly')
        self.end_headers()
        self.wfile.write(data)

    def signed_in(self):
        """As the gateway: only the session cookie the login set opens the API, so the sandbox's cookie store counts."""
        cookies = [c.strip() for c in self.headers.get('Cookie', '').split(';')]
        return self.server.authenticated and 'hermes_session_at=synthetic-fixture' in cookies

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        path = urlsplit(self.path).path
        self.server.calls.append(('POST', path))
        print('POST', path, flush=True)
        if path == '/auth/password-login':
            if json.loads(body) != {'provider': 'basic', 'username': 'fixture', 'password': 'fixture'}:
                self.reply({}, 401)
                return
            self.server.authenticated = True
            self.reply({'ok': True}, cookie=True)
        elif path == '/api/auth/ws-ticket':
            if not self.signed_in():
                self.reply({}, 401)
                return
            self.reply({'ticket': 'fixture', 'ttl_seconds': 30})
        else:
            self.reply({}, 404)

    def do_GET(self):
        path = urlsplit(self.path).path
        self.server.calls.append(('GET', path))
        print('GET', path, flush=True)
        if path == '/api/ws':
            protocols = [p.strip() for p in self.headers.get('Sec-WebSocket-Protocol', '').split(',')]
            if 'hermes-gateway-ticket.fixture' not in protocols:
                self.reply({}, 401)
                return
            self.websocket()
        elif path == '/api/status':
            self.reply({'version': 'fixture', 'gateway_running': True, 'auth_required': True, 'auth_providers': ['basic']})
        elif path == '/api/auth/me':
            self.reply({'user_id': 'fixture', 'display_name': 'Fixture'}, 200 if self.signed_in() else 401)
        elif path.startswith('/api/') and not self.signed_in():
            self.reply({}, 401)
        elif path == '/api/sessions':
            self.reply({'sessions': [SESSION], 'total': 1})
        elif path == '/api/sessions/smoke-session/messages':
            self.reply({'session_id': 'smoke-session', 'messages': [
                {'id': 1, 'role': 'assistant', 'content': '::preview{file="/tmp/flatpak-smoke.html"}', 'timestamp': 1700000000}]})
        elif path == '/api/sessions/smoke-session':
            self.reply({})
        elif path == '/api/fs/read-text' and parse_qs(urlsplit(self.path).query).get('path') == ['/tmp/flatpak-smoke.html']:
            self.reply({'text': HTML, 'byteSize': len(HTML.encode()), 'binary': False, 'truncated': False})
        else:
            self.reply({}, 404)

    def send_frame(self, value, opcode=1):
        data = json.dumps(value).encode() if opcode == 1 else value
        head = bytes([0x80 | opcode])
        head += bytes([len(data)]) if len(data) < 126 else bytes([126]) + struct.pack('!H', len(data))
        self.wfile.write(head + data)
        self.wfile.flush()

    def websocket(self):
        accept = base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key'] +
                     '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101)
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', accept)
        self.send_header('Sec-WebSocket-Protocol', 'hermes-gateway-v1')
        self.end_headers()
        self.send_frame({'jsonrpc': '2.0', 'method': 'event', 'params': {
            'type': 'gateway.ready', 'payload': {'heartbeat': True, 'replay_epoch': 'fixture'}}})
        while True:
            head = self.rfile.read(2)
            if not head:
                return
            opcode = head[0] & 15
            length = head[1] & 127
            if length == 126:
                length = struct.unpack('!H', self.rfile.read(2))[0]
            elif length == 127:
                length = struct.unpack('!Q', self.rfile.read(8))[0]
            assert length < 1024 * 1024
            mask = self.rfile.read(4) if head[1] & 128 else b''
            data = self.rfile.read(length)
            if mask:
                data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
            if opcode == 8:
                return
            if opcode == 9:
                self.send_frame(data, 10)
                continue
            if opcode != 1:
                continue
            call = json.loads(data)
            method = call.get('method')
            print('RPC', method, flush=True)
            self.server.calls.append(('RPC', method))
            if 'id' not in call:
                continue
            self.send_frame({'jsonrpc': '2.0', 'id': call['id'], **self.server.rpc(method)})


def server():
    service = ThreadingHTTPServer(('127.0.0.1', 0), Gateway)
    def rpc(method):
        results = {'client.capabilities': {}, 'gateway.ping': {},
                   'session.active_list': {'sessions': []},
                   'profiles.list': {'profiles': []},
                   'session.resume': {'session_id': 'smoke-runtime', 'running': False, 'info': {}}}
        if method in results:
            return {'result': results[method]}
        return {'error': {'code': -32601, 'message': 'Fixture method unavailable'}}
    service.rpc = rpc
    service.calls = []
    # Requests answered with 200: past the cookie check, unlike a 401 in calls.
    service.served = []
    service.authenticated = False
    return service
