#!/usr/bin/env python3
"""Stage unsigned release artifacts from the verified CI build, with provenance."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def require_clean_checkout(project: Path) -> None:
    status = subprocess.check_output([
        "git", "-C", str(project), "status", "--porcelain=v1", "--untracked-files=all", "-z",
    ])
    if status:
        raise RuntimeError("CI release staging requires a clean tracked and untracked working tree.")


def stage(project: Path, destination: Path) -> None:
    require_clean_checkout(project)
    revision = subprocess.check_output(
        ["git", "-C", str(project), "rev-parse", "HEAD"], text=True,
    ).strip()
    files = {
        "app-release-unsigned.apk": "app/build/outputs/apk/release/app-release-unsigned.apk",
        "app-release.aab": "app/build/outputs/bundle/release/app-release.aab",
    }
    for relative in files.values():
        source = project / relative
        if not source.is_file() or not source.stat().st_size:
            raise RuntimeError(f"verified release artifact missing: {relative}")

    # A failed copy or source recheck must leave the previous complete stage untouched.
    # CI uses ignored build/ci-release; callers can also stage outside the checkout.
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".ci-release-", dir=destination.parent) as temporary:
        pending = Path(temporary)
        hashes = {}
        for name, relative in files.items():
            shutil.copyfile(project / relative, pending / name)
            hashes[name] = hashlib.sha256((pending / name).read_bytes()).hexdigest()
        (pending / "manifest.json").write_text(
            json.dumps({"schema": 1, "revision": revision, "sha256": hashes}, indent=2) + "\n",
            encoding="utf-8",
        )
        require_clean_checkout(project)
        current_revision = subprocess.check_output(
            ["git", "-C", str(project), "rev-parse", "HEAD"], text=True,
        ).strip()
        if current_revision != revision:
            raise RuntimeError("CI source revision changed while release artifacts were staged.")
        destination.mkdir(parents=True, exist_ok=True)
        # Replace the manifest last; consumers must still verify its revision and both hashes.
        for name in (*files, "manifest.json"):
            (pending / name).replace(destination / name)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    stage(root, root / "build/ci-release")
