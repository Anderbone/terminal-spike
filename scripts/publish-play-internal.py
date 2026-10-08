#!/usr/bin/env python3
"""Prepare once, then publish the reviewed main-app bundle to Play internal testing.

Credentials stay outside the repository. No Mosh-extension or production uploads.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.yanjiyu.terminalspike"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
TOKEN_URL = "https://oauth2.googleapis.com/token"
STATE = ROOT / "build/play-release/manifest.json"


def command(args, **kwargs):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, **kwargs)
    if result.returncode:
        # Never echo commands, environment, or signing-tool output containing secrets.
        raise RuntimeError(f"{Path(args[0]).name} failed; inspect the local verification log if applicable")
    return result.stdout


def request(method, url, data=None, token=None, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    try:
        with urllib.request.urlopen(urllib.request.Request(url, body, headers, method=method), timeout=600) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Google API rejected {method} (HTTP {error.code}); no automatic upload retry") from None


def access_token(credential: Path) -> str:
    account = json.loads(credential.read_text())
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=")
    now = int(time.time())
    claims = {"iss": account["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
              "aud": TOKEN_URL, "iat": now, "exp": now + 3600}
    unsigned = b".".join(encode(json.dumps(value).encode()) for value in ({"alg": "RS256", "typ": "JWT"}, claims))
    # OpenSSL handles the cryptography; the private key is never a command argument.
    with tempfile.TemporaryDirectory(prefix="terminal-spike-play-", dir="/tmp") as temporary:
        key = Path(temporary) / "key.pem"
        key.touch(mode=0o600)
        key.write_text(account["private_key"])
        signature = command(["openssl", "dgst", "-sha256", "-sign", str(key)], input=unsigned)
    assertion = (unsigned + b"." + encode(signature)).decode()
    payload = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}).encode()
    return request("POST", TOKEN_URL, payload, content_type="application/x-www-form-urlencoded")["access_token"]


def source_fingerprint() -> str:
    paths = command(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"]).split(b"\0")
    digest = hashlib.sha256()
    for raw in sorted(set(paths) - {b""}):
        path = ROOT / os.fsdecode(raw)
        if not path.is_file():
            continue
        digest.update(raw + b"\0")
        digest.update(str(path.stat().st_mode & 0o777).encode() + b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def consumed_codes(bundles, tracks) -> set[int]:
    return ({int(bundle["versionCode"]) for bundle in bundles.get("bundles", [])}
            | {int(code) for track in tracks.get("tracks", [])
               for release in track.get("releases", []) for code in release.get("versionCodes", [])})


def inspect_play(token):
    edit = request("POST", f"{API}/edits", {}, token)["id"]
    try:
        bundles = request("GET", f"{API}/edits/{edit}/bundles", token=token)
        tracks = request("GET", f"{API}/edits/{edit}/tracks", token=token)
        return consumed_codes(bundles, tracks), next(
            (track for track in tracks.get("tracks", []) if track["track"] == "internal"), {}
        )
    finally:
        request("DELETE", f"{API}/edits/{edit}", token=token)


def verify_bundle(bundle: Path, signing_dir: Path, environment):
    output = command(["jarsigner", "-verify", str(bundle)], text=True, env=environment)
    if "jar verified." not in output:
        raise RuntimeError("AAB signature was not verified")
    actual = command(["keytool", "-printcert", "-jarfile", str(bundle)], text=True, env=environment)
    expected = command(["keytool", "-printcert", "-file", str(signing_dir / "terminal-spike-upload-cert.pem")], text=True, env=environment)
    cert = re.compile(r"SHA256:\s*([0-9A-Fa-f:]+)")
    a, b = cert.search(actual), cert.search(expected)
    if a is None or b is None or a.group(1).lower() != b.group(1).lower():
        raise RuntimeError("AAB signer differs from the external upload certificate")


def store_assets():
    root = ROOT / "store-assets/google-play"
    listing = {field: (root / "metadata/en-US" / filename).read_text().rstrip("\n")
               for field, filename in (("title", "title.txt"), ("shortDescription", "short_description.txt"),
                                       ("fullDescription", "full_description.txt"))}
    images = {"phoneScreenshots": sorted((root / "screenshots/phone").glob("*.png")),
              "featureGraphic": [root / "graphics/feature-graphic-1024x500.png"]}
    if not 2 <= len(images["phoneScreenshots"]) <= 8:
        raise RuntimeError("expected 2 to 8 prepared phone screenshots")
    return listing, {kind: [path.read_bytes() for path in paths] for kind, paths in images.items()}


def verify_store_assets(token, edit, listing, images):
    base = f"{API}/edits/{edit}/listings/en-US"
    actual = request("GET", base, token=token)
    if any(actual.get(field) != value for field, value in listing.items()):
        raise RuntimeError("English listing readback differs from prepared copy")
    hashes = {}
    for kind, contents in images.items():
        expected = [hashlib.sha256(content).hexdigest() for content in contents]
        actual_images = request("GET", f"{base}/{kind}", token=token).get("images", [])
        if [image.get("sha256", "").lower() for image in actual_images] != expected:
            raise RuntimeError(f"{kind} readback differs from prepared images or order")
        hashes[kind] = expected
    return hashes


def upload_store_assets(token, edit, listing, images):
    base = f"{API}/edits/{edit}/listings/en-US"
    request("PATCH", base, listing, token)
    for kind, contents in images.items():
        request("DELETE", f"{base}/{kind}", token=token)
        for content in contents:
            uploaded = request("POST", f"{UPLOAD}/edits/{edit}/listings/en-US/{kind}?uploadType=media",
                               content, token, "image/png")["image"]
            if uploaded.get("sha256", "").lower() != hashlib.sha256(content).hexdigest():
                raise RuntimeError(f"{kind} upload hash differs; edit was not committed")
    return verify_store_assets(token, edit, listing, images)


def prepare(args, used):
    if not args.version_name or not re.fullmatch(r"\d+\.\d+\.\d+", args.version_name):
        raise RuntimeError("prepare requires --version-name MAJOR.MINOR.PATCH")
    # Invalidate old evidence before changing or rebuilding the source.
    STATE.unlink(missing_ok=True)
    gradle = ROOT / "app/build.gradle.kts"
    text = gradle.read_text()
    old_code = int(re.search(r"versionCode = (\d+)", text).group(1))
    code = max(used | {old_code}) + 1
    text = re.sub(r"versionCode = \d+", f"versionCode = {code}", text, count=1)
    text = re.sub(r'versionName = "[^"]+"', f'versionName = "{args.version_name}"', text, count=1)
    gradle.write_text(text)
    before = source_fingerprint()
    password = (args.signing_dir / "terminal-spike-upload.password").read_text().strip()
    environment = dict(os.environ, TERMINAL_SPIKE_RELEASE_STORE_FILE=str(args.signing_dir / "terminal-spike-upload.p12"),
                       TERMINAL_SPIKE_RELEASE_STORE_PASSWORD=password, TERMINAL_SPIKE_RELEASE_KEY_PASSWORD=password,
                       TERMINAL_SPIKE_RELEASE_KEY_ALIAS="terminal-spike-upload", LC_ALL="C")
    print(f"Preparing main app {args.version_name} ({code}); logs: build/verification/", flush=True)
    subprocess.run([str(ROOT / "scripts/verify-android.sh")], cwd=ROOT, env=environment, check=True)
    bundle = ROOT / "app/build/outputs/bundle/release/app-release.aab"
    verify_bundle(bundle, args.signing_dir, environment)
    if source_fingerprint() != before:
        raise RuntimeError("source changed during verification; do not publish this build")
    STATE.parent.mkdir(parents=True, exist_ok=True)
    frozen = STATE.parent / "app-release.aab"
    frozen.write_bytes(bundle.read_bytes())
    STATE.write_text(json.dumps({"schema": 1, "package": PACKAGE, "track": "internal", "versionCode": code,
                                "versionName": args.version_name, "source": before,
                                "sha256": hashlib.sha256(frozen.read_bytes()).hexdigest()}, indent=2) + "\n")
    print("Prepared and verified. Review, commit and push this source; then run publish. No upload yet.")


def publish(args, used):
    state = json.loads(STATE.read_text())
    bundle = STATE.parent / "app-release.aab"
    if (state.get("schema") != 1 or state.get("package") != PACKAGE or state.get("track") != "internal"
            or state["source"] != source_fingerprint()
            or state["sha256"] != hashlib.sha256(bundle.read_bytes()).hexdigest()):
        raise RuntimeError("prepared source/artifact changed; prepare again")
    if command(["git", "status", "--porcelain"]).strip():
        raise RuntimeError("review and commit all intended source before publishing")
    head = command(["git", "rev-parse", "HEAD"], text=True).strip()
    remote = command(["git", "ls-remote", "origin", "refs/heads/main"], text=True).split()
    if not remote or remote[0] != head:
        raise RuntimeError("push the reviewed commit to origin/main before publishing")
    code = state["versionCode"]
    receipt = STATE.parent / "publish-receipt.json"
    if receipt.exists():
        previous = json.loads(receipt.read_text())
        if previous.get("versionCode") == code and previous.get("state") == "started":
            raise RuntimeError("an upload attempt already started; inspect its saved edit before retrying")
    if code in used:
        raise RuntimeError("version code is already consumed; inspect Play before preparing a new code")
    listing, images = store_assets() if args.with_store_assets else ({}, {})
    if args.require_green_ci:
        runs = json.loads(command(["gh", "run", "list", "--workflow", "android-ci.yml", "--commit", head,
                                   "--event", "push", "--limit", "1", "--json", "databaseId"], text=True))
        if not runs:
            raise RuntimeError("no push CI run exists for the prepared commit")
        subprocess.run(["gh", "run", "watch", str(runs[0]["databaseId"]), "--exit-status", "--interval", "60"],
                       cwd=ROOT, stdout=subprocess.DEVNULL, check=True, timeout=3600)
    verify_bundle(bundle, args.signing_dir, dict(os.environ, LC_ALL="C"))
    artifact_bytes = bundle.read_bytes()
    if (source_fingerprint() != state["source"]
            or hashlib.sha256(artifact_bytes).hexdigest() != state["sha256"]
            or command(["git", "rev-parse", "HEAD"], text=True).strip() != head
            or command(["git", "status", "--porcelain"]).strip()
            or command(["git", "ls-remote", "origin", "refs/heads/main"], text=True).split() != remote):
        raise RuntimeError("source or artifact changed while waiting; publication stopped")
    token = access_token(args.credential)
    edit = request("POST", f"{API}/edits", {}, token)["id"]
    # Preserve the edit ID immediately: a timeout after upload must never trigger a blind retry.
    receipt = STATE.parent / "publish-receipt.json"
    receipt.write_text(json.dumps({"edit": edit, "versionCode": code, "commit": head, "state": "started"}) + "\n")
    uploaded = request("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", artifact_bytes, token,
                       "application/octet-stream")
    if int(uploaded["versionCode"]) != code or uploaded.get("sha256", "").lower() != state["sha256"]:
        raise RuntimeError("Play uploaded artifact metadata differs; edit was not committed")
    asset_hashes = upload_store_assets(token, edit, listing, images) if args.with_store_assets else {}
    release = {"name": state["versionName"], "versionCodes": [str(code)], "status": "completed"}
    if args.with_store_assets:
        release["releaseNotes"] = [{"language": "en-US", "text": (
            ROOT / "store-assets/google-play/release-notes/main-en-US.txt").read_text().rstrip("\n")}]
    request("PUT", f"{API}/edits/{edit}/tracks/internal", {"track": "internal", "releases": [release]}, token)
    request("POST", f"{API}/edits/{edit}:validate", {}, token)
    request("POST", f"{API}/edits/{edit}:commit", {}, token)
    _, track = inspect_play(token)
    if not any(str(code) in release.get("versionCodes", []) and release.get("status") == "completed"
               for release in track.get("releases", [])):
        raise RuntimeError("internal track did not confirm the uploaded release; inspect before retrying")
    if args.with_store_assets:
        readback = request("POST", f"{API}/edits", {}, token)["id"]
        try:
            verify_store_assets(token, readback, listing, images)
        finally:
            request("DELETE", f"{API}/edits/{readback}", token=token)
    receipt.write_text(json.dumps({"edit": edit, "versionCode": code, "commit": head, "state": "completed",
                                  "track": track, "storeAssetHashes": asset_hashes}, indent=2) + "\n")
    print(f"Play internal track confirmed {state['versionName']} ({code}); CI {'passed' if args.require_green_ci else 'not checked'}. Store availability may lag.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inspect", "prepare", "publish"))
    parser.add_argument("--signing-dir", type=Path, default=ROOT.parent / "app_sign")
    parser.add_argument("--credential", type=Path, help="External Play service-account JSON")
    parser.add_argument("--version-name")
    parser.add_argument("--require-green-ci", action="store_true")
    parser.add_argument("--with-store-assets", action="store_true",
                        help="Publish prepared en-US copy, phone screenshots and feature graphic in the same edit")
    args = parser.parse_args()
    args.signing_dir = args.signing_dir.resolve()
    if args.signing_dir.is_relative_to(ROOT):
        parser.error("signing material must remain outside the repository")
    (args.signing_dir / "README.txt").read_text()  # Required external release instructions.
    if args.credential is None:
        candidates = list(args.signing_dir.glob("*.json"))
        if len(candidates) != 1:
            parser.error("specify the external --credential JSON explicitly")
        args.credential = candidates[0]
    if args.credential.resolve().is_relative_to(ROOT):
        parser.error("credentials must remain outside the repository")
    token = access_token(args.credential)
    used, track = inspect_play(token)
    if args.action == "inspect":
        print(json.dumps({"consumedCodes": sorted(used), "internal": track}, indent=2))
    elif args.action == "prepare":
        prepare(args, used)
    else:
        publish(args, used)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        # Detailed HTTP responses, credentials, and subprocess arguments stay out of output.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        raise SystemExit(f"ERROR {message}. No automatic publication retry.")
