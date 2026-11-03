import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('repo_smoke.sh')


class RepoSmokeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('sh'), 'a POSIX shell is required')
    def test_unsupported_platform_stops_before_tools_or_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            uname = root / 'uname'
            uname.write_text('#!/bin/sh\necho Darwin\n')
            uname.chmod(0o755)
            result = subprocess.run([shutil.which('sh'), str(SCRIPT), '--fixture'], text=True,
                                    capture_output=True, env={**os.environ, 'PATH': str(root)}, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('This installed-Flatpak test requires Linux', result.stderr)
            self.assertEqual(list(root.iterdir()), [uname])

    @unittest.skipUnless(sys.platform == 'linux' and all(shutil.which(tool) for tool in
                        ('flatpak', 'ostree', 'gpg', 'gpgconf', 'timeout')), 'Linux Flatpak/OSTree/GPG tools required')
    def test_two_routes_update_and_reject_wrong_key_in_isolated_installations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Temp roots must be gone afterwards, including signing agents and HTTP-server files.
            result = subprocess.run(['timeout', '80', shutil.which('sh'), str(SCRIPT), '--fixture'], text=True, capture_output=True,
                                    env={**os.environ, 'TMPDIR': directory}, timeout=90)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for route in ('bundle', 'ref'):
                self.assertIn(route + ': updated from release 1 to release 2', result.stdout)
                self.assertIn(route + ': refused wrong-key update and retained release 2', result.stdout)
            self.assertIn('both routes passed with two prepared releases', result.stdout)
            self.assertEqual(list(root.iterdir()), [])
