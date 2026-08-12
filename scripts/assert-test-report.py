"""Validate one JUnit XML report without disclosing report contents."""

from pathlib import Path
import re
import sys
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


def suite_count(suite: ET.Element, name: str) -> int:
    return parse_count(
        suite.attrib.get(name),
        name,
        required=name in REQUIRED_COUNT_NAMES,
    )


def report_counts(root: ET.Element) -> dict[str, int]:
    if root.tag == "testsuite":
        return {name: suite_count(root, name) for name in COUNT_NAMES}
    if root.tag != "testsuites":
        raise ReportError("unexpected XML root")

    child_suites = root.findall("testsuite")
    counts: dict[str, int] = {}
    for name in COUNT_NAMES:
        raw = root.attrib.get(name)
        if raw is not None:
            counts[name] = parse_count(raw, name, required=True)
        else:
            counts[name] = sum(suite_count(suite, name) for suite in child_suites)
    return counts


def fail(message: str, exit_code: int = 1) -> int:
    print(f"ERROR {message}", file=sys.stderr)
    return exit_code


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        return fail("expected exactly one XML report", exit_code=2)

    report_path = Path(argv[0])
    if not report_path.is_file():
        return fail("report is missing")

    try:
        root = ET.parse(report_path).getroot()
    except (ET.ParseError, OSError):
        return fail("report is malformed")

    try:
        counts = report_counts(root)
    except ReportError as error:
        return fail(str(error))

    print(
        "TEST_REPORT"
        f" tests={counts['tests']}"
        f" failures={counts['failures']}"
        f" errors={counts['errors']}"
        f" skipped={counts['skipped']}"
    )
    if counts["tests"] <= 0:
        return fail("report contains no tests")
    if counts["failures"] != 0 or counts["errors"] != 0:
        return fail("report contains failures or errors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
