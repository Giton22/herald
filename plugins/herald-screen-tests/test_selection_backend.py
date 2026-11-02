import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import sys
sys.path.insert(0, str(Path(__file__).parent.parent / 'herald-screen' / 'herald_screen_backend'))
from selection_backend import SelectionError, SelectionService


class SelectionBackendTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)
        self.base = self.home / "bot-desktop"
        self.base.mkdir()
        (self.base / "Xauthority").write_bytes(b"fixture-only")
        (self.base / "Xauthority").chmod(0o600)
        (self.base / "env").write_text("DISPLAY=:123\nXAUTHORITY=" + str(self.base / "Xauthority") + "\n")
        self.lease = self.base / "lease.json"
        self.set_lease("human", "opaque/id\n", 7)

    def tearDown(self):
        self.tmp.cleanup()

    def set_lease(self, holder, viewer, epoch):
        self.lease.write_text(json.dumps({"holder": holder, "viewer_id": viewer, "epoch": epoch}))

    async def test_valid_unicode_and_empty_selection(self):
        svc = SelectionService(self.home, _native_read=lambda env: asyncio.sleep(0, result="  héllo 🌱\n\n"))
        self.assertEqual(await svc.read("opaque/id\n", 7), "  héllo 🌱\n\n")
        empty = SelectionService(self.home, _native_read=lambda env: asyncio.sleep(0, result=""))
        with self.assertRaisesRegex(SelectionError, "No text"):
            await empty.read("opaque/id\n", 7)

    async def test_invalid_authority_never_reads(self):
        async def forbidden(env):
            self.fail("native reader called before authorization")
        svc = SelectionService(self.home, _native_read=forbidden)
        for contents in (None, "{bad", json.dumps({"holder":"watcher","viewer_id":"opaque/id\n","epoch":7}),
                         json.dumps({"holder":"human","viewer_id":"opaque/id\n","epoch":True}),
                         json.dumps({"holder":"human","viewer_id":4,"epoch":7}),
                         json.dumps({"holder":"human","viewer_id":"","epoch":7}),
                         json.dumps({"holder":"human","viewer_id":"opaque/id\n","epoch":-1})):
            if contents is None:
                self.lease.unlink(missing_ok=True)
            else:
                self.lease.write_text(contents)
            with self.assertRaises(SelectionError):
                await svc.read("opaque/id\n", 7)
        self.set_lease("human", "opaque/id\n", 7)
        with self.assertRaises(SelectionError):
            await svc.read("other", 7)
        with self.assertRaises(SelectionError):
            await svc.read("opaque/id\n", True)

    async def test_epoch_change_during_native_read_discards_value(self):
        entered, release = asyncio.Event(), asyncio.Event()
        async def reader(env):
            entered.set()
            await release.wait()
            return "secret fixture"
        task = asyncio.create_task(SelectionService(self.home, _native_read=reader).read("opaque/id\n", 7))
        await entered.wait()
        self.set_lease("human", "opaque/id\n", 8)
        release.set()
        with self.assertRaisesRegex(SelectionError, "changed"):
            await task

    async def test_cancel_and_oversize(self):
        started, wait = asyncio.Event(), asyncio.Event()
        async def blocked(env):
            started.set()
            await wait.wait()
        task = asyncio.create_task(SelectionService(self.home, _native_read=blocked).read("opaque/id\n", 7))
        await started.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        svc = SelectionService(self.home, _native_read=lambda env: asyncio.sleep(0, result="x" * 65537))
        with self.assertRaisesRegex(SelectionError, "too large"):
            await svc.read("opaque/id\n", 7)

    async def test_env_target_rejected_before_reader(self):
        (self.base / "env").write_text("DISPLAY=:20\nXAUTHORITY=/tmp/not-profile-auth\n")
        async def forbidden(env):
            self.fail("native reader called with out-of-profile auth")
        with self.assertRaises(SelectionError):
            await SelectionService(self.home, _native_read=forbidden).read("opaque/id\n", 7)

    async def test_real_helper_timeout_and_cancellation_leave_no_process(self):
        original = asyncio.create_subprocess_exec
        processes = []
        started = asyncio.Event()
        async def spawn(*args, **kwargs):
            process = await original(*args, **kwargs)
            processes.append(process)
            started.set()
            return process
        # This child never opens X11 or a clipboard: lifecycle-only probe.
        with patch('selection_backend._READER', 'import time;time.sleep(30)'), patch('selection_backend.asyncio.create_subprocess_exec', side_effect=spawn):
            service = SelectionService(self.home, _timeout=.05)
            with self.assertRaisesRegex(SelectionError, 'timed out'):
                await service.read('opaque/id\n', 7)
            self.assertIsNotNone(processes[-1].returncode)
            started.clear()
            service = SelectionService(self.home)
            task = asyncio.create_task(service.read('opaque/id\n', 7))
            await asyncio.wait_for(started.wait(), 5)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertIsNotNone(processes[-1].returncode)
        self.assertTrue(all(not Path('/proc', str(process.pid)).exists() for process in processes))

    async def test_missing_or_cross_scope_metadata_is_denied(self):
        async def forbidden(env):
            self.fail('Native read on invalid metadata')
        service = SelectionService(self.home, _native_read=forbidden)
        (self.base / 'env').unlink()
        with self.assertRaises(SelectionError):
            await service.read('opaque/id\n', 7)
        (self.base / 'env').symlink_to(self.lease)
        with self.assertRaises(SelectionError):
            await service.read('opaque/id\n', 7)

    async def test_native_error_is_safe(self):
        async def broken(env):
            raise RuntimeError("secret selection and environment")
        with self.assertRaises(SelectionError) as caught:
            await SelectionService(self.home, _native_read=broken).read("opaque/id\n", 7)
        self.assertNotIn("secret", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
