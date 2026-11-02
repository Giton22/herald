"""Offline release-pin checks: python3 -m unittest discover -s scripts/tests -v."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-screen-release-pin.sh'


class ReleasePinTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.plugin = self.root / 'plugins/herald-screen'
        self.plugin.mkdir(parents=True)
        (self.plugin / 'plugin.yaml').write_text('name: herald-screen\n')
        (self.plugin / 'main.py').write_text('pass\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'plugin')
        self.sha = self.git('rev-parse', 'HEAD')
        self.git('update-ref', 'refs/remotes/origin/master', self.sha)
        self.git('tag', 'v1.0.0')
        self.env = dict(os.environ, GITHUB_SERVER_URL='https://github.com',
                        GITHUB_REPOSITORY='Giton22/kyllene', GITHUB_SHA=self.sha,
                        GITHUB_REF_TYPE='tag', GITHUB_REF='refs/tags/v1.0.0')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True).strip()

    def check_pin(self, success, output='', error=None):
        result = subprocess.run(['bash', str(SCRIPT)], cwd=self.root, env=self.env,
                                text=True, capture_output=True)
        self.assertEqual(result.returncode == 0, success, result.stderr)
        self.assertEqual(result.stdout.strip(), output)
        if error:
            self.assertIn(error, result.stderr)

    def test_valid_and_ancestor_of_master(self):
        (self.root / 'later').write_text('later')
        self.git('add', 'later')
        self.git('commit', '-qm', 'later')
        self.git('update-ref', 'refs/remotes/origin/master', 'HEAD')
        self.git('checkout', '-q', '--detach', self.sha)
        self.check_pin(True, self.sha)

    def test_fork_and_other_server_unconfigured(self):
        for key, value in [('GITHUB_REPOSITORY', 'someone/kyllene'),
                           ('GITHUB_SERVER_URL', 'https://enterprise.example')]:
            with self.subTest(key=key):
                old = self.env[key]
                self.env[key] = value
                self.check_pin(True)
                self.env[key] = old

    def test_bad_sha_and_mismatched_head(self):
        self.env['GITHUB_SHA'] = self.sha[:12]
        self.check_pin(False, error='full lowercase')
        self.env['GITHUB_SHA'] = '0' * 40
        self.check_pin(False, error='not a commit')
        (self.root / 'later').write_text('later')
        self.git('add', 'later')
        self.git('commit', '-qm', 'later')
        self.env['GITHUB_SHA'] = self.sha
        self.check_pin(False, error='does not match HEAD')

    def test_tag_required_and_matching(self):
        self.env['GITHUB_REF_TYPE'] = 'branch'
        self.check_pin(False, error='expected a tag')
        self.env['GITHUB_REF_TYPE'] = 'tag'
        self.env['GITHUB_REF'] = 'refs/tags/missing'
        self.check_pin(False, error='tag does not resolve')
        (self.root / 'later').write_text('later')
        self.git('add', 'later')
        self.git('commit', '-qm', 'later')
        self.git('tag', 'v2.0.0')
        self.git('checkout', '-q', '--detach', self.sha)
        self.env['GITHUB_REF'] = 'refs/tags/v2.0.0'
        self.check_pin(False, error='tag does not match')

    def test_master_must_contain_commit(self):
        self.git('update-ref', '-d', 'refs/remotes/origin/master')
        self.check_pin(False, error='master is unavailable')
        self.git('checkout', '-q', '--orphan', 'other')
        self.git('commit', '-qm', 'unrelated')
        self.git('update-ref', 'refs/remotes/origin/master', 'HEAD')
        self.git('checkout', '-q', '--detach', self.sha)
        self.check_pin(False, error='not an ancestor')

    def test_subtree_changes_rejected(self):
        for name in ['main.py', 'untracked', 'ignored.tmp']:
            with self.subTest(name=name):
                if name == 'ignored.tmp':
                    (self.root / '.git/info/exclude').write_text('*.tmp\n')
                file = self.plugin / name
                old = file.read_text() if file.exists() else None
                file.write_text('changed')
                self.check_pin(False, error='subtree has tracked changes')
                if old is None:
                    file.unlink()
                else:
                    file.write_text(old)

    def test_missing_or_untracked_manifest(self):
        (self.plugin / 'plugin.yaml').unlink()
        self.check_pin(False, error='plugin.yaml missing')
        self.git('rm', '-q', 'plugins/herald-screen/plugin.yaml')
        self.git('commit', '-qm', 'remove manifest')
        self.sha = self.git('rev-parse', 'HEAD')
        self.git('update-ref', 'refs/remotes/origin/master', self.sha)
        self.git('tag', '-f', 'v1.0.0')
        self.env['GITHUB_SHA'] = self.sha
        (self.plugin / 'plugin.yaml').write_text('untracked')
        self.check_pin(False, error='not a tracked regular file')

    def test_shallow_checkout_rejected(self):
        (self.root / '.git/shallow').write_text(self.sha + '\n')
        self.check_pin(False, error='full checkout required')


if __name__ == '__main__':
    unittest.main()
