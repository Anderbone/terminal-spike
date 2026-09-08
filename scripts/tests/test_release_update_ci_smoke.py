from pathlib import Path
import os
import shutil
import signal
import subprocess
import tempfile
import time
import unittest


SOURCE_ROOT = Path(__file__).resolve().parents[2]
SIGNING_NAMES = (
    "TERMINAL_SPIKE_RELEASE_STORE_FILE",
    "TERMINAL_SPIKE_RELEASE_STORE_PASSWORD",
    "TERMINAL_SPIKE_RELEASE_KEY_ALIAS",
    "TERMINAL_SPIKE_RELEASE_KEY_PASSWORD",
)


class ReleaseUpdateCiSmokeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike release ci ")
        self.root = Path(self.temporary.name)
        self.scripts = self.root / "scripts"
        self.scripts.mkdir()
        shutil.copy2(
            SOURCE_ROOT / "scripts/run-release-update-ci-smoke.py",
            self.scripts / "run-release-update-ci-smoke.py",
        )
        self.runner = self.scripts / "run-release-update-ci-smoke.py"
        self.runner.chmod(0o755)
        self.fixture = self.root / "integration-tests/openssh"
        self.fixture.mkdir(parents=True)
        (self.fixture / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        self.bin = self.root / "fake-bin"
        self.bin.mkdir()
        self.state = self.root / "state"
        self.state.mkdir()
        self.log = self.root / "commands.log"
        self.output = self.root / "output"

        sdk = self.root / "sdk"
        (sdk / "cmdline-tools/latest/bin").mkdir(parents=True)
        (sdk / "platform-tools").mkdir()
        (sdk / "emulator").mkdir()
        (sdk / "build-tools/36.0.0").mkdir(parents=True)
        jdk = self.root / "jdk/bin"
        jdk.mkdir(parents=True)

        self.write_tool(
            sdk / "cmdline-tools/latest/bin/android",
            "log('android ' + ' '.join(sys.argv[1:]))\n",
        )
        self.write_tool(
            sdk / "cmdline-tools/latest/bin/avdmanager",
            """
log('avdmanager ' + ' '.join(sys.argv[1:]))
name = sys.argv[sys.argv.index('--name') + 1]
avd = Path(os.environ['ANDROID_AVD_HOME']) / f'{name}.avd'
avd.mkdir(parents=True)
(avd / 'config.ini').write_text('', encoding='utf-8')
""",
        )
        self.write_tool(
            sdk / "emulator/emulator",
            """
log('emulator ' + ' '.join(sys.argv[1:]))
while not (state / 'emulator-stop').exists():
    time.sleep(0.01)
""",
        )
        self.write_tool(sdk / "platform-tools/adb", self.fake_adb_body())
        self.write_tool(sdk / "build-tools/36.0.0/apksigner", self.fake_apksigner_body())
        self.write_tool(jdk / "keytool", self.fake_keytool_body())
        self.write_tool(jdk / "jarsigner", self.fake_jarsigner_body())
        self.write_tool(self.root / "gradlew", self.fake_gradle_body())
        self.write_tool(self.fixture / "release-app-install-update-smoke.sh", self.fake_smoke_body())
        self.write_tool(self.bin / "docker", "log('docker ' + ' '.join(sys.argv[1:]))\n")

        self.env = os.environ.copy()
        for name in SIGNING_NAMES:
            self.env.pop(name, None)
        self.env.update(
            {
                "ANDROID_SDK_ROOT": str(sdk),
                "JAVA_HOME": str(self.root / "jdk"),
                "PATH": f"{self.bin}:{self.env['PATH']}",
                "FAKE_COMMAND_LOG": str(self.log),
                "FAKE_STATE_DIR": str(self.state),
                "TMPDIR": str(self.root),
                "TERMINAL_SPIKE_EMULATOR_BOOT_ATTEMPTS": "2",
                "TERMINAL_SPIKE_EMULATOR_BOOT_POLL_SECONDS": "0",
                "TERMINAL_SPIKE_PACKAGE_MANAGER_ATTEMPTS": "2",
                "TERMINAL_SPIKE_PACKAGE_MANAGER_POLL_SECONDS": "0",
            }
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_tool(self, path: Path, body: str) -> None:
        prelude = """#!/usr/bin/env python3
import os
from pathlib import Path
import sys
import time
state = Path(os.environ['FAKE_STATE_DIR'])
def log(value):
    with Path(os.environ['FAKE_COMMAND_LOG']).open('a', encoding='utf-8') as stream:
        stream.write(value + '\\n')
"""
        path.write_text(prelude + body, encoding="utf-8")
        path.chmod(0o755)

    @staticmethod
    def fake_adb_body() -> str:
        return r'''
args = sys.argv[1:]
joined = ' '.join(args)
log('adb ' + joined)
if args == ['devices', '-l']:
    print('List of devices attached')
    print('emulator-5554\tdevice product:sdk model:sdk device:emu transport_id:8')
elif joined.endswith('shell getprop sys.boot_completed'):
    print(os.environ.get('FAKE_BOOTED', '1'))
elif joined.endswith('shell getprop ro.kernel.qemu'):
    print(os.environ.get('FAKE_QEMU', '1'))
elif joined.endswith('emu avd name'):
    print(os.environ.get('FAKE_AVD', 'terminal-spike-release-test'))
    print('OK')
elif joined.endswith('shell pm path android'):
    print('package:/system/framework/framework-res.apk')
elif joined.endswith('emu kill'):
    (state / 'emulator-stop').touch()
'''

    @staticmethod
    def fake_apksigner_body() -> str:
        return r'''
log('apksigner ' + ' '.join(sys.argv[1:]))
if os.environ.get('FAKE_UNSIGNED_APK') == '1':
    raise SystemExit(1)
print('Signer #1 certificate SHA-256 digest: ' + os.environ.get('FAKE_APK_CERT', 'aabb'))
'''

    @staticmethod
    def fake_keytool_body() -> str:
        return r'''
log('keytool ' + ' '.join(sys.argv[1:]))
if '-genkeypair' in sys.argv:
    if os.environ.get('FAKE_KEYTOOL_FAIL') == '1':
        raise SystemExit(1)
    path = Path(sys.argv[sys.argv.index('-keystore') + 1])
    path.write_bytes(b'ephemeral key')
elif '-printcert' in sys.argv:
    print('SHA256: ' + os.environ.get('FAKE_AAB_CERT', 'AA:BB'))
else:
    print('SHA256: AA:BB')
'''

    @staticmethod
    def fake_jarsigner_body() -> str:
        return r'''
log('jarsigner ' + ' '.join(sys.argv[1:]))
if os.environ.get('FAKE_UNSIGNED_AAB') == '1':
    raise SystemExit(1)
print('jar verified.')
'''

    @staticmethod
    def fake_gradle_body() -> str:
        return r'''
log('gradlew ' + ' '.join(sys.argv[1:]))
values = [os.environ.get(name, '') for name in (
    'TERMINAL_SPIKE_RELEASE_STORE_FILE',
    'TERMINAL_SPIKE_RELEASE_STORE_PASSWORD',
    'TERMINAL_SPIKE_RELEASE_KEY_ALIAS',
    'TERMINAL_SPIKE_RELEASE_KEY_PASSWORD',
)]
if not all(values):
    raise SystemExit(9)
if os.environ.get('FAKE_BUILD_FAIL') == '1':
    raise SystemExit(1)
root = Path(__file__).resolve().parent
apk = root / 'app/build/outputs/apk/release/app-release.apk'
aab = root / 'app/build/outputs/bundle/release/app-release.aab'
apk.parent.mkdir(parents=True, exist_ok=True)
apk.write_bytes(b'signed apk')
if os.environ.get('FAKE_MISSING_AAB') != '1':
    aab.parent.mkdir(parents=True, exist_ok=True)
    aab.write_bytes(b'signed aab')
'''

    @staticmethod
    def fake_smoke_body() -> str:
        return r'''
log('release-smoke ' + ' '.join(sys.argv[1:]))
if os.environ.get('FAKE_SMOKE_FAIL') == '1':
    print('RELEASE_APP_UPDATE_SMOKE stage=fixture status=start')
    print('RELEASE_APP_UPDATE_SMOKE stage=secret-value status=pass')
    print('private credential=secret-value', file=sys.stderr)
    raise SystemExit(1)
values = [
    'ssh_before=pass',
    'ssh_after=pass',
    'data=preserved',
    'credential=prompted_after_update',
    'extension=absent',
]
if os.environ.get('FAKE_SMOKE_INCOMPLETE') == '1':
    values.pop()
print('RELEASE_APP_UPDATE_SMOKE ' + ' '.join(values))
'''

    def run_runner(
        self,
        *,
        env: dict[str, str] | None = None,
        extra: tuple[str, ...] = (),
        timeout: float = 15,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                str(self.runner),
                "--output-dir",
                str(self.output),
                *extra,
            ],
            cwd=self.root,
            env=env or self.env,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    def fresh_env(self, **values: str) -> dict[str, str]:
        environment = self.env.copy()
        environment.update(values)
        return environment

    def commands(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []

    def reset(self) -> None:
        shutil.rmtree(self.output, ignore_errors=True)
        shutil.rmtree(self.state, ignore_errors=True)
        self.state.mkdir()
        shutil.rmtree(self.root / "app", ignore_errors=True)
        self.log.unlink(missing_ok=True)

    def assert_clean(self) -> None:
        self.assertEqual([], list(self.root.glob("terminal-spike-release-ci.*")))
        self.assertFalse((self.root / "app/build/outputs/apk/release").exists())
        self.assertFalse((self.root / "app/build/outputs/bundle/release").exists())
        self.assertTrue(any(line.startswith("docker compose") for line in self.commands()))

    def test_dry_run_is_status_only_and_runs_no_tools(self) -> None:
        result = self.run_runner(extra=("--dry-run",))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("signing=ephemeral", result.stdout)
        self.assertIn("outputs=status-only", result.stdout)
        self.assertIn("migration=same-version-reinstall", result.stdout)
        self.assertEqual([], self.commands())

    def test_success_verifies_both_artifacts_runs_smoke_and_retains_only_status(self) -> None:
        result = self.run_runner()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_clean()
        commands = self.commands()
        self.assertTrue(any("--dependency-verification=strict" in line for line in commands))
        self.assertTrue(any(line.startswith("apksigner verify --print-certs") for line in commands))
        self.assertTrue(any(line.startswith("jarsigner -verify") for line in commands))
        self.assertTrue(any(line.startswith("release-smoke emulator-5554") for line in commands))
        evidence = (self.output / "release-smoke-result.txt").read_text(encoding="utf-8")
        self.assertIn("stage=update_ssh status=pass", evidence)
        self.assertIn("stage=complete status=pass migration=same-version-reinstall signing=ephemeral", evidence)
        for forbidden in (
            "terminal-spike-test-only",
            "127.0.0.1",
            "PRIVATE KEY",
            "app-release-acceptance.apk",
        ):
            self.assertNotIn(forbidden, result.stdout + result.stderr + evidence)

    def test_preview_build_tools_are_not_selected(self) -> None:
        preview = self.root / "sdk/build-tools/99.0.0-rc1/apksigner"
        preview.parent.mkdir(parents=True)
        self.write_tool(preview, "log('preview-apksigner')\nsys.exit(91)\n")

        result = self.run_runner()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("preview-apksigner", self.commands())

    def test_rejects_inherited_partial_signing_and_key_generation_failure(self) -> None:
        inherited = self.run_runner(
            env=self.fresh_env(TERMINAL_SPIKE_RELEASE_STORE_PASSWORD="do-not-use")
        )
        self.assertNotEqual(0, inherited.returncode)
        self.assertIn("must be unset", inherited.stderr)
        self.assertFalse(any(line.startswith("keytool ") for line in self.commands()))
        self.reset()

        key_failure = self.run_runner(env=self.fresh_env(FAKE_KEYTOOL_FAIL="1"))
        self.assertNotEqual(0, key_failure.returncode)
        self.assertIn("key generation failed", key_failure.stderr)
        self.assert_clean()

    def test_rejects_build_failure_or_missing_artifact_and_cleans(self) -> None:
        cases = (("FAKE_BUILD_FAIL", "signed release build failed"), ("FAKE_MISSING_AAB", "APK or AAB is missing"))
        for variable, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: "1"}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assert_clean()
                self.reset()

    def test_rejects_unsigned_or_mismatched_apk_and_aab(self) -> None:
        cases = (
            ("FAKE_UNSIGNED_APK", "1", "APK signature verification failed"),
            ("FAKE_UNSIGNED_AAB", "1", "AAB signature verification failed"),
            ("FAKE_APK_CERT", "ccdd", "signer does not match"),
            ("FAKE_AAB_CERT", "CC:DD", "signer does not match"),
        )
        for variable, value, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: value}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assert_clean()
                self.reset()

    def test_rejects_boot_identity_and_smoke_failures(self) -> None:
        cases = (
            ("FAKE_BOOTED", "0", "timed out waiting for emulator boot"),
            ("FAKE_QEMU", "0", "refuses a non-emulator"),
            ("FAKE_AVD", "wrong-avd", "unexpected AVD name"),
            ("FAKE_SMOKE_FAIL", "1", "black-box release update SSH smoke failed"),
            ("FAKE_SMOKE_INCOMPLETE", "1", "missing a required assertion"),
        )
        for variable, value, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: value}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assert_clean()
                self.reset()

    def test_failed_smoke_retains_only_known_stage_markers(self) -> None:
        result = self.run_runner(env=self.fresh_env(FAKE_SMOKE_FAIL="1"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("stage=black_box_fixture status=start", result.stdout)
        self.assertNotIn("secret-value", result.stdout + result.stderr)
        self.assert_clean()

    def test_invalid_port_is_rejected_before_tools(self) -> None:
        result = self.run_runner(extra=("--port", "5555"))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.commands())

    def test_signal_exit_removes_emulator_signing_and_release_outputs(self) -> None:
        environment = self.fresh_env(
            FAKE_BOOTED="0",
            TERMINAL_SPIKE_EMULATOR_BOOT_ATTEMPTS="1000",
            TERMINAL_SPIKE_EMULATOR_BOOT_POLL_SECONDS="0.05",
        )
        process = subprocess.Popen(
            [str(self.runner), "--output-dir", str(self.output)],
            cwd=self.root,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        for _ in range(100):
            if self.log.exists() and "emulator " in self.log.read_text(encoding="utf-8"):
                break
            time.sleep(0.02)
        process.send_signal(signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(130, process.returncode, stdout + stderr)
        self.assertIn("interrupted", stderr)
        self.assert_clean()

    def test_ci_job_retains_status_only_and_never_uploads_signing_or_apks(self) -> None:
        workflow = (SOURCE_ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
        job = workflow.split("  release-update-e2e:", 1)[1]
        self.assertIn("scripts/run-release-update-ci-smoke.py", job)
        self.assertIn("--output-dir build/ci-release-smoke-result", job)
        self.assertIn(
            "path: build/ci-release-smoke-result/release-smoke-result.txt",
            job,
        )
        self.assertNotIn("app-release.apk", job)
        self.assertNotIn("app-release.aab", job)
        self.assertNotIn(".p12", job)
        self.assertNotIn("acceptance key", job.lower())


if __name__ == "__main__":
    unittest.main()
