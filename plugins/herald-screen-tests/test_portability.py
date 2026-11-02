import importlib.util
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('portability_api', Path(__file__).parent.parent / 'herald-screen/dashboard/plugin_api.py')
api = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = api
spec.loader.exec_module(api)

class PortabilityTests(unittest.TestCase):
    def test_unconfigured_and_invalid_ports_are_denied(self):
        for value in ('', '0', '65536', '-1', '1.2.3.4:80', '１２３', '9' * 5000):
            with self.subTest(value=value[:30]), patch.dict(os.environ, {'HERMES_SCREEN_GATEWAY_PORT': value}):
                self.assertIsNone(api.upstream_url())
        with patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(api.upstream_url())
        with patch.dict(os.environ, {'HERMES_SCREEN_GATEWAY_PORT': '8765'}):
            self.assertEqual(api.upstream_url(), 'ws://127.0.0.1:8765/api/ws')

    def test_profile_change_denies_access(self):
        with patch.object(api, 'get_hermes_home', return_value=api.HOME):
            self.assertTrue(api.active_profile())
        with patch.object(api, 'get_hermes_home', return_value=api.HOME / 'another-profile'):
            self.assertFalse(api.active_profile())
