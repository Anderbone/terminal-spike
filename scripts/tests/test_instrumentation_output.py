from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parents[1]
PARSER = SCRIPTS / "instrumentation-output-to-junit.py"
REDACTOR = SCRIPTS / "redact-android-test-log.py"


class InstrumentationOutputTest(unittest.TestCase):
    @staticmethod
    def completed_case(name: str, code: int = 0) -> str:
        return (
            "INSTRUMENTATION_STATUS: class=example.Test\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n"
        )

    def run_parser(
        self, text: str, *arguments: str
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "result.txt"
            output = Path(directory) / "result.xml"
            source.write_text(text, encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(PARSER), str(source), str(output), *arguments],
                check=False,
                capture_output=True,
                text=True,
            )
            xml = output.read_text(encoding="utf-8") if output.exists() else ""
            return result, xml

    def test_converts_one_nonzero_success_and_counts_skips(self) -> None:
        result, xml = self.run_parser(
            "INSTRUMENTATION_STATUS_CODE: -3\n"
            "INSTRUMENTATION_STATUS_CODE: -4\n"
            "OK (7 tests)\n"
        )
        self.assertEqual(0, result.returncode)
        self.assertIn('tests="7"', xml)
        self.assertIn('skipped="2"', xml)

    def test_aggregates_explicit_shards_but_rejects_them_in_single_run_mode(self) -> None:
        text = "OK (7 tests)\nOK (8 tests)\n"
        rejected, _ = self.run_parser(text)
        accepted, xml = self.run_parser(text, "--sharded")
        self.assertNotEqual(0, rejected.returncode)
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertIn('tests="15"', xml)

    def test_rejects_zero_missing_ambiguous_and_failed_results(self) -> None:
        fixtures = (
            "OK (0 tests)\n",
            "nothing useful\n",
            "OK (1 test)\nOK (1 test)\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                result, xml = self.run_parser(fixture)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('errors="1"', xml)

    def test_failed_run_retains_test_identity_and_stack_but_exits_failed(self):
        result, xml = self.run_parser(
            "INSTRUMENTATION_STATUS: class=example.Test\n"
            "INSTRUMENTATION_STATUS: test=broken\n"
            "INSTRUMENTATION_STATUS: stack=AssertionError: mismatch\n"
            "  at example.Test.broken(Test.kt:42)\n"
            "INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\n")
        self.assertNotEqual(0, result.returncode)
        suite = ET.fromstring(xml)
        self.assertEqual("1", suite.get("failures"))
        self.assertEqual("broken", suite.find("testcase").get("name"))
        self.assertIn("Test.kt:42", suite.find("testcase/failure").text)

    def test_completed_failed_summary_does_not_invent_an_extra_crash(self):
        result, xml = self.run_parser(
            "INSTRUMENTATION_STATUS: class=example.Test\n"
            "INSTRUMENTATION_STATUS: test=broken\n"
            "INSTRUMENTATION_STATUS: stack=AssertionError: mismatch\n"
            "INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\n"
            "Tests found: 1, Tests run: 1,  Failures: 1\n")
        self.assertNotEqual(0, result.returncode)
        suite = ET.fromstring(xml)
        self.assertEqual("1", suite.get("tests"))
        self.assertEqual("1", suite.get("failures"))
        self.assertEqual("0", suite.get("errors"))

    def test_incomplete_run_retains_completed_cases_and_a_completion_error(self):
        result, xml = self.run_parser(
            "INSTRUMENTATION_STATUS: class=example.Test\n"
            "INSTRUMENTATION_STATUS: test=finishedBeforeTimeout\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn('name="finishedBeforeTimeout"', xml)
        self.assertIn('name="run-completion"', xml)
        self.assertIn('errors="1"', xml)

    def test_standard_android_junit_failure_summary_is_complete_without_found_count(self):
        result, xml = self.run_parser(
            self.completed_case("broken", -2) + "FAILURES!!!\nTests run: 1,  Failures: 1\n"
        )
        self.assertNotEqual(0, result.returncode)
        suite = ET.fromstring(xml)
        self.assertEqual(("1", "1", "0"), tuple(suite.get(k) for k in ("tests", "failures", "errors")))
        self.assertIsNone(suite.find("testcase[@name='run-completion']"))

    def test_real_eight_case_failure_shape_retains_four_failures_without_a_crash(self):
        text = "".join(self.completed_case(f"case{i}", -2 if i < 4 else 0) for i in range(8))
        result, xml = self.run_parser(text + "FAILURES!!!\nTests run: 8,  Failures: 4\n")
        self.assertNotEqual(0, result.returncode)
        suite = ET.fromstring(xml)
        self.assertEqual(("8", "4", "0"), tuple(suite.get(k) for k in ("tests", "failures", "errors")))
        self.assertEqual(8, len(suite.findall("testcase")))

    def test_failed_summary_must_match_completed_records_and_failure_counts(self):
        cases = self.completed_case("broken", -2) + self.completed_case("passes")
        for summary in (
            "Tests found: 3, Tests run: 3,  Failures: 1\n",
            "Tests found: 2, Tests run: 2,  Failures: 2\n",
        ):
            with self.subTest(summary=summary):
                result, xml = self.run_parser(cases + "FAILURES!!!\n" + summary)
                self.assertNotEqual(0, result.returncode)
                self.assertIsNotNone(ET.fromstring(xml).find("testcase[@name='run-completion']/error"))

    def test_complete_mixed_success_and_failure_shards_do_not_invent_a_crash(self):
        text = (self.completed_case("passes") + "OK (1 test)\n" +
                self.completed_case("broken", -2) + "FAILURES!!!\nTests run: 1,  Failures: 1\n")
        rejected, _ = self.run_parser(text)
        result, xml = self.run_parser(text, "--sharded")
        self.assertNotEqual(0, rejected.returncode)
        self.assertNotEqual(0, result.returncode)
        suite = ET.fromstring(xml)
        self.assertEqual(("2", "1", "0"), tuple(suite.get(k) for k in ("tests", "failures", "errors")))

    def test_shard_record_count_mismatches_cannot_cancel_each_other_out(self):
        text = (self.completed_case("first") + "OK (2 tests)\n" +
                "".join(self.completed_case(f"later{i}") for i in range(3)) + "OK (2 tests)\n")
        result, xml = self.run_parser(text, "--sharded")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNotNone(ET.fromstring(xml).find("testcase[@name='run-completion']/error"))

    def test_completed_failed_run_followed_by_real_crash_keeps_completion_error(self):
        result, xml = self.run_parser(
            self.completed_case("broken", -2) +
            "FAILURES!!!\nTests found: 1, Tests run: 1,  Failures: 1\nProcess crashed\n"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("1", ET.fromstring(xml).get("errors"))

    def test_duplicate_completed_identity_is_not_a_successful_run(self):
        result, xml = self.run_parser(self.completed_case("same") * 2 + "OK (2 tests)\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNotNone(ET.fromstring(xml).find("testcase[@name='run-completion']/error"))

    def test_malformed_status_retains_xml_diagnostics(self):
        result, xml = self.run_parser("INSTRUMENTATION_STATUS_CODE: not-a-number\nOK (1 test)\n")
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(xml, "Malformed statuses must still produce diagnostic XML")
        self.assertIsNotNone(ET.fromstring(xml).find("testcase[@name='run-completion']/error"))

    def test_summary_only_failure_reports_keep_their_declared_counts(self):
        result, xml = self.run_parser("FAILURES!!!\nTests run: 2,  Failures: 1\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(("2", "1", "0"), tuple(ET.fromstring(xml).get(k) for k in ("tests", "failures", "errors")))

    def test_started_case_without_a_final_record_cannot_pass_on_summary_alone(self):
        result, xml = self.run_parser(
            "INSTRUMENTATION_STATUS: class=example.Test\n"
            "INSTRUMENTATION_STATUS: test=neverCompleted\n"
            "INSTRUMENTATION_STATUS_CODE: 1\nOK (1 test)\n"
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIsNotNone(ET.fromstring(xml).find("testcase[@name='run-completion']/error"))

    def test_unknown_negative_status_is_not_counted_as_an_assumption_skip(self):
        result, xml = self.run_parser(self.completed_case("unknown", -30) + "OK (1 test)\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("0", ET.fromstring(xml).get("skipped"))

    def test_summary_looking_text_inside_pending_case_does_not_split_the_run(self):
        result, xml = self.run_parser(
            "INSTRUMENTATION_STATUS: class=example.Test\n"
            "INSTRUMENTATION_STATUS: test=broken\n"
            "INSTRUMENTATION_STATUS: stack=AssertionError: captured output\n"
            "OK (7 tests)\n"
            "INSTRUMENTATION_STATUS: stream=\n"
            "Tests run: 100,  Failures: 99\n"
            "INSTRUMENTATION_STATUS_CODE: -2\n"
            "FAILURES!!!\nTests run: 1,  Failures: 1\n"
        )
        self.assertNotEqual(0, result.returncode)
        suite = ET.fromstring(xml)
        self.assertEqual(("1", "1", "0"), tuple(suite.get(k) for k in ("tests", "failures", "errors")))
        self.assertIn("OK (7 tests)", suite.find("testcase/failure").text)

    def test_shards_with_partial_case_identity_do_not_claim_complete_success(self):
        result, xml = self.run_parser(
            "OK (2 tests)\n" + self.completed_case("oneIdentifiedCase") + "OK (1 test)\n",
            "--sharded",
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIsNotNone(ET.fromstring(xml).find("testcase[@name='run-completion']/error"))

    def test_terminal_controls_are_visible_escapes_in_valid_xml(self):
        result, xml = self.run_parser(
            "captured terminal output: \x0b\ufffe\n"
            "INSTRUMENTATION_STATUS: class=example.\x1bTest\n"
            "INSTRUMENTATION_STATUS: test=control\x00case\n"
            "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("\x00", xml)
        suite = ET.fromstring(xml)
        case = suite.find("testcase")
        self.assertEqual("example.\\u001bTest", case.get("classname"))
        self.assertEqual("control\\u0000case", case.get("name"))
        self.assertIn("\\u000b\\ufffe", suite.find("system-out").text)

    def test_crash_or_failure_marker_cannot_become_success(self):
        for fixture in ("FAILURES!!!\nOK (1 test)\n", "INSTRUMENTATION_FAILED: crash\n"):
            result, xml = self.run_parser(fixture)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('errors="1"', xml)

    def test_redacts_credentials_addresses_and_private_key_blocks(self) -> None:
        source = (
            "password=visible 192.168.1.7 person@example.test\n"
            "privateKeyBase64 still-visible\n"
            "-----BEGIN OPENSSH PRIVATE KEY-----\nsecret\n"
            "-----END OPENSSH PRIVATE KEY-----\n"
        )
        result = subprocess.run(
            [sys.executable, str(REDACTOR)],
            input=source,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertNotIn("visible", result.stdout)
        self.assertNotIn("still-visible", result.stdout)
        self.assertNotIn("192.168.1.7", result.stdout)
        self.assertNotIn("person@example.test", result.stdout)
        self.assertNotIn("OPENSSH PRIVATE KEY", result.stdout)

    def test_redacts_emulator_setup_wizard_google_api_keys(self) -> None:
        for suffix in ("a", "_", "-"):
            with self.subTest(suffix=suffix):
                key = "AIza" + "x" * 34 + suffix
                source = (
                    f"SetupWizard: Flag(csat_notification_API_KEY, '{key}', 4, 0)\n"
                    "INSTRUMENTATION_STATUS_CODE: 0\n"
                )
                result = subprocess.run(
                    [sys.executable, str(REDACTOR)],
                    input=source,
                    check=True,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(
                    source.replace(key, "<redacted-google-api-key>"), result.stdout
                )

    def test_redaction_never_consumes_the_next_instrumentation_record(self) -> None:
        source = (
            "INSTRUMENTATION_STATUS: test=savesPassword\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: test=wipesSecret\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
        )
        result = subprocess.run(
            [sys.executable, str(REDACTOR)],
            input=source,
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual(source, result.stdout)


if __name__ == "__main__":
    unittest.main()
