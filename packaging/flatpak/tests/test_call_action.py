import configparser
from pathlib import Path
import shlex
import unittest


DESKTOP = Path(__file__).resolve().parents[1] / 'app.kyllene.Kyllene.Devel.desktop'


class CallActionTest(unittest.TestCase):
    def test_call_action_uses_existing_desktop_voice_link(self):
        entry = configparser.ConfigParser(interpolation=None)
        entry.read(DESKTOP)
        self.assertIn('Call', entry['Desktop Entry'].get('Actions', '').split(';'))
        action = entry['Desktop Action Call']
        self.assertEqual('Call Hermes', action['Name'])
        self.assertEqual(['kyllene', 'herald://voice-chat'], shlex.split(action['Exec']))

    def test_normal_launch_keeps_uri_forwarding(self):
        entry = configparser.ConfigParser(interpolation=None)
        entry.read(DESKTOP)
        self.assertEqual(['kyllene', '%u'], shlex.split(entry['Desktop Entry']['Exec']))


if __name__ == '__main__':
    unittest.main()
