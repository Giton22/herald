import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('selection', ROOT / 'packaging/flatpak/ci-selection.py')
selection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selection)


class SelectionTest(unittest.TestCase):
    def test_ordinary_pr_gets_fast_checks(self):
        for paths in ([], ['shared/ui/src/commonMain/Chat.kt'], ['docs/flatpak.md'], ['androidApp/src/Main.kt']):
            self.assertFalse(selection.package_required('pull_request', paths))

    def test_package_inputs_and_distribution_always_qualify(self):
        for path in ('packaging/flatpak/tests/repo_smoke.sh', 'gradle/libs.versions.toml',
                     'shared/core/build.gradle.kts', 'gradle.properties', 'gradlew',
                     'desktopApp/src/jvmMain/Main.kt', '.github/workflows/release.yml',
                     '.github/workflows/linux.yml', '.github/workflows/linux-checks.yml',
                     '.github/workflows/flatpak-repo.yml',
                     'website/kyllene.flatpakref', 'website/kyllene.flatpakrepo'):
            with self.subTest(path=path):
                self.assertTrue(selection.package_required('pull_request', [path]))

    def test_master_manual_and_scheduled_runs_do_not_filter_app_changes(self):
        for event in ('push', 'workflow_dispatch', 'schedule'):
            self.assertTrue(selection.package_required(event, ['shared/ui/src/commonMain/Chat.kt']))

    def test_unknown_event_fails_instead_of_skipping(self):
        with self.assertRaises(ValueError):
            selection.package_required('unknown', [])

    def test_cli_uses_pr_diff_including_removed_package_inputs(self):
        import os
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
            git('init', '-q')
            git('config', 'user.email', 'fixture@example.invalid')
            git('config', 'user.name', 'Fixture')
            (root / 'gradle.properties').write_text('fixture')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD')
            (root / 'gradle.properties').rename(root / 'notes.txt')
            git('add', '-A')
            git('commit', '-qm', 'move package input')
            output = root / 'output'
            env = {**os.environ, 'GITHUB_EVENT_NAME': 'pull_request', 'PR_BASE': base,
                   'PR_HEAD': git('rev-parse', 'HEAD'), 'GITHUB_OUTPUT': str(output)}
            subprocess.run([__import__('sys').executable, str(ROOT / 'packaging/flatpak/ci-selection.py')],
                           cwd=root, env=env, check=True)
            self.assertEqual(output.read_text(), 'package=true\n')
            output.unlink()
            env['PR_BASE'] = 'missing-ref'
            result = subprocess.run([__import__('sys').executable, str(ROOT / 'packaging/flatpak/ci-selection.py')],
                                    cwd=root, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())

    def test_contract_tests_cannot_skip_silently_and_android_only_prs_skip_linux(self):
        checks = (ROOT / '.github/workflows/linux-checks.yml').read_text()
        linux = (ROOT / '.github/workflows/linux.yml').read_text()
        # No other job runs test_installed_smoke.py, and it skips without Pillow.
        self.assertIn('python3-pil', checks)
        repository = linux.split('  repository:\n', 1)[1].split('\n  linux:\n')[0]
        self.assertIn('unittest test_repo_smoke', repository)
        self.assertIn('! grep -q skipped', repository)
        self.assertIn('  pull_request:\n    paths: *paths\n', linux)

    def test_release_attachment_waits_for_both_builds_and_uses_the_qualified_artifact(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        build, attach = workflow.split('  flatpak:\n', 1)[1].split('  flatpak-attach:\n')
        self.assertNotIn('needs: release', build)
        self.assertIn('needs: [release, flatpak, flatpak-checks]', attach)
        self.assertNotIn('if: always()', attach)
        self.assertIn('name: qualified-flatpak', build)
        self.assertIn('name: qualified-flatpak', attach)
        self.assertIn('repo_smoke.sh build/flatpak-repo app.kyllene.Kyllene stable', build)
        self.assertIn('installed_smoke.py --app app.kyllene.Kyllene build/kyllene.flatpak', build)
        self.assertIn('gh release upload --repo "$GITHUB_REPOSITORY"', attach)
        self.assertNotIn('build-bundle', attach)
        self.assertNotIn('GPG_KEY', attach)
