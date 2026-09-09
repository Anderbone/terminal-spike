#!/usr/bin/env python3
"""Hermetic ephemeral-signing caller for the black-box release update smoke."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time


APP_PACKAGE = "com.yanjiyu.terminalspike"
EXPECTED_AVD = "terminal-spike-release-test"
SIGNING_ENVIRONMENT = (
    "TERMINAL_SPIKE_RELEASE_STORE_FILE",
    "TERMINAL_SPIKE_RELEASE_STORE_PASSWORD",
    "TERMINAL_SPIKE_RELEASE_KEY_ALIAS",
    "TERMINAL_SPIKE_RELEASE_KEY_PASSWORD",
)
CERTIFICATE_PATTERN = re.compile(r"SHA-256 digest:\s*([0-9A-Fa-f]+)")
KEYTOOL_CERTIFICATE_PATTERN = re.compile(r"SHA256:\s*([0-9A-Fa-f:]+)")


class ReleaseSmokeFailure(RuntimeError):
    pass


class ReleaseSmokeRunner:
    def __init__(self, arguments: argparse.Namespace) -> None:
        self.arguments = arguments
        self.project = Path(__file__).resolve().parents[1]
        self.fixture = self.project / "integration-tests/openssh"
        self.smoke = self.fixture / "release-app-install-update-smoke.sh"
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
        self.apksigner_bin = self.resolve_build_tool("apksigner")
        java_home = Path(os.environ.get("JAVA_HOME", ""))
        self.keytool_bin = java_home / "bin/keytool" if java_home else Path("keytool")
        self.jarsigner_bin = java_home / "bin/jarsigner" if java_home else Path("jarsigner")
        self.serial = f"emulator-{arguments.port}"
        self.output_dir = arguments.output_dir.resolve()
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.result_path = self.output_dir / "release-smoke-result.txt"
        self.result_path.write_text("", encoding="utf-8")
        self.workspace: Path | None = None
        self.avd_home: Path | None = None
        self.emulator_process: subprocess.Popen[bytes] | None = None
        self.fixture_may_be_running = False
        self.release_apk = self.project / "app/build/outputs/apk/release/app-release.apk"
        self.release_aab = self.project / "app/build/outputs/bundle/release/app-release.aab"

    def resolve_build_tool(self, name: str) -> Path:
        override = os.environ.get(name.upper())
        if override:
            return Path(override)
        candidates = [
            path
            for path in (self.sdk_root / "build-tools").glob(f"*/{name}")
            if re.fullmatch(r"\d+(?:\.\d+)+", path.parent.name)
        ]
        candidates.sort(key=lambda path: tuple(int(part) for part in path.parent.name.split(".")))
        return candidates[-1] if candidates else Path(name)

    def log(self, stage: str, status: str, **fields: str) -> None:
        line = f"RELEASE_CI_SMOKE stage={stage} status={status}"
        if fields:
            line += " " + " ".join(f"{key}={value}" for key, value in sorted(fields.items()))
        print(line, flush=True)
        with self.result_path.open("a", encoding="utf-8") as result:
            result.write(line + "\n")

    def command(
        self,
        arguments: list[str | Path],
        *,
        label: str,
        environment: dict[str, str] | None = None,
        input_text: str | None = None,
        timeout: float = 300,
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
            raise ReleaseSmokeFailure(f"{label} did not complete within its bounded execution") from error
        if check and result.returncode != 0:
            raise ReleaseSmokeFailure(f"{label} failed")
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

    @staticmethod
    def executable(path: Path) -> bool:
        if path.parent == Path("."):
            resolved = shutil.which(str(path))
            return resolved is not None
        return path.is_file() and os.access(path, os.X_OK)

    def preflight(self) -> None:
        inherited = [name for name in SIGNING_ENVIRONMENT if os.environ.get(name)]
        if inherited:
            raise ReleaseSmokeFailure("external release-signing inputs must be unset")
        if self.arguments.api != 35:
            raise ReleaseSmokeFailure("release update smoke is pinned to stable API 35")
        required = (
            self.adb_bin,
            self.avdmanager_bin,
            self.emulator_bin,
            self.apksigner_bin,
            self.keytool_bin,
            self.jarsigner_bin,
            self.project / "gradlew",
            self.smoke,
        )
        if not all(self.executable(path) for path in required):
            raise ReleaseSmokeFailure("required release-smoke tooling is unavailable")
        if not any(self.executable(path) for path in (self.android_bin, self.sdkmanager_bin)):
            raise ReleaseSmokeFailure("Android SDK installer is unavailable")
        self.log("preflight", "pass", api=str(self.arguments.api))

    def prepare_workspace(self) -> tuple[Path, str, str]:
        temp_root = Path(os.environ.get("TMPDIR", "/tmp")).resolve()
        self.workspace = Path(
            tempfile.mkdtemp(prefix="terminal-spike-release-ci.", dir=temp_root)
        ).resolve()
        self.workspace.chmod(0o700)
        self.avd_home = self.workspace / "avd"
        self.avd_home.mkdir(mode=0o700)
        alias = "terminal-spike-ci-acceptance"
        password = secrets.token_urlsafe(32)
        keystore = self.workspace / "acceptance.p12"
        self.command(
            [
                self.keytool_bin,
                "-genkeypair",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore,
                "-storepass",
                password,
                "-keypass",
                password,
                "-alias",
                alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "3072",
                "-validity",
                "2",
                "-dname",
                "CN=Terminal Spike CI Acceptance,O=Local Test,C=GB",
            ],
            label="ephemeral acceptance key generation",
        )
        if not keystore.is_file() or keystore.stat().st_size == 0:
            raise ReleaseSmokeFailure("ephemeral acceptance key was not created")
        self.log("acceptance_key", "pass", ephemeral="true")
        return keystore, alias, password

    def build_and_verify(self, keystore: Path, alias: str, password: str) -> tuple[Path, Path]:
        environment = os.environ.copy()
        environment.update(
            {
                "TERMINAL_SPIKE_RELEASE_STORE_FILE": str(keystore),
                "TERMINAL_SPIKE_RELEASE_STORE_PASSWORD": password,
                "TERMINAL_SPIKE_RELEASE_KEY_ALIAS": alias,
                "TERMINAL_SPIKE_RELEASE_KEY_PASSWORD": password,
            }
        )
        result = self.command(
            [
                self.project / "gradlew",
                "--dependency-verification=strict",
                "--no-daemon",
                "--no-configuration-cache",
                "--max-workers=2",
                ":app:assembleRelease",
                ":app:bundleRelease",
                ":app:verifyReleasePackaging",
            ],
            label="ephemeral signed release build",
            environment=environment,
            timeout=1800,
        )
        assert self.workspace is not None
        (self.workspace / "gradle-private.log").write_text(
            result.stdout + result.stderr,
            encoding="utf-8",
        )
        if not self.release_apk.is_file() or not self.release_aab.is_file():
            raise ReleaseSmokeFailure("signed release APK or AAB is missing")
        private_apk = self.workspace / "app-release-acceptance.apk"
        private_aab = self.workspace / "app-release-acceptance.aab"
        shutil.copy2(self.release_apk, private_apk)
        shutil.copy2(self.release_aab, private_aab)

        key_output = self.command(
            [
                self.keytool_bin,
                "-list",
                "-v",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore,
                "-storepass",
                password,
                "-alias",
                alias,
            ],
            label="acceptance certificate inspection",
        ).stdout
        apk_result = self.command(
            [self.apksigner_bin, "verify", "--print-certs", private_apk],
            label="release APK signature verification",
            check=False,
        )
        if apk_result.returncode != 0:
            diagnostic = (apk_result.stdout + apk_result.stderr).lower()
            reason = "signature-rejected" if "does not verify" in diagnostic else "tool-error"
            raise ReleaseSmokeFailure(f"release APK signature verification failed ({reason})")
        apk_output = apk_result.stdout
        aab_verify = self.command(
            [self.jarsigner_bin, "-verify", private_aab],
            label="release AAB signature verification",
            check=False,
        )
        if aab_verify.returncode != 0:
            diagnostic = (aab_verify.stdout + aab_verify.stderr).lower()
            reason = "signature-rejected" if "unsigned" in diagnostic else "tool-error"
            raise ReleaseSmokeFailure(f"release AAB signature verification failed ({reason})")
        aab_output = self.command(
            [self.keytool_bin, "-printcert", "-jarfile", private_aab],
            label="release AAB certificate inspection",
        ).stdout
        key_match = KEYTOOL_CERTIFICATE_PATTERN.search(key_output)
        apk_match = CERTIFICATE_PATTERN.search(apk_output)
        aab_match = KEYTOOL_CERTIFICATE_PATTERN.search(aab_output)
        if not key_match or not apk_match or not aab_match:
            raise ReleaseSmokeFailure("release certificate output is incomplete")
        normalize = lambda value: value.replace(":", "").lower()
        expected = normalize(key_match.group(1))
        if normalize(apk_match.group(1)) != expected or normalize(aab_match.group(1)) != expected:
            raise ReleaseSmokeFailure("release APK/AAB signer does not match the ephemeral key")
        if "jar verified" not in (aab_verify.stdout + aab_verify.stderr).lower():
            raise ReleaseSmokeFailure("release AAB did not report a verified signature")
        self.log("release_build", "pass", aab="verified", apk="verified", signing="ephemeral")
        return private_apk, private_aab

    def prepare_emulator(self) -> None:
        assert self.workspace is not None and self.avd_home is not None
        image = "system-images;android-35;google_apis;x86_64"
        environment = os.environ.copy()
        environment["ANDROID_AVD_HOME"] = str(self.avd_home)
        installer = (
            [self.android_bin, "sdk", "install", image]
            if self.executable(self.android_bin)
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
                EXPECTED_AVD,
                "--package",
                image,
                "--device",
                "pixel_6",
            ],
            label="release-smoke AVD creation",
            input_text="no\n",
            environment=environment,
        )
        config = self.avd_home / f"{EXPECTED_AVD}.avd/config.ini"
        with config.open("a", encoding="utf-8") as stream:
            stream.write("\nhw.keyboard=yes\n")
        private_log = (self.workspace / "emulator-private.log").open("wb")
        self.emulator_process = subprocess.Popen(
            [
                str(self.emulator_bin),
                "-avd",
                EXPECTED_AVD,
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
            stdout=private_log,
            stderr=subprocess.STDOUT,
        )
        private_log.close()
        attempts = int(os.environ.get("TERMINAL_SPIKE_EMULATOR_BOOT_ATTEMPTS", "180"))
        interval = float(os.environ.get("TERMINAL_SPIKE_EMULATOR_BOOT_POLL_SECONDS", "2"))
        for _ in range(attempts):
            if self.emulator_process.poll() is not None:
                raise ReleaseSmokeFailure("emulator exited before boot completed")
            result = self.adb(
                "shell", "getprop", "sys.boot_completed", label="emulator boot poll", check=False
            )
            if result.stdout.strip() == "1":
                break
            time.sleep(interval)
        else:
            raise ReleaseSmokeFailure("timed out waiting for emulator boot")
        self.verify_emulator()
        package_attempts = int(os.environ.get("TERMINAL_SPIKE_PACKAGE_MANAGER_ATTEMPTS", "60"))
        for _ in range(package_attempts):
            result = self.adb(
                "shell", "pm", "path", "android", label="package-manager poll", check=False
            )
            if result.stdout.startswith("package:"):
                break
            time.sleep(float(os.environ.get("TERMINAL_SPIKE_PACKAGE_MANAGER_POLL_SECONDS", "1")))
        else:
            raise ReleaseSmokeFailure("timed out waiting for emulator package manager")
        self.log("emulator", "pass", avd=EXPECTED_AVD)

    def verify_emulator(self) -> None:
        listing = self.command([self.adb_bin, "devices", "-l"], label="ADB enumeration").stdout
        matches = [line.split() for line in listing.splitlines() if line.split()[:1] == [self.serial]]
        if len(matches) != 1 or len(matches[0]) < 2 or matches[0][1] != "device":
            raise ReleaseSmokeFailure("emulator is not uniquely connected and authorized")
        if self.adb(
            "shell", "getprop", "ro.kernel.qemu", label="emulator identity"
        ).stdout.strip() != "1":
            raise ReleaseSmokeFailure("release smoke refuses a non-emulator target")
        observed = self.adb("emu", "avd", "name", label="release AVD identity").stdout.splitlines()
        if not observed or observed[0].strip() != EXPECTED_AVD:
            raise ReleaseSmokeFailure("release smoke resolved an unexpected AVD name")

    def run_smoke(self, private_apk: Path) -> None:
        self.verify_emulator()
        self.fixture_may_be_running = True
        environment = os.environ.copy()
        environment["ADB"] = str(self.adb_bin)
        environment["APKSIGNER"] = str(self.apksigner_bin)
        result = self.command(
            [self.smoke, self.serial, private_apk, private_apk],
            label="black-box release update SSH smoke",
            environment=environment,
            timeout=900,
            check=False,
        )
        assert self.workspace is not None
        (self.workspace / "smoke-private.log").write_text(
            result.stdout + result.stderr,
            encoding="utf-8",
        )
        # Retain only fixed stage markers, including on failure; private SSH/signing output stays private.
        for line in result.stdout.splitlines():
            marker = re.fullmatch(
                r"RELEASE_APP_UPDATE_SMOKE stage=(preflight|fixture|initial_install|initial_connection|"
                r"update_install|preserved_connection|notification_rationale|tmux_chooser) "
                r"status=(start|pass|dismissed|open_shell)",
                line,
            )
            if marker:
                self.log("black_box_" + marker[1], marker[2])
        if result.returncode != 0:
            # Classify only exact, fixed harness errors; never publish private output.
            failure_reason = "unclassified"
            for message, reason in (
                ("Timed out waiting for a privacy-safe terminal marker.", "terminal_marker"),
                ("Timed out waiting for UI node content-desc=Native terminal renderer.", "terminal_renderer"),
                ("Timed out waiting for privacy-safe UI marker: Terminal", "terminal_screen"),
                ("Timed out waiting for the terminal or notification rationale.", "terminal_ready"),
            ):
                if message in result.stderr.splitlines():
                    failure_reason = reason
                    break
            self.log("black_box_failure", "fail", reason=failure_reason)
            raise ReleaseSmokeFailure("black-box release update SSH smoke failed")
        required = (
            "ssh_before=pass",
            "ssh_after=pass",
            "data=preserved",
            "credential=prompted_after_update",
            "extension=absent",
        )
        if not all(value in result.stdout for value in required):
            raise ReleaseSmokeFailure("black-box smoke result is missing a required assertion")
        self.log(
            "update_ssh",
            "pass",
            credential="prompted",
            data="preserved",
            extension="absent",
            ssh_after="pass",
            ssh_before="pass",
        )

    def cleanup(self) -> None:
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
        for generated in (
            self.project / "app/build/outputs/apk/release",
            self.project / "app/build/outputs/bundle/release",
        ):
            if generated.is_dir():
                shutil.rmtree(generated)
        if self.workspace is not None and self.workspace.is_dir():
            shutil.rmtree(self.workspace)

    def run(self) -> None:
        self.preflight()
        keystore, alias, password = self.prepare_workspace()
        private_apk, _private_aab = self.build_and_verify(keystore, alias, password)
        self.prepare_emulator()
        self.run_smoke(private_apk)
        self.log("complete", "pass", migration="same-version-reinstall", signing="ephemeral")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", type=int, default=35)
    parser.add_argument("--port", type=int, default=int(os.environ.get("TERMINAL_SPIKE_EMULATOR_PORT", "5554")))
    parser.add_argument("--output-dir", type=Path, default=Path("build/release-ci-smoke"))
    parser.add_argument("--dry-run", action="store_true")
    arguments = parser.parse_args()
    if arguments.port < 5554 or arguments.port > 5682 or arguments.port % 2:
        parser.error("emulator port must be an even console port from 5554 through 5682")
    return arguments


def main() -> int:
    arguments = parse_arguments()
    if arguments.dry_run:
        print(
            "RELEASE_CI_SMOKE_PLAN api=35 avd=terminal-spike-release-test "
            "signing=ephemeral outputs=status-only migration=same-version-reinstall"
        )
        return 0
    java_home = Path(os.environ.get("JAVA_HOME", ""))
    if java_home and (java_home / "bin/java").is_file():
        os.environ["PATH"] = f"{java_home / 'bin'}:{os.environ.get('PATH', '')}"
    runner = ReleaseSmokeRunner(arguments)
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
        failure = ReleaseSmokeFailure("release CI smoke was interrupted")
    except Exception as error:
        failure = error
    finally:
        try:
            runner.cleanup()
        except Exception as cleanup_error:
            failure = cleanup_error if failure is None else ReleaseSmokeFailure(
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
