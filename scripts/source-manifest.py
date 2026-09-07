#!/usr/bin/env python3
"""Print the content-sensitive manifest identity for release-relevant source."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from release_evidence import EvidenceError, calculate_source_manifest


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    arguments = parser.parse_args(argv)
    try:
        print(calculate_source_manifest(arguments.repo_root.resolve()))
    except EvidenceError as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
