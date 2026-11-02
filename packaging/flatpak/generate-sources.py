#!/usr/bin/env python3
"""Regenerate Flatpak Maven inputs using flatpak-gradle-generator 1.8.0."""
import json
import posixpath
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
from urllib.parse import urlsplit


def merge_sources(groups):
    files = {}
    for group in groups:
        for row in group:
            path = PurePosixPath(posixpath.normpath(str(PurePosixPath(row['dest']) / row['dest-filename'])))
            row = {**row, 'dest': str(path.parent), 'dest-filename': path.name}
            if path.is_absolute() or '..' in path.parts or path.parts[0] != 'offline-repository':
                raise ValueError(f'Unsafe dependency destination: {path}')
            url = urlsplit(row['url'])
            row = {**row, 'url': url._replace(path=posixpath.normpath(url.path)).geturl()}
            # The same file may resolve from two repositories; only its content must agree.
            if path in files:
                if {k: v for k, v in files[path].items() if k != 'url'} != {k: v for k, v in row.items() if k != 'url'}:
                    raise ValueError(f'Conflicting downloads for {path}')
                continue
            files[path] = row
    return [files[key] for key in sorted(files)]


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[2]
    (root / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='flatpak-sources-', dir=root / 'build') as output:
        subprocess.run([str(root / 'gradlew'), '--no-daemon', '--no-parallel',
                        '--max-workers=2', '--no-configuration-cache',
                        '-Dflatpak.sources.output=' + output, '-I',
                        str(Path(__file__).with_name('generate.init.gradle')),
                        'flatpakGradleGenerator'], cwd=root, check=True, timeout=900)
        groups = [json.loads(file.read_text()) for file in sorted(Path(output).glob('*.json'))]
    if not groups:
        raise ValueError('Generator produced no dependency inputs')
    Path(__file__).with_name('gradle-sources.json').write_text(
        json.dumps(merge_sources(groups), indent=2) + '\n')
