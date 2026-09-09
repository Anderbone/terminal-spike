from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import unittest


SOURCE_SCRIPTS = Path(__file__).resolve().parents[1]


class AndroidEmulatorRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike emulator runner ")
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in (
            "run-android-emulator-tests.sh",
            "instrumentation-output-to-junit.py",
            "redact-android-test-log.py",
            "verify-android-test-contract.py",
        ):
            shutil.copy2(SOURCE_SCRIPTS / name, scripts / name)
            (scripts / name).chmod(0o755)
        (scripts / "android-test-contract.json").write_text(
            json.dumps(
                {
                    "schema": 1,
                    "supported_apis": list(range(26, 38)),
                    "suites": {
                        "full": {"required_tests": ["com.example.Test#works"]},
                        "boundary": {"required_tests": ["com.example.Test#works"]},
                    },
                    "allowed_skip_rules": [],
                }
            ),
            encoding="utf-8",
        )
        self.runner = scripts / "run-android-emulator-tests.sh"
        self.app_apk = self.root / "app.apk"
        self.test_apk = self.root / "test.apk"
        self.orchestrator_apk = self.root / "orchestrator.apk"
        self.test_services_apk = self.root / "test-services.apk"
        self.app_apk.write_bytes(b"app")
        self.test_apk.write_bytes(b"test")
        self.orchestrator_apk.write_bytes(b"orchestrator")
        self.test_services_apk.write_bytes(b"services")
        self.output = self.root / "results"
        self.tmp = self.root / "temporary"
        self.tmp.mkdir()
        self.state = self.root / "state"
        self.state.mkdir()
        self.log = self.root / "sdk.log"

        sdk = self.root / "sdk"
        (sdk / "cmdline-tools/latest/bin").mkdir(parents=True)
        (sdk / "emulator").mkdir()
        (sdk / "platform-tools").mkdir()
        self.write_tool(
            sdk / "cmdline-tools/latest/bin/sdkmanager",
            'printf "sdkmanager %s\\n" "$*" >> "$FAKE_SDK_LOG"\n',
        )
        self.write_tool(
            sdk / "cmdline-tools/latest/bin/avdmanager",
            'cat >/dev/null\n'
            'name=""\n'
            'for ((index = 1; index <= $#; index++)); do\n'
            '  if [[ "${!index}" == "--name" ]]; then next=$((index + 1)); name="${!next}"; fi\n'
            'done\n'
            'mkdir -p "$ANDROID_AVD_HOME/$name.avd"\n'
            ': >"$ANDROID_AVD_HOME/$name.avd/config.ini"\n'
            'printf "avdmanager %s AVD_HOME=%s\\n" "$*" "$ANDROID_AVD_HOME" >> "$FAKE_SDK_LOG"\n',
        )
        self.write_tool(
            sdk / "emulator/emulator",
            'printf "emulator %s AVD_HOME=%s\\n" "$*" "$ANDROID_AVD_HOME" >> "$FAKE_SDK_LOG"\nexec tail -f /dev/null\n',
        )
        self.write_tool(
            sdk / "platform-tools/adb",
            r'''printf "adb %s\n" "$*" >> "$FAKE_SDK_LOG"
case "$*" in
  *"shell getprop sys.boot_completed") printf '%s\n' "${FAKE_BOOTED:-1}" ;;
  *"shell getprop ro.kernel.qemu") printf '%s\n' "${FAKE_QEMU:-1}" ;;
  *"emu avd name") printf '%s\nOK\n' "$FAKE_AVD" ;;
  *"emu kill") [[ "${FAKE_STUCK_SHUTDOWN:-0}" != "1" ]] || sleep 300 ;;
  *"shell pm path android") [[ "${FAKE_PM_READY:-1}" == "1" ]] && printf 'package:/system/framework/framework-res.apk\n' ;;
  *" install "*) [[ "${FAKE_INSTALL_FAIL:-0}" != "1" ]] ;;
  *"logcat -c") [[ "${FAKE_LOGCAT_CLEAR_FAIL:-0}" != "1" ]] ;;
  *"shell am instrument"*"terminalSpikeBackupCleanInstallStage export"*)
    printf '%s\n' 'INSTRUMENTATION_STATUS: class=com.yanjiyu.terminalspike.backup.BackupDocumentsProviderIntegrationTest'
    printf '%s\n' 'INSTRUMENTATION_STATUS: test=exportStandardAndFullArchivesForCleanInstall'
    printf '%s\n' 'INSTRUMENTATION_STATUS_CODE: 0' 'OK (1 test)'
    ;;
  *"shell am instrument"*"terminalSpikeBackupCleanInstallStage restore-standard"*)
    printf '%s\n' 'INSTRUMENTATION_STATUS: class=com.yanjiyu.terminalspike.backup.BackupDocumentsProviderIntegrationTest'
    printf '%s\n' 'INSTRUMENTATION_STATUS: test=restoreStandardArchiveAfterCleanInstall'
    printf '%s\n' 'INSTRUMENTATION_STATUS_CODE: 0' 'OK (1 test)'
    ;;
  *"shell am instrument"*"terminalSpikeBackupCleanInstallStage restore-full"*)
    printf '%s\n' 'INSTRUMENTATION_STATUS: class=com.yanjiyu.terminalspike.backup.BackupDocumentsProviderIntegrationTest'
    printf '%s\n' 'INSTRUMENTATION_STATUS: test=restoreFullArchiveAfterCleanInstall'
    printf '%s\n' 'INSTRUMENTATION_STATUS_CODE: 0' 'OK (1 test)'
    ;;
  *"ShellMain am instrument"*) printf '%s\n' "$FAKE_INSTRUMENTATION_OUTPUT" ;;
  *"exec-out run-as com.yanjiyu.terminalspike cat files/backup-documents/clean-install-"*)
    printf 'opaque-encrypted-archive-bytes'
    ;;
  *"logcat -d") printf 'password=hidden 192.168.1.2\n' ;;
  *"shell pm grant com.yanjiyu.terminalspike android.permission.ACCESS_LOCAL_NETWORK") touch "$FAKE_STATE_DIR/permission" ;;
  *"shell pm revoke com.yanjiyu.terminalspike android.permission.ACCESS_LOCAL_NETWORK") rm -f "$FAKE_STATE_DIR/permission" "$FAKE_STATE_DIR/pid" ;;
  *"shell dumpsys package com.yanjiyu.terminalspike")
    [[ -f "$FAKE_STATE_DIR/permission" ]] && printf 'android.permission.ACCESS_LOCAL_NETWORK: granted=true\n' || printf 'android.permission.ACCESS_LOCAL_NETWORK: granted=false\n'
    ;;
  *"shell am start -W -n com.yanjiyu.terminalspike/.MainActivity") printf '123\n' >"$FAKE_STATE_DIR/pid" ;;
  *"shell pidof com.yanjiyu.terminalspike") [[ -f "$FAKE_STATE_DIR/pid" ]] && cat "$FAKE_STATE_DIR/pid" ;;
  *"shell dumpsys activity activities")
    [[ -f "$FAKE_STATE_DIR/pid" ]] && printf 'mResumedActivity com.yanjiyu.terminalspike/.MainActivity\n'
    ;;
esac
''',
        )
        self.env = os.environ.copy()
        self.env.update(
            {
                "ANDROID_SDK_ROOT": str(sdk),
                "FAKE_SDK_LOG": str(self.log),
                "FAKE_STATE_DIR": str(self.state),
                "FAKE_BOOTED": "1",
                "FAKE_QEMU": "1",
                "FAKE_INSTRUMENTATION_OUTPUT": (
                    "INSTRUMENTATION_STATUS: class=com.example.Test\n"
                    "INSTRUMENTATION_STATUS: test=works\n"
                    "INSTRUMENTATION_STATUS_CODE: 0\n"
                    "OK (1 test)"
                ),
                "TMPDIR": str(self.tmp),
                "TERMINAL_SPIKE_EMULATOR_BOOT_ATTEMPTS": "1",
                "TERMINAL_SPIKE_EMULATOR_BOOT_POLL_SECONDS": "0",
                "TERMINAL_SPIKE_INSTRUMENTATION_TIMEOUT_SECONDS": "5",
            }
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_tool(self, path: Path, body: str) -> None:
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def run_runner(self, *arguments: str, env: dict[str, str] | None = None):
        selected_env = (env or self.env).copy()
        if "FAKE_AVD" not in selected_env and "--api" in arguments and "--suite" in arguments:
            api = arguments[arguments.index("--api") + 1]
            suite = arguments[arguments.index("--suite") + 1]
            selected_env["FAKE_AVD"] = f"terminal-spike-api{api}-{suite}"
        return subprocess.run(
            [
                str(self.runner),
                *arguments,
                "--app-apk",
                str(self.app_apk),
                "--test-apk",
                str(self.test_apk),
                "--orchestrator-apk",
                str(self.orchestrator_apk),
                "--test-services-apk",
                str(self.test_services_apk),
                "--output-dir",
                str(self.output),
            ],
            cwd=self.root,
            env=selected_env,
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )

    def commands(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []

    def test_dry_run_prints_exact_matrix_identity_without_sdk_access(self) -> None:
        result = self.run_runner("--api", "26", "--suite", "full", "--dry-run")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("api=26 avd=terminal-spike-api26-full suite=full", result.stdout)
        self.assertIn("filter=<all>", result.stdout)
        self.assertIn("isolation=orchestrator", result.stdout)
        self.assertEqual([], self.commands())

    def test_rejects_unknown_api_and_suite_before_sdk_access(self) -> None:
        for args in (("--api", "25", "--suite", "full"), ("--api", "35", "--suite", "tiny")):
            with self.subTest(args=args):
                result = self.run_runner(*args)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.commands())

    def test_diagnostic_filter_is_exact_and_cannot_weaken_openssh(self) -> None:
        custom = "com.example.FirstTest,com.example.SecondTest#method"
        result = self.run_runner(
            "--api", "26", "--suite", "full", "--test-filter", custom, "--dry-run"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"filter={custom}", result.stdout)

        blocked = self.run_runner(
            "--api", "35", "--suite", "openssh", "--test-filter", "com.example.Test"
        )
        self.assertNotEqual(0, blocked.returncode)
        self.assertIn("fixed required test lists", blocked.stderr)

        blocked_lan = self.run_runner(
            "--api", "37", "--suite", "lan-openssh", "--test-filter", "com.example.Test"
        )
        self.assertNotEqual(0, blocked_lan.returncode)
        self.assertIn("fixed required test lists", blocked_lan.stderr)

    def test_success_targets_only_exact_emulator_and_cleans_temporary_avd(self) -> None:
        result = self.run_runner("--api", "35", "--suite", "full")
        self.assertEqual(0, result.returncode, result.stderr)
        emulator = [line for line in self.commands() if line.startswith("emulator ")]
        self.assertEqual(1, len(emulator))
        self.assertIn("-memory 4096", emulator[0])
        self.assertIn("-partition-size 4096", emulator[0])
        self.assertIn("-gpu swiftshader ", emulator[0])
        self.assertNotIn("-gpu software", emulator[0])
        self.assertNotIn("GuestAngle", emulator[0])
        adb = [line for line in self.commands() if line.startswith("adb ")]
        self.assertTrue(adb)
        self.assertTrue(all(line.startswith("adb -s emulator-5554 ") for line in adb))
        instrumentation = [line for line in adb if "ShellMain am instrument" in line]
        self.assertEqual(1, len(instrumentation))
        self.assertIn("targetInstrumentation", instrumentation[0])
        self.assertIn("TerminalSpikeTestRunner", instrumentation[0])
        self.assertIn("clearPackageData true", instrumentation[0])
        self.assertTrue((self.output / "TEST-api35-full.xml").is_file())
        self.assertNotIn("192.168.1.2", (self.output / "logcat-api35-full.txt").read_text())
        self.assertEqual([], list(self.tmp.glob("terminal-spike-avd.*")))

    def test_stuck_adb_and_emulator_shutdown_are_bounded_after_success(self) -> None:
        self.write_tool(
            self.root / "sdk/emulator/emulator",
            "exec python3 -c 'import os, signal, time; from pathlib import Path; "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            'Path(os.environ["FAKE_STATE_DIR"], "emulator.pid").write_text(str(os.getpid())); '
            "time.sleep(300)'\n",
        )
        env = self.env.copy()
        env["FAKE_STUCK_SHUTDOWN"] = "1"
        result = self.run_runner("--api", "35", "--suite", "full", env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.output / "TEST-api35-full.xml").is_file())
        self.assertEqual([], list(self.tmp.glob("terminal-spike-avd.*")))
        pid = int((self.state / "emulator.pid").read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_emulator_memory_override_is_bounded_and_forwarded(self) -> None:
        invalid_env = self.env.copy()
        invalid_env["TERMINAL_SPIKE_EMULATOR_MEMORY_MB"] = "1024"
        invalid = self.run_runner("--api", "35", "--suite", "full", env=invalid_env)
        self.assertNotEqual(0, invalid.returncode)
        self.assertIn("integer from 2048 through 8192", invalid.stderr)
        self.assertFalse(any(line.startswith("emulator ") for line in self.commands()))
        self.assertEqual([], list(self.tmp.glob("terminal-spike-avd.*")))

        override_env = self.env.copy()
        override_env["TERMINAL_SPIKE_EMULATOR_MEMORY_MB"] = "6144"
        result = self.run_runner("--api", "35", "--suite", "full", env=override_env)
        self.assertEqual(0, result.returncode, result.stderr)
        emulator = [line for line in self.commands() if line.startswith("emulator ")]
        self.assertEqual(1, len(emulator))
        self.assertIn("-memory 6144", emulator[0])

    def test_current_android_installer_receives_selected_image_license_acceptance(self) -> None:
        android_cli = self.root / "sdk/cmdline-tools/latest/bin/android"
        self.write_tool(
            android_cli,
            'read -r acceptance\n'
            '[[ "$acceptance" == "y" ]]\n'
            'printf "android %s\\n" "$*" >> "$FAKE_SDK_LOG"\n',
        )

        result = self.run_runner("--api", "32", "--suite", "boundary")

        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertIn(
            "android sdk install system-images;android-32;google_apis;x86_64",
            commands,
        )
        self.assertFalse(any(line.startswith("sdkmanager ") for line in commands))

    def test_sdkmanager_fallback_receives_selected_image_license_acceptance(self) -> None:
        self.write_tool(
            self.root / "sdk/cmdline-tools/latest/bin/sdkmanager",
            'read -r acceptance\n'
            '[[ "$acceptance" == "y" ]]\n'
            'printf "sdkmanager %s\\n" "$*" >> "$FAKE_SDK_LOG"\n',
        )
        result = self.run_runner("--api", "32", "--suite", "boundary")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("sdkmanager system-images;android-32;google_apis;x86_64", self.commands())

    def test_old_image_logcat_clear_failure_does_not_suppress_tests(self) -> None:
        env = self.env.copy()
        env["FAKE_LOGCAT_CLEAR_FAIL"] = "1"
        result = self.run_runner("--api", "26", "--suite", "boundary", env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.output / "TEST-api26-boundary.xml").is_file())

    def test_api37_required_suite_proves_external_permission_revocation_and_relaunch(self) -> None:
        result = self.run_runner("--api", "37", "--suite", "boundary")
        self.assertEqual(0, result.returncode, result.stderr)
        emulator = [line for line in self.commands() if line.startswith("emulator ")]
        self.assertIn("-gpu software -feature Vulkan -feature GuestAngle", emulator[0])
        evidence = self.output / "local-network-revocation-api37-boundary.txt"
        self.assertEqual(
            [
                "LOCAL_NETWORK_REVOCATION_E2E stage=grant status=pass",
                "LOCAL_NETWORK_REVOCATION_E2E stage=running status=pass",
                "LOCAL_NETWORK_REVOCATION_E2E stage=revoked status=pass",
                "LOCAL_NETWORK_REVOCATION_E2E stage=relaunched-denied status=pass",
            ],
            evidence.read_text(encoding="utf-8").splitlines(),
        )
        commands = self.commands()
        self.assertTrue(any("shell pm revoke" in line for line in commands))
        self.assertTrue(any("shell dumpsys activity activities" in line for line in commands))

    def test_boot_timeout_fails_without_install_and_still_cleans(self) -> None:
        env = self.env.copy()
        env["FAKE_BOOTED"] = "0"
        result = self.run_runner("--api", "26", "--suite", "boundary", env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Timed out", result.stderr)
        self.assertFalse(any(" install " in f" {line} " for line in self.commands()))
        self.assertEqual([], list(self.tmp.glob("terminal-spike-avd.*")))

    def test_non_emulator_identity_fails_before_install(self) -> None:
        env = self.env.copy()
        env["FAKE_QEMU"] = "0"
        result = self.run_runner("--api", "32", "--suite", "boundary", env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Refusing non-emulator", result.stderr)
        self.assertFalse(any(" install " in f" {line} " for line in self.commands()))

    def test_install_failure_is_bounded_and_never_starts_instrumentation(self) -> None:
        env = self.env.copy()
        env["FAKE_INSTALL_FAIL"] = "1"
        env["TERMINAL_SPIKE_INSTALL_ATTEMPTS"] = "2"
        env["TERMINAL_SPIKE_INSTALL_POLL_SECONDS"] = "0"
        result = self.run_runner("--api", "26", "--suite", "boundary", env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Failed to install APK after bounded retries", result.stderr)
        self.assertFalse(any("ShellMain am instrument" in line for line in self.commands()))

    def test_zero_test_and_failure_outputs_fail(self) -> None:
        for output in ("OK (0 tests)", "FAILURES!!!\nTests run: 1, Failures: 1"):
            with self.subTest(output=output):
                env = self.env.copy()
                env["FAKE_INSTRUMENTATION_OUTPUT"] = output
                result = self.run_runner("--api", "33", "--suite", "boundary", env=env)
                self.assertNotEqual(0, result.returncode)
                shutil.rmtree(self.output, ignore_errors=True)
                self.log.unlink(missing_ok=True)

    def test_full_suite_rejects_missing_or_unexpected_contract_membership(self) -> None:
        fixtures = (
            (
                "INSTRUMENTATION_STATUS: class=com.example.OtherTest\n"
                "INSTRUMENTATION_STATUS: test=works\n"
                "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)",
                "membership differs",
            ),
            (
                "INSTRUMENTATION_STATUS: class=com.example.Test\n"
                "INSTRUMENTATION_STATUS: test=works\n"
                "INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)",
                "skip identity differs",
            ),
        )
        for output, expected_error in fixtures:
            with self.subTest(expected_error=expected_error):
                env = self.env.copy()
                env["FAKE_INSTRUMENTATION_OUTPUT"] = output
                result = self.run_runner("--api", "35", "--suite", "full", env=env)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected_error, result.stderr)
                shutil.rmtree(self.output, ignore_errors=True)
                self.log.unlink(missing_ok=True)

    def test_explicit_diagnostic_filter_bypasses_release_suite_contract(self) -> None:
        env = self.env.copy()
        env["FAKE_INSTRUMENTATION_OUTPUT"] = (
            "INSTRUMENTATION_STATUS: class=com.example.DiagnosticTest\n"
            "INSTRUMENTATION_STATUS: test=focused\n"
            "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)"
        )
        result = self.run_runner(
            "--api",
            "35",
            "--suite",
            "full",
            "--test-filter",
            "com.example.DiagnosticTest#focused",
            env=env,
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_openssh_requires_key_and_all_named_tests_without_skips(self) -> None:
        missing = self.run_runner("--api", "35", "--suite", "openssh")
        self.assertNotEqual(0, missing.returncode)
        key = self.root / "fixture key"
        key.write_text("private fixture", encoding="utf-8")
        env = self.env.copy()
        env["FAKE_INSTRUMENTATION_OUTPUT"] = "OK (4 tests)"
        incomplete = self.run_runner(
            "--api", "35", "--suite", "openssh", "--private-key-file", str(key), env=env
        )
        self.assertNotEqual(0, incomplete.returncode)
        adb = [line for line in self.commands() if line.startswith("adb ")]
        self.assertTrue(any("reverse tcp:22222 tcp:22222" in line for line in adb))
        instrumentation = [line for line in adb if "ShellMain am instrument" in line]
        self.assertEqual(1, len(instrumentation))
        self.assertIn("sshE2eHost 127.0.0.1", instrumentation[0])
        self.assertIn("terminalSpikeSftpHost 127.0.0.1", instrumentation[0])

    def test_api37_lan_openssh_uses_private_gateway_and_requires_both_methods(self) -> None:
        wrong_api = self.run_runner("--api", "35", "--suite", "lan-openssh")
        self.assertNotEqual(0, wrong_api.returncode)
        self.assertIn("pinned to API 37", wrong_api.stderr)

        env = self.env.copy()
        env["FAKE_INSTRUMENTATION_OUTPUT"] = (
            "INSTRUMENTATION_STATUS: class=com.yanjiyu.terminalspike.connection.LocalNetworkSocketRuntimeTest\n"
            "INSTRUMENTATION_STATUS: test=deniedPermissionBlocksRawTcpAndProductionSshBeforeProtocolTraffic\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_STATUS: class=com.yanjiyu.terminalspike.connection.LocalNetworkSocketRuntimeTest\n"
            "INSTRUMENTATION_STATUS: test=grantedPermissionCompletesProductionSshAndSftpAgainstTheSameLanServer\n"
            "INSTRUMENTATION_STATUS_CODE: 0\nOK (2 tests)"
        )
        result = self.run_runner("--api", "37", "--suite", "lan-openssh", env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        instrumentation = [
            line for line in self.commands() if "ShellMain am instrument" in line
        ]
        self.assertEqual(1, len(instrumentation))
        self.assertIn("terminalSpikeLanHost 10.0.2.2", instrumentation[0])
        self.assertIn("terminalSpikeRunApi37Lan true", instrumentation[0])

    def test_clean_install_backup_uninstalls_and_restores_both_encrypted_archives(self) -> None:
        wrong_api = self.run_runner("--api", "36", "--suite", "backup-clean-install")
        self.assertNotEqual(0, wrong_api.returncode)
        self.assertIn("pinned to API 35", wrong_api.stderr)

        dry_run = self.run_runner(
            "--api", "35", "--suite", "backup-clean-install", "--dry-run"
        )
        self.assertEqual(0, dry_run.returncode, dry_run.stderr)
        self.assertIn("isolation=uninstall-reinstall", dry_run.stdout)

        result = self.run_runner("--api", "35", "--suite", "backup-clean-install")
        self.assertEqual(0, result.returncode, result.stderr)
        evidence = self.output / "backup-clean-install-api35.txt"
        self.assertTrue(evidence.is_file())
        self.assertIn("old_private_state=absent", evidence.read_text(encoding="utf-8"))
        self.assertTrue((self.output / "TEST-api35-backup-clean-install-export.xml").is_file())
        self.assertTrue((self.output / "TEST-api35-backup-clean-install-restore-standard.xml").is_file())
        self.assertTrue((self.output / "TEST-api35-backup-clean-install-restore-full.xml").is_file())
        commands = self.commands()
        self.assertTrue(any("uninstall com.yanjiyu.terminalspike" in line for line in commands))
        direct_stages = [
            line for line in commands
            if "shell am instrument" in line and "terminalSpikeBackupCleanInstallStage" in line
        ]
        self.assertEqual(3, len(direct_stages))
        self.assertTrue(any(" export " in f" {line} " for line in direct_stages))
        self.assertTrue(any(" restore-standard " in f" {line} " for line in direct_stages))
        self.assertTrue(any(" restore-full " in f" {line} " for line in direct_stages))
        self.assertEqual(
            2,
            sum(line.endswith("uninstall com.yanjiyu.terminalspike") for line in commands),
        )


if __name__ == "__main__":
    unittest.main()
