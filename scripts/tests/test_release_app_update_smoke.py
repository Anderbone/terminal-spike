from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


SOURCE_ROOT = Path(__file__).resolve().parents[2]


class ReleaseAppUpdateSmokeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike release smoke ")
        self.root = Path(self.temporary.name)
        self.fixture = self.root / "integration-tests/openssh"
        self.fixture.mkdir(parents=True)
        shutil.copy2(
            SOURCE_ROOT / "integration-tests/openssh/release-app-install-update-smoke.sh",
            self.fixture / "release-app-install-update-smoke.sh",
        )
        self.runner = self.fixture / "release-app-install-update-smoke.sh"
        self.runner.chmod(0o755)
        (self.fixture / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        self.write_tool(
            self.fixture / "smoke.sh",
            r'''printf 'smoke\n' >> "$FAKE_COMMAND_LOG"
[[ "${FAKE_SMOKE_FAIL:-0}" != "1" ]]
''',
        )
        self.bin = self.root / "fake tools"
        self.bin.mkdir()
        self.log = self.root / "commands.log"
        self.initial_apk = self.root / "initial app.apk"
        self.update_apk = self.root / "update app.apk"
        self.initial_apk.write_bytes(b"initial")
        self.update_apk.write_bytes(b"update")
        self.serial = "emulator-5560"
        self.write_tool(
            self.bin / "adb",
            r'''printf 'adb %s\n' "$*" >> "$FAKE_COMMAND_LOG"
case "$*" in
  "devices -l")
    printf 'List of devices attached\n%s\tdevice product:sdk model:sdk device:emu transport_id:9\n' "$FAKE_SERIAL"
    ;;
  *" get-state") printf 'device\n' ;;
  *"shell getprop ro.kernel.qemu") printf '%s\n' "${FAKE_QEMU:-1}" ;;
  *"emu avd name") printf '%s\nOK\n' "${FAKE_AVD:-terminal-spike-release-test}" ;;
  *"shell pm list packages"*) : ;;
  *" install "*) [[ "${FAKE_INSTALL_FAIL:-0}" != "1" ]] ;;
esac
''',
        )
        self.write_tool(
            self.bin / "apksigner",
            r'''printf 'apksigner %s\n' "$*" >> "$FAKE_COMMAND_LOG"
case "$*" in
  *"update app.apk") cert=${FAKE_UPDATE_CERT:-CERTIFICATE_A} ;;
  *) cert=CERTIFICATE_A ;;
esac
printf '%s certificate SHA-256 digest: %s\n' "${FAKE_SIGNER_PREFIX:-Signer #1}" "$cert"
''',
        )
        self.write_tool(
            self.bin / "aapt",
            r'''printf 'aapt %s\n' "$*" >> "$FAKE_COMMAND_LOG"
case "$*" in
  *"update app.apk") package=${FAKE_UPDATE_PACKAGE:-com.yanjiyu.terminalspike}; version=${FAKE_UPDATE_VERSION:-2} ;;
  *) package=${FAKE_INITIAL_PACKAGE:-com.yanjiyu.terminalspike}; version=${FAKE_INITIAL_VERSION:-1} ;;
esac
printf "package: name='%s' versionCode='%s' versionName='test'\n" "$package" "$version"
''',
        )
        self.write_tool(
            self.bin / "docker",
            r'''printf 'docker %s\n' "$*" >> "$FAKE_COMMAND_LOG"
''',
        )
        self.env = os.environ.copy()
        self.env.update(
            {
                "ADB": str(self.bin / "adb"),
                "APKSIGNER": str(self.bin / "apksigner"),
                "AAPT": str(self.bin / "aapt"),
                "PATH": f"{self.bin}:{self.env['PATH']}",
                "FAKE_COMMAND_LOG": str(self.log),
                "FAKE_SERIAL": self.serial,
                "JAVA_HOME": "/home/jiyu/.cache/terminal-spike-tools/jdk",
                "TMPDIR": str(self.root),
            }
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_tool(self, path: Path, body: str) -> None:
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def run_runner(self, *, env: dict[str, str] | None = None, serial: str | None = None):
        return subprocess.run(
            [str(self.runner), serial or self.serial, str(self.initial_apk), str(self.update_apk)],
            cwd=self.root,
            env=env or self.env,
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def commands(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []

    def test_unsafe_serial_is_rejected_before_any_external_command(self) -> None:
        result = self.run_runner(serial="emulator-5560;bad")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.commands())

    def test_physical_or_wrong_avd_target_is_rejected_before_fixture_and_install(self) -> None:
        for variable, value in (("FAKE_QEMU", "0"), ("FAKE_AVD", "personal-phone")):
            with self.subTest(variable=variable):
                env = self.env.copy()
                env[variable] = value
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                commands = self.commands()
                self.assertFalse(any(line.startswith("docker ") for line in commands))
                self.assertFalse(any(" install " in f" {line} " for line in commands))
                self.log.unlink(missing_ok=True)

    def test_mismatched_certificate_package_or_version_is_rejected_before_fixture(self) -> None:
        cases = (
            ("FAKE_UPDATE_CERT", "CERTIFICATE_B"),
            ("FAKE_UPDATE_PACKAGE", "example.not.the.app"),
            ("FAKE_INITIAL_VERSION", "3"),
        )
        for variable, value in cases:
            with self.subTest(variable=variable):
                env = self.env.copy()
                env[variable] = value
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(line.startswith("docker ") for line in self.commands()))
                self.log.unlink(missing_ok=True)

    def test_versioned_signer_output_preserves_certificate_continuity_check(self) -> None:
        for prefix in ("V2 Signer:", "V3 Signer:", "V3.1 Signer:"):
            with self.subTest(prefix=prefix):
                env = self.env.copy()
                env["FAKE_SIGNER_PREFIX"] = prefix
                env["FAKE_SMOKE_FAIL"] = "1"
                result = self.run_runner(env=env)
                self.assertIn("stage=preflight status=pass", result.stdout)
                self.assertIn("stage=fixture status=start", result.stdout)
                self.log.unlink(missing_ok=True)

                env["FAKE_UPDATE_CERT"] = "CERTIFICATE_B"
                result = self.run_runner(env=env)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("same signing certificate", result.stderr)
                self.assertFalse(any(line.startswith("docker ") for line in self.commands()))
                self.log.unlink(missing_ok=True)

    def test_fixture_start_failure_is_cleaned_without_installing(self) -> None:
        env = self.env.copy()
        env["FAKE_SMOKE_FAIL"] = "1"
        result = self.run_runner(env=env)
        self.assertNotEqual(0, result.returncode)
        commands = self.commands()
        self.assertEqual(1, commands.count("smoke"))
        self.assertEqual(2, sum(line.endswith(" down") for line in commands if line.startswith("docker ")))
        self.assertFalse(any(" install " in f" {line} " for line in commands))
        self.assertEqual([], list(self.root.glob("terminal-spike-release-smoke.*")))

    def test_install_failure_rechecks_exact_avd_and_always_stops_fixture(self) -> None:
        env = self.env.copy()
        env["FAKE_INSTALL_FAIL"] = "1"
        result = self.run_runner(env=env)
        self.assertNotEqual(0, result.returncode)
        commands = self.commands()
        adb = [line for line in commands if line.startswith("adb ")]
        scoped = [line for line in adb if line != "adb devices -l"]
        self.assertTrue(scoped)
        self.assertTrue(all(line.startswith(f"adb -s {self.serial} ") for line in scoped))
        self.assertGreaterEqual(adb.count("adb devices -l"), 3)
        self.assertEqual(2, sum(line.endswith(" down") for line in commands if line.startswith("docker ")))
        self.assertEqual([], list(self.root.glob("terminal-spike-release-smoke.*")))

    def test_current_ui_contract_uses_connections_and_opt_in_password_flow(self) -> None:
        text = self.runner.read_text(encoding="utf-8")
        self.assertIn('wait_for_ui_text "Connections"', text)
        self.assertIn('tap_node content-desc "Add host"', text)
        self.assertIn('fixture_host=10.0.2.2', text)
        self.assertIn('tap_node content-desc "Connect to $fixture_host"', text)
        self.assertNotIn('adb reverse ', text)
        self.assertIn('node_center text "Open shell"', text)
        self.assertIn("credential=prompted_after_update", text)
        self.assertNotIn('wait_for_ui_text "Workspace"', text)
        self.assertNotIn('tap_node text "New connection"', text)
        self.assertNotIn('tap_node text "Save host details"', text)


if __name__ == "__main__":
    unittest.main()
