import json
from pathlib import Path
import re
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[3]
FLATPAK = ROOT / 'packaging/flatpak'


class CatalogTest(unittest.TestCase):
    """The offline build only has what gradle-sources.json lists: a version bump must regenerate it."""

    def setUp(self):
        self.catalog = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())
        self.dests = {row['dest'] for row in json.loads((FLATPAK / 'gradle-sources.json').read_text())}

    def test_every_catalog_plugin_is_in_the_offline_repository(self):
        versions = self.catalog['versions']
        missing = []
        for plugin in self.catalog['plugins'].values():
            version = versions[plugin['version']['ref']] if 'ref' in plugin.get('version', {}) else plugin['version']
            marker = 'offline-repository/' + plugin['id'].replace('.', '/') + '/' + \
                plugin['id'] + '.gradle.plugin/' + version
            if marker not in self.dests:
                missing.append(plugin['id'] + ':' + version)
        self.assertEqual(missing, [], 'Regenerate with packaging/flatpak/generate-sources.py')

    def test_gradle_archive_matches_the_wrapper(self):
        wrapper = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
        version = re.search(r'gradle-([\d.]+)-bin\.zip', wrapper).group(1)
        manifest = json.loads((FLATPAK / 'app.kyllene.Kyllene.Devel.json').read_text())
        urls = [s['url'] for s in manifest['modules'][0]['sources']
                if isinstance(s, dict) and 'url' in s]
        self.assertIn(f'https://services.gradle.org/distributions/gradle-{version}-bin.zip', urls)


if __name__ == '__main__':
    unittest.main()
