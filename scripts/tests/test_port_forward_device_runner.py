"""Exercise the actual runner main in temporary storage with every command mocked."""
from contextlib import redirect_stderr, redirect_stdout
from hashlib import sha256
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("forward_runner", SOURCE / "run-port-forward-device-tests.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
CLASSES = (
    "com.yanjiyu.terminalspike.connection.PortForwardRealEndToEndTest",
    "com.yanjiyu.terminalspike.ui.connections.PortForwardEditorTest",
    "com.yanjiyu.terminalspike.core.data.db.AppDatabaseMigrationTest",
)


class PortForwardDeviceRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="terminal spike forwarding runner ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.fixture = self.root / "integration-tests/openssh"
        self.fixture.mkdir(parents=True)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("android-test-contract.json", "verify-android-test-contract.py",
                     "instrumentation-output-to-junit.py"):
            shutil.copyfile(SOURCE / name, scripts / name)
        self.contract_path = scripts / "android-test-contract.json"
        self.contract = json.loads(self.contract_path.read_text())
        self.tests = [test for test in self.contract["suites"]["full"]["required_tests"]
                      if test.split("#", 1)[0] in CLASSES]
        self.assertEqual(8, len(self.tests))
        self.raw = self.output(self.tests)
        self.payload = b"fixture-only apk"
        for relative in ("app/build/outputs/apk/debug/app-debug.apk",
                         "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
            apk = self.root / relative
            apk.parent.mkdir(parents=True, exist_ok=True)
            apk.write_bytes(self.payload)
        self.calls = []
        self.listing = "fixture-usb device usb:1-2 model:SM_S911B\n"
        self.model = "SM-S911B\n"
        self.models = []
        self.existing_fixture = False
        self.tampered_apk = False
        self.fail_command = None

    def output(self, identities, *, status=0, summary=None, completion="INSTRUMENTATION_CODE: -1"):
        records = []
        for identity in identities:
            test_class, method = identity.split("#", 1)
            records.append(
                f"INSTRUMENTATION_STATUS: class={test_class}\n"
                f"INSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 1\n"
                f"INSTRUMENTATION_STATUS: class={test_class}\n"
                f"INSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {status}"
            )
        return "\n".join(records + [summary or f"OK ({len(identities)} tests)", completion]) + "\n"

    def run_command(self, *command, **kwargs):
        self.calls.append(command)
        if self.fail_command and self.fail_command(command):
            raise subprocess.CalledProcessError(7, command)
        stdout = ""
        if command[:3] == ("adb", "devices", "-l"):
            stdout = self.listing
        elif command[0] == "adb":
            self.assertEqual(("-s", "fixture-usb"), command[1:3])
            arguments = command[3:]
            if arguments == ("shell", "getprop", "ro.product.model"):
                stdout = self.models.pop(0) if self.models else self.model
            elif arguments[:3] == ("shell", "pm", "path"):
                stdout = "package:/fixture/base.apk\n"
            elif arguments[:2] == ("shell", "sha256sum"):
                stdout = ("0" * 64 if self.tampered_apk else sha256(self.payload).hexdigest()) + "  base.apk\n"
            elif arguments[:3] == ("shell", "am", "instrument"):
                stdout = self.raw
        elif command[-2:] == ("ps", "-q") and self.existing_fixture:
            stdout = "another-fixture-owner\n"
        return subprocess.CompletedProcess(command, 0, stdout, "")

    def run_main(self):
        self.stdout = io.StringIO()
        self.stderr = io.StringIO()
        with patch.object(RUNNER, "ROOT", self.root), patch.object(RUNNER, "FIXTURE", self.fixture), \
             patch.object(RUNNER, "run", side_effect=self.run_command), \
             patch("sys.argv", ["run-port-forward-device-tests.py", "--serial", "fixture-usb",
                                "--host-address", "192.168.50.10", "--trusted-lan"]), \
             redirect_stdout(self.stdout), redirect_stderr(self.stderr):
            RUNNER.main()

    def assert_rejected(self):
        with self.assertRaises((RuntimeError, SystemExit)):
            self.run_main()
        self.assertNotIn("PORT_FORWARD_DEVICE_RESULT", self.stdout.getvalue())
        self.assertEqual(1, sum(command[-1:] == ("down",) for command in self.calls))

    def instrumentations(self):
        return [command for command in self.calls if command[:6] ==
                ("adb", "-s", "fixture-usb", "shell", "am", "instrument")]

    def test_complete_current_eight_case_contract_runs_on_only_the_verified_usb_phone(self):
        self.run_main()
        self.assertIn("tests=8 failures=0 skips=0", self.stdout.getvalue())
        self.assertEqual("", self.stderr.getvalue())
        instrument, = self.instrumentations()
        self.assertEqual(",".join(CLASSES), instrument[instrument.index("class") + 1])
        self.assertIn("portForwardE2e", instrument)
        self.assertIn("forwardHost", instrument)
        self.assertEqual(2, sum(command[3:4] == ("install",) for command in self.calls))
        scoped = [command for command in self.calls if command[0] == "adb" and command[:3] != ("adb", "devices", "-l")]
        self.assertTrue(scoped)
        self.assertTrue(all(command[1:3] == ("-s", "fixture-usb") for command in scoped))
        self.assertEqual(1, sum(command[-1:] == ("down",) for command in self.calls))

    def test_new_required_method_in_a_selected_class_cannot_be_lost_to_a_count_literal(self):
        added = CLASSES[0] + "#futureRequiredCase"
        self.contract["suites"]["full"]["required_tests"].append(added)
        self.contract["suites"]["full"]["required_tests"].sort()
        self.contract_path.write_text(json.dumps(self.contract))
        self.raw = self.output(self.tests + [added])
        self.run_main()
        self.assertIn("tests=9 failures=0 skips=0", self.stdout.getvalue())

    def test_old_seven_case_subset_is_not_the_current_complete_gate(self):
        self.raw = self.output(self.tests[:7])
        self.assert_rejected()

    def test_seven_unrelated_cases_cannot_replace_real_tunnel_and_migration_cases(self):
        self.raw = self.output(["unrelated.Fixture#wrong" + str(index) for index in range(7)])
        self.assert_rejected()

    def test_duplicate_identity_cannot_substitute_for_a_missing_case(self):
        self.raw = self.output(self.tests[:6] + self.tests[:1])
        self.assert_rejected()

    def test_summary_only_and_partial_identities_cannot_claim_the_named_gate(self):
        reports = ["OK (7 tests)\nINSTRUMENTATION_CODE: -1\n"]
        raw = self.output(self.tests[:7])
        reports += [raw.replace("INSTRUMENTATION_STATUS: class=" + self.tests[0].split("#")[0] + "\n", ""),
                    raw.replace("INSTRUMENTATION_STATUS: test=" + self.tests[0].split("#")[1] + "\n", "")]
        for report in reports:
            with self.subTest(report=report[:50]):
                self.calls.clear()
                self.raw = report
                self.assert_rejected()

    def test_skips_failures_errors_and_unknown_statuses_cannot_pass(self):
        for status in (-3, -4, -2, -1, -99):
            with self.subTest(status=status):
                self.calls.clear()
                self.raw = self.output(self.tests[:7], status=status)
                self.assert_rejected()

    def test_malformed_status_cannot_be_hidden_by_success_text(self):
        self.raw = self.output(self.tests[:7]).replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: invalid", 1)
        self.assert_rejected()

    def test_declared_summary_must_match_all_completed_case_records(self):
        for count in (7, 9):
            with self.subTest(count=count):
                self.calls.clear()
                self.raw = self.output(self.tests, summary=f"OK ({count} tests)")
                self.assert_rejected()

    def test_multiple_runs_or_an_extra_started_case_are_not_one_complete_invocation(self):
        first = self.output(self.tests[:7], completion="")
        for tail in (self.output(self.tests[7:]),
                     "INSTRUMENTATION_STATUS: class=fixture.Partial\nINSTRUMENTATION_STATUS: test=unfinished\n"
                     "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_CODE: -1\n"):
            with self.subTest(tail=tail[:50]):
                self.calls.clear()
                self.raw = first + tail
                self.assert_rejected()

    def test_success_records_require_one_final_normal_instrumentation_completion(self):
        for completion in ("", "INSTRUMENTATION_CODE: 0", "INSTRUMENTATION_CODE: -2",
                           "INSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1",
                           "INSTRUMENTATION_CODE: -1\nINSTRUMENTATION_FAILED: fixture crash"):
            with self.subTest(completion=completion):
                self.calls.clear()
                self.raw = self.output(self.tests, completion=completion)
                self.assert_rejected()

    def test_fold_s10e_wireless_offline_unauthorized_and_malformed_rows_cannot_install(self):
        for listing in ("fixture-usb device usb:1-2 model:SM_F976B\n",
                        "fixture-usb device model:SM_S911B\n",
                        "fixture-usb device model:SM_G9708\n",
                        "fixture-usb offline usb:1-2 model:SM_S911B\n",
                        "fixture-usb unauthorized usb:1-2 model:SM_S911B\n", "fixture-usb\n"):
            with self.subTest(listing=listing):
                self.calls.clear()
                self.listing = listing
                with self.assertRaises(RuntimeError):
                    self.run_main()
                self.assertFalse(any(command[3:4] == ("install",) for command in self.calls))
                self.assertEqual([], self.instrumentations())
                self.assertFalse(any(command[-1:] == ("down",) for command in self.calls))

    def test_model_change_after_install_aborts_before_another_device_operation(self):
        self.models = ["SM-S911B\n", "SM-S911B\n", "SM-F976B\n"]
        self.assert_rejected()
        self.assertEqual(1, sum(command[3:4] == ("install",) for command in self.calls))
        self.assertEqual([], self.instrumentations())

    def test_an_already_running_fixture_is_preserved_before_device_actions(self):
        self.existing_fixture = True
        with self.assertRaises(RuntimeError):
            self.run_main()
        self.assertFalse(any(command[0] == "adb" for command in self.calls))
        self.assertFalse(any(command[-1:] == ("down",) for command in self.calls))

    def test_apk_hash_mismatch_aborts_and_releases_the_owned_fixture(self):
        self.tampered_apk = True
        self.assert_rejected()
        self.assertEqual([], self.instrumentations())

    def test_fixture_install_and_instrumentation_command_failures_still_teardown(self):
        for kind in ("fixture", "install", "instrument"):
            with self.subTest(kind=kind):
                self.calls.clear()
                self.fail_command = lambda command: (
                    (kind == "fixture" and command[0] == str(self.fixture / "smoke.sh")) or
                    (kind == "install" and command[3:4] == ("install",)) or
                    (kind == "instrument" and command[:6] == ("adb", "-s", "fixture-usb", "shell", "am", "instrument"))
                )
                with self.assertRaises(subprocess.CalledProcessError):
                    self.run_main()
                self.assertEqual(1, sum(command[-1:] == ("down",) for command in self.calls))

    def test_missing_class_or_invalid_contract_fails_before_external_actions(self):
        original = json.loads(json.dumps(self.contract))
        for kind in ("missing-class", "bad-schema", "duplicate"):
            with self.subTest(kind=kind):
                self.calls.clear()
                self.contract = json.loads(json.dumps(original))
                required = self.contract["suites"]["full"]["required_tests"]
                if kind == "missing-class":
                    required[:] = [test for test in required if not test.startswith(CLASSES[0] + "#")]
                    self.contract["allowed_skip_rules"] = []
                elif kind == "bad-schema":
                    self.contract["schema"] = 99
                else:
                    required.append(required[0])
                self.contract_path.write_text(json.dumps(self.contract))
                with self.assertRaises((RuntimeError, SystemExit)):
                    self.run_main()
                self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
