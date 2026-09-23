#!/usr/bin/env python3
"""Stage unsigned release artifacts from the verified CI build, with provenance."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def stage(project: Path, destination: Path) -> None:
    revision = subprocess.check_output(
        ["git", "-C", str(project), "rev-parse", "HEAD"], text=True,
    ).strip()
    subprocess.run(["git", "-C", str(project), "diff", "--exit-code", "HEAD", "--quiet"], check=True)
    destination.mkdir(parents=True, exist_ok=True)
    files = {
        "app-release-unsigned.apk": "app/build/outputs/apk/release/app-release-unsigned.apk",
        "app-release.aab": "app/build/outputs/bundle/release/app-release.aab",
    }
    hashes = {}
    for name, relative in files.items():
        source = project / relative
        if not source.is_file() or not source.stat().st_size:
            raise RuntimeError(f"verified release artifact missing: {relative}")
        shutil.copyfile(source, destination / name)
        hashes[name] = hashlib.sha256((destination / name).read_bytes()).hexdigest()
    (destination / "manifest.json").write_text(
        json.dumps({"schema": 1, "revision": revision, "sha256": hashes}, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    stage(root, root / "build/ci-release")
