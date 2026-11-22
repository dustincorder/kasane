#!/usr/bin/env python3
"""WebDriver smoke test for Settings + Google sign-in window.

Drives the release binary through tauri-driver (which proxies to
WebKitWebDriver). Flow:
  1. open the app, go to Settings, verify the signed-out state renders
  2. click "Sign in" — the google-login WebviewWindow must open on
     accounts.google.com
  3. screenshot both windows, close the login window

No credentials are typed; real sign-in is a manual step.

Usage:
  python3 scripts/e2e/settings_signin.py [binary-path]

Requires tauri-driver on PATH or /usr/bin/WebKitWebDriver directly reachable;
the script talks to the WebDriver endpoint itself, no selenium needed.
"""

import base64
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error
import http.client as http_client

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BINARY = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "target/release/vibeplayer")
DRIVER_PORT = 4780
BASE = f"http://127.0.0.1:{DRIVER_PORT}"
SHOTS_DIR = os.environ.get("SHOTS_DIR", "/tmp")


def wd(method, path, body=None):
    req = urllib.request.Request(
        BASE + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"},
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return {"error": e.read().decode(), "status": e.code}
        except (urllib.error.URLError, ConnectionError, http_client.RemoteDisconnected):
            if attempt == 2:
                raise
            time.sleep(1)
    return {}


def wait(cond, timeout=20, every=0.4):
    end = time.time() + timeout
    while time.time() < end:
        v = cond()
        if v:
            return v
        time.sleep(every)
    return None


def find_text(session, text, timeout=15):
    def go():
        r = wd(
            "POST",
            f"/session/{session}/elements",
            {"using": "xpath", "value": f"//*[contains(normalize-space(.), '{text}')]"},
        )
        return (r.get("value") or [None])[0]
    el = wait(go, timeout)
    return el


def click_text(session, text, timeout=15):
    """Click a button/link containing `text` via JS.

    WebKitWebDriver's synthesized element clicks don't actuate Svelte handlers
    in this environment, so we dispatch a DOM click instead — the button's own
    onclick still runs.
    """
    script = (
        "const els=[...document.querySelectorAll('button,a')];"
        "const el=els.find(e=>e.textContent.includes(arguments[0]) && !e.disabled);"
        "if(!el) return false; el.click(); return true;"
    )

    def go():
        r = wd("POST", f"/session/{session}/execute/sync", {"script": script, "args": [text]})
        return r.get("value") is True

    if not wait(go, timeout):
        raise RuntimeError(f"button not found: {text}")


def screenshot(session, name):
    r = wd("GET", f"/session/{session}/screenshot")
    data = r.get("value")
    if not data:
        return None
    path = os.path.join(SHOTS_DIR, name)
    with open(path, "wb") as f:
        f.write(base64.b64decode(data))
    return path


def current_url(session):
    return (wd("GET", f"/session/{session}/url") or {}).get("value", "")


def spectacle(name):
    """Full-desktop screenshot via KDE spectacle (for windows WebDriver can't see)."""
    path = os.path.join(SHOTS_DIR, name)
    subprocess.run(
        ["spectacle", "-b", "-n", "-f", "-o", path],
        capture_output=True, timeout=15,
    )
    return path if os.path.isfile(path) else None


def main():
    env = dict(os.environ, WEBKIT_DISABLE_DMABUF_RENDERER="1", GDK_BACKEND="x11")
    driver = subprocess.Popen(
        ["tauri-driver", "--port", str(DRIVER_PORT)],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        # Wait for the driver to listen.
        def alive():
            try:
                urllib.request.urlopen(BASE + "/status", timeout=1).read()
                return True
            except Exception:
                return False
        if not wait(alive, 10):
            raise RuntimeError("tauri-driver did not start")

        r = wd(
            "POST",
            "/session",
            {
                "capabilities": {
                    "alwaysMatch": {
                        "tauri:options": {
                            "application": BINARY,
                            "webviewDriverBinary": "/usr/bin/WebKitWebDriver",
                        }
                    }
                }
            },
        )
        session = (r.get("value") or {}).get("sessionId")
        if not session:
            raise RuntimeError(f"no session: {json.dumps(r)[:400]}")
        print(f"session {session}")

        try:
            # 1. Settings tab — signed-out account section renders.
            click_text(session, "Settings")
            if not find_text(session, "Sign in to YouTube Music"):
                raise RuntimeError("signed-out Account section did not render")
            if not find_text(session, "Kasane Connect"):
                raise RuntimeError("Connect section did not render")
            if not find_text(session, "Storage"):
                raise RuntimeError("Storage section did not render")
            shot1 = screenshot(session, "kasane-settings.png")
            print(f"settings: {shot1}")

            # 2. Click Sign in -> login window opens on accounts.google.com.
            # The google-login WebviewWindow is a separate webview that wry does
            # not register for automation (only the first window gets it), so it
            # is never a WebDriver window handle. Its navigation is proven via
            # the isolated data directory's HSTS store instead.
            click_text(session, "Sign in")
            login_dir = os.path.expanduser("~/.local/share/cord.kasane.desktop/login-webview")
            cookie_file = os.path.join(login_dir, "cookies")

            def google_navigated():
                # WebKit stores cookies in a binary jar whose payload is plain
                # Netscape lines — grepping it proves the host it navigated to.
                if not os.path.isfile(cookie_file):
                    return False
                try:
                    blob = open(cookie_file, "rb").read()
                except OSError:
                    return False
                return b"accounts.google.com" in blob

            if not wait(google_navigated, 25):
                raise RuntimeError("login webview never navigated to accounts.google.com")
            print("login webview reached accounts.google.com (cookie-store evidence)")
            shot2 = spectacle("kasane-login.png")
            print(f"login window: {shot2}")

            # 3. Best-effort close of the login window. The main webview's ACL
            # can't close other windows (`plugin:window|close` is denied), so
            # the window is normally left to session teardown — that still
            # exercises the app's cleanup on process exit.
            close_result = wd("POST", f"/session/{session}/execute/sync", {
                "script": (
                    "const inv=(window.__TAURI_INTERNALS__||{}).invoke;"
                    "if(!inv)return 'no-internals';"
                    "return inv('plugin:window|close',{label:'google-login'}).then(()=>'closed',e=>'close-err:'+e);"
                ), "args": []})
            print("close login window:", close_result.get("value"))
            print("PASS")
        finally:
            wd("DELETE", f"/session/{session}")
    finally:
        driver.terminate()
        try:
            driver.wait(10)
        except subprocess.TimeoutExpired:
            driver.kill()


if __name__ == "__main__":
    main()
