#!/usr/bin/env python3
"""Check the packaged macOS launcher's native appearance option; requires macOS and Python 3."""
import sys

if sys.platform != 'darwin':
    sys.exit('This launcher check requires macOS.')

from pathlib import Path


def main():
    if len(sys.argv) != 2:
        print('Usage: check_macos_launcher.py Kyllene.app/Contents/app/Kyllene.cfg', file=sys.stderr)
        return 1
    try:
        lines = Path(sys.argv[1]).read_text(encoding='utf-8').splitlines()
    except OSError as error:
        print(f'Cannot read launcher configuration: {error}', file=sys.stderr)
        return 1
    section = ''
    appearances = []
    for line in lines:
        line = line.strip()
        if line.startswith('[') and line.endswith(']'):
            section = line
        if section == '[JavaOptions]' and line.startswith('java-options='):
            option = line.removeprefix('java-options=')
            if option.partition('=')[0] == '-Dapple.awt.application.appearance':
                appearances.append(option)
    if appearances != ['-Dapple.awt.application.appearance=system']:
        print(f'Expected exactly one system appearance option; found {appearances}', file=sys.stderr)
        return 1
    print('macOS launcher follows system appearance.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
