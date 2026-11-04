#!/usr/bin/env python3
"""Select full package validation; ordinary PRs still run JVM and contract checks."""
import os
from pathlib import Path
import subprocess


def package_required(event, paths):
    if event in ('push', 'workflow_dispatch', 'schedule'):
        return True
    if event != 'pull_request':
        raise ValueError('Unsupported CI event: ' + event)
    return any(path.startswith(('packaging/flatpak/', 'gradle/', 'desktopApp/'))
               or path.endswith('.gradle.kts')
               or path in ('gradle.properties', 'gradlew', 'gradlew.bat',
                           '.github/workflows/linux.yml', '.github/workflows/linux-checks.yml',
                           '.github/workflows/release.yml',
                           '.github/workflows/flatpak-repo.yml',
                           'website/kyllene.flatpakref', 'website/kyllene.flatpakrepo')
               for path in paths)


if __name__ == '__main__':
    event = os.environ['GITHUB_EVENT_NAME']
    paths = []
    if event == 'pull_request':
        # No rename detection: both the old and new paths must select packaging when moving an input.
        paths = subprocess.check_output(
            ['git', 'diff', '--no-renames', '--name-only', '-z',
             os.environ['PR_BASE'] + '...' + os.environ['PR_HEAD']], text=True).split('\0')
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
        output.write('package=' + str(package_required(event, paths)).lower() + '\n')
