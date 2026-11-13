#!/usr/bin/env python3
"""Prepare the opt-in emulator probe. Does not install APKs or read credentials.

BgUtils and YouTube.js are MIT licensed. Public Google web-protocol headers are dependency
implementation details, generated under ignored build/ instead of checked into
Kasane sources. No user/developer API key or account token is required here.
"""
import hashlib
import io
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VERSION = "4.0.3"
SHA256 = "4ae4a52040c5b3a77452ccf5fa7c2edeee36bbfc84e37943ad9b61d85293bb9b"
URL = f"https://registry.npmjs.org/bgutils-js/-/bgutils-js-{VERSION}.tgz"
OUT = ROOT / "android/app/build/generated/attestationProbeAssets/attestation"


def main():
    esbuild = ROOT / "node_modules/.bin/esbuild"
    if not esbuild.is_file():
        found = shutil.which("esbuild")
        if not found:
            raise SystemExit("Existing esbuild is required for this optional probe; no dependencies were installed.")
        esbuild = Path(found)
    with urllib.request.urlopen(URL, timeout=30) as response:
        archive = response.read(2 * 1024 * 1024 + 1)
    if len(archive) > 2 * 1024 * 1024 or hashlib.sha256(archive).hexdigest() != SHA256:
        raise SystemExit("BgUtils archive integrity check failed.")
    with tempfile.TemporaryDirectory(prefix="kasane-attestation-") as temporary:
        directory = Path(temporary)
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
            for member in package.getmembers():
                if not member.isfile() or not (member.name.startswith("package/dist/") or member.name == "package/LICENSE"):
                    continue
                relative = PurePosixPath(member.name).relative_to("package")
                if ".." in relative.parts or member.size > 2 * 1024 * 1024:
                    raise SystemExit("Invalid dependency archive entry.")
                target = directory / "bgutils" / str(relative)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(package.extractfile(member).read())
        # Existing Kasane code cannot prepare web player URLs. Keep this MIT
        # implementation optional and test-only until full playback is verified.
        yt_url = "https://registry.npmjs.org/youtubei.js/-/youtubei.js-18.3.0.tgz"
        with urllib.request.urlopen(yt_url, timeout=30) as response:
            yt_archive = response.read(4 * 1024 * 1024 + 1)
        if len(yt_archive) > 4 * 1024 * 1024 or hashlib.sha256(yt_archive).hexdigest() != "ffcdab3af8b05c66ee5b38475928c5cacbc603018eca64d846a1693729994dd8":
            raise SystemExit("YouTube.js archive integrity check failed.")
        with tarfile.open(fileobj=io.BytesIO(yt_archive), mode="r:gz") as package:
            for name, output in [("package/bundle/browser.js", "youtube-player.js"), ("package/LICENSE", "youtube-player-LICENSE")]:
                member = package.getmember(name)
                if not member.isfile() or member.size > 2 * 1024 * 1024:
                    raise SystemExit("Invalid YouTube.js dependency entry.")
                (directory / output).write_bytes(package.extractfile(member).read())
        OUT.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(directory / "youtube-player-LICENSE", OUT / "youtube-player-LICENSE")
        entry = directory / "probe-entry.js"
        shutil.copyfile(ROOT / "scripts/attestation/probe-entry.js", entry)
        OUT.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(esbuild), str(entry), "--bundle", "--format=iife", "--platform=browser",
                        "--target=chrome100", "--legal-comments=inline", f"--outfile={OUT / 'probe.js'}"], check=True)
        shutil.copyfile(directory / "bgutils/LICENSE", OUT / "LICENSE")
    print(f"Prepared emulator-only BgUtils {VERSION} and YouTube.js 18.3.0; archive integrity verified.")


if __name__ == "__main__":
    main()
