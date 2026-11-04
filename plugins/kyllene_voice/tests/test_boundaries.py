"""Synthetic transport and credential boundary checks; no network access."""
import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch

import hermes_test_path  # noqa: F401

spec = importlib.util.spec_from_file_location('candidate_voice_api', Path(__file__).resolve().parents[1] / 'dashboard' / 'plugin_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class Boundaries(unittest.TestCase):
    def test_credentials_are_read_only_for_status_and_reject_custom_base(self):
        claims = {'https://api.openai.com/auth': {'chatgpt_account_id': 'account'}}
        with patch('hermes_cli.auth_codex.resolve_codex_runtime_credentials', return_value={'base_url': api.BASE, 'api_key': 'synthetic'}) as resolve, patch('hermes_cli.auth_constants._decode_jwt_claims', return_value=claims):
            self.assertEqual(api.credentials(False), ('synthetic', 'account'))
            resolve.assert_called_once_with(refresh_if_expiring=False, read_only=True)
            api.credentials(True)
            resolve.assert_called_with(refresh_if_expiring=True, read_only=False)
            resolve.return_value['base_url'] = 'https://elsewhere.invalid'
            with self.assertRaises(ValueError):
                api.credentials(True)

    def test_fixed_destination_and_no_redirect(self):
        class Reply:
            status = 201
            headers = {'Location': '/calls/rtc_test'}
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return b'v=0\r\nanswer'
        class Opener:
            def open(self, request, timeout):
                self.request = request
                self.timeout = timeout
                return Reply()
        opener = Opener()
        with patch.object(api.urllib.request, 'build_opener', return_value=opener) as factory:
            self.assertEqual(api.exchange('synthetic', 'account', 'offer', 'persona', 'ember', []), 'v=0\r\nanswer')
        self.assertEqual(opener.request.full_url, api.URL)
        self.assertEqual(opener.timeout, 30)
        self.assertEqual(json.loads(opener.request.data)['session']['delegation'], {'type': 'client'})
        handler = factory.call_args.args[0]
        self.assertIsInstance(handler, api.NoRedirect)
        self.assertIsNone(handler.redirect_request(None, None, 302, None, None, 'https://elsewhere.invalid'))

    def test_history_limits(self):
        item = {'type': 'message', 'role': 'user', 'content': [{'type': 'input_text', 'text': 'x' * 1200}]}
        self.assertEqual(api.validate_history([item] * 5), [item] * 5)
        with self.assertRaises(ValueError): api.validate_history([item] * 6)
        with self.assertRaises(ValueError): api.validate_history([{'type': 'message', 'role': 'user', 'content': [{'type': 'input_text', 'text': ' '}]}])


if __name__ == '__main__': unittest.main()
