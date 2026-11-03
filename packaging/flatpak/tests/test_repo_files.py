import base64
import configparser
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
FLATPAK = ROOT / 'packaging/flatpak'
REPO_URL = 'https://flatpak.kyllene.app/repo/'


def armoured_key():
    """The binary key inside packaging/flatpak/kyllene-repo.asc: the base64 lines before the checksum."""
    lines = (FLATPAK / 'kyllene-repo.asc').read_text().splitlines()
    body = lines[lines.index('') + 1:]
    return base64.b64decode(''.join(l for l in body if l and not l.startswith(('=', '-----'))))


def website(name, section):
    parser = configparser.ConfigParser(interpolation=None)
    parser.optionxform = str
    parser.read(ROOT / 'website' / name)
    return parser[section]


class RepoFilesTest(unittest.TestCase):
    def test_the_install_files_trust_the_repository_key(self):
        key = armoured_key()
        for name, section in (('kyllene.flatpakref', 'Flatpak Ref'), ('kyllene.flatpakrepo', 'Flatpak Repo')):
            with self.subTest(name):
                self.assertEqual(base64.b64decode(website(name, section)['GPGKey']), key)

    def test_the_install_file_names_the_stable_app_in_our_repository(self):
        ref = website('kyllene.flatpakref', 'Flatpak Ref')
        self.assertEqual(ref['Name'], 'app.kyllene.Kyllene')
        self.assertEqual(ref['Branch'], 'stable')
        self.assertEqual(ref['Url'], REPO_URL)
        self.assertEqual(ref['IsRuntime'], 'false')
        self.assertEqual(website('kyllene.flatpakrepo', 'Flatpak Repo')['Url'], REPO_URL)

    def test_the_workflows_use_the_same_repository(self):
        release = (ROOT / '.github/workflows/release.yml').read_text()
        publish = (ROOT / '.github/workflows/flatpak-repo.yml').read_text()
        self.assertIn('--repo-url=' + REPO_URL, release)
        self.assertIn(REPO_URL, publish)


if __name__ == '__main__':
    unittest.main()
