import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(shutil.which('fc-list'), 'fontconfig is required')
class FontFilterTest(unittest.TestCase):
    def test_only_web_font_filenames_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fonts = root / 'fonts'
            fonts.mkdir()
            source = ROOT / 'shared/designsystem/src/androidMain/res/font/geist_regular.ttf'
            # Identical valid font bytes isolate filename filtering from parsing.
            for extension in ('ttf', 'otf', 'woff', 'woff2'):
                shutil.copyfile(source, fonts / ('fixture.' + extension))
            config = root / 'fonts.conf'

            def listed(fragment):
                config.write_text('<fontconfig><dir>' + escape(str(fonts)) + '</dir>'
                                  '<cachedir>' + escape(str(root / 'cache')) + '</cachedir>'
                                  + fragment + '</fontconfig>')
                result = subprocess.run(['fc-list', '-f', '%{file}\n'], check=True,
                                        capture_output=True, text=True,
                                        env={**os.environ, 'FONTCONFIG_FILE': str(config)})
                return {Path(line).suffix for line in result.stdout.splitlines()}

            self.assertEqual(listed(''), {'.ttf', '.otf', '.woff', '.woff2'})
            fragment = '<include>' + escape(str(ROOT / 'packaging/flatpak/fonts.conf')) + '</include>'
            self.assertEqual(listed(fragment), {'.ttf', '.otf'})
