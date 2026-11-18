from pathlib import Path
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


SOURCE = Path(__file__).resolve().parents[1] / "run-app-lifecycle-e2e.py"
SPEC = importlib.util.spec_from_file_location("lifecycle_cleanup_subject", SOURCE)
SUBJECT = importlib.util.module_from_spec(SPEC)
with patch.object(sys, "path", [str(SOURCE.parent), *sys.path]):
    SPEC.loader.exec_module(SUBJECT)


class LifecycleCleanupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal-spike-owned-cleanup-")
        self.root = Path(self.temporary.name)
        self.runner = SUBJECT.Runner.__new__(SUBJECT.Runner)
        self.runner.workspace = self.root / "workspace"
        self.runner.workspace.mkdir()
        self.runner.fixture = self.root / "fixture"
        self.runner.output_dir = self.root / "reports"
        self.runner.output_dir.mkdir()
        self.runner.private_log = self.runner.workspace / "private.log"
        self.runner.private_log.write_text("owned diagnostics")
        self.runner.fixture_started = True
        self.runner.restore_data_saver = Mock()
        self.runner.restore_standby = Mock()
        self.runner.restore_idle = Mock()
        for name in ("restore_data_saver", "restore_standby", "restore_idle"):
            getattr(self.runner, name).__name__ = name
        self.runner.command = Mock(return_value=subprocess.CompletedProcess([], 0))
        self.runner.adb = Mock(return_value=subprocess.CompletedProcess([], 0))
        self.runner.capture_relevant_logcat = Mock(return_value="owned logcat")
        self.runner.write_redacted = Mock()
        self.process = Mock()
        self.process.poll.side_effect = lambda: self.process.returncode
        self.process.returncode = None
        self.process.terminate.side_effect = lambda: setattr(self.process, "returncode", -15)
        self.process.kill.side_effect = lambda: setattr(self.process, "returncode", -9)
        self.runner.emulator_process = self.process

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def assert_child_stopped_and_workspace_removed(self) -> None:
        self.assertIsNotNone(self.process.returncode)
        self.assertFalse((self.root / "workspace").exists())
        self.assertFalse(any(call.args[:2] == ("emu", "kill") for call in self.runner.adb.call_args_list))

    def test_cleanup_never_closes_a_serial_target_and_terminates_its_own_child(self) -> None:
        self.runner.cleanup()
        self.process.terminate.assert_called_once()
        self.assert_child_stopped_and_workspace_removed()

    def test_fixture_failure_does_not_skip_child_or_workspace_cleanup(self) -> None:
        self.runner.command.side_effect = SUBJECT.LifecycleFailure("owned fixture shutdown failure")
        with self.assertRaises(SUBJECT.LifecycleFailure):
            self.runner.cleanup()
        self.assert_child_stopped_and_workspace_removed()

    def test_redaction_failure_does_not_skip_workspace_cleanup(self) -> None:
        self.runner.write_redacted.side_effect = SUBJECT.LifecycleFailure("owned redaction failure")
        with self.assertRaises(SUBJECT.LifecycleFailure):
            self.runner.cleanup()
        self.assert_child_stopped_and_workspace_removed()

    def test_every_pressure_restore_is_attempted_before_reporting_failure(self) -> None:
        self.runner.restore_data_saver.side_effect = RuntimeError("owned restore failure")
        with self.assertRaises(SUBJECT.LifecycleFailure):
            self.runner.cleanup()
        self.runner.restore_standby.assert_called_once()
        self.runner.restore_idle.assert_called_once()
        self.assert_child_stopped_and_workspace_removed()

    def test_stubborn_child_is_killed_after_bounded_termination_wait(self) -> None:
        self.process.terminate.side_effect = None
        self.process.wait.side_effect = [subprocess.TimeoutExpired("owned child", 5), -9]
        self.runner.cleanup()
        self.process.terminate.assert_called_once()
        self.process.kill.assert_called_once()
        self.assertTrue(all(call.kwargs["timeout"] <= 5 for call in self.process.wait.call_args_list))
        self.assert_child_stopped_and_workspace_removed()

    def test_failed_child_stop_preserves_its_workspace_but_reports_failure(self) -> None:
        self.process.terminate.side_effect = PermissionError("owned TERM failure")
        self.process.kill.side_effect = PermissionError("owned KILL failure")
        self.process.wait.side_effect = subprocess.TimeoutExpired("owned child", 5)
        with self.assertRaises(SUBJECT.LifecycleFailure):
            self.runner.cleanup()
        self.assertTrue(self.runner.workspace.is_dir())
        self.assertFalse(any(call.args[:2] == ("emu", "kill") for call in self.runner.adb.call_args_list))


if __name__ == "__main__":
    unittest.main()
