from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


SOURCE_SCRIPT = Path(__file__).resolve().parents[1] / "install-wireless.sh"
OLD_SERIAL = "adb-RZCW81JZ9CP-NpnzVa._adb-tls-connect._tcp"
FOLD_SERIAL = "adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp"


class InstallWirelessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike device guard ")
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.script = scripts / "install-wireless.sh"
        shutil.copy2(SOURCE_SCRIPT, self.script)
        self.script.chmod(0o755)

        self.bin_dir = self.root / "fake bin"
        self.bin_dir.mkdir()
        self.adb_log = self.root / "adb.log"
        self.gradle_log = self.root / "gradle.log"
        self.fake_adb = self.bin_dir / "adb"
        self.fake_adb.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_ADB_LOG"
case "$*" in
  *" get-state") printf '%s\\n' "${FAKE_ADB_STATE-device}" ;;
  *" shell getprop ro.product.model") printf '%s\\n' "${FAKE_ADB_MODEL:-SM-S911B}" ;;
  *" install "*) printf 'Success\\n' ;;
  *" shell am start -W "*) printf 'Status: ok\\n' ;;
  *" shell dumpsys activity activities")
    if [[ "${FAKE_ADB_FOREGROUND:-yes}" == "yes" ]]; then
      printf 'topResumedActivity=ActivityRecord com.yanjiyu.terminalspike/.MainActivity\\n'
    else
      printf 'topResumedActivity=ActivityRecord com.example/.OtherActivity\\n'
    fi
    ;;
  "devices -l") printf 'List of devices attached\\n' ;;
esac
""",
            encoding="utf-8",
        )
        self.fake_adb.chmod(0o755)

        self.gradlew = self.root / "gradlew"
        self.gradlew.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
printf 'SERIAL=%s ARGS=%s\\n' "${ANDROID_SERIAL:-}" "$*" >> "$FAKE_GRADLE_LOG"
""",
            encoding="utf-8",
        )
        self.gradlew.chmod(0o755)

        self.apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"fake apk")
        self.env = os.environ.copy()
        self.env.update(
            {
                "PATH": f"{self.bin_dir}{os.pathsep}{self.env['PATH']}",
                "FAKE_ADB_LOG": str(self.adb_log),
                "FAKE_GRADLE_LOG": str(self.gradle_log),
                "FAKE_ADB_STATE": "device",
                "FAKE_ADB_MODEL": "SM-S911B",
                "FAKE_ADB_FOREGROUND": "yes",
            }
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_script(
        self,
        *arguments: str,
        env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(self.script), *arguments],
            cwd=self.root,
            env=env or self.env,
            check=False,
            capture_output=True,
            text=True,
        )

    def adb_commands(self) -> list[str]:
        if not self.adb_log.exists():
            return []
        return self.adb_log.read_text(encoding="utf-8").splitlines()

    def test_old_phone_test_exports_exact_serial_and_scopes_every_adb_command(self) -> None:
        result = self.run_script(
            "--test-old-phone",
            OLD_SERIAL,
            ":app:connectedDebugAndroidTest",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            f"SERIAL={OLD_SERIAL} ARGS=:app:connectedDebugAndroidTest\n",
            self.gradle_log.read_text(encoding="utf-8"),
        )
        commands = self.adb_commands()
        self.assertGreaterEqual(len(commands), 2)
        self.assertTrue(all(command.startswith(f"-s {OLD_SERIAL} ") for command in commands))
        self.assertTrue(commands[-1].endswith("shell getprop ro.product.model"))

    def test_old_phone_accepts_only_device_test_profile_and_benchmark_tasks(self) -> None:
        accepted = (
            ":app:connectedDebugAndroidTest",
            ":benchmark:connectedBenchmarkReleaseAndroidTest",
            ":benchmark:pixel6Api35BenchmarkAndroidTest",
            ":app:generateBaselineProfile",
        )
        result = self.run_script("--test-old-phone", OLD_SERIAL, *accepted)
        self.assertEqual(0, result.returncode, result.stderr)

        rejected = self.run_script("--test-old-phone", OLD_SERIAL, "assembleDebug")
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn("Refusing non-device-test", rejected.stderr)

    def test_unavailable_states_fail_before_gradle(self) -> None:
        for state in ("offline", "unauthorized", ""):
            with self.subTest(state=state):
                env = self.env.copy()
                env["FAKE_ADB_STATE"] = state
                result = self.run_script(
                    "--test-old-phone",
                    OLD_SERIAL,
                    ":app:connectedDebugAndroidTest",
                    env=env,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.gradle_log.exists())
                self.adb_log.unlink(missing_ok=True)

    def test_model_mismatch_blocks_tests_and_installs(self) -> None:
        env = self.env.copy()
        env["FAKE_ADB_MODEL"] = "SM-F976B"
        test_result = self.run_script(
            "--test-old-phone",
            FOLD_SERIAL,
            ":app:connectedDebugAndroidTest",
            env=env,
        )
        install_result = self.run_script(
            "--install-old-phone",
            FOLD_SERIAL,
            str(self.apk),
            env=env,
        )

        self.assertNotEqual(0, test_result.returncode)
        self.assertNotEqual(0, install_result.returncode)
        self.assertFalse(self.gradle_log.exists())
        self.assertFalse(any(" install " in f" {line} " for line in self.adb_commands()))

    def test_old_phone_install_is_scoped_model_checked_launched_and_verified(self) -> None:
        result = self.run_script("--install-old-phone", OLD_SERIAL, str(self.apk))

        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.adb_commands()
        self.assertTrue(all(command.startswith(f"-s {OLD_SERIAL} ") for command in commands))
        install_index = next(index for index, command in enumerate(commands) if " install -r " in command)
        self.assertTrue(commands[install_index - 1].endswith("shell getprop ro.product.model"))
        self.assertTrue(commands[-1].endswith("shell dumpsys activity activities"))

    def test_fold_install_requires_acknowledgement_and_exact_model(self) -> None:
        env = self.env.copy()
        env["FAKE_ADB_MODEL"] = "SM-F976B"
        missing_ack = self.run_script(
            "--final-fold-install",
            FOLD_SERIAL,
            str(self.apk),
            env=env,
        )
        wrong_model = self.run_script(
            "--final-fold-install",
            OLD_SERIAL,
            str(self.apk),
            "--feature-complete",
        )

        self.assertNotEqual(0, missing_ack.returncode)
        self.assertIn("requires --feature-complete", missing_ack.stderr)
        self.assertNotEqual(0, wrong_model.returncode)

    def test_final_fold_mode_installs_only_main_apk_without_adb_incremental_mode(self) -> None:
        env = self.env.copy()
        env["FAKE_ADB_MODEL"] = "SM-F976B"
        result = self.run_script(
            "--final-fold-install",
            FOLD_SERIAL,
            str(self.apk),
            "--feature-complete",
            env=env,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.adb_commands()
        self.assertTrue(all(command.startswith(f"-s {FOLD_SERIAL} ") for command in commands))
        install = next(command for command in commands if " install " in f" {command} ")
        self.assertIn(" install -r ", f" {install} ")
        self.assertNotIn("--incremental", install)
        self.assertTrue(commands[-1].endswith("shell dumpsys activity activities"))

    def test_apk_outside_main_build_output_is_rejected_without_adb(self) -> None:
        outside = self.root / "extension.apk"
        outside.write_bytes(b"not main app")
        result = self.run_script("--install-old-phone", OLD_SERIAL, str(outside))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Only the repository's main debug APK", result.stderr)
        self.assertEqual([], self.adb_commands())

    def test_foreground_failure_is_reported(self) -> None:
        env = self.env.copy()
        env["FAKE_ADB_FOREGROUND"] = "no"
        result = self.run_script("--install-old-phone", OLD_SERIAL, str(self.apk), env=env)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("did not become the top-resumed activity", result.stderr)

    def test_invalid_serial_is_rejected_before_adb(self) -> None:
        result = self.run_script(
            "--test-old-phone",
            "serial;echo-danger",
            ":app:connectedDebugAndroidTest",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.adb_commands())


if __name__ == "__main__":
    unittest.main()
