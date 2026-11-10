#!/usr/bin/env python3
"""Fail unless Play tracks, GitHub downloads, README and both website locales agree."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPO = "Anderbone/terminal-spike"
GITHUB = f"https://github.com/{REPO}"
DOWNLOAD = f"{GITHUB}/releases/latest/download/terminal-spike-latest.apk"
PLAY_URL = "https://play.google.com/store/apps/details?id=com.yanjiyu.terminalspike"
WEBSITES = ("https://yanjiyu.com/terminal-spike/", "https://yanjiyu.com/zh/terminal-spike/")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Terminal-Spike-Publication-Check",
                                                   "Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()


def gh(path):
    result = subprocess.run(["gh", "api", path], cwd=ROOT, capture_output=True, check=True)
    return json.loads(result.stdout)


def verify_tracks(tracks, version, code):
    expected = {"production", "internal", "alpha", "beta"}
    require({item["track"] for item in tracks} == expected, "Play channel set differs from the four expected channels")
    for track in tracks:
        releases = track.get("releases", [])
        require(len(releases) == 1 and releases[0].get("name") == version
                and releases[0].get("versionCodes") == [str(code)]
                and releases[0].get("status") == "completed",
                f"Play {track['track']} is not completed at {version} ({code})")


def verify_release(release, tag_commit, head, version, apk_hash):
    require(release.get("tag_name") == f"v{version}" and not release.get("draft")
            and not release.get("prerelease"), "GitHub Latest is not the expected public release")
    require(tag_commit == head, "GitHub release tag does not match the reviewed release commit")
    assets = release.get("assets", [])
    names = {asset["name"]: asset for asset in assets}
    needed = {f"terminal-spike-{version}.apk", "terminal-spike-latest.apk", "SHA256SUMS"}
    sources = [name for name in names if name.startswith("terminal-spike-source-") and name.endswith(".tar.gz")]
    require(needed <= names.keys() and len(sources) == 1, "GitHub release is missing APK, stable download, checksums or source")
    require(all(asset.get("state") == "uploaded" for asset in assets), "GitHub release has an incomplete asset")
    for name in (f"terminal-spike-{version}.apk", "terminal-spike-latest.apk"):
        require(names[name].get("digest") == f"sha256:{apk_hash}", f"GitHub {name} hash differs from verified APK")
    sums = fetch(names["SHA256SUMS"]["browser_download_url"]).decode()
    expected_hashes = {}
    for line in sums.splitlines():
        checksum, filename = line.split(maxsplit=1)
        expected_hashes[filename.lstrip(" *")] = checksum
    for name in needed - {"SHA256SUMS"} | set(sources):
        require(name in expected_hashes and names[name].get("digest") == f"sha256:{expected_hashes[name]}",
                f"GitHub checksum mismatch for {name}")
    require(hashlib.sha256(fetch(DOWNLOAD)).hexdigest() == apk_hash,
            "The public Latest APK download does not match the verified APK")


def verify_links(readme, pages):
    require(DOWNLOAD in readme and f"{GITHUB}/releases/download/" not in readme,
            "README still pins an old APK or lacks the stable Latest download")
    for url, html in pages.items():
        require(DOWNLOAD in html and PLAY_URL in html and f"{GITHUB}/releases/latest" in html,
                f"Website is missing current Play/Latest download links: {url}")
        require(f"{GITHUB}/releases/download/" not in html and "/blob/38fba27/" not in html
                and not re.search(r"Google Play\s*·\s*\d+\.\d+\.\d+", html),
                f"Website still pins an old version: {url}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--credential", type=Path, help="External Play service-account JSON")
    args = parser.parse_args()
    if args.credential is None:
        credentials = list((ROOT.parent / "app_sign").glob("*.json"))
        require(len(credentials) == 1, "Specify the external --credential")
        args.credential = credentials[0]
    require(not args.credential.resolve().is_relative_to(ROOT), "Keep Play credentials outside the repository")
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "([^"]+)"', gradle).group(1)
    code = int(re.search(r"versionCode = (\d+)", gradle).group(1))
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT), "Uncommitted work remains")
    require(gh(f"repos/{REPO}/commits/main")["sha"] == head, "origin/main differs from the release checkout")
    apk = ROOT / f"build/github-release/terminal-spike-{version}.apk"
    apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    spec = importlib.util.spec_from_file_location("play", ROOT / "scripts/publish-play-internal.py")
    play = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(play)
    token = play.access_token(args.credential)
    edit = play.request("POST", f"{play.API}/edits", {}, token)["id"]
    try:
        tracks = play.request("GET", f"{play.API}/edits/{edit}/tracks", token=token).get("tracks", [])
        verify_tracks(tracks, version, code)
    finally:
        play.request("DELETE", f"{play.API}/edits/{edit}", token=token)
    print(f"PASS Play: all four tracks completed at {version} ({code})", flush=True)
    release = gh(f"repos/{REPO}/releases/latest")
    tag_commit = gh(f"repos/{REPO}/commits/v{version}")["sha"]
    verify_release(release, tag_commit, head, version, apk_hash)
    print("PASS GitHub: public Latest release, tag, APK download, source and SHA-256 checksums", flush=True)
    verify_links((ROOT / "README.md").read_text(), {url: fetch(url).decode() for url in WEBSITES})
    print("PASS README and English/Chinese website: current Play and Latest APK links", flush=True)
    receipt = {"versionName": version, "versionCode": code, "commit": head, "apkSha256": apk_hash,
               "tracks": tracks, "githubRelease": release["html_url"], "download": DOWNLOAD,
               "websites": list(WEBSITES), "publicPlayAvailability": "not confirmed by Publisher API"}
    (ROOT / "build/play-release/publication-verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("PUBLICATION VERIFIED. Google review/managed publishing/store availability require a separate Console check.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        raise SystemExit(f"PUBLICATION INCOMPLETE: {message}")
