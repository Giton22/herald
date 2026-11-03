"""Stock subscription errors through the hosted ASGI dispatcher; no live calls."""
import asyncio
import contextlib
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import hermes_test_path  # noqa: F401
from hermes_cli.plugin_host_child import HostRuntime
from hermes_cli.plugin_host_wire import decode, encode

DASH = Path(__file__).resolve().parents[1] / 'dashboard'
OFFER = 'v=0\r\no=test\r\ns=test\r\nt=0 0\r\nm=audio 9 RTP/AVP 0\r\n'
PAYLOAD = {'sdp': OFFER, 'billing': 'subscription', 'history': []}


class StockVoiceErrorsTest(unittest.TestCase):
    def setUp(self):
        self.host = HostRuntime.__new__(HostRuntime)
        self.host.asgi_apps = {}
        self.host._run = asyncio.run
        self.scopes = []
        @contextlib.contextmanager
        def scope(profile):
            self.scopes.append(profile)
            yield
        self.scope_patch = patch('hermes_cli.web_server_profiles._config_profile_scope', scope)
        self.scope_patch.start()
        self.call({})  # Load the real plugin without resolving settings or credentials.
        app = next(iter(self.host.asgi_apps.values()))
        self.api = next(route.endpoint.__globals__ for route in app.routes
                        if getattr(route, 'path', None) == '/gpt-live/session')
        self.creds_mock = Mock(return_value=('TOKEN_SECRET', 'ACCOUNT_SECRET'))
        self.exchange_mock = Mock(return_value='v=0\r\nanswer')
        self.patches = patch.dict(self.api, credentials=self.creds_mock, exchange=self.exchange_mock)
        self.patches.start()

    def tearDown(self):
        self.patches.stop()
        self.scope_patch.stop()

    def call(self, body, query='profile=alpha'):
        result = decode(self.host.op_asgi(dict(plugin='kyllene_voice', dashboard_dir=str(DASH),
            api_file='plugin_api.py', method='POST', path='/gpt-live/session', query=query,
            headers=[], body=encode(json.dumps(body).encode()), authenticated=True)))
        return result['status'], json.loads(result['body'])

    def config(self, voice):
        return {'audio': {'output': {'voice': voice}}, 'instructions': 'PERSONA_SECRET'}

    def assert_detail(self, data):
        # ApiResult.ErrorBody recognises detail, message and error, but not reason.
        self.assertIsInstance(data.get('detail'), str)
        self.assertTrue(data['detail'])
        for secret in ('PERSONA_SECRET', 'TOKEN_SECRET', 'ACCOUNT_SECRET', 'PROVIDER_SECRET'):
            self.assertNotIn(secret, json.dumps(data))

    def test_unsupported_voice_before_credentials_in_selected_scope(self):
        for voice in ('unknown', [], None):
            with self.subTest(voice=voice), patch('tools.voice_live.build_session_config', return_value=self.config(voice)):
                status, data = self.call(PAYLOAD, 'profile=beta')
                self.assertEqual(status, 400)
                self.assertEqual(self.scopes[-1], 'beta')
                self.assert_detail(data)
                self.assertIn('voice.gpt_live.voice', data['detail'])
                self.assertIn('supported', data['detail'])
                self.assertIn('No API fallback', data['detail'])
                self.creds_mock.assert_not_called()
                self.exchange_mock.assert_not_called()

    def test_supported_voices_are_never_substituted_and_profile_is_scoped(self):
        for voice in self.api['VOICES']:
            with self.subTest(voice=voice), patch('tools.voice_live.build_session_config', return_value=self.config(voice)):
                status, data = self.call(PAYLOAD, 'profile=alpha')
                self.assertEqual(status, 200)
                self.assertEqual(self.scopes[-1], 'alpha')
                self.assertEqual(data['auth'], 'subscription')
                self.exchange_mock.assert_called_with('TOKEN_SECRET', 'ACCOUNT_SECRET', OFFER, 'PERSONA_SECRET', voice, [])

    def test_error_envelopes_are_sanitized_and_android_compatible(self):
        status, data = self.call({**PAYLOAD, 'billing': 'api'})
        self.assertEqual(status, 400)
        self.assert_detail(data)
        self.creds_mock.assert_not_called()
        status, data = self.call({**PAYLOAD, 'padding': 'x' * self.api['MAX_BODY']})
        self.assertEqual(status, 413)
        self.assert_detail(data)
        with patch.object(self.api['_slots'], 'acquire', return_value=False):
            status, data = self.call(PAYLOAD)
        self.assertEqual(status, 503)
        self.assert_detail(data)
        with patch('tools.voice_live.build_session_config', return_value=self.config('ember')):
            for target in (self.creds_mock, self.exchange_mock):
                target.side_effect = RuntimeError('PROVIDER_SECRET TOKEN_SECRET PERSONA_SECRET')
                status, data = self.call(PAYLOAD)
                self.assertEqual(status, 502)
                self.assert_detail(data)
                self.assertIn('No API fallback', data['detail'])
                target.side_effect = None
        self.assertEqual(self.exchange_mock.call_count, 1)


if __name__ == '__main__':
    unittest.main()
