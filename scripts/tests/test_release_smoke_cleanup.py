from pathlib import Path
import importlib.util
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch


SOURCE = Path(__file__).resolve().parents[1] / "run-release-update-ci-smoke.py"
SPEC = importlib.util.spec_from_file_location("release_cleanup_subject", SOURCE)
SUBJECT = importlib.util.module_from_spec(SPEC)
with patch.object(sys, "path", [str(SOURCE.parent), *sys.path]):
    SPEC.loader.exec_module(SUBJECT)


class ReleaseSmokeCleanupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal-spike-owned-release-cleanup-")
        self.root = Path(self.temporary.name)
        self.runner = SUBJECT.ReleaseSmokeRunner.__new__(SUBJECT.ReleaseSmokeRunner)
        self.runner.project = self.root
        self.runner.fixture = self.root / "fixture"
        self.runner.fixture_may_be_running = False
        self.runner.output_dir = self.root / "reports"
        self.runner.output_dir.mkdir()
        self.runner.result_path = self.runner.output_dir / "release-smoke-result.txt"
        self.runner.result_path.write_text("")
        self.runner.workspace = self.root / "private"
        self.runner.workspace.mkdir()
        (self.runner.workspace / "acceptance.p12").write_bytes(b"owned dummy key data")
        self.runner.command = Mock(return_value=subprocess.CompletedProcess([], 0))
        self.runner.adb = Mock(return_value=subprocess.CompletedProcess([], 0))
        self.child = Mock()
        self.child.returncode = None
        self.child.poll.side_effect = lambda: self.child.returncode
        self.child.terminate.side_effect = lambda: setattr(self.child, "returncode", -15)
        self.child.kill.side_effect = lambda: setattr(self.child, "returncode", -9)
        self.runner.emulator_process = self.child

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_cleanup_without_a_started_fixture_never_calls_docker_or_console_kill(self) -> None:
        self.runner.cleanup()

        self.runner.command.assert_not_called()
        self.runner.adb.assert_not_called()
        self.child.terminate.assert_called_once()
        self.assertFalse((self.root / "private").exists())

    def test_existing_project_artifacts_are_preserved(self) -> None:
        artifact = self.root / "app/build/outputs/apk/release/original.apk"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"owned preexisting artifact")

        self.runner.cleanup()

        self.assertEqual(b"owned preexisting artifact", artifact.read_bytes())
        self.assertFalse((self.root / "private").exists())

    def test_fixture_failure_cannot_skip_child_shutdown_or_private_key_removal(self) -> None:
        self.runner.fixture_may_be_running = True
        self.runner.command.side_effect = SUBJECT.ReleaseSmokeFailure("owned fixture error")

        with self.assertRaises(SUBJECT.ReleaseSmokeFailure):
            self.runner.cleanup()

        self.assertIsNotNone(self.child.returncode)
        self.assertFalse((self.root / "private").exists())
        self.runner.adb.assert_not_called()

    def test_an_exited_child_is_reaped_without_termination(self) -> None:
        self.child.returncode = 1

        self.runner.cleanup()

        self.child.wait.assert_called_once_with(timeout=5)
        self.child.terminate.assert_not_called()
        self.child.kill.assert_not_called()
        self.runner.adb.assert_not_called()

    def test_unstoppable_child_keeps_its_private_workspace_and_reports_failure(self) -> None:
        self.child.terminate.side_effect = PermissionError("owned TERM failure")
        self.child.wait.side_effect = subprocess.TimeoutExpired("owned child", 5)
        self.child.kill.side_effect = PermissionError("owned KILL failure")

        with self.assertRaises(SUBJECT.ReleaseSmokeFailure):
            self.runner.cleanup()

        self.assertTrue(self.runner.workspace.is_dir())
        self.runner.adb.assert_not_called()

    def test_verified_artifact_changed_during_copy_is_rejected_before_any_signer(self) -> None:
        source = self.root / "verified"
        source.mkdir()
        names = ("app-release-unsigned.apk", "app-release.aab")
        original_copy = SUBJECT.shutil.copy2
        self.runner.arguments = SimpleNamespace(verified_release_dir=source)
        self.runner.apksigner_bin = Path("owned-fake-apksigner")
        self.runner.jarsigner_bin = Path("owned-fake-jarsigner")
        self.runner.keytool_bin = Path("owned-fake-keytool")
        self.runner.command.side_effect = lambda args, **kwargs: subprocess.CompletedProcess(
            args, 0, stdout=("a" * 40 if "rev-parse" in args else ""), stderr="",
        )
        for changed in names:
            with self.subTest(changed=changed):
                for name in names:
                    (source / name).write_bytes(b"owned verified bytes")
                (source / "manifest.json").write_text(json.dumps({
                    "schema": 1, "revision": "a" * 40,
                    "sha256": {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in names},
                }))
                self.runner.command.reset_mock()

                def concurrent_change(path, destination):
                    if Path(path).name == changed:
                        Path(path).write_bytes(b"owned changed source after validation")
                    return original_copy(path, destination)

                with patch.object(SUBJECT.shutil, "copy2", side_effect=concurrent_change):
                    with self.assertRaisesRegex(SUBJECT.ReleaseSmokeFailure, "changed while copying"):
                        self.runner.build_and_verify(self.root / "dummy.p12", "owned-alias", "owned-dummy-password")
                self.assertTrue(all(call.args[0][0] == "git" for call in self.runner.command.call_args_list))


if __name__ == "__main__":
    unittest.main()
