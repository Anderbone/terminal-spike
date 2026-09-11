#!/usr/bin/env python3
"""Require release-facing documents to contain the manifest-derived current facts."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

from release_evidence import EvidenceError, documentation_summary, load_json_object


START_MARKER = "<!-- release-evidence-current:start -->"
END_MARKER = "<!-- release-evidence-current:end -->"
DEFAULT_DOCUMENTS = (
    "docs/DEVELOPING.md",
    "docs/PUBLISHING.md",
    "docs/NEXT_STEPS.md",
    "IMPLEMENTATION_STATUS.md",
)
CURRENT_QUALIFIER = re.compile(r"\b(?:current|latest|release[- ]candidate)\b", re.IGNORECASE)
TEST_COUNT_CLAIM = re.compile(
    r"(?:\b[0-9][0-9,]*\s+(?:named\s+|exact\s+|app\s+|JVM\s+)*"
    r"(?:tests?|methods?|passes|skips)\b|"
    r"\b(?:JVM|suite|runner|contract|membership|test totals?)\b[^\n]{0,80}"
    r"\b[0-9][0-9,]*\b)",
    re.IGNORECASE,
)


def expected_block(manifest: dict) -> str:
    return f"{START_MARKER}\n{documentation_summary(manifest)}\n{END_MARKER}"


def verify_document(path: Path, block: str) -> None:
    if not path.is_file():
        raise EvidenceError(f"release document is missing: {path}")
    text = path.read_text(encoding="utf-8")
    if text.count(START_MARKER) != 1 or text.count(END_MARKER) != 1:
        raise EvidenceError(f"{path} must contain exactly one release-evidence marker block")
    start = text.index(START_MARKER)
    end = text.index(END_MARKER, start) + len(END_MARKER)
    if text[start:end] != block:
        raise EvidenceError(f"{path} current release evidence differs from the candidate manifest")
    outside_block = text[:start] + text[end:]
    for paragraph in re.split(r"\n\s*\n", outside_block):
        if CURRENT_QUALIFIER.search(paragraph) and TEST_COUNT_CLAIM.search(paragraph):
            raise EvidenceError(
                f"{path} contains a current test-count claim outside the manifest-derived block"
            )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--documents", nargs="*", default=list(DEFAULT_DOCUMENTS))
    arguments = parser.parse_args(argv)
    repo_root = arguments.repo_root.resolve()
    manifest_path = arguments.manifest
    if not manifest_path.is_absolute():
        manifest_path = repo_root / manifest_path
    try:
        manifest = load_json_object(manifest_path, "candidate manifest")
        if manifest.get("schema") != 1:
            raise EvidenceError("candidate manifest must use schema 1")
        block = expected_block(manifest)
        documents: list[Path] = []
        for raw in arguments.documents:
            relative = Path(raw)
            if relative.is_absolute() or ".." in relative.parts:
                raise EvidenceError("release document paths must be repository-relative")
            documents.append(repo_root / relative)
        if not documents:
            raise EvidenceError("at least one release document is required")
        for document in documents:
            verify_document(document, block)
    except (EvidenceError, OSError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    print(f"RELEASE_DOCUMENTATION_MATCH documents={len(documents)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
