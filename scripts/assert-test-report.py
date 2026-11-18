"""Validate one JUnit XML report without disclosing report contents."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

from junit_report import ReportError, report_counts


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
    except RecursionError:
        return fail("report nesting is too deep")

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
