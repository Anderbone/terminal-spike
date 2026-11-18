"""Target and named-result guards for the real PTY runner; never executes ADB or Gradle."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("pty_runner", ROOT / "scripts/run-local-arch-pty-device-tests.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
S23 = "old-usb device usb:1-2 product:test model:SM_S911B device:test"
S10E = "192.0.2.10:1234 device product:test model:SM_G9708 device:test"
FOLD = "fold-wifi device product:test model:SM_F976B device:test"


class PtyRunnerTargetTest(unittest.TestCase):
    def test_usb_is_preferred_for_implicit_selection(self):
        self.assertEqual(("old-usb", "SM-S911B"), RUNNER.select_target("\n".join([S10E, FOLD, S23]), None, True))

    def test_explicit_s10e_wireless_primitive_target_is_supported(self):
        self.assertEqual(("192.0.2.10:1234", "SM-G9708"), RUNNER.select_target(S10E, "192.0.2.10:1234", True))

    def test_s10e_is_not_implicitly_added_to_arch_acceptance(self):
        with self.assertRaises(RuntimeError):
            RUNNER.select_target(S10E, None, False)

    def test_foldable_offline_unauthorized_and_wireless_s23_are_rejected(self):
        for listing in [FOLD, S10E.replace(" device ", " offline "), S10E.replace(" device ", " unauthorized "), S23.replace("usb:1-2 ", "")]:
            with self.subTest(listing=listing), self.assertRaises(RuntimeError):
                RUNNER.select_target(listing, None, True)

    def test_ambiguous_targets_require_an_exact_serial(self):
        listing = S10E + "\n" + S10E.replace("192.0.2.10:1234", "mdns-s10e")
        with self.assertRaises(RuntimeError):
            RUNNER.select_target(listing, None, True)
        self.assertEqual(("mdns-s10e", "SM-G9708"), RUNNER.select_target(listing, "mdns-s10e", True))

    def test_identity_checks_model_hardware_and_explicit_target(self):
        with patch.object(RUNNER, "adb_output", side_effect=[S10E, "SM-G9708", "R28M227XA0E"]) as adb:
            RUNNER.verify_identity("adb", "192.0.2.10:1234", "SM-G9708", True)
        self.assertEqual([
            ("adb", "devices", "-l"),
            ("adb", "-s", "192.0.2.10:1234", "shell", "getprop", "ro.product.model"),
            ("adb", "-s", "192.0.2.10:1234", "shell", "getprop", "ro.serialno"),
        ], [c.args for c in adb.call_args_list])

    def test_replaced_model_or_s10e_hardware_is_rejected(self):
        for output in [[S10E, "SM-F976B"], [S10E, "SM-G9708", "unapproved-hardware"]]:
            with self.subTest(output=output), patch.object(RUNNER, "adb_output", side_effect=output), self.assertRaises(RuntimeError):
                RUNNER.verify_identity("adb", "192.0.2.10:1234", "SM-G9708", True)

    def test_transport_is_rechecked_before_each_operation(self):
        with patch.object(RUNNER, "adb_output", return_value=S23.replace("usb:1-2 ", "")), self.assertRaises(RuntimeError):
            RUNNER.verify_identity("adb", "old-usb", "SM-S911B", True)


class PtyRunnerResultTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.log = Path(self.folder.name) / "instrumentation.log"
        import json
        contract = json.loads((ROOT / "scripts/android-test-contract.json").read_text())
        self.tests = [test.split("#", 1)[1] for test in contract["suites"]["full"]["required_tests"]
                      if test.startswith(RUNNER.TEST_CLASS + "#")]
        self.assertEqual(8, len(self.tests))

    def write_results(self, tests, code=0, ending="INSTRUMENTATION_CODE: -1"):
        self.log.write_text("\n".join(
            f"INSTRUMENTATION_STATUS: class={RUNNER.TEST_CLASS}\n"
            f"INSTRUMENTATION_STATUS: test={test}\nINSTRUMENTATION_STATUS_CODE: {code}"
            for test in tests
        ) + "\n" + ending)

    def test_every_exact_identity_must_pass(self):
        self.write_results(self.tests)
        self.assertEqual(8, RUNNER.verify_results(self.log, RUNNER.TEST_CLASS))

    def test_success_text_cannot_hide_a_missing_or_unexpected_case(self):
        for tests in [self.tests[:-1], self.tests[:-1] + ["unexpected"]]:
            self.write_results(tests, ending="OK (8 tests)\nINSTRUMENTATION_CODE: -1")
            with self.assertRaises(RuntimeError):
                RUNNER.verify_results(self.log, RUNNER.TEST_CLASS)

    def test_skips_failures_duplicates_and_abnormal_completion_are_rejected(self):
        for code in [-3, -4]:
            self.write_results(self.tests, code=code)
            with self.assertRaises(RuntimeError):
                RUNNER.verify_results(self.log, RUNNER.TEST_CLASS)
        for tests, code in [(self.tests, -2), (self.tests + self.tests[:1], 0)]:
            self.write_results(tests, code=code)
            with self.assertRaises(SystemExit):
                RUNNER.verify_results(self.log, RUNNER.TEST_CLASS)
        self.write_results(self.tests, ending="INSTRUMENTATION_FAILED: process crashed")
        with self.assertRaises(RuntimeError):
            RUNNER.verify_results(self.log, RUNNER.TEST_CLASS)


if __name__ == "__main__":
    unittest.main()
