import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

FLATPAK = Path(__file__).resolve().parents[1]


def prepare(manifest, ref='refs/heads/master'):
    return subprocess.run([sys.executable, str(FLATPAK / 'prepare-ci.py'),
                           str(manifest), '--repository', 'file:///workspace/app',
                           '--ref', ref, '--commit', 'a' * 40],
                          capture_output=True, text=True)


class CiSourceTest(unittest.TestCase):
    def test_ci_builds_the_tested_git_revision_without_workspace_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'app.json'
            original = json.loads((FLATPAK / 'app.kyllene.Kyllene.Devel.json').read_text())
            manifest.write_text(json.dumps(original))
            result = prepare(manifest, 'flatpak-ci-tested')
            self.assertEqual(result.returncode, 0, result.stderr)
            prepared = json.loads((root / 'ci.json').read_text())
            expected = json.loads(json.dumps(original))
            expected['modules'][0]['sources'][0].update(
                url='file:///workspace/app', branch='flatpak-ci-tested', commit='a' * 40)
            self.assertEqual(prepared, expected)
            self.assertEqual(json.loads(manifest.read_text()), original)
            # Only a Git source reaches the app's checkout: no directory copy of the workspace.
            sources = [s for m in prepared['modules'] for s in m['sources'] if isinstance(s, dict)]
            self.assertNotIn('dir', [s['type'] for s in sources])

    def test_the_app_module_is_found_by_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'app.json'
            dependency = {'name': 'dependency', 'sources': [
                {'type': 'git', 'url': 'https://example.org/dep.git', 'commit': 'b' * 40}]}
            app = {'name': 'kyllene', 'sources': [
                {'type': 'file', 'path': 'x'},
                {'type': 'git', 'url': 'https://example.org/app.git', 'commit': 'c' * 40}]}
            manifest.write_text(json.dumps({'modules': [dependency, app]}))
            self.assertEqual(prepare(manifest).returncode, 0)
            prepared = json.loads((root / 'ci.json').read_text())
            self.assertEqual(prepared['modules'][0], dependency)
            self.assertEqual(prepared['modules'][1]['sources'][1]['commit'], 'a' * 40)

    def test_unexpected_source_type_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'app.json'
            manifest.write_text(json.dumps({'modules': [{'name': 'kyllene', 'sources': [{'type': 'dir'}]}]}))
            result = prepare(manifest)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((root / 'ci.json').exists())


if __name__ == '__main__':
    unittest.main()
