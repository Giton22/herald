import os
import sys
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

try:
    from PIL import Image
    import installed_smoke as smoke
    PILLOW = True
except ImportError:
    PILLOW = False


@unittest.skipUnless(sys.platform == 'linux', 'Installed Flatpak integration requires Linux')
@unittest.skipUnless(PILLOW, 'Installed Flatpak integration requires Pillow (python3-pil)')
class InstalledSmokeTest(unittest.TestCase):
    def test_failed_private_capture_never_falls_back_to_desktop_screenshot(self):
        with patch.dict(os.environ, DISPLAY=':9876'), \
             patch.object(Image.core, 'grabscreen_x11', side_effect=OSError('private display unavailable')), \
             patch('PIL.ImageGrab.shutil.which', return_value='/usr/bin/spectacle'), \
             patch('PIL.ImageGrab.subprocess.call') as fallback:
            with self.assertRaisesRegex(OSError, 'private display unavailable'):
                smoke.pixels((25, 195, 125))
            fallback.assert_not_called()

    def test_gateway_setup_failure_uninstalls_only_the_owned_commit(self):
        commands = []
        def run(*args):
            commands.append(args)
            return 'owned-commit'
        with patch.object(smoke, 'run', side_effect=run), \
             patch.object(smoke, 'server', side_effect=OSError('fixture bind failed')):
            with self.assertRaisesRegex(OSError, 'fixture bind failed'):
                smoke.main(Path('fixture.flatpak'))
        self.assertIn(('flatpak', 'uninstall', '--user', '--noninteractive', '-y', smoke.APP), commands)

    def test_process_cleanup_failure_still_closes_gateway_and_owned_installation(self):
        commands = []
        def run(*args):
            commands.append(args)
            if args[:2] == ('flatpak', 'ps'):
                raise OSError('process listing failed')
            return 'owned-commit'
        gateway = Mock()
        app = Mock(pid=12345)
        app.poll.return_value = None
        with patch.object(smoke.subprocess, 'Popen', return_value=app), \
             patch.object(smoke, 'run', side_effect=run), \
             patch.object(smoke, 'server', return_value=gateway), \
             patch.object(smoke, 'until', side_effect=RuntimeError('UI check failed')):
            with self.assertRaisesRegex(OSError, 'process listing failed') as failure:
                smoke.main(Path('fixture.flatpak'))
        self.assertIsInstance(failure.exception.__context__, RuntimeError)
        app.terminate.assert_called_once()
        app.wait.assert_called_once_with(timeout=15)
        gateway.shutdown.assert_called_once()
        gateway.server_close.assert_called_once()
        self.assertIn(('flatpak', 'uninstall', '--user', '--noninteractive', '-y', smoke.APP), commands)

    def test_changed_ref_is_preserved_when_cleanup_cannot_prove_ownership(self):
        def run(*args):
            if args[:2] == ('flatpak', 'uninstall'):
                self.fail('must preserve a ref that changed during the test')
            return 'other-commit'
        with patch.object(smoke, 'run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, 'ref changed'):
                smoke.uninstall('owned-commit')

    def test_existing_installation_is_never_replaced(self):
        commands = []
        def run(*args):
            commands.append(args)
            return smoke.APP
        with patch.object(smoke, 'run', side_effect=run):
            with self.assertRaisesRegex(RuntimeError, 'Refusing to replace'):
                smoke.main(Path('fixture.flatpak'))
        self.assertEqual(commands, [('flatpak', 'list', '--user', '--app', '--columns=application')])

    def test_ignored_resize_cannot_pass_using_existing_widget_pixels(self):
        app = Mock()
        app.poll.return_value = None
        with patch.object(smoke, 'run', return_value='WIDTH=1280\nHEIGHT=840'), \
             patch.object(smoke, 'pixels', return_value=[(430, 400)]), \
             patch.object(smoke.time, 'monotonic', side_effect=[0, 0, 31]), \
             patch.object(smoke.time, 'sleep'):
            with self.assertRaisesRegex(TimeoutError, 'window resize'):
                smoke.verify_resize('owned-window', app, [(430, 400)])

    def test_stale_pixels_after_resize_do_not_prove_a_fresh_widget_response(self):
        app = Mock()
        app.poll.return_value = None
        with patch.object(smoke, 'run', return_value='WIDTH=1200\nHEIGHT=800'), \
             patch.object(smoke, 'pixels', side_effect=lambda color: [(390, 360)] if color == smoke.BUTTON else None), \
             patch.object(smoke.time, 'monotonic', side_effect=[0, 0, 0, 0, 0, 0, 31]), \
             patch.object(smoke.time, 'sleep'):
            with self.assertRaisesRegex(TimeoutError, 'post-resize widget click'):
                smoke.verify_resize('owned-window', app, [(430, 400)])

    def test_page_reloaded_by_the_resize_cannot_pass_the_second_click(self):
        app = Mock()
        app.poll.return_value = None
        # Moved button, and the first click's colour again: what a reloaded page shows after one more click.
        shown = {smoke.BUTTON: [(390, 360)], smoke.CLICKED: [(0, 0)] * 2000}
        with patch.object(smoke, 'run', return_value='WIDTH=1200\nHEIGHT=800'), \
             patch.object(smoke, 'pixels', side_effect=shown.get), \
             patch.object(smoke.time, 'monotonic', side_effect=[0, 0, 0, 0, 0, 0, 31]), \
             patch.object(smoke.time, 'sleep'):
            with self.assertRaisesRegex(TimeoutError, 'post-resize widget click'):
                smoke.verify_resize('owned-window', app, [(430, 400)])

    def test_an_app_that_ignores_terminate_is_killed(self):
        app = Mock(pid=12345)
        app.poll.return_value = None
        app.wait.side_effect = [smoke.subprocess.TimeoutExpired('kyllene', 15), 0]
        with patch.object(smoke, 'run', return_value=''):
            smoke.stop(app)
        app.terminate.assert_called_once()
        app.kill.assert_called_once()

    def test_post_install_lookup_failure_reports_retained_or_unknown_state_without_deletion(self):
        for reconciliation_fails in (False, True):
            commands = []
            def run(*args):
                commands.append(args)
                if args[:2] == ('flatpak', 'info'):
                    raise OSError('commit lookup failed')
                if args[:2] == ('flatpak', 'list') and len(commands) > 1:
                    if reconciliation_fails:
                        raise OSError('reconciliation unavailable')
                    return smoke.APP
                return ''
            with self.subTest(reconciliation_fails=reconciliation_fails), patch.object(smoke, 'run', side_effect=run):
                with self.assertRaisesRegex(RuntimeError, 'commit unknown'):
                    smoke.main(Path('fixture.flatpak'))
            self.assertEqual(sum(c[:2] == ('flatpak', 'list') for c in commands), 2)
            self.assertFalse(any(c[:2] == ('flatpak', 'uninstall') for c in commands))

    def test_stale_layout_is_not_used_for_the_post_resize_click(self):
        app = Mock()
        app.poll.return_value = None
        with patch.object(smoke, 'run', return_value='WIDTH=1200\nHEIGHT=800') as commands, \
             patch.object(smoke, 'pixels', return_value=[(430, 400)]), \
             patch.object(smoke.time, 'monotonic', side_effect=[0, 0, 0, 0, 31]), \
             patch.object(smoke.time, 'sleep'):
            with self.assertRaisesRegex(TimeoutError, 'resized widget layout'):
                smoke.verify_resize('owned-window', app, [(430, 400)])
        self.assertFalse(any('mousemove' in call.args for call in commands.call_args_list))
