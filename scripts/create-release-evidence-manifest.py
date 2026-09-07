#!/usr/bin/env python3
"""Create one deterministic, validated, privacy-safe release candidate manifest."""

from __future__ import annotations

import argparse
from collections import defaultdict
import glob
import json
from pathlib import Path
import re
import sys
from typing import Any
from urllib.parse import urlparse

from release_evidence import (
    COUNT_NAMES,
    EvidenceError,
    HEX_SHA256,
    calculate_source_manifest,
    expected_counts,
    file_sha256,
    junit_counts,
    load_json_object,
    repository_commit,
    repository_is_dirty,
    require_exact_keys,
    require_fresh,
    require_identifier,
    require_string,
    require_unique,
    resolve_repo_path,
)


ROOT_KEYS = {
    "schema",
    "source",
    "fresh_after",
    "test_suites",
    "artifacts",
    "devices",
    "hosted_runs",
    "pending",
}
SOURCE_KEYS = {"commit", "dirty", "manifest", "test_contract"}
TEST_KEYS = {"id", "label", "kind", "reports", "expected"}
ARTIFACT_KEYS = {
    "id",
    "path",
    "metadata",
    "package",
    "version_code",
    "version_name",
    "signer_sha256",
    "sha256",
}
ARTIFACT_METADATA_KEYS = {
    "schema",
    "artifact_sha256",
    "package",
    "version_code",
    "version_name",
    "signer_sha256",
    "package_verifier",
    "signature_verifier",
}
DEVICE_KEYS = {"id", "model", "api", "transport", "scenarios"}
SCENARIO_KEYS = {"id", "result"}
HOSTED_KEYS = {"id", "url", "conclusion"}
PENDING_KEYS = {"id", "category", "reason"}
KINDS = {"jvm", "android", "host"}
TRANSPORTS = {"usb", "wifi", "emulator"}
PASSING_CONCLUSIONS = {"success", "neutral", "skipped"}
PENDING_CATEGORIES = {"hosted_ci", "manual", "external", "legal", "signing", "store"}
FORBIDDEN_PRIVATE_TEXT = re.compile(
    r"(?:/home/|/Users/|adb-[A-Za-z0-9]|(?:^|[^0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?:[^0-9]|$))"
)


def require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvidenceError(f"{label} must be a list")
    return value


def read_epoch(repo_root: Path, raw_path: Any) -> tuple[str, float]:
    relative, path = resolve_repo_path(repo_root, raw_path, "fresh_after")
    if not path.is_file():
        raise EvidenceError(f"fresh_after file is missing: {path}")
    raw = path.read_text(encoding="utf-8").strip()
    if re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", raw) is None:
        raise EvidenceError("fresh_after file must contain a Unix epoch")
    return relative, float(raw)


def validate_source(
    repo_root: Path,
    value: Any,
    earliest_mtime: float,
) -> tuple[dict[str, Any], str]:
    if not isinstance(value, dict):
        raise EvidenceError("source must be an object")
    require_exact_keys(value, SOURCE_KEYS, "source")
    expected_commit = require_string(value["commit"], "source.commit").lower()
    actual_commit = repository_commit(repo_root)
    if expected_commit != actual_commit:
        raise EvidenceError("source.commit differs from repository HEAD")
    expected_dirty = value["dirty"]
    if not isinstance(expected_dirty, bool):
        raise EvidenceError("source.dirty must be Boolean")
    actual_dirty = repository_is_dirty(repo_root)
    if expected_dirty != actual_dirty:
        raise EvidenceError("source.dirty differs from repository state")

    manifest_relative, manifest_path = resolve_repo_path(
        repo_root, value["manifest"], "source.manifest"
    )
    require_fresh(manifest_path, earliest_mtime, "source manifest")
    recorded_manifest = manifest_path.read_text(encoding="ascii").strip().lower()
    if HEX_SHA256.fullmatch(recorded_manifest) is None:
        raise EvidenceError("source manifest file does not contain one SHA-256")
    actual_manifest = calculate_source_manifest(repo_root)
    if recorded_manifest != actual_manifest:
        raise EvidenceError("source manifest differs from current source")

    contract_relative, contract_path = resolve_repo_path(
        repo_root, value["test_contract"], "source.test_contract"
    )
    if not contract_path.is_file():
        raise EvidenceError("Android test contract is missing")
    contract = load_json_object(contract_path, "Android test contract")
    if contract.get("schema") != 1:
        raise EvidenceError("Android test contract must use schema 1")
    return (
        {
            "commit": actual_commit,
            "dirty": actual_dirty,
            "manifest_path": manifest_relative,
            "manifest_sha256": actual_manifest,
        },
        file_sha256(contract_path),
    )


def expand_reports(repo_root: Path, patterns: Any, label: str) -> list[tuple[str, Path]]:
    raw_patterns = require_list(patterns, label)
    if not raw_patterns:
        raise EvidenceError(f"{label} must not be empty")
    reports: list[tuple[str, Path]] = []
    for index, raw_pattern in enumerate(raw_patterns):
        pattern = require_string(raw_pattern, f"{label}[{index}]")
        relative = Path(pattern)
        if relative.is_absolute() or ".." in relative.parts:
            raise EvidenceError(f"{label}[{index}] must be repository-relative")
        matches = sorted(Path(path) for path in glob.glob(str(repo_root / pattern), recursive=True))
        files = [path.resolve() for path in matches if path.is_file()]
        if not files:
            raise EvidenceError(f"{label}[{index}] matched no reports")
        for path in files:
            try:
                relative_path = path.relative_to(repo_root.resolve())
            except ValueError as error:
                raise EvidenceError(f"{label}[{index}] resolves outside the repository") from error
            reports.append((relative_path.as_posix(), path))
    require_unique((relative for relative, _ in reports), f"{label} expanded paths")
    return reports


def validate_tests(
    repo_root: Path,
    values: Any,
    earliest_mtime: float,
) -> tuple[list[dict[str, Any]], dict[str, dict[str, int]]]:
    entries = require_list(values, "test_suites")
    if not entries:
        raise EvidenceError("test_suites must not be empty")
    output: list[dict[str, Any]] = []
    all_report_paths: list[str] = []
    totals: dict[str, dict[str, int]] = defaultdict(lambda: {name: 0 for name in COUNT_NAMES})
    ids: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise EvidenceError(f"test_suites[{index}] must be an object")
        require_exact_keys(entry, TEST_KEYS, f"test_suites[{index}]")
        suite_id = require_identifier(entry["id"], f"test_suites[{index}].id")
        label = require_string(entry["label"], f"test_suites[{index}].label")
        kind = require_identifier(entry["kind"], f"test_suites[{index}].kind")
        if kind not in KINDS:
            raise EvidenceError(f"test suite {suite_id} has unsupported kind {kind}")
        expected = expected_counts(entry["expected"], f"test suite {suite_id} expected")
        reports = expand_reports(repo_root, entry["reports"], f"test suite {suite_id} reports")
        actual = {name: 0 for name in COUNT_NAMES}
        for relative, path in reports:
            require_fresh(path, earliest_mtime, f"test suite {suite_id} report")
            counts = junit_counts(path)
            for name in COUNT_NAMES:
                actual[name] += counts[name]
            all_report_paths.append(relative)
        if actual != expected:
            raise EvidenceError(
                f"test suite {suite_id} counts differ: expected {expected}, actual {actual}"
            )
        for name in COUNT_NAMES:
            totals[kind][name] += actual[name]
        ids.append(suite_id)
        output.append(
            {
                "id": suite_id,
                "label": label,
                "kind": kind,
                "counts": actual,
                "reports": [relative for relative, _ in reports],
            }
        )
    require_unique(ids, "test suite ids")
    require_unique(all_report_paths, "test report paths")
    return sorted(output, key=lambda item: item["id"]), dict(sorted(totals.items()))


def validate_artifacts(
    repo_root: Path,
    values: Any,
    earliest_mtime: float,
) -> list[dict[str, Any]]:
    entries = require_list(values, "artifacts")
    if not entries:
        raise EvidenceError("artifacts must not be empty")
    output: list[dict[str, Any]] = []
    ids: list[str] = []
    paths: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise EvidenceError(f"artifacts[{index}] must be an object")
        require_exact_keys(entry, ARTIFACT_KEYS, f"artifacts[{index}]")
        artifact_id = require_identifier(entry["id"], f"artifacts[{index}].id")
        relative, path = resolve_repo_path(repo_root, entry["path"], f"artifact {artifact_id}.path")
        require_fresh(path, earliest_mtime, f"artifact {artifact_id}")
        expected_sha = require_string(entry["sha256"], f"artifact {artifact_id}.sha256").lower()
        if HEX_SHA256.fullmatch(expected_sha) is None or file_sha256(path) != expected_sha:
            raise EvidenceError(f"artifact {artifact_id} SHA-256 differs")
        signer = require_string(
            entry["signer_sha256"], f"artifact {artifact_id}.signer_sha256"
        ).lower()
        if HEX_SHA256.fullmatch(signer) is None:
            raise EvidenceError(f"artifact {artifact_id} signer is not a SHA-256")
        version_code = entry["version_code"]
        if not isinstance(version_code, int) or isinstance(version_code, bool) or version_code <= 0:
            raise EvidenceError(f"artifact {artifact_id}.version_code must be positive")
        package = require_string(entry["package"], f"artifact {artifact_id}.package")
        if re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+", package) is None:
            raise EvidenceError(f"artifact {artifact_id}.package is invalid")
        metadata_relative, metadata_path = resolve_repo_path(
            repo_root, entry["metadata"], f"artifact {artifact_id}.metadata"
        )
        require_fresh(metadata_path, earliest_mtime, f"artifact {artifact_id} metadata")
        metadata = load_json_object(metadata_path, f"artifact {artifact_id} metadata")
        require_exact_keys(metadata, ARTIFACT_METADATA_KEYS, f"artifact {artifact_id} metadata")
        if metadata["schema"] != 1:
            raise EvidenceError(f"artifact {artifact_id} metadata must use schema 1")
        comparisons = {
            "artifact_sha256": expected_sha,
            "package": package,
            "version_code": version_code,
            "version_name": require_string(
                entry["version_name"], f"artifact {artifact_id}.version_name"
            ),
            "signer_sha256": signer,
        }
        for field, expected_value in comparisons.items():
            actual_value = metadata[field]
            if isinstance(expected_value, str) and isinstance(actual_value, str):
                actual_value = actual_value.lower() if field.endswith("sha256") else actual_value
            if actual_value != expected_value:
                raise EvidenceError(f"artifact {artifact_id} metadata contradicts {field}")
        package_verifier = require_string(
            metadata["package_verifier"], f"artifact {artifact_id} metadata.package_verifier"
        )
        signature_verifier = require_string(
            metadata["signature_verifier"], f"artifact {artifact_id} metadata.signature_verifier"
        )
        if package_verifier not in {"aapt2", "bundletool"}:
            raise EvidenceError(f"artifact {artifact_id} has unsupported package verifier")
        if signature_verifier not in {"apksigner", "jarsigner"}:
            raise EvidenceError(f"artifact {artifact_id} has unsupported signature verifier")
        ids.append(artifact_id)
        paths.append(relative)
        output.append(
            {
                "id": artifact_id,
                "path": relative,
                "package": package,
                "version_code": version_code,
                "version_name": comparisons["version_name"],
                "signer_sha256": signer,
                "sha256": expected_sha,
                "metadata_evidence_path": metadata_relative,
                "metadata_evidence_sha256": file_sha256(metadata_path),
                "package_verifier": package_verifier,
                "signature_verifier": signature_verifier,
            }
        )
    require_unique(ids, "artifact ids")
    require_unique(paths, "artifact paths")
    return sorted(output, key=lambda item: item["id"])


def reject_private_text(value: str, label: str) -> None:
    if FORBIDDEN_PRIVATE_TEXT.search(value):
        raise EvidenceError(f"{label} contains private machine, serial, or address data")


def validate_devices(values: Any) -> list[dict[str, Any]]:
    entries = require_list(values, "devices")
    if not entries:
        raise EvidenceError("devices must not be empty")
    output: list[dict[str, Any]] = []
    ids: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise EvidenceError(f"devices[{index}] must be an object")
        require_exact_keys(entry, DEVICE_KEYS, f"devices[{index}]")
        device_id = require_identifier(entry["id"], f"devices[{index}].id")
        model = require_string(entry["model"], f"device {device_id}.model")
        reject_private_text(model, f"device {device_id}.model")
        api = entry["api"]
        if not isinstance(api, int) or isinstance(api, bool) or api < 1:
            raise EvidenceError(f"device {device_id}.api must be positive")
        transport = require_identifier(entry["transport"], f"device {device_id}.transport")
        if transport not in TRANSPORTS:
            raise EvidenceError(f"device {device_id} has unsupported transport")
        scenarios: list[dict[str, str]] = []
        scenario_ids: list[str] = []
        for scenario_index, scenario in enumerate(require_list(entry["scenarios"], f"device {device_id}.scenarios")):
            if not isinstance(scenario, dict):
                raise EvidenceError(f"device {device_id} scenario {scenario_index} must be an object")
            require_exact_keys(scenario, SCENARIO_KEYS, f"device {device_id} scenario {scenario_index}")
            scenario_id = require_identifier(
                scenario["id"], f"device {device_id} scenario {scenario_index}.id"
            )
            result = require_identifier(
                scenario["result"], f"device {device_id} scenario {scenario_id}.result"
            )
            if result != "passed":
                raise EvidenceError(f"device {device_id} scenario {scenario_id} did not pass")
            scenario_ids.append(scenario_id)
            scenarios.append({"id": scenario_id, "result": result})
        if not scenarios:
            raise EvidenceError(f"device {device_id} must contain scenarios")
        require_unique(scenario_ids, f"device {device_id} scenario ids")
        ids.append(device_id)
        output.append(
            {
                "id": device_id,
                "model": model,
                "api": api,
                "transport": transport,
                "scenarios": sorted(scenarios, key=lambda item: item["id"]),
            }
        )
    require_unique(ids, "device ids")
    return sorted(output, key=lambda item: item["id"])


def validate_hosted(values: Any) -> list[dict[str, str]]:
    entries = require_list(values, "hosted_runs")
    output: list[dict[str, str]] = []
    ids: list[str] = []
    urls: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise EvidenceError(f"hosted_runs[{index}] must be an object")
        require_exact_keys(entry, HOSTED_KEYS, f"hosted_runs[{index}]")
        run_id = require_identifier(entry["id"], f"hosted_runs[{index}].id")
        url = require_string(entry["url"], f"hosted run {run_id}.url")
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
            raise EvidenceError(f"hosted run {run_id}.url must be a public HTTPS URL")
        conclusion = require_identifier(entry["conclusion"], f"hosted run {run_id}.conclusion")
        if conclusion not in PASSING_CONCLUSIONS:
            raise EvidenceError(f"hosted run {run_id} is not successful")
        ids.append(run_id)
        urls.append(url)
        output.append({"id": run_id, "url": url, "conclusion": conclusion})
    require_unique(ids, "hosted run ids")
    require_unique(urls, "hosted run URLs")
    return sorted(output, key=lambda item: item["id"])


def validate_pending(values: Any) -> list[dict[str, str]]:
    entries = require_list(values, "pending")
    output: list[dict[str, str]] = []
    ids: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise EvidenceError(f"pending[{index}] must be an object")
        require_exact_keys(entry, PENDING_KEYS, f"pending[{index}]")
        pending_id = require_identifier(entry["id"], f"pending[{index}].id")
        category = require_identifier(entry["category"], f"pending {pending_id}.category")
        if category not in PENDING_CATEGORIES:
            raise EvidenceError(f"pending {pending_id} has unsupported category")
        reason = require_string(entry["reason"], f"pending {pending_id}.reason")
        reject_private_text(reason, f"pending {pending_id}.reason")
        ids.append(pending_id)
        output.append({"id": pending_id, "category": category, "reason": reason})
    require_unique(ids, "pending ids")
    return sorted(output, key=lambda item: item["id"])


def create_manifest(repo_root: Path, evidence_dir: Path) -> dict[str, Any]:
    inputs_path = evidence_dir / "candidate-inputs.json"
    inputs = load_json_object(inputs_path, "candidate inputs")
    require_exact_keys(inputs, ROOT_KEYS, "candidate inputs")
    if inputs["schema"] != 1:
        raise EvidenceError("candidate inputs must use schema 1")
    fresh_after_path, earliest_mtime = read_epoch(repo_root, inputs["fresh_after"])
    require_fresh(inputs_path, earliest_mtime, "candidate inputs")
    source, contract_sha256 = validate_source(repo_root, inputs["source"], earliest_mtime)
    tests, totals = validate_tests(repo_root, inputs["test_suites"], earliest_mtime)
    artifacts = validate_artifacts(repo_root, inputs["artifacts"], earliest_mtime)
    devices = validate_devices(inputs["devices"])
    hosted = validate_hosted(inputs["hosted_runs"])
    pending = validate_pending(inputs["pending"])
    if not hosted and not any(item["category"] == "hosted_ci" for item in pending):
        raise EvidenceError("missing hosted runs require an explicit hosted_ci pending entry")
    return {
        "schema": 1,
        "source": source,
        "test_contract_sha256": contract_sha256,
        "fresh_after": fresh_after_path,
        "tests": tests,
        "test_totals_by_kind": totals,
        "artifacts": artifacts,
        "devices": devices,
        "hosted_runs": hosted,
        "pending": pending,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    arguments = parser.parse_args(argv)
    repo_root = arguments.repo_root.resolve()
    evidence_dir = arguments.evidence_dir
    if not evidence_dir.is_absolute():
        evidence_dir = repo_root / evidence_dir
    output = arguments.output
    if not output.is_absolute():
        output = repo_root / output
    try:
        manifest = create_manifest(repo_root, evidence_dir.resolve())
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(
            json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=True) + "\n",
            encoding="utf-8",
        )
    except (EvidenceError, OSError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1
    print(f"RELEASE_EVIDENCE_MANIFEST {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
