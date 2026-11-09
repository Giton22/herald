import builtins
from pathlib import Path
import runpy
import sys
import unittest
from unittest.mock import patch


class SmokePlatformTest(unittest.TestCase):
    def test_unsupported_cli_exits_before_optional_imports_or_external_actions(self):
        runner = Path(__file__).with_name('installed_smoke.py')
        original_import = builtins.__import__
        def guarded_import(name, *args, **kwargs):
            if name.split('.')[0] in {'PIL', 'subprocess', 'argparse'}:
                self.fail('unsupported invocation reached optional/tool imports: ' + name)
            return original_import(name, *args, **kwargs)
        for platform in ('win32', 'darwin'):
            with self.subTest(platform=platform), patch.object(sys, 'platform', platform), \
                 patch('builtins.__import__', side_effect=guarded_import):
                with self.assertRaisesRegex(SystemExit, 'This installed-Flatpak test requires Linux'):
                    runpy.run_path(str(runner), run_name='__main__')
