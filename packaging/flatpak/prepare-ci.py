#!/usr/bin/env python3
"""Select the public Git revision tested by CI, preserving locked build inputs."""
import argparse
import json
from pathlib import Path


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--ref', required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    # By name, so a dependency module added before the app can't take its place.
    modules = [m for m in manifest['modules'] if isinstance(m, dict) and m.get('name') == 'kyllene']
    if len(modules) != 1:
        raise ValueError('Expected exactly one kyllene module')
    sources = [s for s in modules[0]['sources'] if isinstance(s, dict) and s.get('type') in ('git', 'dir')]
    if len(sources) != 1 or sources[0]['type'] != 'git':
        raise ValueError('CI requires a pinned Git source, never a workspace directory')
    sources[0].update(url=args.repository, branch=args.ref, commit=args.commit)
    args.manifest.with_name('ci.json').write_text(json.dumps(manifest, indent=2) + '\n')
