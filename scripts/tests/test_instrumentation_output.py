from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]
PARSER = SCRIPTS / "instrumentation-output-to-junit.py"
REDACTOR = SCRIPTS / "redact-android-test-log.py"


class InstrumentationOutputTest(unittest.TestCase):
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
            "FAILURES!!!\nOK (1 test)\n",
            "INSTRUMENTATION_FAILED: crash\n",
        )
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                result, xml = self.run_parser(fixture)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", xml)

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
