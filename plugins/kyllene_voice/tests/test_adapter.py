import asyncio
import contextlib
import importlib.util
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import hermes_test_path  # noqa: F401
from hermes_cli.plugin_host_child import HostRuntime
from hermes_cli.plugin_host_wire import decode, encode

DASH = Path(__file__).resolve().parents[1] / 'dashboard'
OFFER = 'v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n'
HISTORY = [{'type': 'message', 'role': 'user', 'content': [{'type': 'input_text', 'text': 'hello'}]},
           {'type': 'message', 'role': 'assistant', 'content': [{'type': 'output_text', 'text': 'hi'}]}]

class Tests(unittest.TestCase):
    def setUp(self):
        self.host = HostRuntime.__new__(HostRuntime)
        self.host.asgi_apps = {}
        self.host._run = asyncio.run
        self.scopes = []
        @contextlib.contextmanager
        def scope(profile):
            self.scopes.append(profile)
            yield
        self.scope = patch('hermes_cli.web_server_profiles._config_profile_scope', scope)
        self.scope.start()
        from hermes_cli import web_server  # finish gateway plugin discovery before loading our stage
        self.call()
        self.api = sys.modules['hermes_dashboard_plugin_kyllene_voice']
        self.creds = patch.object(self.api, 'credentials', return_value=('token', 'account'))
        self.exchange = patch.object(self.api, 'exchange', return_value='v=0\r\nanswer')
        self.settings = patch.object(self.api, 'stock_settings', return_value=('profile instructions', 'ember'))
        self.c = self.creds.start(); self.e = self.exchange.start(); self.settings.start()
    def tearDown(self):
        self.scope.stop(); self.creds.stop(); self.exchange.stop(); self.settings.stop()
    def call(self, body=None, profile='alpha', auth=True):
        result = decode(self.host.op_asgi(dict(plugin='kyllene_voice', dashboard_dir=str(DASH), api_file='plugin_api.py',
            method='POST', path='/gpt-live/session', query='profile='+profile, headers=[],
            body=encode(json.dumps(body if body is not None else {}).encode()), authenticated=auth)))
        return result['status'], json.loads(result['body'])
    def status(self, profile='alpha', auth=True):
        result = decode(self.host.op_asgi(dict(plugin='kyllene_voice', dashboard_dir=str(DASH), api_file='plugin_api.py',
            method='GET', path='/gpt-live/status', query='profile='+profile, headers=[], body=encode(b''), authenticated=auth)))
        return result['status'], json.loads(result['body'])

    def test_status_is_authenticated_scoped_read_only_and_no_call(self):
        self.assertEqual(self.status(auth=False)[0], 401)
        status, body = self.status(profile='beta')
        self.assertEqual(status, 200)
        self.assertEqual(body, {'ok': True, 'source': 'plugin', 'contract': 1})
        self.assertEqual(self.scopes[-1], 'beta')
        self.c.assert_called_once_with(False)
        self.e.assert_not_called()

    def test_status_sanitizes_credentials_and_voice_failure(self):
        self.c.side_effect = self.api.ProviderCredentialsError('SECRET')
        status, body = self.status()
        self.assertEqual(status, 400)
        self.assertIn('dashboard sign-in is separate', body['detail'])
        self.assertNotIn('SECRET', str(body))
        self.e.assert_not_called()

    def test_provider_auth_error_is_not_dashboard_401(self):
        self.e.side_effect = self.api.urllib.error.HTTPError('https://example.invalid', 401, 'SECRET', {}, None)
        status, body = self.call({'sdp': OFFER, 'billing': 'subscription', 'history': HISTORY})
        self.assertEqual(status, 502)
        self.assertIn('dashboard sign-in is separate', body['detail'])
        self.assertNotIn('SECRET', str(body))

    def test_admission_and_scoped_history(self):
        body = {'sdp': OFFER, 'billing': 'subscription', 'history': HISTORY}
        self.assertEqual(self.call(body, auth=False)[0], 401)
        for profile in ('alpha', 'beta'):
            status, result = self.call(body, profile=profile)
            self.assertEqual(status, 200); self.assertEqual(result['auth'], 'subscription')
            self.assertEqual(self.scopes[-1], profile)
            self.e.assert_called_with('token', 'account', OFFER, 'profile instructions', 'ember', HISTORY)
    def test_invalid_and_bounds_do_not_resolve_creds(self):
        body = {'sdp': OFFER, 'billing': 'subscription', 'history': HISTORY}
        for change in ({'sdp': 'v=0'}, {'billing': 'api'}, {'history': HISTORY * 13},
                       {'history': [{'type':'message','role':'developer','content':[{'type':'input_text','text':'x'}]}]},
                       {'history': [{'type':'message','role':'assistant','content':[{'type':'input_text','text':'x'}]}]},
                       {'history': [{'type':'message','role':'user','content':[{'type':'input_text','text':'x'*1201}]}]},
                       {'other':'x'}):
            self.assertEqual(self.call({**body, **change})[0], 400)
        self.c.assert_not_called(); self.e.assert_not_called()
    def test_failure_redacted_no_fallback(self):
        self.e.side_effect = RuntimeError('SECRET')
        status, result = self.call({'sdp':OFFER,'billing':'subscription','history':HISTORY})
        self.assertEqual(status, 502); self.assertNotIn('SECRET', str(result))
        self.assertIn('No API fallback', result['detail']); self.e.assert_called_once()
    def test_real_profile_config_function(self):
        self.settings.stop()
        try:
            with patch('tools.voice_live.build_session_config', return_value={'instructions':'PROFILE PERSONA', 'audio':{'output':{'voice':'ember'}}}):
                self.assertEqual(self.api.stock_settings(), ('PROFILE PERSONA','ember'))
            with patch('tools.voice_live.build_session_config', return_value={'instructions':'x', 'audio':{'output':{'voice':'marin'}}}):
                self.assertEqual(self.api.stock_settings(), ('x', 'marin'))
        finally: self.settings.start()
    def test_provider_wire_preserves_roles(self):
        self.exchange.stop()
        captured = []
        class Reply:
            status=201
            headers={'Location':'/calls/rtc_test'}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, n): return b'v=0\r\nanswer'
        class Opener:
            def open(self, req, timeout):
                captured.append(json.loads(req.data)); return Reply()
        try:
            with patch.object(self.api.urllib.request, 'build_opener', return_value=Opener()):
                self.api.exchange('token','account',OFFER,'persona','ember',HISTORY)
            session = captured[0]['session']
            self.assertEqual(session['initial_items'], HISTORY)
            self.assertEqual(session['delegation'], {'type':'client'})
            self.assertEqual(session['model'], 'gpt-live-1-codex')
        finally: self.exchange.start()

if __name__ == '__main__': unittest.main()
