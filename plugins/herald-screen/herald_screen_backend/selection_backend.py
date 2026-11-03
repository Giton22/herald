"""Protected, lease-fenced reader for the local X11 PRIMARY selection."""
from __future__ import annotations

import asyncio
import json
import os
import re
import sys
from pathlib import Path
from typing import Callable

_MAX_BYTES = 64 * 1024
_DISPLAY = re.compile(r":(?:0|[1-9][0-9]*)(?:\.[0-9]+)?\Z")
_READER = r'''import tkinter as tk, sys
root = tk.Tk()
root.withdraw()
try:
    try:
        try:
            value = root.selection_get(selection="PRIMARY", type="UTF8_STRING")
        except tk.TclError:
            value = root.selection_get(selection="PRIMARY", type="STRING")
    except tk.TclError:
        sys.exit(21)
    data = value.encode("utf-8", "strict")
    if len(data) > 65536:
        sys.exit(22)
    sys.stdout.buffer.write(data)
finally:
    root.destroy()
'''


class SelectionError(Exception):
    """Safe error suitable for returning to a user."""
    def __init__(self, message: str = "Selected text is unavailable."):
        super().__init__(message)


class SelectionService:
    def __init__(self, profile_home: Path, *, _native_read: Callable | None = None,
                 _timeout: float = 3.0):
        self.profile_home = Path(profile_home)
        self._native_read = _native_read
        self._timeout = _timeout

    def _base(self):
        try:
            profile = self.profile_home.resolve(strict=True)
            path = profile / "bot-desktop"
            if path.is_symlink() or not path.is_dir():
                raise ValueError
            base = path.resolve(strict=True)
            if base.parent != profile:
                raise ValueError
            return base
        except (OSError, ValueError):
            raise SelectionError("Selected text is unavailable.") from None

    def _lease(self):
        try:
            path = self._base() / "lease.json"
            if path.is_symlink():
                raise ValueError
            with path.open("rb") as stream:
                raw = stream.read(8193)
            if len(raw) > 8192:
                raise ValueError
            data = json.loads(raw)
            if (not isinstance(data, dict) or data.get("holder") != "human" or
                    not isinstance(data.get("viewer_id"), str) or not data["viewer_id"] or
                    type(data.get("epoch")) is not int or data["epoch"] < 0):
                raise ValueError
            return data["holder"], data["viewer_id"], data["epoch"]
        except (OSError, UnicodeError, ValueError, TypeError, json.JSONDecodeError):
            raise SelectionError("Control is no longer available; try again.") from None

    def _display_env(self):
        base = self._base()
        vals = {}
        try:
            env_path = base / "env"
            if env_path.is_symlink():
                raise ValueError
            with env_path.open("rb") as stream:
                raw = stream.read(8193)
            if len(raw) > 8192:
                raise ValueError
            for line in raw.decode("utf-8").splitlines():
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k in ("DISPLAY", "XAUTHORITY"):
                    vals[k] = v
        except (OSError, UnicodeError, ValueError):
            raise SelectionError("Selected text is unavailable.") from None
        disp, auth = vals.get("DISPLAY"), vals.get("XAUTHORITY")
        if not isinstance(disp, str) or not _DISPLAY.fullmatch(disp) or not auth:
            raise SelectionError("Selected text is unavailable.")
        ap = Path(auth)
        if not ap.is_absolute():
            ap = base / ap
        try:
            if ap.parent != base or ap.is_symlink():
                raise ValueError
            resolved = ap.resolve(strict=True)
            info = resolved.stat()
            if (resolved.parent != base or not resolved.is_file() or
                    info.st_uid != os.getuid() or info.st_mode & 0o077):
                raise ValueError
        except (OSError, ValueError):
            raise SelectionError("Selected text is unavailable.") from None
        return {"DISPLAY": disp, "XAUTHORITY": str(resolved), "PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}

    async def _read_native(self, env):
        proc = await asyncio.create_subprocess_exec(sys.executable, "-I", "-c", _READER,
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL, env=env)
        try:
            out, _ = await asyncio.wait_for(proc.communicate(), self._timeout)
        except BaseException:
            if proc.returncode is None:
                proc.kill()
                await proc.wait()
            raise
        if proc.returncode == 22:
            raise SelectionError("Selected text is too large to copy.")
        if proc.returncode == 21:
            raise SelectionError("No text is currently selected.")
        if proc.returncode != 0:
            raise SelectionError("Selected text is unavailable.")
        if len(out) > _MAX_BYTES:
            raise SelectionError("Selected text is too large to copy.")
        try:
            return out.decode("utf-8", "strict")
        except UnicodeDecodeError:
            raise SelectionError("Selected text could not be read.") from None

    async def read(self, viewer_id: str, epoch: int) -> str:
        if not isinstance(viewer_id, str) or not viewer_id or type(epoch) is not int or epoch < 0:
            raise SelectionError("Control is no longer available; try again.")
        initial = self._lease()
        if initial != ("human", viewer_id, epoch):
            raise SelectionError("Control is no longer available; try again.")
        env = self._display_env()
        try:
            text = await (self._native_read(env) if self._native_read else self._read_native(env))
        except asyncio.CancelledError:
            raise
        except SelectionError:
            raise
        except TimeoutError:
            raise SelectionError("Selected-text read timed out.") from None
        except Exception:
            raise SelectionError("Selected text is unavailable.") from None
        if not isinstance(text, str):
            raise SelectionError("Selected text could not be read.")
        try:
            size = len(text.encode("utf-8", "strict"))
        except UnicodeError:
            raise SelectionError("Selected text could not be read.") from None
        if size > _MAX_BYTES:
            raise SelectionError("Selected text is too large to copy.")
        if not text:
            raise SelectionError("No text is currently selected.")
        if self._lease() != initial:
            raise SelectionError("Control changed while copying; try again.")
        return text
