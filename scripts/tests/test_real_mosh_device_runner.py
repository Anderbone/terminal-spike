from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


SOURCE_ROOT = Path(__file__).resolve().parents[2]


class RealMoshDeviceRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike real mosh runner ")
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in (
            "run-real-mosh-device-tests.sh",
            "instrumentation-output-to-junit.py",
            "redact-android-test-log.py",
            "assert-test-report.py",
        ):
            shutil.copy2(SOURCE_ROOT / "scripts" / name, scripts / name)
            (scripts / name).chmod(0o755)
        self.runner = scripts / "run-real-mosh-device-tests.sh"
        self.fixture = self.root / "integration-tests/openssh"
        self.fixture.mkdir(parents=True)
        (self.fixture / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        self.log = self.root / "commands.log"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.write_tool(
            self.root / "gradlew",
            r'''printf 'gradle %s\n' "$*" >> "$FAKE_COMMAND_LOG"
mkdir -p app/build/outputs/apk/debug app/build/outputs/apk/androidTest/debug
mkdir -p app/build/outputs/runtime-test-utils
: > app/build/outputs/apk/debug/app-debug.apk
: > app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
: > app/build/outputs/runtime-test-utils/orchestrator-1.6.1.apk
: > app/build/outputs/runtime-test-utils/test-services-1.6.0.apk
''',
        )
        self.write_tool(
            self.bin / "docker",
            r'''printf 'docker %s\n' "$*" >> "$FAKE_COMMAND_LOG"
''',
        )
        self.write_tool(
            self.fixture / "smoke.sh",
            r'''printf 'smoke bind=%s verify=%s\n' "$TERMINAL_SPIKE_SSH_BIND_ADDRESS" "$TERMINAL_SPIKE_SSH_VERIFY_HOST" >> "$FAKE_COMMAND_LOG"
mkdir -p "$(dirname "$0")/.state/client"
printf 'fixture-private-key' > "$(dirname "$0")/.state/client/id_ed25519"
docker compose up
''',
        )
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
  *"shell pm path "*) printf 'package:/data/app/%s/base.apk\n' "${@: -1}" ;;
  *"shell sha256sum "*)
    if [[ "${FAKE_APK_CHANGED:-0}" == 1 || ( "${FAKE_AFTER_TEST_CHANGED:-0}" == 1 && -f "$FAKE_COMMAND_LOG.instrumented" ) ]]; then
      printf 'changed  base.apk\n'
    else
      printf 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  base.apk\n'
    fi
    ;;
  *" install "*) [[ "${FAKE_INSTALL_FAIL:-0}" != "1" ]] ;;
  *"toybox nc -z"*) [[ "${FAKE_REACHABLE:-1}" == "1" ]] ;;
  *"ShellMain am instrument"*)
    touch "$FAKE_COMMAND_LOG.instrumented"
    [[ "${FAKE_INSTRUMENTATION_EXIT:-0}" == "0" ]] || exit "$FAKE_INSTRUMENTATION_EXIT"
    printf 'privateKeyBase64 fixture-private-key\npassword=terminal-spike-test-only\n'
    if [[ "$*" == *"#realServerCarriesInteractiveTerminalBytesThroughTheExtension"* ]]; then
      printf 'INSTRUMENTATION_STATUS: test=realServerCarriesInteractiveTerminalBytesThroughTheExtension\n'
      printf 'INSTRUMENTATION_STATUS_CODE: %s\n' "${FAKE_STATUS_CODE:-0}"
      printf 'OK (1 test)\n'
    else
      for test in \
        appSelectedTmuxLocalScrollAndReaderAnchorSurviveActivityRecreation \
        realServerCarriesInteractiveTerminalBytesThroughTheExtension \
        fourConcurrentSessionsResizeIndependentlyAndOneCloseDoesNotStopTheOthers \
        realWorkerDeathFailsOnlyItsSessionAndTheReleasedSlotIsReusable \
        realBrokerDeathFailsTheSessionThenClientRebindsForFreshTraffic; do
        [[ "$test" != "${FAKE_OMIT_TEST:-}" ]] && printf 'INSTRUMENTATION_STATUS: test=%s\n' "$test"
      done
      printf 'INSTRUMENTATION_STATUS_CODE: %s\n' "${FAKE_STATUS_CODE:-0}"
      printf 'OK (%s tests)\n' "${FAKE_PASSWORD_COUNT:-5}"
    fi
    ;;
  *"logcat -d") printf 'secret: hidden 192.168.5.9\n' ;;
esac
''',
        )
        self.serial = "adb-RZCW81JZ9CP-NpnzVa._adb-tls-connect._tcp"
        self.output = self.root / "results"
        self.tmp = self.root / "temporary"
        self.tmp.mkdir()
        self.env = os.environ.copy()
        self.env.update(
            {
                "PATH": f"{self.bin}:{self.env['PATH']}",
                "FAKE_COMMAND_LOG": str(self.log),
                "FAKE_SERIAL": self.serial,
                "TMPDIR": str(self.tmp),
                "TERMINAL_SPIKE_INSTALL_ATTEMPTS": "1",
                "TERMINAL_SPIKE_INSTALL_POLL_SECONDS": "0",
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

    def test_success_runs_fixed_matrix_on_only_the_verified_old_phone(self) -> None:
        result = self.run_runner()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("password_tests=5 private_key_tests=1 failures=0 skips=0", result.stdout)
        commands = self.commands()
        adb = [line for line in commands if line.startswith("adb ")]
        scoped = [line for line in adb if line != "adb devices -l"]
        self.assertTrue(scoped)
        self.assertTrue(all(line.startswith(f"adb -s {self.serial} ") for line in scoped))
        self.assertEqual(4, sum(" install " in f" {line} " for line in adb))
        instrumentation = [line for line in adb if "ShellMain am instrument" in line]
        self.assertEqual(2, len(instrumentation))
        self.assertIn("MoshRealEndToEndTest", instrumentation[0])
        self.assertNotIn("#realServer", instrumentation[0])
        self.assertIn("#realServerCarriesInteractiveTerminalBytesThroughTheExtension", instrumentation[1])
        self.assertGreaterEqual(adb.count("adb devices -l"), 8)
        self.assertFalse(any("mosh-extension-debug.apk" in line for line in adb))
        self.assertLess(commands.index("docker compose --project-directory " + str(self.fixture) + " -f " + str(self.fixture / "compose.yaml") + " down"), commands.index("smoke bind=192.168.5.10 verify=192.168.5.10"))
        self.assertEqual(2, sum(line.endswith(" down") for line in commands if line.startswith("docker ")))
        self.assertTrue((self.output / "TEST-real-mosh-password.xml").is_file())
        self.assertTrue((self.output / "TEST-real-mosh-private-key.xml").is_file())
        public_text = "\n".join(path.read_text() for path in self.output.glob("*.txt"))
        self.assertNotIn("fixture-private-key", public_text)
        self.assertNotIn("terminal-spike-test-only", public_text)
        self.assertNotIn("192.168.5.", public_text)
        self.assertEqual([], list(self.tmp.glob("terminal-spike-real-mosh.*")))

    def test_rejects_apk_replacement_before_or_during_the_test(self) -> None:
        for variable in ("FAKE_APK_CHANGED", "FAKE_AFTER_TEST_CHANGED"):
            with self.subTest(variable=variable):
                env = self.env.copy()
                env[variable] = "1"
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Installed APK changed", result.stderr)
                self.assertEqual([], list(self.output.glob("TEST-*.xml")))
                self.log.unlink(missing_ok=True)
                Path(str(self.log) + ".instrumented").unlink(missing_ok=True)
                shutil.rmtree(self.output, ignore_errors=True)

    def test_rejects_unsafe_arguments_before_external_actions(self) -> None:
        cases = (
            ["--serial", "bad serial", "--host-address", "192.168.5.10", "--trusted-lan"],
            ["--serial", self.serial, "--host-address", "999.1.2.3", "--trusted-lan"],
            ["--serial", self.serial, "--host-address", "127.0.0.1"],
        )
        for arguments in cases:
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    [str(self.runner), *arguments],
                    cwd=self.root,
                    env=self.env,
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], self.commands())

    def test_fold_or_inconsistent_model_is_blocked_before_build_install_or_fixture(self) -> None:
        for variable, model in (("FAKE_INVENTORY_MODEL", "SM_F976B"), ("FAKE_PROP_MODEL", "SM-F976B")):
            with self.subTest(variable=variable):
                env = self.env.copy()
                env[variable] = model
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                commands = self.commands()
                self.assertFalse(any(line.startswith("gradle ") for line in commands))
                self.assertFalse(any(" install " in f" {line} " for line in commands))
                self.assertFalse(any(line.startswith("smoke ") for line in commands))
                self.log.unlink(missing_ok=True)

    def test_install_or_instrumentation_failure_still_stops_fixture_and_cleans_secrets(self) -> None:
        for variable, value in (("FAKE_INSTALL_FAIL", "1"), ("FAKE_INSTRUMENTATION_EXIT", "7")):
            with self.subTest(variable=variable):
                env = self.env.copy()
                env[variable] = value
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                commands = self.commands()
                self.assertEqual(2, sum(line.endswith(" down") for line in commands if line.startswith("docker ")))
                self.assertEqual([], list(self.tmp.glob("terminal-spike-real-mosh.*")))
                self.log.unlink(missing_ok=True)
                shutil.rmtree(self.output, ignore_errors=True)

    def test_skip_wrong_count_and_missing_named_test_all_fail(self) -> None:
        for variable, value in (
            ("FAKE_STATUS_CODE", "-3"),
            ("FAKE_PASSWORD_COUNT", "4"),
            ("FAKE_OMIT_TEST", "realBrokerDeathFailsTheSessionThenClientRebindsForFreshTraffic"),
        ):
            with self.subTest(variable=variable):
                env = self.env.copy()
                env[variable] = value
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, sum("ShellMain am instrument" in line for line in self.commands()))
                self.assertEqual(2, sum(line.endswith(" down") for line in self.commands() if line.startswith("docker ")))
                self.log.unlink(missing_ok=True)
                shutil.rmtree(self.output, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
