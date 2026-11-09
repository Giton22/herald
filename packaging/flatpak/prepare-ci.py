#!/usr/bin/env python3
"""Select the public Git revision tested by CI, preserving locked build inputs.

With --release VERSION it writes release.json instead of ci.json: the stable app, app.kyllene.Kyllene on the
stable branch, built as that version, with a .desktop file and metainfo of its own beside the manifest.
"""
import argparse
import datetime
import json
from pathlib import Path
import re

DEVEL = 'app.kyllene.Kyllene.Devel'
STABLE = 'app.kyllene.Kyllene'
# What a tag carries after its "v" (1.2.3, 1.2.3-rc.1): no spaces or shell characters, so it reaches the build
# command as one word.
VERSION = re.compile(r'\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?')
GRADLE_TASK = ':desktopApp:createReleaseDistributable'


def stable(manifest, module, version):
    """The Devel manifest made into the stable app's, built as [version]."""
    if manifest.get('app-id') != DEVEL:
        raise ValueError('Expected the ' + DEVEL + ' manifest')
    manifest['app-id'] = STABLE
    manifest['branch'] = 'stable'
    commands = [c.replace(DEVEL, STABLE) for c in module['build-commands']]
    gradle = [i for i, c in enumerate(commands) if GRADLE_TASK in c]
    if len(gradle) != 1:
        raise ValueError('Expected exactly one Gradle build command')
    commands[gradle[0]] += ' -PappVersion=' + version
    module['build-commands'] = commands
    for source in module['sources']:
        if isinstance(source, dict) and source.get('type') == 'file':
            source['path'] = source['path'].replace(DEVEL, STABLE)


def stable_files(directory, version, date):
    """The stable app's .desktop file and metainfo, from the Devel ones: its own ID and name, and this release."""
    desktop = (directory / (DEVEL + '.desktop')).read_text()
    desktop = desktop.replace(DEVEL, STABLE).replace('Name=Kyllene (Development)', 'Name=Kyllene')
    (directory / (STABLE + '.desktop')).write_text(desktop)
    metainfo = (directory / (DEVEL + '.metainfo.xml')).read_text()
    metainfo = metainfo.replace(DEVEL, STABLE).replace('<name>Kyllene (Development)</name>', '<name>Kyllene</name>')
    # A pre-release (1.2.3-rc.1) is a development release to software centres.
    kind = 'development' if '-' in version else 'stable'
    metainfo, count = re.subn(r'<releases>.*?</releases>',
                              f'<releases><release version="{version}" date="{date}" type="{kind}"/></releases>',
                              metainfo, flags=re.S)
    if count != 1:
        raise ValueError('Expected one <releases> element in the metainfo')
    (directory / (STABLE + '.metainfo.xml')).write_text(metainfo)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--ref', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--release', metavar='VERSION')
    args = parser.parse_args()
    if args.release is not None and not VERSION.fullmatch(args.release):
        raise ValueError('A release version is major.minor.patch, optionally with a -suffix of letters, digits and dots')
    manifest = json.loads(args.manifest.read_text())
    # By name, so a dependency module added before the app can't take its place.
    modules = [m for m in manifest['modules'] if isinstance(m, dict) and m.get('name') == 'kyllene']
    if len(modules) != 1:
        raise ValueError('Expected exactly one kyllene module')
    sources = [s for s in modules[0]['sources'] if isinstance(s, dict) and s.get('type') in ('git', 'dir')]
    if len(sources) != 1 or sources[0]['type'] != 'git':
        raise ValueError('CI requires a pinned Git source, never a workspace directory')
    sources[0].update(url=args.repository, branch=args.ref, commit=args.commit)
    output = 'ci.json'
    if args.release is not None:
        stable(manifest, modules[0], args.release)
        stable_files(args.manifest.parent, args.release, datetime.datetime.now(datetime.timezone.utc).date().isoformat())
        output = 'release.json'
    args.manifest.with_name(output).write_text(json.dumps(manifest, indent=2) + '\n')
