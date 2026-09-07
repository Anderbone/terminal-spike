"""Shared, standard-library-only release-evidence validation helpers."""

from __future__ import annotations

from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import re
import subprocess
from typing import Any, Iterable
import xml.etree.ElementTree as ET


HEX_SHA256 = re.compile(r"[0-9a-f]{64}")
COUNT_NAMES = ("tests", "failures", "errors", "skipped")
EXCLUDED_SOURCE_PARTS = {"build", ".gradle", ".idea", ".kotlin"}


class EvidenceError(ValueError):
    """Raised when candidate evidence is missing, stale, or contradictory."""


def run_git(repo_root: Path, *arguments: str) -> bytes:
    try:
        return subprocess.check_output(
            ["git", *arguments],
            cwd=repo_root,
            stderr=subprocess.DEVNULL,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise EvidenceError(f"git {' '.join(arguments)} failed") from error


def source_files(repo_root: Path) -> list[str]:
    tracked = run_git(repo_root, "ls-files", "-z")
    untracked = run_git(repo_root, "ls-files", "-z", "--others", "--exclude-standard")
    names = {
        item.decode("utf-8")
        for item in (tracked + untracked).split(b"\0")
        if item
    }
    selected: list[str] = []
    for name in sorted(names):
        relative = Path(name)
        if name.startswith("plans/") or set(relative.parts).intersection(EXCLUDED_SOURCE_PARTS):
            continue
        path = repo_root / relative
        if path.is_file():
            selected.append(name)
    return selected


def calculate_source_manifest(repo_root: Path) -> str:
    digest = sha256()
    for name in source_files(repo_root):
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256((repo_root / name).read_bytes()).digest())
    return digest.hexdigest()


def repository_commit(repo_root: Path) -> str:
    commit = run_git(repo_root, "rev-parse", "HEAD").decode("ascii").strip().lower()
    if HEX_SHA256.fullmatch(commit) is None and re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise EvidenceError("HEAD is not a full Git commit identity")
    return commit


def repository_is_dirty(repo_root: Path) -> bool:
    return bool(run_git(repo_root, "status", "--porcelain=v1", "--untracked-files=all"))


def load_json_object(path: Path, label: str) -> dict[str, Any]:
    if not path.is_file():
        raise EvidenceError(f"{label} is missing: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError(f"{label} is unreadable: {path}") from error
    if not isinstance(value, dict):
        raise EvidenceError(f"{label} must be a JSON object")
    return value


def require_exact_keys(value: dict[str, Any], required: set[str], label: str) -> None:
    missing = sorted(required - set(value))
    unexpected = sorted(set(value) - required)
    if missing or unexpected:
        details: list[str] = []
        if missing:
            details.append(f"missing {', '.join(missing)}")
        if unexpected:
            details.append(f"unexpected {', '.join(unexpected)}")
        raise EvidenceError(f"{label} fields differ: {'; '.join(details)}")


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EvidenceError(f"{label} must be a non-empty string")
    if any(character in value for character in ("\r", "\n", "\0")):
        raise EvidenceError(f"{label} contains a control separator")
    return value


def require_identifier(value: Any, label: str) -> str:
    result = require_string(value, label)
    if re.fullmatch(r"[a-z0-9][a-z0-9._-]*", result) is None:
        raise EvidenceError(f"{label} is not a stable identifier")
    return result


def require_unique(values: Iterable[str], label: str) -> None:
    duplicates = sorted(item for item, count in Counter(values).items() if count > 1)
    if duplicates:
        raise EvidenceError(f"{label} contains duplicates: {', '.join(duplicates)}")


def resolve_repo_path(repo_root: Path, raw: Any, label: str) -> tuple[str, Path]:
    value = require_string(raw, label)
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        raise EvidenceError(f"{label} must be a repository-relative path")
    resolved = (repo_root / relative).resolve()
    try:
        resolved.relative_to(repo_root.resolve())
    except ValueError as error:
        raise EvidenceError(f"{label} escapes the repository") from error
    return relative.as_posix(), resolved


def require_fresh(path: Path, earliest_mtime: float, label: str) -> None:
    if not path.is_file():
        raise EvidenceError(f"{label} is missing: {path}")
    if path.stat().st_mtime < earliest_mtime:
        raise EvidenceError(f"{label} is stale: {path}")


def parse_non_negative_count(raw: str | None, label: str) -> int:
    if raw is None or re.fullmatch(r"[0-9]+", raw) is None:
        raise EvidenceError(f"{label} has an invalid count")
    return int(raw)


def junit_counts(path: Path) -> dict[str, int]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise EvidenceError(f"test report is malformed: {path}") from error
    if root.tag == "testsuite":
        suites = [root]
    elif root.tag == "testsuites":
        suites = list(root.findall("testsuite"))
        if not suites:
            raise EvidenceError(f"test report has no suites: {path}")
    else:
        raise EvidenceError(f"test report has unexpected root: {path}")
    counts = {name: 0 for name in COUNT_NAMES}
    for suite in suites:
        for name in COUNT_NAMES:
            raw = suite.attrib.get(name)
            if name == "skipped" and raw is None:
                raw = "0"
            counts[name] += parse_non_negative_count(raw, f"{path} {name}")
    if counts["tests"] <= 0:
        raise EvidenceError(f"test report contains no tests: {path}")
    if counts["failures"] or counts["errors"]:
        raise EvidenceError(f"test report contains failures or errors: {path}")
    return counts


def file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def expected_counts(value: Any, label: str) -> dict[str, int]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{label} must be an object")
    require_exact_keys(value, set(COUNT_NAMES), label)
    result: dict[str, int] = {}
    for name in COUNT_NAMES:
        count = value[name]
        if not isinstance(count, int) or isinstance(count, bool) or count < 0:
            raise EvidenceError(f"{label}.{name} must be a non-negative integer")
        result[name] = count
    if result["tests"] <= 0:
        raise EvidenceError(f"{label}.tests must be positive")
    if result["failures"] or result["errors"]:
        raise EvidenceError(f"{label} records a failing suite")
    if result["skipped"] > result["tests"]:
        raise EvidenceError(f"{label}.skipped exceeds tests")
    return result


def documentation_summary(manifest: dict[str, Any]) -> str:
    source = manifest.get("source")
    suites = manifest.get("tests")
    if not isinstance(source, dict) or not isinstance(suites, list) or not suites:
        raise EvidenceError("candidate manifest lacks source or test evidence")
    require_string(source.get("commit"), "manifest source commit")
    pieces: list[str] = []
    for suite in suites:
        if not isinstance(suite, dict):
            raise EvidenceError("candidate manifest test entry must be an object")
        label = require_string(suite.get("label"), "manifest test label")
        counts = expected_counts(suite.get("counts"), f"manifest test {label} counts")
        passed = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
        pieces.append(
            f"{label} `{counts['tests']}` tests (`{passed}` passed, "
            f"`{counts['skipped']}` skipped, `0` failures/errors)"
        )
    return (
        "Current release evidence: "
        + "; ".join(pieces)
        + ". The source and artifact hashes and any pending external gates are recorded in "
        + "`build/release-evidence/candidate-manifest.json`."
    )
