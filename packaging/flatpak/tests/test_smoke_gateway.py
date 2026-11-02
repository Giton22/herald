import http.client
import json
import threading
import unittest

from smoke_gateway import HTML, server


class SmokeGatewayTest(unittest.TestCase):
    def setUp(self):
        self.service = server()
        self.worker = threading.Thread(target=self.service.serve_forever)
        self.worker.start()
        self.client = http.client.HTTPConnection('127.0.0.1', self.service.server_port, timeout=2)

    def tearDown(self):
        self.client.close()
        self.service.shutdown()
        self.service.server_close()
        self.worker.join(timeout=2)
        self.assertFalse(self.worker.is_alive())

    def request(self, method, path, body=None, headers=None):
        self.client.request(method, path, json.dumps(body) if body else None, headers or {})
        response = self.client.getresponse()
        return response.status, json.loads(response.read())

    def sign_in(self):
        self.request('POST', '/auth/password-login',
                     {'provider': 'basic', 'username': 'fixture', 'password': 'fixture'})
        return {'Cookie': 'hermes_session_at=synthetic-fixture'}

    def test_the_api_needs_the_cookie_the_login_set(self):
        self.assertEqual(self.request('GET', '/api/sessions')[0], 401)
        cookie = self.sign_in()
        self.assertEqual(self.request('GET', '/api/sessions')[0], 401)
        self.assertEqual(self.request('POST', '/api/auth/ws-ticket')[0], 401)
        self.assertNotIn(('GET', '/api/sessions'), self.service.served)
        self.assertEqual(self.request('GET', '/api/sessions', headers=cookie)[0], 200)
        self.assertEqual(self.request('POST', '/api/auth/ws-ticket', headers=cookie)[0], 200)
        self.assertIn(('GET', '/api/sessions'), self.service.served)

    def test_the_websocket_needs_the_ticket_subprotocol(self):
        upgrade = {'Upgrade': 'websocket', 'Connection': 'Upgrade',
                   'Sec-WebSocket-Key': 'dGhlIHNhbXBsZSBub25jZQ==', 'Sec-WebSocket-Version': '13'}
        self.assertEqual(self.request('GET', '/api/ws', headers={
            **upgrade, 'Sec-WebSocket-Protocol': 'hermes-gateway-v1'})[0], 401)

    def test_fixture_authentication_accepts_only_synthetic_credentials(self):
        status, _ = self.request('POST', '/auth/password-login',
                                 {'provider': 'basic', 'username': 'fixture', 'password': 'wrong'})
        self.assertEqual(status, 401)
        self.assertFalse(self.service.authenticated)
        status, _ = self.request('POST', '/auth/password-login',
                                 {'provider': 'basic', 'username': 'fixture', 'password': 'fixture'})
        self.assertEqual(status, 200)
        self.assertTrue(self.service.authenticated)
        self.assertEqual(self.request('GET', '/api/auth/me',
                                      headers={'Cookie': 'hermes_session_at=synthetic-fixture'})[0], 200)

    def test_fixture_reads_only_its_synthetic_html_and_never_host_files(self):
        cookie = self.sign_in()
        status, body = self.request('GET', '/api/fs/read-text?path=/tmp/flatpak-smoke.html', headers=cookie)
        self.assertEqual(status, 200)
        self.assertEqual(body['text'], HTML)
        self.assertEqual(body['byteSize'], len(HTML.encode()))
        self.assertEqual(self.request('GET', '/api/fs/read-text?path=/etc/passwd', headers=cookie)[0], 404)
        self.assertEqual(self.request('POST', '/api/chat', {'message': 'never forward'})[0], 404)

    def test_unknown_rpc_is_rejected_instead_of_pretending_to_execute_it(self):
        self.assertEqual(self.service.rpc('chat.send'), {'error': {'code': -32601, 'message': 'Fixture method unavailable'}})
        self.assertEqual(self.service.rpc('session.resume')['result']['session_id'], 'smoke-runtime')
        self.assertEqual(self.service.rpc('profiles.list')['result']['profiles'], [])
