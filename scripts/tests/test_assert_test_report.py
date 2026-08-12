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
