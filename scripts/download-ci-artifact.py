#!/usr/bin/env python3
"""Download exact current-run artifacts through REST, with bounded transport recovery."""
import argparse
import fnmatch
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import tempfile
import time
import zipfile


class ArtifactFailure(RuntimeError):
    pass


class RequestFailure(ArtifactFailure):
    def __init__(self, status, transient=False):
        super().__init__(f"Artifact request failed (HTTP {status or 'unknown'}); no credential URLs logged")
        self.status = status
        self.transient = transient


def request(endpoint):
    try:
        result = subprocess.run(["gh", "api", endpoint], capture_output=True, timeout=120)
    except subprocess.TimeoutExpired as error:
        raise RequestFailure(None, True) from error
    if result.returncode:
        error = result.stderr.decode(errors="replace")
        match = re.search(r"HTTP (\d{3})", error)
        status = int(match[1]) if match else None
        transient = status in (429, 502, 503, 504) or any(
            value in error.lower() for value in ("timeout", "connection reset", "<!doctype", "<html"))
        raise RequestFailure(status, transient)
    if result.stdout.lstrip().lower().startswith((b"<!doctype", b"<html")):
        raise RequestFailure(None, True)
    return result.stdout


def metadata(endpoint):
    for attempt in range(2):
        try:
            return json.loads(request(endpoint))
        except RequestFailure as error:
            if attempt or not error.transient:
                raise
            time.sleep(3)
    raise AssertionError("unreachable")


def unpack(data, artifact, destination):
    digest = artifact.get("digest")
    if not digest or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ArtifactFailure("Artifact is missing a valid SHA-256 digest")
    if "sha256:" + hashlib.sha256(data).hexdigest() != digest:
        raise ArtifactFailure("Artifact SHA-256 mismatch")
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as error:
        raise ArtifactFailure("Artifact is not a ZIP archive") from error
    with archive:
        if sum(info.file_size for info in archive.infolist()) > 512 * 1024 * 1024:
            raise ArtifactFailure("Artifact exceeds the bounded extraction size")
        for info in archive.infolist():
            name = PurePosixPath(info.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in info.filename or (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ArtifactFailure("Artifact contains an unsafe archive path")
            target = destination / info.filename
            # Never extract through an existing workspace symlink.
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ArtifactFailure("Artifact path escapes its destination")
        # Decode and CRC-check every member before changing destination contents.
        # Sibling staging keeps each final file replacement on the same filesystem.
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".terminal-spike-artifact-", dir=destination.parent) as temporary:
            staging = Path(temporary)
            try:
                archive.extractall(staging)
            except zipfile.BadZipFile as error:
                raise ArtifactFailure("Artifact ZIP failed integrity validation") from error
            destination.mkdir(parents=True, exist_ok=True)
            for source in staging.rglob("*"):
                target = destination / source.relative_to(staging)
                if not target.resolve().is_relative_to(destination.resolve()):
                    raise ArtifactFailure("Artifact path escapes its destination")
                if source.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    source.replace(target)


def list_artifacts(endpoint):
    artifacts = []
    seen = set()
    total = None
    page = 1
    while True:
        listing = metadata(endpoint if page == 1 else f"{endpoint}&page={page}")
        count = listing.get("total_count")
        if type(count) is not int or count < 0 or (total is not None and count != total):
            raise ArtifactFailure("Artifact listing count is invalid or changed during pagination")
        total = count
        batch = listing["artifacts"]
        for artifact in batch:
            if artifact["id"] in seen:
                raise ArtifactFailure("Artifact listing repeated an ID during pagination")
            seen.add(artifact["id"])
            artifacts.append(artifact)
        if len(artifacts) > total or (not batch and len(artifacts) < total):
            raise ArtifactFailure("Artifact listing is incomplete or inconsistent")
        if len(artifacts) == total:
            return artifacts
        page += 1


def download(repo, run, selector, destination, *, pattern=False, expected_sha=None):
    prefix = f"repos/{repo}/actions"
    listing = list_artifacts(f"{prefix}/runs/{run}/artifacts?per_page=100")
    artifacts = [a for a in listing if
                 (fnmatch.fnmatchcase(a["name"], selector) if pattern else a["name"] == selector)]
    if not artifacts or (not pattern and len(artifacts) != 1):
        raise ArtifactFailure("Required artifact membership is missing or ambiguous")
    for artifact in artifacts:
        if artifact["expired"]:
            raise ArtifactFailure("Required artifact has expired")
        identity = artifact.get("workflow_run", {})
        if identity.get("id") != int(run) or (expected_sha and identity.get("head_sha") != expected_sha):
            raise ArtifactFailure("Artifact belongs to a different run or source revision")
        if Path(artifact["name"]).name != artifact["name"] or artifact["name"] in (".", ".."):
            raise ArtifactFailure("Artifact has an unsafe name")
        artifact_id = artifact["id"]
        print(f"Downloading artifact {artifact['name']} id={artifact_id} run={run}", flush=True)
        for attempt in range(2):
            try:
                data = request(f"{prefix}/artifacts/{artifact_id}/zip")
                break
            except RequestFailure as error:
                if attempt:
                    raise
                if error.status == 404:
                    # A 404 is retryable only when the same exact artifact still exists.
                    current = metadata(f"{prefix}/artifacts/{artifact_id}")
                    if any(current.get(k) != artifact.get(k) for k in ("id", "name", "digest", "workflow_run")) or current["expired"]:
                        raise ArtifactFailure("Artifact disappeared or changed during download") from error
                elif not error.transient:
                    raise
                print("Artifact transport failed; retrying this exact ID once.", flush=True)
                time.sleep(3)
        target = destination / artifact["name"] if pattern else destination
        unpack(data, artifact, target)
        print(f"Artifact id={artifact_id} SHA-256 verified", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--name")
    group.add_argument("--pattern")
    parser.add_argument("--path", type=Path, required=True)
    args = parser.parse_args()
    def deadline_expired(signum, frame):
        raise ArtifactFailure("Artifact transfer exceeded its 180-second total deadline")

    signal.signal(signal.SIGALRM, deadline_expired)
    signal.alarm(180)
    try:
        download(os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_RUN_ID"], args.name or args.pattern,
                 args.path, pattern=bool(args.pattern), expected_sha=os.environ["GITHUB_SHA"])
    except (ArtifactFailure, KeyError, ValueError) as error:
        parser.exit(1, f"ERROR {error}\n")
