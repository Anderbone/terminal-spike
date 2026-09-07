from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


SOURCE_ROOT = Path(__file__).resolve().parents[2]


class AppLifecycleE2eTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike lifecycle ")
        self.root = Path(self.temporary.name)
        self.scripts = self.root / "scripts"
        self.scripts.mkdir()
        for name in ("run-app-lifecycle-e2e.py", "redact-android-test-log.py"):
            shutil.copy2(SOURCE_ROOT / "scripts" / name, self.scripts / name)
            (self.scripts / name).chmod(0o755)
        self.runner = self.scripts / "run-app-lifecycle-e2e.py"
        self.fixture = self.root / "integration-tests/openssh"
        self.fixture.mkdir(parents=True)
        (self.fixture / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        self.bin = self.root / "fake-bin"
        self.bin.mkdir()
        self.state = self.root / "state"
        self.state.mkdir()
        self.log = self.root / "commands.log"
        self.app_apk = self.root / "app-debug.apk"
        self.app_apk.write_bytes(b"debug app")
        self.output = self.root / "output"

        sdk = self.root / "sdk"
        (sdk / "cmdline-tools/latest/bin").mkdir(parents=True)
        (sdk / "platform-tools").mkdir()
        (sdk / "emulator").mkdir()
        self.write_python_tool(
            sdk / "cmdline-tools/latest/bin/android",
            "log('android ' + ' '.join(sys.argv[1:]))\n",
        )
        self.write_python_tool(
            sdk / "cmdline-tools/latest/bin/avdmanager",
            """
log('avdmanager ' + ' '.join(sys.argv[1:]))
name = sys.argv[sys.argv.index('--name') + 1]
avd = Path(os.environ['ANDROID_AVD_HOME']) / f'{name}.avd'
avd.mkdir(parents=True)
(avd / 'config.ini').write_text('', encoding='utf-8')
""",
        )
        self.write_python_tool(
            sdk / "emulator/emulator",
            """
log('emulator ' + ' '.join(sys.argv[1:]))
while not (state / 'emulator-stop').exists():
    time.sleep(0.01)
""",
        )
        self.write_python_tool(sdk / "platform-tools/adb", self.fake_adb_body())
        self.write_python_tool(
            self.bin / "docker",
            "log('docker ' + ' '.join(sys.argv[1:]))\n",
        )
        self.write_python_tool(
            self.fixture / "smoke.sh",
            """
log('smoke')
if os.environ.get('FAKE_FIXTURE_FAIL') == '1':
    raise SystemExit(1)
""",
        )
        self.env = os.environ.copy()
        self.env.update(
            {
                "ADB": str(sdk / "platform-tools/adb"),
                "ANDROID_SDK_ROOT": str(sdk),
                "PATH": f"{self.bin}:{self.env['PATH']}",
                "FAKE_COMMAND_LOG": str(self.log),
                "FAKE_STATE_DIR": str(self.state),
                "FAKE_SERIAL": "emulator-5554",
                "TMPDIR": str(self.root),
                "TERMINAL_SPIKE_EMULATOR_BOOT_ATTEMPTS": "2",
                "TERMINAL_SPIKE_EMULATOR_BOOT_POLL_SECONDS": "0",
                "TERMINAL_SPIKE_LIFECYCLE_POLL_ATTEMPTS": "2",
                "TERMINAL_SPIKE_LIFECYCLE_POLL_SECONDS": "0",
                "TERMINAL_SPIKE_INSTALL_ATTEMPTS": "1",
                "TERMINAL_SPIKE_INSTALL_POLL_SECONDS": "0",
            }
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_python_tool(self, path: Path, body: str) -> None:
        prelude = """#!/usr/bin/env python3
import os
from pathlib import Path
import sqlite3
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
        return r"""
args = sys.argv[1:]
joined = ' '.join(args)
log('adb ' + joined)
serial = os.environ.get('FAKE_SERIAL', 'emulator-5554')
package = 'com.yanjiyu.terminalspike'
if args == ['devices', '-l']:
    print('List of devices attached')
    print(f'{serial}\tdevice product:sdk model:sdk device:emu transport_id:9')
elif args[-1:] == ['get-state']:
    print('device')
elif joined.endswith('shell getprop sys.boot_completed'):
    print(os.environ.get('FAKE_BOOTED', '1'))
elif joined.endswith('shell getprop ro.kernel.qemu'):
    print(os.environ.get('FAKE_QEMU', '1'))
elif joined.endswith('shell getprop ro.product.model'):
    print(os.environ.get('FAKE_MODEL', 'SM-S911B'))
elif joined.endswith('emu avd name'):
    print(os.environ.get('FAKE_AVD', 'terminal-spike-api35-lifecycle'))
    print('OK')
elif joined.endswith('emu kill'):
    (state / 'emulator-stop').touch()
elif joined.endswith('shell pm path android'):
    print('package:/system/framework/framework-res.apk')
elif ' install ' in f' {joined} ':
    if os.environ.get('FAKE_INSTALL_FAIL') == '1':
        raise SystemExit(1)
elif f'shell am start -W -n {package}/.MainActivity' in joined:
    pid = state / 'pid'
    if not pid.exists():
        pid.write_text('222\n' if (state / 'killed').exists() else '111\n')
    print('Status: ok')
elif joined.endswith(f'shell pidof {package}'):
    pid = state / 'pid'
    if pid.exists():
        print(pid.read_text().strip())
elif f'shell run-as {package} cat /proc/' in joined:
    sys.stdout.write(os.environ.get('FAKE_PID_OWNER', package) + '\0')
elif f'shell run-as {package} kill -9' in joined:
    if os.environ.get('FAKE_KILL_FAIL') == '1':
        raise SystemExit(1)
    if os.environ.get('FAKE_DEATH_STUCK') != '1':
        (state / 'pid').unlink(missing_ok=True)
        (state / 'killed').touch()
elif f'shell dumpsys activity services {package}' in joined:
    if (state / 'pid').exists() and os.environ.get('FAKE_SERVICE_MISSING') != '1':
        print(f'ServiceRecord {package}.SessionForegroundService')
elif joined.endswith('shell dumpsys notification --noredact'):
    if (state / 'pid').exists() or os.environ.get('FAKE_STALE_NOTIFICATION') == '1':
        print(f'NotificationRecord(0x1: pkg={package} user=UserHandle{{0}} id=2001 tag=null)')
elif joined.endswith('shell dumpsys deviceidle get deep'):
    value = (state / 'idle').read_text().strip() if (state / 'idle').exists() else 'ACTIVE'
    print(value)
elif joined.endswith('shell dumpsys deviceidle force-idle'):
    (state / 'idle').write_text('ACTIVE\n' if os.environ.get('FAKE_IDLE_ENTRY_FAIL') == '1' else 'IDLE\n')
elif joined.endswith('shell dumpsys deviceidle unforce'):
    (state / 'idle').write_text('ACTIVE\n')
elif f'shell am get-standby-bucket {package}' in joined:
    value = (state / 'bucket').read_text().strip() if (state / 'bucket').exists() else '10'
    print(value)
elif f'shell am set-standby-bucket {package}' in joined:
    value = (
        '10'
        if args[-1] == 'restricted' and os.environ.get('FAKE_STANDBY_ENTRY_FAIL') == '1'
        else ('45' if args[-1] == 'restricted' else args[-1])
    )
    (state / 'bucket').write_text(value + '\n')
elif joined.endswith('shell cmd netpolicy get restrict-background'):
    value = (state / 'data-saver').read_text().strip() if (state / 'data-saver').exists() else 'false'
    print('Restrict background status: ' + ('enabled' if value == 'true' else 'disabled'))
elif 'shell cmd netpolicy set restrict-background' in joined:
    value = 'false' if args[-1] == 'true' and os.environ.get('FAKE_DATA_SAVER_ENTRY_FAIL') == '1' else args[-1]
    (state / 'data-saver').write_text(value + '\n')
elif f'shell dumpsys activity exit-info {package}' in joined:
    print('ApplicationExitInfo reason=2 (SIGNALED)')
elif 'shell logcat -d' in joined:
    print('lifecycle log is privacy safe')
elif joined.endswith(f'exec-out run-as {package} cat databases/terminal-spike.db'):
    database_path = state / 'terminal-spike.db'
    database_path.unlink(missing_ok=True)
    with sqlite3.connect(database_path) as database:
        database.execute('CREATE TABLE host_profiles (id TEXT)')
        database.execute('CREATE TABLE recent_sessions (id TEXT)')
        database.execute('CREATE TABLE ssh_credentials (id TEXT, secret_id TEXT)')
        database.execute("INSERT INTO host_profiles VALUES ('host')")
        if os.environ.get('FAKE_RECENT_MISSING') != '1':
            database.execute("INSERT INTO recent_sessions VALUES ('recent')")
        secret_id = 'secret' if os.environ.get('FAKE_STORED_SECRET') == '1' else None
        database.execute('INSERT INTO ssh_credentials VALUES (?, ?)', ('credential', secret_id))
    sys.stdout.buffer.write(database_path.read_bytes())
elif f'exec-out run-as {package} cat databases/terminal-spike.db-' in joined:
    raise SystemExit(1)
elif joined.endswith('exec-out cat /sdcard/terminal-spike-window.xml'):
    markers = (state / 'markers').read_text().splitlines() if (state / 'markers').exists() else []
    if os.environ.get('FAKE_RECONNECT_MARKER_MISSING') == '1':
        markers = [marker for marker in markers if marker != 'LIFECYCLE_RECONNECTED_42']
    marker_nodes = ''.join(
        f'<node text="{marker}" class="android.widget.TextView" bounds="[0,1000][500,1100]" />'
        for marker in markers
    )
    print(f'''<?xml version="1.0" encoding="UTF-8"?>
<hierarchy>
<node text="Connections" class="android.widget.TextView" bounds="[0,0][100,100]" />
<node text="Terminal" class="android.widget.TextView" bounds="[100,0][200,100]" />
<node text="Add host" class="android.widget.TextView" bounds="[0,100][100,200]" />
<node text="" content-desc="Add host" class="android.view.View" bounds="[0,100][100,200]" />
<node text="" content-desc="Connect to 127.0.0.1" class="android.view.View" bounds="[0,200][100,300]" />
<node text="Password" class="android.widget.TextView" bounds="[0,300][100,400]" />
<node text="Save" class="android.widget.TextView" bounds="[0,400][100,500]" />
<node text="Connect" class="android.widget.TextView" bounds="[0,500][100,600]" />
<node text="Trust once" class="android.widget.TextView" bounds="[0,600][100,700]" />
<node text="Trust and save" class="android.widget.TextView" bounds="[0,700][100,800]" />
<node text="No terminal session is open." class="android.widget.TextView" bounds="[0,800][200,900]" />
{marker_nodes}
<node text="" content-desc="Native terminal renderer" class="android.widget.EditText" bounds="[0,1100][500,1400]" />
<node text="" class="android.widget.EditText" bounds="[0,1400][100,1500]" />
<node text="" class="android.widget.EditText" bounds="[0,1500][100,1600]" />
<node text="" class="android.widget.EditText" bounds="[0,1600][100,1700]" />
</hierarchy>''' + '')
elif 'shell input text printf%s' in joined:
    marker = joined.split('printf%s', 1)[1]
    with (state / 'markers').open('a', encoding='utf-8') as stream:
        stream.write(marker + '\n')
"""

    def run_runner(
        self,
        *,
        env: dict[str, str] | None = None,
        physical_serial: str | None = None,
        dry_run: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        arguments = [
            str(self.runner),
            "--app-apk",
            str(self.app_apk),
            "--output-dir",
            str(self.output),
        ]
        if physical_serial is not None:
            arguments.extend(("--physical-serial", physical_serial))
        if dry_run:
            arguments.append("--dry-run")
        return subprocess.run(
            arguments,
            cwd=self.root,
            env=env or self.env,
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )

    def commands(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []

    def fresh_env(self, **values: str) -> dict[str, str]:
        environment = self.env.copy()
        environment.update(values)
        return environment

    def reset_run_state(self) -> None:
        shutil.rmtree(self.output, ignore_errors=True)
        self.log.unlink(missing_ok=True)
        shutil.rmtree(self.state, ignore_errors=True)
        self.state.mkdir()

    def assert_cleanup_ran(self) -> None:
        self.assertTrue(
            any(
                line.startswith("docker compose") and " down " in f" {line} "
                for line in self.commands()
            )
        )
        self.assertEqual([], list(self.root.glob("terminal-spike-lifecycle-avd.*")))

    def test_dry_run_declares_exact_external_process_and_pressure_plan(self) -> None:
        result = self.run_runner(dry_run=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("mode=emulator", result.stdout)
        self.assertIn("process_death=exact-pid", result.stdout)
        self.assertIn("pressure=background,idle,standby,data-saver", result.stdout)
        self.assertEqual([], self.commands())

    def test_success_uses_exact_kill_restores_policy_and_cleans_everything(self) -> None:
        result = self.run_runner()
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        self.assertTrue(any("shell run-as com.yanjiyu.terminalspike kill -9 111" in line for line in commands))
        self.assertFalse(any("force-stop" in line for line in commands))
        self.assertTrue(any("deviceidle unforce" in line for line in commands))
        self.assertTrue(any("set-standby-bucket com.yanjiyu.terminalspike 10" in line for line in commands))
        self.assertTrue(any("set restrict-background false" in line for line in commands))
        self.assertEqual(2, sum(line.startswith("docker compose") and " down " in f" {line} " for line in commands))
        self.assertEqual([], list(self.root.glob("terminal-spike-lifecycle-avd.*")))
        evidence = (self.output / "lifecycle-result.txt").read_text(encoding="utf-8")
        for stage in (
            "ssh_before",
            "background",
            "device_idle",
            "standby",
            "data_saver",
            "process_death",
            "persisted_state",
            "restart_state",
            "reconnect",
            "complete",
        ):
            self.assertIn(f"stage={stage} status=pass", evidence)
        self.assertNotIn("127.0.0.1", evidence)
        self.assertNotIn("terminal-spike-test-only", evidence)

    def test_refuses_non_emulator_and_wrong_or_foldable_physical_targets(self) -> None:
        non_emulator = self.run_runner(env=self.fresh_env(FAKE_QEMU="0"))
        self.assertNotEqual(0, non_emulator.returncode)
        self.assertIn("refuses non-emulator", non_emulator.stderr)
        self.reset_run_state()
        for model in ("SM-F976B", "SM-S928B"):
            with self.subTest(model=model):
                result = self.run_runner(
                    env=self.fresh_env(FAKE_SERIAL="physical-serial", FAKE_MODEL=model, FAKE_QEMU="0"),
                    physical_serial="physical-serial",
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("only the authorized old SM-S911B", result.stderr)
                self.assertFalse(any(line.startswith("docker ") for line in self.commands()))
                self.reset_run_state()

    def test_explicit_authorized_old_phone_mode_never_mutates_battery_policy(self) -> None:
        result = self.run_runner(
            env=self.fresh_env(
                FAKE_SERIAL="old-phone-serial",
                FAKE_MODEL="SM-S911B",
                FAKE_QEMU="0",
            ),
            physical_serial="old-phone-serial",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        commands = self.commands()
        scoped_adb = [line for line in commands if line.startswith("adb -s ")]
        self.assertTrue(scoped_adb)
        self.assertTrue(all(line.startswith("adb -s old-phone-serial ") for line in scoped_adb))
        self.assertGreaterEqual(sum("getprop ro.product.model" in line for line in commands), 4)
        self.assertFalse(any("deviceidle force-idle" in line for line in commands))
        self.assertFalse(any("set-standby-bucket" in line for line in commands))
        self.assertFalse(any("set restrict-background" in line for line in commands))
        evidence = (self.output / "lifecycle-result.txt").read_text(encoding="utf-8")
        self.assertIn("stage=pressure status=skipped reason=physical-policy-preserved", evidence)

    def test_rejects_pid_ownership_mismatch_and_never_attempts_kill(self) -> None:
        result = self.run_runner(env=self.fresh_env(FAKE_PID_OWNER="other.package"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ownership proof", result.stderr)
        self.assertFalse(any(" kill -9 " in f" {line} " for line in self.commands()))
        self.assert_cleanup_ran()

    def test_rejects_kill_failure_or_failure_to_observe_death(self) -> None:
        cases = (("FAKE_KILL_FAIL", "exact app PID kill failed"), ("FAKE_DEATH_STUCK", "old app PID disappearance"))
        for variable, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: "1"}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assert_cleanup_ran()
                self.reset_run_state()

    def test_rejects_stale_notification_and_reconnect_marker_failure(self) -> None:
        cases = (
            ("FAKE_STALE_NOTIFICATION", "foreground notification cleanup"),
            ("FAKE_RECONNECT_MARKER_MISSING", "terminal output marker"),
        )
        for variable, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: "1"}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assert_cleanup_ran()
                self.reset_run_state()

    def test_rejects_missing_recent_metadata_or_persisted_session_secret(self) -> None:
        cases = (
            ("FAKE_RECENT_MISSING", "host or recent metadata is missing"),
            ("FAKE_STORED_SECRET", "unexpectedly persisted a secret credential"),
        )
        for variable, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: "1"}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assert_cleanup_ran()
                self.reset_run_state()

    def test_each_background_pressure_assertion_has_a_negative_path(self) -> None:
        cases = (
            ("FAKE_SERVICE_MISSING", "foreground service contract"),
            ("FAKE_IDLE_ENTRY_FAIL", "did not enter deep idle"),
            ("FAKE_STANDBY_ENTRY_FAIL", "restricted standby bucket"),
            ("FAKE_DATA_SAVER_ENTRY_FAIL", "restricted-background mode"),
        )
        for variable, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: "1"}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assert_cleanup_ran()
                self.reset_run_state()

    def test_boot_timeout_and_fixture_failure_are_bounded_and_cleaned(self) -> None:
        cases = (
            ("FAKE_BOOTED", "0", "timed out waiting for emulator boot"),
            ("FAKE_FIXTURE_FAIL", "1", "OpenSSH fixture startup failed"),
        )
        for variable, value, expected in cases:
            with self.subTest(variable=variable):
                result = self.run_runner(env=self.fresh_env(**{variable: value}))
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assertEqual([], list(self.root.glob("terminal-spike-lifecycle-avd.*")))
                self.reset_run_state()

    def test_ci_invokes_disposable_lifecycle_runner_and_uploads_only_sanitized_output(self) -> None:
        workflow = (SOURCE_ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
        self.assertIn("lifecycle-e2e:", workflow)
        self.assertIn("scripts/run-app-lifecycle-e2e.py", workflow)
        self.assertIn("--output-dir build/ci-lifecycle-results", workflow)
        self.assertIn("name: android-lifecycle-api-35", workflow)
        self.assertNotIn("--physical-serial", workflow)


if __name__ == "__main__":
    unittest.main()
