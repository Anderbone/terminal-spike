#!/usr/bin/env python3
"""Verify exact Android instrumentation membership and API-specific skip identity."""

from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import re
import sys
from typing import Any


FINAL_STATUS_CODES = {0: "passed", -3: "skipped", -4: "skipped"}
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_$][A-Za-z0-9_.$]*#[^#\r\n]+$")
STATUS_PREFIX = "INSTRUMENTATION_STATUS: "
STATUS_CODE_PREFIX = "INSTRUMENTATION_STATUS_CODE: "


def fail(message: str, exit_code: int = 1) -> None:
    print(f"ERROR {message}", file=sys.stderr)
    raise SystemExit(exit_code)


def require_unique_strings(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        fail(f"{label} must be a list of strings")
    duplicates = sorted(item for item, count in Counter(value).items() if count > 1)
    if duplicates:
        fail(f"{label} contains duplicate values: {format_ids(duplicates)}")
    return value


def require_identifier_list(value: Any, label: str) -> list[str]:
    values = require_unique_strings(value, label)
    invalid = sorted(item for item in values if IDENTIFIER_PATTERN.fullmatch(item) is None)
    if invalid:
        fail(f"{label} contains invalid identifiers: {format_ids(invalid)}")
    return values


def load_contract(path: Path) -> dict[str, Any]:
    if not path.is_file():
        fail("Android test contract is missing")
    try:
        contract = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"Android test contract is unreadable: {error}")
    if not isinstance(contract, dict) or contract.get("schema") != 1:
        fail("Android test contract must use schema 1")
    supported_apis = contract.get("supported_apis")
    if (
        not isinstance(supported_apis, list)
        or any(not isinstance(api, int) for api in supported_apis)
        or len(supported_apis) != len(set(supported_apis))
    ):
        fail("supported_apis must be a unique list of integers")
    suites = contract.get("suites")
    if not isinstance(suites, dict) or not suites:
        fail("suites must be a non-empty object")
    all_required: dict[str, set[str]] = {}
    for suite_name, suite in suites.items():
        if not isinstance(suite_name, str) or not isinstance(suite, dict):
            fail("every suite must be a named object")
        required = require_identifier_list(suite.get("required_tests"), f"suite {suite_name}")
        if required != sorted(required):
            fail(f"suite {suite_name} required_tests must be sorted")
        if not required:
            fail(f"suite {suite_name} must require at least one test")
        all_required[suite_name] = set(required)
    rules = contract.get("allowed_skip_rules")
    if not isinstance(rules, list):
        fail("allowed_skip_rules must be a list")
    rule_ids: list[str] = []
    for index, rule in enumerate(rules):
        if not isinstance(rule, dict):
            fail(f"skip rule {index} must be an object")
        rule_id = rule.get("id")
        reason = rule.get("reason")
        rule_suites = require_unique_strings(rule.get("suites"), f"skip rule {index} suites")
        tests = require_identifier_list(rule.get("tests"), f"skip rule {index} tests")
        if not isinstance(rule_id, str) or not rule_id or not isinstance(reason, str) or not reason:
            fail(f"skip rule {index} requires a non-empty id and reason")
        rule_ids.append(rule_id)
        unknown_suites = sorted(set(rule_suites) - set(all_required))
        if unknown_suites:
            fail(f"skip rule {rule_id} names unknown suites: {', '.join(unknown_suites)}")
        for suite_name in rule_suites:
            unknown_tests = sorted(set(tests) - all_required[suite_name])
            if unknown_tests:
                fail(
                    f"skip rule {rule_id} names tests outside suite {suite_name}: "
                    f"{format_ids(unknown_tests)}"
                )
        for bound in ("min_api", "max_api"):
            if bound in rule and not isinstance(rule[bound], int):
                fail(f"skip rule {rule_id} {bound} must be an integer")
        if rule.get("min_api", min(supported_apis)) > rule.get("max_api", max(supported_apis)):
            fail(f"skip rule {rule_id} has an empty API range")
    duplicates = sorted(item for item, count in Counter(rule_ids).items() if count > 1)
    if duplicates:
        fail(f"duplicate skip rule ids: {', '.join(duplicates)}")
    return contract


def parse_results(path: Path) -> list[tuple[str, str]]:
    if not path.is_file():
        fail("instrumentation output is missing")
    current_class: str | None = None
    current_test: str | None = None
    results: list[tuple[str, str]] = []
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if raw_line.startswith(f"{STATUS_PREFIX}class="):
            current_class = raw_line.split("=", 1)[1]
        elif raw_line.startswith(f"{STATUS_PREFIX}test="):
            current_test = raw_line.split("=", 1)[1]
        elif raw_line.startswith(STATUS_CODE_PREFIX):
            raw_code = raw_line[len(STATUS_CODE_PREFIX) :]
            try:
                code = int(raw_code)
            except ValueError:
                fail("instrumentation contains an invalid status code")
            if code == 1:
                continue
            if code not in FINAL_STATUS_CODES:
                fail(f"instrumentation contains unsupported final status code {code}")
            if current_class is None or current_test is None:
                fail("instrumentation completed a test without class and method identity")
            identifier = f"{current_class}#{current_test}"
            if IDENTIFIER_PATTERN.fullmatch(identifier) is None:
                fail("instrumentation contains an invalid test identity")
            results.append((identifier, FINAL_STATUS_CODES[code]))
    if not results:
        fail("instrumentation contains no completed test identities")
    duplicates = sorted(item for item, count in Counter(identifier for identifier, _ in results).items() if count > 1)
    if duplicates:
        fail(f"instrumentation completed duplicate tests: {format_ids(duplicates)}")
    return results


def expected_skips(contract: dict[str, Any], suite: str, api: int) -> set[str]:
    skips: set[str] = set()
    for rule in contract["allowed_skip_rules"]:
        if suite not in rule["suites"]:
            continue
        if api < rule.get("min_api", min(contract["supported_apis"])):
            continue
        if api > rule.get("max_api", max(contract["supported_apis"])):
            continue
        overlap = skips.intersection(rule["tests"])
        if overlap:
            fail(f"skip rules overlap for suite {suite} API {api}: {format_ids(sorted(overlap))}")
        skips.update(rule["tests"])
    return skips


def format_ids(identifiers: list[str] | set[str], limit: int = 10) -> str:
    ordered = sorted(identifiers)
    shown = ", ".join(ordered[:limit])
    if len(ordered) > limit:
        shown += f", ... (+{len(ordered) - limit} more)"
    return shown


def main() -> None:
    if len(sys.argv) != 5:
        fail("expected contract, instrumentation output, suite, and API", exit_code=2)
    contract_path = Path(sys.argv[1])
    result_path = Path(sys.argv[2])
    suite = sys.argv[3]
    try:
        api = int(sys.argv[4])
    except ValueError:
        fail("API must be an integer", exit_code=2)

    contract = load_contract(contract_path)
    if api not in contract["supported_apis"]:
        fail(f"API {api} is not covered by the Android test contract", exit_code=2)
    if suite not in contract["suites"]:
        fail(f"suite {suite} is not covered by the Android test contract", exit_code=2)

    results = parse_results(result_path)
    actual = {identifier for identifier, _ in results}
    required = set(contract["suites"][suite]["required_tests"])
    missing = required - actual
    unexpected = actual - required
    if missing or unexpected:
        details = []
        if missing:
            details.append(f"missing: {format_ids(missing)}")
        if unexpected:
            details.append(f"unexpected: {format_ids(unexpected)}")
        fail(f"suite {suite} API {api} membership differs ({'; '.join(details)})")

    actual_skips = {identifier for identifier, status in results if status == "skipped"}
    allowed_skips = expected_skips(contract, suite, api)
    missing_skips = allowed_skips - actual_skips
    unexpected_skips = actual_skips - allowed_skips
    if missing_skips or unexpected_skips:
        details = []
        if missing_skips:
            details.append(f"expected skips that ran: {format_ids(missing_skips)}")
        if unexpected_skips:
            details.append(f"unexpected skips: {format_ids(unexpected_skips)}")
        fail(f"suite {suite} API {api} skip identity differs ({'; '.join(details)})")

    print(
        "ANDROID_TEST_CONTRACT_RESULT "
        f"schema={contract['schema']} suite={suite} api={api} tests={len(results)} "
        f"passed={len(results) - len(actual_skips)} skipped={len(actual_skips)} membership=exact"
    )


if __name__ == "__main__":
    main()
