#!/usr/bin/env python3
"""Install Compose's source-built app image and a launcher using its bundled Java."""
import argparse
from pathlib import Path
import shutil
import shlex


def stage(image, prefix, java):
    values = {}
    for line in (image / 'lib/app/Kyllene.cfg').read_text().splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            values.setdefault(key, []).append(value)
    for key in ('app.mainclass', 'app.classpath'):
        if not values.get(key):
            raise ValueError(f'Missing {key} in Compose launcher configuration')
    # The launcher only knows the classpath form; fail rather than drop a main jar or module.
    unknown = sorted(k for k in values if k.startswith('app.') and k not in ('app.mainclass', 'app.classpath'))
    if unknown:
        raise ValueError('Unsupported Compose launcher configuration: ' + ', '.join(unknown))
    native = image / 'lib/runtime/lib'
    for name in ('libcef.so', 'libjcef.so', 'jcef_helper', 'icudtl.dat',
                 'resources.pak', 'chrome_100_percent.pak', 'chrome_200_percent.pak',
                 'locales/en-US.pak'):
        if not (native / name).is_file():
            raise ValueError(f'Missing Chromium payload: {name}')
    if not java.is_file():
        raise ValueError('Missing bundled Java launcher')
    if not (image / 'bin/Kyllene').is_file():
        raise ValueError('Missing Compose native launcher')

    target = prefix / 'lib/kyllene'
    shutil.copytree(image, target)
    (target / 'lib/runtime/bin').mkdir(exist_ok=True)
    shutil.copy2(java, target / 'lib/runtime/bin/java')
    # jcef_helper is a native-lib entry of jcef.jmod, and jlink writes those without the executable bit.
    (target / 'lib/runtime/lib/jcef_helper').chmod(0o755)
    launcher = prefix / 'bin/kyllene'
    launcher.parent.mkdir(parents=True, exist_ok=True)
    app = target / 'lib/app'
    def quote(value):
        for token, path in (('APPDIR', app), ('BINDIR', target / 'bin'),
                            ('ROOTDIR', target)):
            value = value.replace('${' + token + '}', str(path))
            value = value.replace('$' + token, str(path))
        return shlex.quote(value)

    # No jpackage.app-path: the app reads it as "installed from a DEB/MSI" and would
    # download and open an installer inside the sandbox. Flatpak updates itself.
    args = [str(target / 'lib/runtime/bin/java'), '-classpath',
            ':'.join(values['app.classpath']), *values.get('java-options', []),
            values['app.mainclass'][0]]
    defaults = ' '.join(quote(x) for x in values.get('arguments', []))
    # jpackage's Linux fork/pipe launcher can consume a partial configuration read.
    # Derive arguments from Compose's configuration; keep its image unchanged.
    # An empty LD_LIBRARY_PATH entry means the current directory, so add the
    # separator only when the variable already has a value.
    launcher.write_text('#!/bin/sh\n' +
                        'export XDG_SESSION_TYPE=x11\nunset WAYLAND_DISPLAY\n' +
                        'export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+$LD_LIBRARY_PATH:}"' + quote(str(app)) + '\n' +
                        'if [ "$#" -eq 0 ]; then set -- ' + defaults + '; fi\n' +
                        'exec ' + ' '.join(quote(x) for x in args) + ' "$@"\n')
    launcher.chmod(0o755)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image', type=Path)
    parser.add_argument('prefix', type=Path)
    parser.add_argument('java', type=Path)
    options = parser.parse_args()
    stage(options.image, options.prefix, options.java)
