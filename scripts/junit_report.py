"""Pure JUnit report counting shared by CLI and release-evidence validation."""

import re
import xml.etree.ElementTree as ET


COUNT_NAMES = ("tests", "failures", "errors", "skipped")
REQUIRED_COUNT_NAMES = ("tests", "failures", "errors")
NON_NEGATIVE_INTEGER = re.compile(r"[0-9]+")


class ReportError(ValueError):
    """Raised when a report does not satisfy the accepted XML contract."""


def parse_count(raw: str | None, name: str, *, required: bool) -> int:
    if raw is None:
        if required:
            raise ReportError(f"missing {name} count")
        return 0
    if NON_NEGATIVE_INTEGER.fullmatch(raw) is None:
        raise ReportError(f"invalid {name} count")
    return int(raw)


def report_counts(root: ET.Element) -> dict[str, int]:
    if root.tag not in ("testsuite", "testsuites"):
        raise ReportError("unexpected XML root")

    child_suites = [child for child in root if child.tag in ("testsuite", "testsuites")]
    cases = root.findall("testcase")
    observed = {
        "tests": len(cases),
        "failures": sum(case.find("failure") is not None for case in cases),
        "errors": sum(case.find("error") is not None for case in cases),
        "skipped": sum(case.find("skipped") is not None for case in cases),
    }
    for child in child_suites:
        for name, value in report_counts(child).items():
            observed[name] += value

    has_details = bool(child_suites or cases)
    counts: dict[str, int] = {}
    for name in COUNT_NAMES:
        raw = root.attrib.get(name)
        required = root.tag == "testsuite" and name in REQUIRED_COUNT_NAMES
        if raw is not None or required:
            counts[name] = parse_count(raw, name, required=required)
        else:
            counts[name] = observed[name]
        if has_details and counts[name] != observed[name]:
            raise ReportError(f"{name} count does not match report contents")
    for name in ("failures", "errors", "skipped"):
        if counts[name] > counts["tests"]:
            raise ReportError(f"{name} count exceeds tests")
    return counts
