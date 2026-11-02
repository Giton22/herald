import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

FLATPAK = Path(__file__).resolve().parents[1]


def prepare(manifest, ref='refs/heads/master', *extra):
    return subprocess.run([sys.executable, str(FLATPAK / 'prepare-ci.py'),
                           str(manifest), '--repository', 'file:///workspace/app',
                           '--ref', ref, '--commit', 'a' * 40, *extra],
                          capture_output=True, text=True)


def devel_files(root):
    """The Devel manifest, .desktop file and metainfo, copied into [root]; returns the manifest's path."""
    for suffix in ('.json', '.desktop', '.metainfo.xml'):
        name = 'app.kyllene.Kyllene.Devel' + suffix
        (root / name).write_text((FLATPAK / name).read_text())
    return root / 'app.kyllene.Kyllene.Devel.json'


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

    def test_a_release_builds_the_stable_app_at_its_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = devel_files(root)
            result = prepare(manifest, 'flatpak-release', '--release', '1.2.3')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse((root / 'ci.json').exists())
            prepared = json.loads((root / 'release.json').read_text())
            self.assertEqual(prepared['app-id'], 'app.kyllene.Kyllene')
            self.assertEqual(prepared['branch'], 'stable')
            module = prepared['modules'][0]
            self.assertEqual(module['sources'][0]['commit'], 'a' * 40)
            text = json.dumps(prepared)
            self.assertNotIn('.Devel', text)
            gradle = [c for c in module['build-commands'] if ':desktopApp:createReleaseDistributable' in c]
            self.assertEqual(len(gradle), 1)
            self.assertTrue(gradle[0].endswith(' -PappVersion=1.2.3'))
            # Never the GitHub update check: a Flatpak takes new versions from its remote.
            self.assertNotIn('releasesRepo', text)
            # Every file the manifest installs or copies exists under the stable name.
            for source in module['sources']:
                if isinstance(source, dict) and source.get('type') == 'file' and 'Kyllene' in source['path']:
                    self.assertTrue((root / source['path']).is_file(), source['path'])
            desktop = (root / 'app.kyllene.Kyllene.desktop').read_text()
            self.assertIn('Name=Kyllene\n', desktop)
            self.assertIn('Icon=app.kyllene.Kyllene\n', desktop)
            metainfo = (root / 'app.kyllene.Kyllene.metainfo.xml').read_text()
            self.assertIn('<id>app.kyllene.Kyllene</id>', metainfo)
            self.assertIn('<name>Kyllene</name>', metainfo)
            self.assertIn('app.kyllene.Kyllene.desktop</launchable>', metainfo)
            self.assertRegex(metainfo, r'<release version="1\.2\.3" date="\d{4}-\d\d-\d\d" type="stable"/>')
            self.assertNotIn('Devel', desktop + metainfo)

    def test_a_pre_release_suffix_is_kept(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(prepare(devel_files(root), 'flatpak-release', '--release', '1.2.3-rc.1').returncode, 0)
            prepared = json.loads((root / 'release.json').read_text())
            self.assertIn(' -PappVersion=1.2.3-rc.1', json.dumps(prepared))

    def test_a_release_version_is_one_plain_word(self):
        for version in ('1.2', 'v1.2.3', '1.2.3; rm -rf /', '1.2.3 -PreleasesRepo=x', '1.2.3-$(id)'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result = prepare(devel_files(root), 'flatpak-release', '--release', version)
                self.assertNotEqual(result.returncode, 0, version)
                self.assertFalse((root / 'release.json').exists(), version)


if __name__ == '__main__':
    unittest.main()
