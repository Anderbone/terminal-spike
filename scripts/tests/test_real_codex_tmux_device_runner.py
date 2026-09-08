from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


SOURCE_ROOT = Path(__file__).resolve().parents[2]


class RealCodexTmuxDeviceRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike codex tmux runner ")
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in (
            "run-real-codex-tmux-device-test.sh",
            "instrumentation-output-to-junit.py",
            "redact-android-test-log.py",
            "sanitize-real-codex-instrumentation.py",
            "sanitize-real-codex-tmux-log.py",
            "assert-test-report.py",
        ):
            shutil.copy2(SOURCE_ROOT / "scripts" / name, scripts / name)
            (scripts / name).chmod(0o755)
        self.runner = scripts / "run-real-codex-tmux-device-test.sh"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "commands.log"
        self.output = self.root / "results"
        self.tmp = self.root / "temporary"
        self.tmp.mkdir()
        self.ssh_dir = self.root / "home/.ssh"
        self.ssh_dir.mkdir(parents=True)
        self.authorized_keys = self.ssh_dir / "authorized_keys"
        self.original_authorized_keys = "ssh-ed25519 AAAATEST existing-key\n"
        self.authorized_keys.write_text(self.original_authorized_keys, encoding="utf-8")
        self.serial = "adb-RZCW81JZ9CP-NpnzVa._adb-tls-connect._tcp"

        self.write_tool(
            self.root / "gradlew",
            """printf 'gradle %s\\n' "$*" >> "$FAKE_COMMAND_LOG"
mkdir -p app/build/outputs/apk/debug app/build/outputs/apk/androidTest/debug
mkdir -p app/build/outputs/runtime-test-utils
: > app/build/outputs/apk/debug/app-debug.apk
: > app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
: > app/build/outputs/runtime-test-utils/orchestrator-1.6.1.apk
: > app/build/outputs/runtime-test-utils/test-services-1.6.0.apk
""",
        )
        self.write_tool(
            self.bin / "codex",
            """if [[ "${1:-}" == login && "${2:-}" == status ]]; then
  printf 'Logged in using ChatGPT\\n'
fi
""",
        )
        self.write_tool(self.bin / "node", ":\n")
        self.write_tool(self.bin / "ssh", "printf 'ssh verified\\n' >> \"$FAKE_COMMAND_LOG\"\n")
        self.write_tool(
            self.bin / "adb",
            r'''printf 'adb %s\n' "$*" >> "$FAKE_COMMAND_LOG"
case "$*" in
  "devices -l")
    printf 'List of devices attached\n%s\tdevice product:dm3q model:%s device:dm3q transport_id:7\n' \
      "$FAKE_SERIAL" "${FAKE_INVENTORY_MODEL:-SM_S911B}"
    ;;
  *" get-state") printf 'device\n' ;;
  *"shell getprop ro.product.model") printf '%s\n' "${FAKE_PROP_MODEL:-SM-S911B}" ;;
  *" install "*) [[ "${FAKE_INSTALL_FAIL:-0}" != 1 ]] ;;
  *"toybox nc -z"*) [[ "${FAKE_REACHABLE:-1}" == 1 ]] ;;
  *"shell sh")
    IFS= read -r instrumentation_command
    if [[ "$instrumentation_command" == *"shellStarted"* ]]; then
      [[ "$instrumentation_command" != *"#realServerScrollsToFirstRow"* ]]
      [[ "$instrumentation_command" != *"#appSelectedTmux"* ]]
      test=shellStartedTmuxActualCodexFirstGestureUsesLocalPixelScroll
      if [[ "$instrumentation_command" == *"shellStartedMoshTmux"* ]]; then
        test=shellStartedMoshTmuxActualCodexFirstGestureUsesLocalPixelScroll
      fi
      printf 'INSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 1\n' "$test"
      printf 'INSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 0\n' "$test"
      printf 'OK (1 test)\n'
      exit 0
    fi
    [[ "$instrumentation_command" == *"realServerScrollsToFirstRowThroughProductionSessionAndComposeView"* ]]
    [[ "$instrumentation_command" == *"appSelectedTmuxActualCodexFirstGestureUsesLocalPixelScroll"* ]]
    [[ "$instrumentation_command" == *"appSelectedTmuxExplicitRemoteMouseScrollsRealLessVimAndHtop"* ]]
    [[ "$instrumentation_command" == *"sshE2ePrivateKeyBase64"* ]]
    [[ "$instrumentation_command" == *"sshE2eCodexCommandBase64"* ]]
    printf 'instrumentation-stdin direct=yes tmux=yes mouse-app=yes private-key=yes codex-command=yes\n' >> "$FAKE_COMMAND_LOG"
    printf 'privateKeyBase64=FAKE_PRIVATE_VALUE\n'
    printf 'INSTRUMENTATION_STATUS: stack=visibleHead=[SECRET_ROW] jiyu@private-host\n'
    for test in \
      realServerScrollsToFirstRowThroughProductionSessionAndComposeView \
      appSelectedTmuxActualCodexFirstGestureUsesLocalPixelScroll \
      appSelectedTmuxExplicitRemoteMouseScrollsRealLessVimAndHtop; do
      printf 'INSTRUMENTATION_STATUS: test=%s\n' "$test"
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
      printf 'INSTRUMENTATION_STATUS: test=%s\n' "$test"
      printf 'INSTRUMENTATION_STATUS_CODE: %s\n' "${FAKE_STATUS_CODE:-0}"
    done
    printf 'OK (3 tests)\n'
    [[ "${FAKE_INSTRUMENTATION_FAIL:-0}" == 0 ]]
    ;;
  *"logcat -d"*)
    printf 'I/SshRealScrollE2E: task indicators running=pass ready=pass\n'
    [[ "${FAKE_MISSING_CHECKPOINT:-0}" == 1 ]] || \
      printf 'I/SshRealScrollE2E: actual Codex reader position established autoFollow=false historyAnchor=true fractional=true flings=2 visibleHead=[SECRET_ROW]\nI/SshRealScrollE2E: actual Codex reader output observed autoFollow=false; confirmed=true, outerAlternate=true, historyActive=true, metadataKnown=true, metadataFresh=false, remoteMousePassthrough=false, mouseAny=false, paneInMode=false, historyLines=5300, pane=%%61, capturedStart=0, oldestAvailable=0, remoteHistory=5300, truncatedBefore=false, pendingSnapshots=0, outerExited=false visibleHead=[SECRET_ROW]\nI/SshRealScrollE2E: actual Codex new output preserved reader anchor autoFollow=false pixelDelta=0.0 fractional=true visibleHead=[SECRET_ROW]\nI/SshRealScrollE2E: actual Codex live bottom restored autoFollow=true markerVisible=true visibleTail=[jiyu@private-host]\nI/SshRealScrollE2E: tmux sub-row drag and fling passed visibleTail=[jiyu@private-host]\nI/SshRealScrollE2E: real less remote mouse passed destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=4 visibleTail=[jiyu@private-host]\nI/SshRealScrollE2E: real vim remote mouse passed destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=3 visibleTail=[jiyu@private-host]\nI/SshRealScrollE2E: real htop remote mouse passed destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=3 swipes=5 visibleTail=[jiyu@private-host]\n'
    ;;
esac
''',
        )
        self.env = os.environ.copy()
        self.env.update(
            {
                "PATH": f"{self.bin}:{self.env['PATH']}",
                "HOME": str(self.root / "home"),
                "TMPDIR": str(self.tmp),
                "FAKE_COMMAND_LOG": str(self.log),
                "FAKE_SERIAL": self.serial,
                "TERMINAL_SPIKE_AUTHORIZED_KEYS_FILE": str(self.authorized_keys),
                "TERMINAL_SPIKE_INSTRUMENTATION_TIMEOUT_SECONDS": "5",
            }
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_tool(self, path: Path, body: str) -> None:
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def run_runner(self, *extra: str, env: dict[str, str] | None = None):
        return subprocess.run(
            [
                str(self.runner),
                "--serial",
                self.serial,
                "--host-address",
                "192.168.5.10",
                "--trusted-lan",
                "--output-dir",
                str(self.output),
                *extra,
            ],
            cwd=self.root,
            env=env or self.env,
            check=False,
            capture_output=True,
            text=True,
            timeout=20,
        )

    def commands(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []

    def assert_private_state_clean(self) -> None:
        self.assertEqual(self.original_authorized_keys, self.authorized_keys.read_text())
        self.assertEqual([], list(self.tmp.glob("terminal-spike-real-codex.*")))

    def test_success_runs_exact_gate_only_on_verified_old_phone_and_revokes_key(self) -> None:
        result = self.run_runner()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("REAL_CODEX_TMUX_RESULT tests=3 failures=0 skips=0", result.stdout)
        self.assertIn("direct=pass tmux=pass mouse_apps=less,vim,htop", result.stdout)
        self.assertIn("reader_anchor=pass live_bottom=pass", result.stdout)
        self.assert_private_state_clean()
        adb = [line for line in self.commands() if line.startswith("adb ")]
        scoped = [line for line in adb if line != "adb devices -l"]
        self.assertTrue(scoped)
        self.assertTrue(all(line.startswith(f"adb -s {self.serial} ") for line in scoped))
        self.assertEqual(4, sum(" install " in f" {line} " for line in adb))
        instrumentation = [line for line in adb if line.endswith(" shell sh")]
        self.assertEqual(1, len(instrumentation))
        self.assertNotIn("sshE2ePrivateKeyBase64", instrumentation[0])
        self.assertNotIn("sshE2eCodexCommandBase64", instrumentation[0])
        self.assertIn(
            "instrumentation-stdin direct=yes tmux=yes mouse-app=yes private-key=yes codex-command=yes",
            self.commands(),
        )
        source = self.runner.read_text(encoding="utf-8")
        self.assertIn("check_for_update_on_startup=false", source)
        self.assertIn("--disable in_app_updates", source)
        self.assertNotIn("remove_authorized_key || true", source)
        self.assertTrue((self.output / "TEST-real-codex-tmux.xml").is_file())
        public = "\n".join(path.read_text() for path in self.output.glob("*.txt"))
        self.assertNotIn("FAKE_PRIVATE_VALUE", public)
        self.assertNotIn("private-host", public)
        self.assertNotIn("visibleTail", public)
        self.assertNotIn("SECRET_ROW", public)

    def test_manual_modes_run_only_the_exact_method_without_claiming_other_gates(self) -> None:
        for flag, installs in (("--manual-tmux", 4), ("--manual-mosh", 4)):
            with self.subTest(flag=flag):
                result = self.run_runner(flag)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("tests=1 failures=0 skips=0 manual_tmux=pass task_indicators=pass", result.stdout)
                self.assertNotIn("direct=pass", result.stdout)
                self.assertNotIn("mouse_apps=", result.stdout)
                self.assert_private_state_clean()
                self.assertEqual(installs, sum(" install " in line for line in self.commands()))
                self.assertFalse(any("mosh-extension" in line for line in self.commands()))
                self.log.unlink(missing_ok=True)
                shutil.rmtree(self.output, ignore_errors=True)

    def test_instrumentation_and_checkpoint_failures_still_revoke_key(self) -> None:
        for variable in ("FAKE_INSTRUMENTATION_FAIL", "FAKE_MISSING_CHECKPOINT"):
            with self.subTest(variable=variable):
                env = self.env.copy()
                env[variable] = "1"
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                self.assert_private_state_clean()
                shutil.rmtree(self.output, ignore_errors=True)
                self.log.unlink(missing_ok=True)

    def test_refuses_fold_and_invalid_inputs_before_authorizing_key(self) -> None:
        fold_env = self.env.copy()
        fold_env["FAKE_INVENTORY_MODEL"] = "SM_F976B"
        self.assertNotEqual(0, self.run_runner(env=fold_env).returncode)
        self.assert_private_state_clean()

        for extra in (("--host-port", "0"), ("--host-username", "bad user")):
            with self.subTest(extra=extra):
                self.assertNotEqual(0, self.run_runner(*extra).returncode)
                self.assert_private_state_clean()

    def test_requires_newline_terminated_authorized_keys(self) -> None:
        self.authorized_keys.write_text("existing-without-newline", encoding="utf-8")
        result = self.run_runner()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("existing-without-newline", self.authorized_keys.read_text())
        self.assertFalse(any(line.startswith("gradle ") for line in self.commands()))


if __name__ == "__main__":
    unittest.main()
