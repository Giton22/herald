"""Exercise a real installed sandbox on a private Xvfb display."""
import sys

if __name__ == '__main__' and sys.platform != 'linux':
    raise SystemExit('This installed-Flatpak test requires Linux.')

import argparse
from contextlib import ExitStack
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from PIL import ImageGrab
from smoke_gateway import server

APP = 'app.kyllene.Kyllene.Devel'
BUTTON = (18, 69, 171)
# The widget's background: before any click, after the first, after the second.
LOADED, CLICKED, CLICKED_TWICE = (25, 195, 125), (195, 25, 224), (224, 160, 25)


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=120).stdout.strip()


def until(probe, description, app, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if app.poll() is not None:
            raise RuntimeError('Application exited before ' + description)
        value = probe()
        if value:
            return value
        time.sleep(.2)
    raise TimeoutError(description)


def pixels(color):
    image = ImageGrab.grab(xdisplay=os.environ['DISPLAY'])
    image = image.convert('RGB')
    # Sample only the controlled Xvfb screen, never a user's desktop.
    points = [(x, y) for y in range(0, image.height, 4)
              for x in range(0, image.width, 4) if image.getpixel((x, y)) == color]
    return points if len(points) > 1000 else None


def verify_resize(window, app, before):
    run('xdotool', 'windowsize', window, '1200', '800')
    until(lambda: {'WIDTH=1200', 'HEIGHT=800'} <=
          set(run('xdotool', 'getwindowgeometry', '--shell', window).splitlines()), 'window resize', app)
    # X11 geometry changes before Compose relocates Chromium; wait for its new frame.
    old_origin = (min(x for x, y in before), min(y for x, y in before))
    def relocated():
        points = pixels(BUTTON)
        return points if points and (min(x for x, y in points), min(y for x, y in points)) != old_origin else None
    painted = until(relocated, 'resized widget layout', app)
    run('xdotool', 'mousemove', '--window', window, str((min(x for x, y in painted) + max(x for x, y in painted)) // 2),
        str((min(y for x, y in painted) + max(y for x, y in painted)) // 2), 'click', '1')
    # A reloaded page would turn magenta again; only the kept page reaches the third colour.
    until(lambda: pixels(CLICKED_TWICE), 'post-resize widget click', app)


def main(bundle):
    existing = run('flatpak', 'list', '--user', '--app', '--columns=application').splitlines()
    if APP in existing:
        raise RuntimeError('Refusing to replace an installed app; use a disposable Flatpak user installation')
    with ExitStack() as resources:
        try:
            run('flatpak', 'install', '--user', '--noninteractive', '-y', str(bundle))
            commit = run('flatpak', 'info', '--user', '--show-commit', APP)
        except Exception as failure:
            state = 'unknown'
            try:
                if APP in run('flatpak', 'list', '--user', '--app', '--columns=application').splitlines():
                    state = 'retained'
            except Exception:
                pass
            raise RuntimeError(f'Installation {state}; commit unknown. No uninstall attempted. '
                               'Inspect the disposable user installation before retrying.') from failure
        resources.callback(uninstall, commit)
        service = server()
        resources.callback(service.server_close)
        threading.Thread(target=service.serve_forever, daemon=True).start()
        resources.callback(service.shutdown)
        profile = resources.enter_context(tempfile.TemporaryDirectory(prefix='kyllene smoke café '))
        log = resources.enter_context(tempfile.TemporaryFile(mode='w+'))
        app = subprocess.Popen(['flatpak', 'run', '--command=env', APP,
            'XDG_DATA_HOME=' + profile + '/data', 'XDG_CONFIG_HOME=' + profile + '/config',
            'XDG_CACHE_HOME=' + profile + '/cache', '/app/bin/kyllene'],
            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        resources.callback(stop, app)
        try:
            def windows():
                # Flatpak's wrapper PID differs from Java; this display has exactly one app.
                result = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name', '^Kyllene$'], capture_output=True, text=True, timeout=10)
                found = result.stdout.splitlines()
                return found[0] if len(found) == 1 else None
            window = until(windows, 'mapped window', app)
            def xdo(*args):
                return run('xdotool', *args)
            def click(x, y):
                xdo('mousemove', '--window', window, str(x), str(y), 'click', '1')
            xdo('windowmove', window, '0', '0')
            xdo('windowsize', window, '1280', '840')
            xdo('windowfocus', '--sync', window)
            # Each screen may still be drawing when the fixed pause ends on a slow runner, so a step that
            # landed on nothing is tried again; the fields are selected before typing, so retries don't add text.
            def attempt(step, done, description, tries=3):
                for _ in range(tries - 1):
                    step()
                    try:
                        return until(done, description, app, seconds=10)
                    except TimeoutError:
                        pass
                step()
                return until(done, description, app)
            time.sleep(2)
            def enter_address():
                click(550, 264)
                xdo('key', 'ctrl+a')
                xdo('type', '--clearmodifiers', f'http://127.0.0.1:{service.server_port}')
                xdo('key', 'Tab', 'Return')
            attempt(enter_address, lambda: ('GET', '/api/status') in service.calls, 'gateway address input')
            def sign_in():
                time.sleep(1)
                click(640, 632)
                time.sleep(1)
                xdo('key', 'ctrl+a')
                xdo('type', 'fixture')
                xdo('key', 'Tab', 'ctrl+a')
                xdo('type', 'fixture')
                click(640, 454)
            attempt(sign_in, lambda: service.authenticated, 'synthetic sign-in')
            # Served, not just asked for: the sandbox sent back the cookie the login set.
            until(lambda: ('GET', '/api/sessions') in service.served, 'session list', app)
            # The fixture opens /api/ws only with the ticket subprotocol, so any call proves the handshake.
            until(lambda: any(kind == 'RPC' for kind, _ in service.calls), 'ticketed WebSocket', app)
            time.sleep(1)
            def open_first_chat():
                # The All/Running/Needs-you filter chips sit at y~415-442; probing them can select
                # Needs-you and hide the fixture session. Reset to All (leftmost chip), then scan the
                # chat list strictly below the chips for the single fixture row.
                click(32, 428)
                time.sleep(0.4)
                for y in range(450, 601, 12):
                    click(140, y)
                    time.sleep(0.35)
                    if pixels(LOADED):
                        return
            attempt(open_first_chat, lambda: pixels(LOADED), 'local HTML widget rendering')
            button = until(lambda: pixels(BUTTON), 'painted widget button', app)
            click((min(x for x, y in button) + max(x for x, y in button)) // 2,
                  (min(y for x, y in button) + max(y for x, y in button)) // 2)
            until(lambda: pixels(CLICKED), 'widget JavaScript click', app)
            verify_resize(window, app, button)
            print('Installed sandbox: address input, synthetic login, HTML/JavaScript and resize passed')
        except Exception:
            log.seek(0)
            print(log.read(), file=sys.stderr)
            # Leave a picture of the stuck screen so a failed probe can be diagnosed from the artifact.
            try:
                ImageGrab.grab(xdisplay=os.environ['DISPLAY']).save('smoke-failure.png')
            except Exception:
                pass
            raise


def stop(app):
    try:
        for row in run('flatpak', 'ps', '--columns=instance,pid,application').splitlines():
            fields = row.split()
            if len(fields) == 3 and fields[1:] == [str(app.pid), APP]:
                run('flatpak', 'kill', fields[0])
    finally:
        if app.poll() is None:
            app.terminate()
        try:
            app.wait(timeout=15)
        except subprocess.TimeoutExpired:
            # A JVM stuck in shutdown must not outlive the test, or uninstalling runs under it.
            app.kill()
            app.wait(timeout=15)


def uninstall(commit):
    if run('flatpak', 'info', '--user', '--show-commit', APP) != commit:
        raise RuntimeError('Installed ref changed during test; refusing cleanup')
    run('flatpak', 'uninstall', '--user', '--noninteractive', '-y', APP)
    print('Owned installation removed; no app data deleted')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('bundle', type=Path)
    # A release bundle is the stable app, app.kyllene.Kyllene.
    parser.add_argument('--app', default=APP)
    args = parser.parse_args()
    # Always create a private display; no environment flag can select the live desktop.
    child = ("import sys; from pathlib import Path; import installed_smoke; "
             "installed_smoke.APP = sys.argv[2]; installed_smoke.main(Path(sys.argv[1]))")
    raise SystemExit(subprocess.call(['xvfb-run', '-a', '-s', '-screen 0 1280x900x24',
                                     sys.executable, '-c', child, str(args.bundle.resolve(strict=True)), args.app],
                                     cwd=Path(__file__).resolve().parent))
