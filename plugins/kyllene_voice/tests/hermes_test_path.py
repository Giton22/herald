"""Use an explicit Hermes checkout, or the environment's installed package."""
import os
import sys
from pathlib import Path

root = os.environ.get('HERMES_REPO_ROOT')
if root:
    path = Path(root).expanduser().resolve()
    if not (path / 'hermes_cli' / 'plugin_host_child.py').is_file():
        raise RuntimeError(f'HERMES_REPO_ROOT is not a compatible Hermes checkout: {path}')
    sys.path.insert(0, str(path))
