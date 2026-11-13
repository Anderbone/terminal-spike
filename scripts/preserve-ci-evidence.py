#!/usr/bin/env python3
"""Keep already-sanitized CI reports on the runner independently of Artifact uploads."""
import argparse
import os
from pathlib import Path
import re
import shutil


def preserve(source: Path, root: Path, run: str, attempt: str, name: str) -> Path:
    if not all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in (run, attempt, name)):
        raise ValueError("Unsafe CI evidence identity")
    destination = root / run / attempt / name
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    if source.is_dir():
        for file in source.rglob("*"):
            if file.is_file() and not file.is_symlink():
                target = destination / file.relative_to(source)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(file, target)
    print(f"Runner evidence retained at {destination}", flush=True)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--name", required=True)
    args = parser.parse_args()
    if not os.environ.get("GITHUB_RUN_ID"):
        parser.error("This archive requires a GitHub run identity")
    preserve(args.source, Path.home() / ".local/state/terminal-spike-ci-evidence",
             os.environ["GITHUB_RUN_ID"], os.environ.get("GITHUB_RUN_ATTEMPT", "1"), args.name)
