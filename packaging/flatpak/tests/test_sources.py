import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'generate-sources.py'


class SourcesTest(unittest.TestCase):
    def test_conflicting_downloads_cannot_overwrite_the_same_destination(self):
        self.assertTrue(SCRIPT.is_file(), 'Dependency generator is not implemented')
        spec = importlib.util.spec_from_file_location('sources', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        row = {'type': 'file', 'url': 'https://example.org/a.jar',
               'sha512': 'a' * 128, 'dest': 'offline-repository/a',
               'dest-filename': 'a.jar'}
        redirected = {**row, 'dest': 'offline-repository/old/../a'}
        self.assertEqual(module.merge_sources([[redirected]]), [row])
        mirror = {**row, 'url': 'https://example.org/old/../a.jar'}
        self.assertEqual(module.merge_sources([[row], [mirror]]), [row])
        self.assertEqual(module.merge_sources([[row], [row]]), [row])
        other_repository = {**row, 'url': 'https://plugins.example.org/a.jar'}
        self.assertEqual(module.merge_sources([[row], [other_repository]]), [row])
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            module.merge_sources([[row], [{**row, 'sha512': 'b' * 128}]])
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            module.merge_sources([[{**row, 'dest': '../escape'}]])
