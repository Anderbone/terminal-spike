from pathlib import Path
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "sanitize-real-codex-instrumentation.py"


class SanitizeRealCodexInstrumentationTest(unittest.TestCase):
    def test_preserves_protocol_but_removes_stack_terminal_and_arguments(self) -> None:
        raw = """INSTRUMENTATION_STATUS: class=com.example.RealTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
INSTRUMENTATION_STATUS: test=realCase
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: stack=visibleHead=[SECRET_ROW] host=user@private-host
privateKeyBase64=PRIVATE_KEY_BYTES
INSTRUMENTATION_STATUS: test=realCase
INSTRUMENTATION_STATUS_CODE: -2
Time: 12.345
FAILURES!!!
Tests found: 1, Tests run: 1,  Failures: 1
INSTRUMENTATION_CODE: -1
"""
        result = subprocess.run(
            [str(SCRIPT)], input=raw, text=True, capture_output=True, check=False
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("INSTRUMENTATION_STATUS: class=com.example.RealTest", result.stdout)
        self.assertIn("INSTRUMENTATION_STATUS: test=realCase", result.stdout)
        self.assertIn("INSTRUMENTATION_STATUS_CODE: -2", result.stdout)
        self.assertIn("FAILURES!!!", result.stdout)
        for forbidden in ("SECRET_ROW", "private-host", "PRIVATE_KEY_BYTES", "stack=", "stream="):
            self.assertNotIn(forbidden, result.stdout)

    def test_green_result_remains_parseable(self) -> None:
        raw = """INSTRUMENTATION_STATUS: class=com.example.RealTest
INSTRUMENTATION_STATUS: test=realCase
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.RealTest
INSTRUMENTATION_STATUS: test=realCase
INSTRUMENTATION_STATUS_CODE: 0
Time: 1.000
OK (1 test)
INSTRUMENTATION_CODE: -1
"""
        sanitized = subprocess.run(
            [str(SCRIPT)], input=raw, text=True, capture_output=True, check=True
        ).stdout
        self.assertIn("OK (1 test)", sanitized)
        self.assertEqual(2, sanitized.count("INSTRUMENTATION_STATUS: test=realCase"))


if __name__ == "__main__":
    unittest.main()
