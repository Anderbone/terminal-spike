#!/usr/bin/env python3
"""External real-SSH process-death and background-pressure acceptance runner."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


APP_PACKAGE = "com.yanjiyu.terminalspike"
ACTIVITY_COMPONENT = f"{APP_PACKAGE}/.MainActivity"
AUTHORIZED_PHYSICAL_MODEL = "SM-S911B"
FIXTURE_PORT = "22222"
FIXTURE_USER = "terminal"
FIXTURE_PASSWORD = "terminal-spike-test-only"
NOTIFICATION_ID = "2001"
SAFE_SERIAL = re.compile(r"^[A-Za-z0-9._:-]+$")
SAFE_MARKER = re.compile(r"^[A-Z0-9_]+$")


class LifecycleFailure(RuntimeError):
    pass


class Runner:
    def __init__(self, arguments: argparse.Namespace) -> None:
        self.arguments = arguments
        self.project = Path(__file__).resolve().parents[1]
        self.fixture = self.project / "integration-tests/openssh"
        self.output_dir = arguments.output_dir.resolve()
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.result_path = self.output_dir / "lifecycle-result.txt"
        self.result_path.write_text("", encoding="utf-8")
        self.sdk_root = Path(
            os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or ""
        )
        self.adb_bin = Path(os.environ.get("ADB", str(self.sdk_root / "platform-tools/adb")))
        android_tools_dir = self.sdk_root / "cmdline-tools/22.0/bin"
        if not android_tools_dir.is_dir():
            android_tools_dir = self.sdk_root / "cmdline-tools/latest/bin"
        self.android_bin = android_tools_dir / "android"
        self.sdkmanager_bin = android_tools_dir / "sdkmanager"
        self.avdmanager_bin = android_tools_dir / "avdmanager"
        self.emulator_bin = self.sdk_root / "emulator/emulator"
        self.serial = arguments.physical_serial or f"emulator-{arguments.port}"
        self.avd_name = f"terminal-spike-api{arguments.api}-lifecycle"
        self.physical = arguments.physical_serial is not None
        self.workspace: Path | None = None
        self.avd_home: Path | None = None
        self.private_log: Path | None = None
        self.emulator_process: subprocess.Popen[bytes] | None = None
        self.fixture_started = False
        self.idle_baseline: str | None = None
        self.standby_baseline: str | None = None
        self.data_saver_baseline: str | None = None
        self.idle_changed = False
        self.standby_changed = False
        self.data_saver_changed = False
        self.poll_attempts = int(os.environ.get("TERMINAL_SPIKE_LIFECYCLE_POLL_ATTEMPTS", "120"))
        self.poll_interval = float(os.environ.get("TERMINAL_SPIKE_LIFECYCLE_POLL_SECONDS", "0.25"))
        self.ui_path: Path | None = None

    def log(self, stage: str, status: str, **fields: str) -> None:
        safe_fields = " ".join(f"{key}={value}" for key, value in sorted(fields.items()))
        line = f"APP_LIFECYCLE_E2E stage={stage} status={status}"
        if safe_fields:
            line += f" {safe_fields}"
        print(line, flush=True)
        with self.result_path.open("a", encoding="utf-8") as result:
            result.write(line + "\n")

    def command(
        self,
        arguments: list[str | Path],
        *,
        label: str,
        input_text: str | None = None,
        environment: dict[str, str] | None = None,
        timeout: float = 120,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        try:
            result = subprocess.run(
                [str(argument) for argument in arguments],
                input=input_text,
                capture_output=True,
                text=True,
                env=environment,
                timeout=timeout,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise LifecycleFailure(f"{label} did not complete within its bounded execution") from error
        if check and result.returncode != 0:
            raise LifecycleFailure(f"{label} failed")
        return result

    def adb(
        self,
        *arguments: str,
        label: str,
        timeout: float = 120,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        return self.command(
            [self.adb_bin, "-s", self.serial, *arguments],
            label=label,
            timeout=timeout,
            check=check,
        )

    def adb_bytes(
        self,
        *arguments: str,
        label: str,
        timeout: float = 120,
        check: bool = True,
    ) -> subprocess.CompletedProcess[bytes]:
        try:
            result = subprocess.run(
                [str(self.adb_bin), "-s", self.serial, *arguments],
                capture_output=True,
                timeout=timeout,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise LifecycleFailure(f"{label} did not complete within its bounded execution") from error
        if check and result.returncode != 0:
            raise LifecycleFailure(f"{label} failed")
        return result

    def poll(self, label: str, predicate) -> object:
        for _ in range(self.poll_attempts):
            value = predicate()
            if value:
                return value
            time.sleep(self.poll_interval)
        raise LifecycleFailure(f"timed out waiting for {label}")

    def preflight(self) -> None:
        if self.arguments.api != 35:
            raise LifecycleFailure("the lifecycle acceptance runner is pinned to stable API 35")
        if not SAFE_SERIAL.fullmatch(self.serial):
            raise LifecycleFailure("resolved ADB serial has unsafe syntax")
        if not self.arguments.app_apk.is_file():
            raise LifecycleFailure("debug app APK is missing")
        if not self.adb_bin.is_file() or not os.access(self.adb_bin, os.X_OK):
            raise LifecycleFailure("adb is unavailable")
        if not (self.fixture / "smoke.sh").is_file():
            raise LifecycleFailure("OpenSSH fixture smoke entrypoint is missing")
        if self.physical:
            self.verify_physical_target()
        else:
            required = [self.avdmanager_bin, self.emulator_bin]
            if not all(path.is_file() and os.access(path, os.X_OK) for path in required):
                raise LifecycleFailure("required Android emulator tooling is unavailable")
            if not any(
                path.is_file() and os.access(path, os.X_OK)
                for path in (self.android_bin, self.sdkmanager_bin)
            ):
                raise LifecycleFailure("Android SDK installer is unavailable")
        self.log("preflight", "pass", mode="physical" if self.physical else "emulator")

    def verify_adb_listing(self) -> None:
        listing = self.command([self.adb_bin, "devices", "-l"], label="ADB enumeration").stdout
        matches = []
        for line in listing.splitlines():
            fields = line.split()
            if fields and fields[0] == self.serial:
                matches.append(fields)
        if len(matches) != 1 or len(matches[0]) < 2 or matches[0][1] != "device":
            raise LifecycleFailure("target is not uniquely connected and authorized")
        if self.adb("get-state", label="ADB target state").stdout.strip() != "device":
            raise LifecycleFailure("target is offline, unauthorized, or unavailable")

    def verify_physical_target(self) -> None:
        self.verify_adb_listing()
        model = self.adb("shell", "getprop", "ro.product.model", label="physical model").stdout.strip()
        if model != AUTHORIZED_PHYSICAL_MODEL:
            raise LifecycleFailure("physical mode accepts only the authorized old SM-S911B")
        qemu = self.adb(
            "shell", "getprop", "ro.kernel.qemu", label="physical transport identity"
        ).stdout.strip()
        if qemu == "1":
            raise LifecycleFailure("physical mode refuses emulator targets")

    def verify_emulator_target(self) -> None:
        self.verify_adb_listing()
        if self.adb(
            "shell", "getprop", "ro.kernel.qemu", label="emulator identity"
        ).stdout.strip() != "1":
            raise LifecycleFailure("default mode refuses non-emulator targets")
        observed_name = self.adb("emu", "avd", "name", label="AVD identity").stdout.splitlines()
        if not observed_name or observed_name[0].strip() != self.avd_name:
            raise LifecycleFailure("emulator AVD identity does not match the owned lifecycle AVD")

    def prepare_emulator(self) -> None:
        temp_root = Path(os.environ.get("TMPDIR", "/tmp")).resolve()
        self.workspace = Path(
            tempfile.mkdtemp(prefix="terminal-spike-lifecycle-avd.", dir=temp_root)
        ).resolve()
        self.avd_home = self.workspace / "avd"
        self.avd_home.mkdir()
        self.private_log = self.workspace / "emulator-private.log"
        image = f"system-images;android-{self.arguments.api};google_apis;x86_64"
        environment = os.environ.copy()
        environment["ANDROID_AVD_HOME"] = str(self.avd_home)
        installer = (
            [self.android_bin, "sdk", "install", image]
            if self.android_bin.is_file() and os.access(self.android_bin, os.X_OK)
            else [self.sdkmanager_bin, image]
        )
        self.command(
            installer,
            label="Android system-image installation",
            environment=environment,
            timeout=600,
        )
        self.command(
            [
                self.avdmanager_bin,
                "create",
                "avd",
                "--force",
                "--name",
                self.avd_name,
                "--package",
                image,
                "--device",
                "pixel_6",
            ],
            label="lifecycle AVD creation",
            input_text="no\n",
            environment=environment,
        )
        config = self.avd_home / f"{self.avd_name}.avd/config.ini"
        with config.open("a", encoding="utf-8") as stream:
            stream.write("\nhw.keyboard=yes\n")
        private_stream = self.private_log.open("wb")
        self.emulator_process = subprocess.Popen(
            [
                str(self.emulator_bin),
                "-avd",
                self.avd_name,
                "-port",
                str(self.arguments.port),
                "-no-window",
                "-no-audio",
                "-no-boot-anim",
                "-gpu",
                "swiftshader_indirect",
                "-wipe-data",
                "-no-snapshot",
                "-no-metrics",
            ],
            env=environment,
            stdout=private_stream,
            stderr=subprocess.STDOUT,
        )
        private_stream.close()

        boot_attempts = int(os.environ.get("TERMINAL_SPIKE_EMULATOR_BOOT_ATTEMPTS", "180"))
        boot_interval = float(os.environ.get("TERMINAL_SPIKE_EMULATOR_BOOT_POLL_SECONDS", "2"))
        for _ in range(boot_attempts):
            if self.emulator_process.poll() is not None:
                raise LifecycleFailure("emulator exited before boot completed")
            result = self.adb(
                "shell",
                "getprop",
                "sys.boot_completed",
                label="emulator boot poll",
                check=False,
            )
            if result.stdout.strip() == "1":
                break
            time.sleep(boot_interval)
        else:
            raise LifecycleFailure("timed out waiting for emulator boot")

        self.verify_emulator_target()
        self.poll(
            "emulator package manager",
            lambda: self.adb(
                "shell", "pm", "path", "android", label="package-manager poll", check=False
            ).stdout.startswith("package:"),
        )
        for animation in (
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        ):
            self.adb(
                "shell", "settings", "put", "global", animation, "0", label="animation setup"
            )
        self.adb(
            "shell",
            "settings",
            "put",
            "secure",
            "show_ime_with_hard_keyboard",
            "0",
            label="hardware keyboard setup",
        )
        self.ui_path = self.workspace / "window.xml"
        self.log("emulator", "pass", api=str(self.arguments.api))

    def start_fixture(self) -> None:
        self.command(
            [
                "docker",
                "compose",
                "--project-directory",
                self.fixture,
                "-f",
                self.fixture / "compose.yaml",
                "down",
                "--volumes",
                "--remove-orphans",
            ],
            label="fixture reset",
        )
        self.fixture_started = True
        self.command([self.fixture / "smoke.sh"], label="OpenSSH fixture startup", timeout=300)
        self.log("fixture", "pass")

    def install_app(self) -> None:
        if self.physical:
            self.verify_physical_target()
            options = ["-r"]
        else:
            self.verify_emulator_target()
            self.adb("uninstall", APP_PACKAGE, label="clean app uninstall", check=False)
            options = []
        installed = False
        attempts = int(os.environ.get("TERMINAL_SPIKE_INSTALL_ATTEMPTS", "5"))
        interval = float(os.environ.get("TERMINAL_SPIKE_INSTALL_POLL_SECONDS", "2"))
        for _ in range(attempts):
            result = self.adb(
                "install",
                *options,
                str(self.arguments.app_apk.resolve()),
                label="debug app install",
                check=False,
            )
            if result.returncode == 0:
                installed = True
                break
            time.sleep(interval)
        if not installed:
            raise LifecycleFailure("debug app install failed after bounded retries")
        self.adb("reverse", f"tcp:{FIXTURE_PORT}", f"tcp:{FIXTURE_PORT}", label="SSH reverse")
        self.adb(
            "shell",
            "pm",
            "grant",
            APP_PACKAGE,
            "android.permission.POST_NOTIFICATIONS",
            label="notification permission",
            check=False,
        )
        self.adb("shell", "logcat", "-c", label="logcat clear", check=False)
        if self.workspace is None:
            self.workspace = Path(tempfile.mkdtemp(prefix="terminal-spike-lifecycle-device."))
            self.ui_path = self.workspace / "window.xml"
        self.log("install", "pass")

    def launch(self) -> None:
        if self.physical:
            self.verify_physical_target()
        else:
            self.verify_emulator_target()
        self.adb("shell", "am", "start", "-W", "-n", ACTIVITY_COMPONENT, label="app launch")

    def dump_ui(self) -> ET.Element:
        assert self.ui_path is not None
        self.adb(
            "shell",
            "uiautomator",
            "dump",
            "--compressed",
            "/sdcard/terminal-spike-window.xml",
            label="UI hierarchy capture",
        )
        result = self.adb(
            "exec-out",
            "cat",
            "/sdcard/terminal-spike-window.xml",
            label="UI hierarchy transfer",
        )
        self.ui_path.write_text(result.stdout, encoding="utf-8")
        try:
            return ET.fromstring(result.stdout)
        except ET.ParseError as error:
            raise LifecycleFailure("UI hierarchy is malformed") from error

    @staticmethod
    def matching_nodes(
        root: ET.Element,
        attribute: str,
        value: str,
        class_name: str | None = None,
    ) -> list[ET.Element]:
        return [
            node
            for node in root.iter("node")
            if node.attrib.get(attribute, "") == value
            and (class_name is None or node.attrib.get("class", "") == class_name)
        ]

    def wait_for_node(
        self,
        attribute: str,
        value: str,
        occurrence: int = 0,
        class_name: str | None = None,
    ) -> tuple[int, int]:
        def locate() -> tuple[int, int] | None:
            nodes = self.matching_nodes(self.dump_ui(), attribute, value, class_name)
            if occurrence >= len(nodes):
                return None
            match = re.fullmatch(
                r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                nodes[occurrence].attrib.get("bounds", ""),
            )
            if match is None:
                return None
            left, top, right, bottom = map(int, match.groups())
            return ((left + right) // 2, (top + bottom) // 2)

        return self.poll(f"UI node {attribute}", locate)  # type: ignore[return-value]

    def wait_for_text(self, text: str) -> None:
        self.wait_for_node("text", text)

    def wait_for_text_containing(self, text: str) -> None:
        self.poll(
            "terminal output marker",
            lambda: any(text in node.attrib.get("text", "") for node in self.dump_ui().iter("node")),
        )

    def tap_node(
        self,
        attribute: str,
        value: str,
        occurrence: int = 0,
        class_name: str | None = None,
    ) -> None:
        x, y = self.wait_for_node(attribute, value, occurrence, class_name)
        self.adb("shell", "input", "tap", str(x), str(y), label="UI tap")

    def replace_edit_text(self, occurrence: int, value: str) -> None:
        self.tap_node("class", "android.widget.EditText", occurrence, "android.widget.EditText")
        self.adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", label="text cursor move")
        self.adb(
            "shell",
            "input",
            "keyevent",
            *("KEYCODE_DEL" for _ in range(128)),
            label="text field clear",
        )
        self.adb("shell", "input", "text", value, label="text entry")
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK", label="keyboard dismissal")

    def wait_for_terminal(self) -> None:
        for _ in range(self.poll_attempts):
            root = self.dump_ui()
            shell = self.matching_nodes(root, "text", "Open shell", "android.widget.TextView")
            if shell:
                self.tap_node("text", "Open shell", class_name="android.widget.TextView")
                continue
            if self.matching_nodes(root, "content-desc", "Native terminal renderer"):
                return
            time.sleep(self.poll_interval)
        raise LifecycleFailure("timed out waiting for native terminal renderer")

    def connect_new_saved_host(self) -> None:
        self.wait_for_text("Connections")
        self.tap_node("content-desc", "Add host")
        self.wait_for_text("Add host")
        self.replace_edit_text(0, "127.0.0.1")
        self.replace_edit_text(1, FIXTURE_USER)
        self.replace_edit_text(2, FIXTURE_PORT)
        self.replace_edit_text(3, FIXTURE_PASSWORD)
        self.tap_node("text", "Save", class_name="android.widget.TextView")
        self.wait_for_text("Connections")
        self.tap_node("content-desc", "Connect to 127.0.0.1")
        self.wait_for_text("Password")
        self.replace_edit_text(0, FIXTURE_PASSWORD)
        self.tap_node("text", "Connect", class_name="android.widget.TextView")
        self.wait_for_text("Trust once")
        self.tap_node("text", "Trust and save", class_name="android.widget.TextView")
        self.wait_for_terminal()

    def send_marker(self, marker: str) -> None:
        if not SAFE_MARKER.fullmatch(marker):
            raise LifecycleFailure("internal terminal marker is unsafe")
        self.wait_for_terminal()
        self.tap_node("content-desc", "Native terminal renderer")
        self.adb("shell", "input", "text", f"printf%s{marker}", label="terminal marker input")
        self.adb("shell", "input", "keyevent", "KEYCODE_ENTER", label="terminal marker enter")
        self.wait_for_text_containing(marker)

    def current_pid(self) -> str | None:
        output = self.adb(
            "shell", "pidof", APP_PACKAGE, label="app PID query", check=False
        ).stdout.strip()
        if not output:
            return None
        values = output.split()
        if len(values) != 1 or not values[0].isdigit():
            raise LifecycleFailure("app PID query did not return one exact numeric PID")
        return values[0]

    def require_pid(self) -> str:
        return self.poll("one app PID", self.current_pid)  # type: ignore[return-value]

    def service_present(self) -> bool:
        output = self.adb(
            "shell",
            "dumpsys",
            "activity",
            "services",
            APP_PACKAGE,
            label="foreground service inspection",
        ).stdout
        return APP_PACKAGE in output and "SessionForegroundService" in output

    def notification_present(self) -> bool:
        output = self.adb(
            "shell", "dumpsys", "notification", "--noredact", label="notification inspection"
        ).stdout
        return bool(
            re.search(
                rf"NotificationRecord\([^\n]*pkg={re.escape(APP_PACKAGE)}[^\n]*id={NOTIFICATION_ID}\b",
                output,
            )
        )

    def require_live_background_contract(self, expected_pid: str) -> None:
        if self.current_pid() != expected_pid:
            raise LifecycleFailure("background pressure changed the app PID unexpectedly")
        if not self.service_present():
            raise LifecycleFailure("foreground service disappeared while the SSH session remained live")
        if not self.notification_present():
            raise LifecycleFailure("foreground-service notification disappeared while SSH remained live")

    def background(self) -> None:
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME", label="app backgrounding")

    def read_pressure_baselines(self) -> None:
        self.idle_baseline = self.adb(
            "shell", "dumpsys", "deviceidle", "get", "deep", label="device-idle baseline"
        ).stdout.strip()
        if self.idle_baseline != "ACTIVE":
            raise LifecycleFailure("fresh AVD device-idle baseline is not safely restorable ACTIVE")
        self.standby_baseline = self.adb(
            "shell", "am", "get-standby-bucket", APP_PACKAGE, label="standby baseline"
        ).stdout.strip()
        if not self.standby_baseline.isdigit():
            raise LifecycleFailure("standby baseline is not an exact numeric bucket")
        data_saver_output = self.adb(
            "shell", "cmd", "netpolicy", "get", "restrict-background", label="data-saver baseline"
        ).stdout.lower()
        if "disabled" in data_saver_output:
            self.data_saver_baseline = "false"
        elif "enabled" in data_saver_output:
            self.data_saver_baseline = "true"
        else:
            raise LifecycleFailure("data-saver baseline is not readable")

    def restore_idle(self) -> None:
        if not self.idle_changed:
            return
        self.adb("shell", "dumpsys", "deviceidle", "unforce", label="device-idle restoration")
        observed = self.adb(
            "shell", "dumpsys", "deviceidle", "get", "deep", label="device-idle restore check"
        ).stdout.strip()
        if observed != self.idle_baseline:
            raise LifecycleFailure("device-idle state was not restored exactly")
        self.idle_changed = False

    def restore_standby(self) -> None:
        if not self.standby_changed:
            return
        assert self.standby_baseline is not None
        self.adb(
            "shell",
            "am",
            "set-standby-bucket",
            APP_PACKAGE,
            self.standby_baseline,
            label="standby restoration",
        )
        observed = self.adb(
            "shell", "am", "get-standby-bucket", APP_PACKAGE, label="standby restore check"
        ).stdout.strip()
        if observed != self.standby_baseline:
            raise LifecycleFailure("standby bucket was not restored exactly")
        self.standby_changed = False

    def restore_data_saver(self) -> None:
        if not self.data_saver_changed:
            return
        assert self.data_saver_baseline is not None
        self.adb(
            "shell",
            "cmd",
            "netpolicy",
            "set",
            "restrict-background",
            self.data_saver_baseline,
            label="data-saver restoration",
        )
        observed = self.adb(
            "shell", "cmd", "netpolicy", "get", "restrict-background", label="data-saver restore check"
        ).stdout.lower()
        expected = "enabled" if self.data_saver_baseline == "true" else "disabled"
        if expected not in observed:
            raise LifecycleFailure("data-saver state was not restored exactly")
        self.data_saver_changed = False

    def exercise_pressure(self, pid: str) -> None:
        if self.physical:
            self.log("pressure", "skipped", reason="physical-policy-preserved")
            return
        self.read_pressure_baselines()

        self.background()
        self.require_live_background_contract(pid)
        self.launch()
        self.send_marker("LIFECYCLE_BACKGROUND_42")
        self.log("background", "pass")

        self.background()
        self.idle_changed = True
        self.adb("shell", "dumpsys", "deviceidle", "force-idle", label="device-idle entry")
        if self.adb(
            "shell", "dumpsys", "deviceidle", "get", "deep", label="device-idle assertion"
        ).stdout.strip() != "IDLE":
            raise LifecycleFailure("device did not enter deep idle")
        self.require_live_background_contract(pid)
        self.restore_idle()
        self.launch()
        self.send_marker("LIFECYCLE_IDLE_42")
        self.log("device_idle", "pass", restored="true")

        self.background()
        self.standby_changed = True
        self.adb(
            "shell", "am", "set-standby-bucket", APP_PACKAGE, "restricted", label="standby restriction"
        )
        observed_bucket = self.adb(
            "shell", "am", "get-standby-bucket", APP_PACKAGE, label="standby assertion"
        ).stdout.strip()
        if observed_bucket not in {"45", "restricted"}:
            raise LifecycleFailure("app did not enter the restricted standby bucket")
        self.require_live_background_contract(pid)
        self.restore_standby()
        self.launch()
        self.send_marker("LIFECYCLE_STANDBY_42")
        self.log("standby", "pass", restored="true")

        self.background()
        self.data_saver_changed = True
        self.adb(
            "shell",
            "cmd",
            "netpolicy",
            "set",
            "restrict-background",
            "true",
            label="data-saver restriction",
        )
        observed_data_saver = self.adb(
            "shell", "cmd", "netpolicy", "get", "restrict-background", label="data-saver assertion"
        ).stdout.lower()
        if "enabled" not in observed_data_saver:
            raise LifecycleFailure("data saver did not enter restricted-background mode")
        self.require_live_background_contract(pid)
        self.restore_data_saver()
        self.launch()
        self.send_marker("LIFECYCLE_DATA_SAVER_42")
        self.log("data_saver", "pass", restored="true")

    def kill_exact_process(self, pid: str) -> None:
        self.background()
        if self.physical:
            self.verify_physical_target()
        ownership = self.adb(
            "shell",
            "run-as",
            APP_PACKAGE,
            "cat",
            f"/proc/{pid}/cmdline",
            label="app PID ownership proof",
        ).stdout.replace("\x00", "").strip()
        if ownership != APP_PACKAGE:
            raise LifecycleFailure("exact PID ownership proof did not match the app package")
        self.adb(
            "shell",
            "run-as",
            APP_PACKAGE,
            "kill",
            "-9",
            pid,
            label="exact app PID kill",
        )
        self.poll("old app PID disappearance", lambda: self.current_pid() is None)
        self.poll(
            "foreground service cleanup",
            lambda: not self.service_present(),
        )
        self.poll(
            "foreground notification cleanup",
            lambda: not self.notification_present(),
        )
        if self.current_pid() is not None:
            raise LifecycleFailure("app restarted before explicit relaunch")
        self.log("process_death", "pass", old_pid=pid)

    def prove_persisted_non_secret_state(self) -> None:
        assert self.workspace is not None
        snapshot_dir = self.workspace / "database-snapshot"
        snapshot_dir.mkdir()
        database_name = "terminal-spike.db"
        for suffix in ("", "-wal", "-shm"):
            result = self.adb_bytes(
                "exec-out",
                "run-as",
                APP_PACKAGE,
                "cat",
                f"databases/{database_name}{suffix}",
                label="private database snapshot",
                check=suffix == "",
            )
            if result.returncode == 0 and result.stdout:
                (snapshot_dir / f"{database_name}{suffix}").write_bytes(result.stdout)
        database_path = snapshot_dir / database_name
        try:
            with sqlite3.connect(f"file:{database_path}?mode=ro", uri=True) as database:
                host_count = database.execute("SELECT COUNT(*) FROM host_profiles").fetchone()[0]
                recent_count = database.execute("SELECT COUNT(*) FROM recent_sessions").fetchone()[0]
                stored_secret_count = database.execute(
                    "SELECT COUNT(*) FROM ssh_credentials WHERE secret_id IS NOT NULL"
                ).fetchone()[0]
        except (sqlite3.DatabaseError, OSError) as error:
            raise LifecycleFailure("private post-death database snapshot is unreadable") from error
        if host_count < 1 or recent_count < 1:
            raise LifecycleFailure("post-death non-secret host or recent metadata is missing")
        if stored_secret_count != 0:
            raise LifecycleFailure("session-only password unexpectedly persisted a secret credential")
        self.log(
            "persisted_state",
            "pass",
            hosts=str(host_count),
            recent=str(recent_count),
            stored_secrets=str(stored_secret_count),
        )

    def prove_restart_and_reconnect(self, old_pid: str) -> None:
        self.launch()
        new_pid = self.require_pid()
        if new_pid == old_pid:
            raise LifecycleFailure("explicit relaunch did not create a distinct app PID")

        self.tap_node("text", "Terminal")
        self.wait_for_text("No terminal session is open.")
        root = self.dump_ui()
        for forbidden in ("Connected", "Reconnecting"):
            if self.matching_nodes(root, "text", forbidden):
                raise LifecycleFailure("restart UI falsely represents the dead transport as live")
        self.tap_node("text", "Connections")
        self.wait_for_node("content-desc", "Connect to 127.0.0.1")
        self.log("restart_state", "pass", new_pid=new_pid)

        self.tap_node("content-desc", "Connect to 127.0.0.1")
        self.wait_for_text("Password")
        self.replace_edit_text(0, FIXTURE_PASSWORD)
        self.tap_node("text", "Connect", class_name="android.widget.TextView")
        self.wait_for_terminal()
        self.send_marker("LIFECYCLE_RECONNECTED_42")
        self.log("reconnect", "pass", credential="prompted", ssh="pass")

    def reject_crash_or_anr(self) -> None:
        exit_info = self.adb(
            "shell", "dumpsys", "activity", "exit-info", APP_PACKAGE, label="exit-reason inspection"
        ).stdout
        if re.search(r"reason=(?:4|6)\b|REASON_(?:CRASH|ANR)", exit_info):
            raise LifecycleFailure("Android reported an app crash or ANR during lifecycle acceptance")
        logcat = self.capture_relevant_logcat(label="final logcat capture")
        if re.search(rf"ANR in {re.escape(APP_PACKAGE)}|FATAL EXCEPTION[^\n]*\n(?:.*\n){{0,8}}.*{re.escape(APP_PACKAGE)}", logcat):
            raise LifecycleFailure("logcat reported an app crash or ANR")
        self.write_redacted(logcat, self.output_dir / "lifecycle-logcat.txt")

    def capture_relevant_logcat(self, *, label: str) -> str:
        return self.adb(
            "shell",
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "TerminalSpike:V",
            "AndroidRuntime:E",
            "ActivityManager:E",
            "ActivityTaskManager:E",
            "*:S",
            label=label,
            check=False,
        ).stdout

    def write_redacted(self, text: str, path: Path) -> None:
        redactor = self.project / "scripts/redact-android-test-log.py"
        result = self.command(
            [sys.executable, redactor],
            label="evidence redaction",
            input_text=text,
        )
        path.write_text(result.stdout, encoding="utf-8")

    def cleanup(self) -> None:
        restore_errors: list[str] = []
        for restore in (self.restore_data_saver, self.restore_standby, self.restore_idle):
            try:
                restore()
            except Exception:
                restore_errors.append(restore.__name__)
        if self.fixture_started:
            self.command(
                [
                    "docker",
                    "compose",
                    "--project-directory",
                    self.fixture,
                    "-f",
                    self.fixture / "compose.yaml",
                    "down",
                    "--volumes",
                    "--remove-orphans",
                ],
                label="fixture cleanup",
                check=False,
            )
        if self.emulator_process is not None and self.emulator_process.poll() is None:
            try:
                logcat = self.capture_relevant_logcat(label="cleanup logcat capture")
                self.write_redacted(logcat, self.output_dir / "lifecycle-logcat.txt")
            except Exception:
                pass
        if self.emulator_process is not None:
            self.adb("emu", "kill", label="emulator cleanup", check=False)
            try:
                self.emulator_process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.emulator_process.terminate()
                try:
                    self.emulator_process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.emulator_process.kill()
                    self.emulator_process.wait(timeout=5)
        if self.private_log is not None and self.private_log.is_file():
            self.write_redacted(
                self.private_log.read_text(encoding="utf-8", errors="replace"),
                self.output_dir / "lifecycle-emulator.txt",
            )
        if self.workspace is not None and self.workspace.is_dir():
            shutil.rmtree(self.workspace)
        if restore_errors:
            raise LifecycleFailure(
                "pressure-state cleanup failed: " + ", ".join(sorted(restore_errors))
            )

    def run(self) -> None:
        self.preflight()
        if not self.physical:
            self.prepare_emulator()
        self.start_fixture()
        self.install_app()
        self.launch()
        self.connect_new_saved_host()
        self.send_marker("LIFECYCLE_BEFORE_42")
        pid = self.require_pid()
        service_present = self.service_present()
        notification_present = self.notification_present()
        if not service_present:
            raise LifecycleFailure("live SSH did not establish its foreground service contract")
        if not notification_present:
            raise LifecycleFailure("live SSH did not establish its foreground notification contract")
        self.log("ssh_before", "pass", pid=pid)
        self.exercise_pressure(pid)
        self.kill_exact_process(pid)
        self.prove_persisted_non_secret_state()
        self.prove_restart_and_reconnect(pid)
        self.reject_crash_or_anr()
        self.log(
            "complete",
            "pass",
            metadata="preserved",
            notification="clean",
            process_death="exact-pid",
        )


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", type=int, default=35)
    parser.add_argument("--app-apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--output-dir", type=Path, default=Path("build/lifecycle-e2e"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("TERMINAL_SPIKE_EMULATOR_PORT", "5554")))
    parser.add_argument("--physical-serial")
    parser.add_argument("--dry-run", action="store_true")
    arguments = parser.parse_args()
    if arguments.port < 5554 or arguments.port > 5682 or arguments.port % 2:
        parser.error("emulator port must be an even console port from 5554 through 5682")
    return arguments


def main() -> int:
    arguments = parse_arguments()
    mode = "physical" if arguments.physical_serial else "emulator"
    if arguments.dry_run:
        print(
            "APP_LIFECYCLE_E2E_PLAN "
            f"api={arguments.api} mode={mode} avd=terminal-spike-api{arguments.api}-lifecycle "
            "fixture=loopback process_death=exact-pid pressure=background,idle,standby,data-saver"
        )
        return 0

    runner = Runner(arguments)
    interrupted = False

    def interrupt(_signum, _frame) -> None:
        nonlocal interrupted
        interrupted = True
        raise KeyboardInterrupt

    previous = {
        signum: signal.signal(signum, interrupt)
        for signum in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
    }
    failure: Exception | None = None
    try:
        runner.run()
    except KeyboardInterrupt:
        failure = LifecycleFailure("lifecycle acceptance was interrupted")
    except Exception as error:
        failure = error
    finally:
        try:
            runner.cleanup()
        except Exception as cleanup_error:
            failure = cleanup_error if failure is None else LifecycleFailure(
                f"{failure}; cleanup also failed"
            )
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    if failure is not None:
        print(f"ERROR {failure}", file=sys.stderr)
        return 130 if interrupted else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
