from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "assert-test-report.py"


class AssertTestReportTest(unittest.TestCase):
    def run_report(self, xml: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            report.write_text(xml, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SCRIPT), str(report)],
                check=False,
                capture_output=True,
                text=True,
            )

    def test_pass_report(self) -> None:
        result = self.run_report(
            '<testsuite tests="3" failures="0" errors="0" skipped="1" />'
        )

        self.assertEqual(0, result.returncode)
        self.assertEqual(
            "TEST_REPORT tests=3 failures=0 errors=0 skipped=1\n",
            result.stdout,
        )
        self.assertEqual("", result.stderr)

    def test_failure_report(self) -> None:
        result = self.run_report(
            '<testsuite tests="2" failures="1" errors="0" skipped="0" />'
        )

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(
            "TEST_REPORT tests=2 failures=1 errors=0 skipped=0\n",
            result.stdout,
        )
        self.assertEqual("ERROR report contains failures or errors\n", result.stderr)

    def test_error_report(self) -> None:
        result = self.run_report(
            '<testsuite tests="2" failures="0" errors="1" skipped="0" />'
        )

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(
            "TEST_REPORT tests=2 failures=0 errors=1 skipped=0\n",
            result.stdout,
        )
        self.assertEqual("ERROR report contains failures or errors\n", result.stderr)

    def test_empty_report(self) -> None:
        result = self.run_report(
            '<testsuite tests="0" failures="0" errors="0" skipped="0" />'
        )

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(
            "TEST_REPORT tests=0 failures=0 errors=0 skipped=0\n",
            result.stdout,
        )
        self.assertEqual("ERROR report contains no tests\n", result.stderr)

    def test_malformed_report(self) -> None:
        result = self.run_report("<testsuite>")

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("ERROR report is malformed\n", result.stderr)

    def test_aggregate_report_sums_child_suites_when_totals_are_omitted(self) -> None:
        result = self.run_report(
            """\
<testsuites>
  <testsuite tests="2" failures="0" errors="0" skipped="1" />
  <testsuite tests="3" failures="0" errors="0" skipped="0" />
</testsuites>
"""
        )

        self.assertEqual(0, result.returncode)
        self.assertEqual(
            "TEST_REPORT tests=5 failures=0 errors=0 skipped=1\n",
            result.stdout,
        )
        self.assertEqual("", result.stderr)

    def test_missing_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing_report = Path(directory) / "missing.xml"
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(missing_report)],
                check=False,
                capture_output=True,
                text=True,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("ERROR report is missing\n", result.stderr)

    def test_parent_totals_cannot_hide_a_child_failure(self) -> None:
        result = self.run_report(
            '<testsuites tests="1" failures="0" errors="0">'
            '<testsuite tests="1" failures="1" errors="0" />'
            '</testsuites>'
        )
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("fixture-secret", result.stderr)

    def test_case_failures_errors_and_skips_must_match_declared_counts(self) -> None:
        for outcome in ("failure", "error", "skipped"):
            with self.subTest(outcome=outcome):
                result = self.run_report(
                    '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                    '<testcase classname="fixture.Test" name="private-fixture">'
                    f'<{outcome}>fixture-secret</{outcome}></testcase></testsuite>'
                )
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("fixture-secret", result.stdout + result.stderr)
                self.assertNotIn("private-fixture", result.stdout + result.stderr)

    def test_missing_case_records_cannot_be_hidden_by_positive_totals(self) -> None:
        result = self.run_report(
            '<testsuite tests="2" failures="0" errors="0">'
            '<testcase classname="fixture.Test" name="only-one" /></testsuite>'
        )
        self.assertNotEqual(0, result.returncode)

    def test_explicit_parent_totals_cannot_bypass_invalid_child_counts(self) -> None:
        for count in ("-1", "bad"):
            with self.subTest(count=count):
                result = self.run_report(
                    '<testsuites tests="1" failures="0" errors="0">'
                    f'<testsuite tests="{count}" failures="0" errors="0" />'
                    '</testsuites>'
                )
                self.assertNotEqual(0, result.returncode)

    def test_nested_aggregate_counts_are_validated_recursively(self) -> None:
        result = self.run_report(
            '<testsuites><testsuites><testsuite tests="1" failures="1" errors="0">'
            '<testcase classname="fixture.Test" name="failed"><failure /></testcase>'
            '</testsuite></testsuites></testsuites>'
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("tests=1 failures=1 errors=0 skipped=0", result.stdout)

    def test_consistent_detailed_and_summary_only_reports_remain_supported(self) -> None:
        reports = (
            '<testsuites tests="2" failures="0" errors="0" skipped="1">'
            '<testsuite tests="1" failures="0" errors="0"><testcase name="passes" /></testsuite>'
            '<testsuite tests="1" failures="0" errors="0" skipped="1">'
            '<testcase name="skips"><skipped /></testcase></testsuite></testsuites>',
            '<testsuites tests="2" failures="0" errors="0" skipped="1" />',
        )
        for xml in reports:
            with self.subTest(xml=xml):
                result = self.run_report(xml)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("tests=2 failures=0 errors=0 skipped=1", result.stdout)

    def test_missing_optional_skip_total_is_derived_from_available_records(self) -> None:
        result = self.run_report(
            '<testsuite tests="1" failures="0" errors="0">'
            '<testcase name="skips"><skipped /></testcase></testsuite>'
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("tests=1 failures=0 errors=0 skipped=1", result.stdout)

    def test_nested_suites_and_direct_cases_share_one_aggregate(self) -> None:
        result = self.run_report(
            '<testsuite tests="2" failures="0" errors="0">'
            '<testcase name="outer" />'
            '<testsuite tests="1" failures="0" errors="0"><testcase name="inner" /></testsuite>'
            '</testsuite>'
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("tests=2 failures=0 errors=0 skipped=0", result.stdout)

    def test_aggregate_counts_must_match_even_when_every_case_passed(self) -> None:
        result = self.run_report(
            '<testsuites tests="2" failures="0" errors="0">'
            '<testsuite tests="1" failures="0" errors="0" /></testsuites>'
        )
        self.assertNotEqual(0, result.returncode)

    def test_summary_outcomes_cannot_exceed_total_tests(self) -> None:
        for outcome in ("failures", "errors", "skipped"):
            with self.subTest(outcome=outcome):
                counts = dict(tests=1, failures=0, errors=0, skipped=0)
                counts[outcome] = 2
                attributes = " ".join(f'{name}="{value}"' for name, value in counts.items())
                result = self.run_report(f"<testsuite {attributes} />")
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("TEST_REPORT", result.stdout)
                self.assertIn(f"{outcome} count exceeds tests", result.stderr)

    def test_excessive_nesting_fails_without_a_traceback(self) -> None:
        result = self.run_report(
            "<testsuites>" * 2_000 +
            '<testsuite tests="1" failures="0" errors="0" />' + "</testsuites>" * 2_000
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("ERROR report nesting is too deep\n", result.stderr)

    def test_rejects_unexpected_root(self) -> None:
        result = self.run_report('<report tests="1" failures="0" errors="0" />')

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("ERROR unexpected XML root\n", result.stderr)

    def test_rejects_non_integer_count(self) -> None:
        result = self.run_report(
            '<testsuite tests="many" failures="0" errors="0" skipped="0" />'
        )

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("ERROR invalid tests count\n", result.stderr)

    def test_requires_exactly_one_path(self) -> None:
        for arguments in ([], ["one.xml", "two.xml"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), *arguments],
                    check=False,
                    capture_output=True,
                    text=True,
                )

                self.assertEqual(2, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertEqual(
                    "ERROR expected exactly one XML report\n",
                    result.stderr,
                )


if __name__ == "__main__":
    unittest.main()
