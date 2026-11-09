import configparser
from pathlib import Path
import shlex
import unittest

ENTRY = Path(__file__).resolve().parents[1] / 'app.kyllene.Kyllene.Devel.desktop'


class DesktopActionsTest(unittest.TestCase):
    def setUp(self):
        self.entry = configparser.ConfigParser(interpolation=None)
        self.entry.read(ENTRY)

    def test_primary_launcher_forwards_one_uri_without_claiming_the_hermes_scheme(self):
        main = self.entry['Desktop Entry']
        self.assertEqual(shlex.split(main['Exec']), ['kyllene', '%u'])
        types = main.get('MimeType', '').split(';')
        self.assertIn('x-scheme-handler/herald', types)
        self.assertNotIn('x-scheme-handler/hermes', types)

    def test_menu_actions_use_the_existing_entry_point(self):
        main = self.entry['Desktop Entry']
        actions = [item for item in main.get('Actions', '').split(';') if item]
        self.assertTrue({'NewChat', 'QuickEntry'}.issubset(actions))
        for action, name, uri in [('NewChat', 'New chat', 'herald://new-chat'),
                                  ('QuickEntry', 'Quick entry', 'herald://quick-entry')]:
            group = self.entry['Desktop Action ' + action]
            self.assertEqual(group['Name'], name)
            self.assertEqual(shlex.split(group['Exec']), ['kyllene', uri])


if __name__ == '__main__':
    unittest.main()
