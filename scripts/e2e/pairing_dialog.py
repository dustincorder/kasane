#!/usr/bin/env python3
"""Repro/verify for the desktop pairing-dialog SAS code.

Drives the release binary through tauri-driver. Flow:
  outgoing: Devices -> Add <peer addr> -> Pair -> read the 6-digit code
            shown in the dialog, screenshot, Confirm -> expect 'paired'.
  incoming: wait for a peer-initiated pairing prompt, read the code,
            screenshot, Confirm.

Usage:
  python3 scripts/e2e/pairing_dialog.py outgoing 127.0.0.1:47650
  python3 scripts/e2e/pairing_dialog.py incoming

The peer is kasane-connect-cli (serve --auto-accept or pair).
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
BINARY = os.environ.get("KC_BINARY", os.path.join(ROOT, "target/release/kasane"))
DRIVER_PORT = 4781
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


def js(session, script, args=None):
    r = wd("POST", f"/session/{session}/execute/sync", {"script": script, "args": args or []})
    return r.get("value")


def click_text(session, text, timeout=15):
    script = (
        "const els=[...document.querySelectorAll('button,a')];"
        "const el=els.find(e=>e.textContent.includes(arguments[0]) && !e.disabled);"
        "if(!el) return false; el.click(); return true;"
    )
    if not wait(lambda: js(session, script, [text]) is True, timeout):
        raise RuntimeError(f"button not found: {text}")


def find_text(session, text, timeout=15):
    script = "return document.body.innerText.includes(arguments[0]);"
    return wait(lambda: js(session, script, [text]) is True, timeout)


def screenshot(session, name):
    r = wd("GET", f"/session/{session}/screenshot")
    data = r.get("value")
    if not data:
        return None
    path = os.path.join(SHOTS_DIR, name)
    with open(path, "wb") as f:
        f.write(base64.b64decode(data))
    return path


def dialog_code(session, timeout=25):
    """Return the 6-digit code shown in the pairing dialog, or None."""
    import re
    def go():
        text = dialog_state(session) or ""
        m = re.search(r"\b(\d{6})\b", text)
        return m.group(1) if m else None
    return wait(go, timeout)


def dialog_state(session):
    script = (
        "const d=[...document.querySelectorAll('div')].find("
        "  e=>e.className.includes('fixed')&&e.textContent.includes('matches on both screens'));"
        "return d?d.innerText:null;"
    )
    return js(session, script)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "outgoing"
    peer = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1:47650"

    env = dict(os.environ, WEBKIT_DISABLE_DMABUF_RENDERER="1")
    driver = subprocess.Popen(
        ["tauri-driver", "--port", str(DRIVER_PORT)],
        env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        def alive():
            try:
                urllib.request.urlopen(BASE + "/status", timeout=1).read()
                return True
            except Exception:
                return False
        if not wait(alive, 10):
            raise RuntimeError("tauri-driver did not start")

        r = wd("POST", "/session", {
            "capabilities": {"alwaysMatch": {"tauri:options": {
                "application": BINARY,
                "webviewDriverBinary": "/usr/bin/WebKitWebDriver",
            }}}
        })
        session = (r.get("value") or {}).get("sessionId")
        if not session:
            raise RuntimeError(f"no session: {json.dumps(r)[:400]}")
        print(f"session {session}")

        try:
            # Give the connect service a moment to bind + announce.
            time.sleep(2)
            if not find_text(session, "Kasane", 20):
                raise RuntimeError("app did not render")
            # Go to the Devices view.
            click_text(session, "Devices")
            time.sleep(1)

            if mode == "outgoing":
                # Fill the manual-address input and click Add.
                ok = js(session,
                        "const inp=document.querySelector('input[placeholder*=\\\"address\\\" i]');"
                        "if(!inp) return false;"
                        "const setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;"
                        "setter.call(inp, arguments[0]);"
                        "inp.dispatchEvent(new Event('input',{bubbles:true})); return true;",
                        [peer])
                if not ok:
                    raise RuntimeError("manual address input not found")
                click_text(session, "Add")
                code = dialog_code(session)
                print(f"dialog state: {json.dumps(dialog_state(session))}")
                shot = screenshot(session, "kasane-pairing-outgoing.png")
                print(f"dialog code: {code!r}  screenshot: {shot}")
                if not code or len(code.strip()) != 6:
                    print("BUG REPRODUCED: no 6-digit code in dialog")
                else:
                    # Confirm our side; the cli peer auto-accepts.
                    click_text(session, "Confirm")
                    paired = find_text(session, "paired", 15)
                    screenshot(session, "kasane-pairing-done.png")
                    print(f"paired status visible: {bool(paired)}")
            else:
                # Incoming: wait for a peer to initiate pairing.
                code = dialog_code(session, timeout=90)
                print(f"dialog state: {json.dumps(dialog_state(session))}")
                shot = screenshot(session, "kasane-pairing-incoming.png")
                print(f"incoming dialog code: {code!r}  screenshot: {shot}")
                if code:
                    click_text(session, "Confirm")
                    paired = find_text(session, "paired", 15)
                    screenshot(session, "kasane-pairing-incoming-done.png")
                    print(f"paired status visible: {bool(paired)}")
        finally:
            wd("DELETE", f"/session/{session}")
    finally:
        driver.terminate()


if __name__ == "__main__":
    main()
