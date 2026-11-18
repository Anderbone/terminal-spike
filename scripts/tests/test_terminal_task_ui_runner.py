import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("task_ui_runner", SCRIPTS / "run-terminal-task-ui-tests.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class TaskUiRunnerTest(unittest.TestCase):
    def run_fixture(self, *, status=0, code=0, names=None):
        calls = []
        actual_run = subprocess.run
        names = sorted(runner.EXPECTED) if names is None else names
        output = "".join(f"INSTRUMENTATION_STATUS: class=com.yanjiyu.terminalspike.TerminalTaskIndicatorTest\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 1\n"
                         f"INSTRUMENTATION_STATUS: class=com.yanjiyu.terminalspike.TerminalTaskIndicatorTest\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: {status}\n" for name in names)
        output += "OK (4 tests)\n"

        def fake_run(command, **kwargs):
            calls.append(command)
            if command[0] == "/fake/adb":
                return subprocess.CompletedProcess(command, code if "shell" in command else 0, output, "")
            return actual_run(command, **kwargs)

        with tempfile.TemporaryDirectory() as temporary, patch.object(sys, "argv", ["runner", "--serial", "phone", "--output-dir", temporary]), patch.object(
            runner.shutil, "which", return_value="/fake/adb"
        ), patch.object(runner, "verify_device", return_value="SM-G9708") as device, patch.object(runner, "verify_apks") as apks, patch.object(
            runner.subprocess, "run", side_effect=fake_run
        ):
            try:
                runner.main()
            except (SystemExit, subprocess.CalledProcessError) as error:
                return error, calls, device.call_count, apks.call_count
            return None, calls, device.call_count, apks.call_count

    def test_exact_four_tests_use_only_isolated_target(self):
        error, calls, devices, artifacts = self.run_fixture()
        self.assertIsNone(error)
        self.assertEqual(3, devices)
        self.assertEqual(3, artifacts)
        shell = next(command[-1] for command in calls if command[0] == "/fake/adb" and "shell" in command)
        self.assertIn("com.yanjiyu.terminalspike.deviceverify.test/", shell)
        self.assertTrue(shell.startswith("am instrument -w -r -e class "))
        self.assertNotIn("orchestrator", shell.lower())
        self.assertNotIn("androidx.test.services", shell)
        self.assertNotIn("clearPackageData", shell)
        self.assertNotIn("targetInstrumentation", shell)
        self.assertNotIn("pm clear", shell)
        self.assertTrue(all(command[1:3] == ["-s", "phone"] for command in calls if command[0] == "/fake/adb"))

    def test_failure_skip_wrong_names_and_failed_process_cannot_claim_green(self):
        for options in ({"status": -2}, {"status": -3}, {"code": 1}, {"names": ["unrelated"] * 4}):
            with self.subTest(options=options):
                error, _, _, _ = self.run_fixture(**options)
                self.assertIsNotNone(error)

    def test_bad_artifact_cannot_install_anything(self):
        with patch.object(sys, "argv", ["runner", "--serial", "phone", "--output-dir", "unused"]), patch.object(
            runner.shutil, "which", return_value="/fake/adb"
        ), patch.object(runner, "verify_apks", side_effect=RuntimeError("unsafe artifact")), patch.object(runner.subprocess, "run") as command:
            with self.assertRaises(RuntimeError):
                runner.main()
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
