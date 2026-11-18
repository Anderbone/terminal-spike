#!/usr/bin/env python3
"""Run the PTY gate on an authorized S23 USB or S10e target; Arch gates stay USB S23-only."""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TEST_CLASS = "com.yanjiyu.terminalspike.localarch.NativePtyDeviceTest"
S10E_HARDWARE_SERIAL = "R28M227XA0E"


def target_model(row, primitive_gate):
    fields = row.split()
    if len(fields) < 2 or fields[1] != "device":
        return None
    if "model:SM_S911B" in fields and any(f.startswith("usb:") for f in fields):
        return "SM-S911B"
    if primitive_gate and "model:SM_G9708" in fields:
        return "SM-G9708"
    return None


def select_target(listing, serial, primitive_gate):
    rows = [row for row in listing.splitlines() if target_model(row, primitive_gate)]
    if serial:
        rows = [row for row in rows if row.split()[0] == serial]
    else:
        usb = [row for row in rows if any(f.startswith("usb:") for f in row.split())]
        if usb:
            rows = usb
    if len(rows) != 1:
        raise RuntimeError("Select one authorized USB S23 or PTY-only S10e with --serial; no foldable fallback")
    return rows[0].split()[0], target_model(rows[0], primitive_gate)


def adb_output(adb, *args):
    return subprocess.check_output([adb, *args], text=True, timeout=30).strip()


def verify_identity(adb, serial, model, primitive_gate):
    rows = [row for row in adb_output(adb, "devices", "-l").splitlines()
            if row.split()[:1] == [serial]]
    if len(rows) != 1 or target_model(rows[0], primitive_gate) != model:
        raise RuntimeError("Authorized target is no longer available on its permitted transport")
    if adb_output(adb, "-s", serial, "shell", "getprop", "ro.product.model") != model:
        raise RuntimeError("Device model changed; refusing install or test")
    if model == "SM-G9708" and adb_output(adb, "-s", serial, "shell", "getprop", "ro.serialno") != S10E_HARDWARE_SERIAL:
        raise RuntimeError("Refusing an unapproved S10e hardware serial")


def verify_results(log, test_class):
    contract = json.loads((ROOT / "scripts/android-test-contract.json").read_text())
    expected = {test for test in contract["suites"]["full"]["required_tests"]
                if test.startswith(test_class + "#")}
    parsed = runpy.run_path(str(ROOT / "scripts/verify-android-test-contract.py"))["parse_results"](log)
    if not expected or {test for test, _ in parsed} != expected or any(status != "passed" for _, status in parsed):
        raise RuntimeError("Device gate did not pass every exact required test without skips")
    output = log.read_text()
    if "INSTRUMENTATION_FAILED" in output or "FAILURES" in output or "INSTRUMENTATION_CODE: -1" not in output:
        raise RuntimeError("Instrumentation did not complete normally")
    return len(parsed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--serial", help="Exact current authorized ADB endpoint")
    parser.add_argument("--no-build", action="store_true", help="Reuse APKs from a completed verification build")
    parser.add_argument("--arch-bootstrap", action="store_true", help="Download/install the pinned Arch image in isolated test storage")
    parser.add_argument("--arch-development", action="store_true", help="Run real pacman/Node/native-npm workloads after the bootstrap gate")
    parser.add_argument("--arch-prisma", action="store_true", help="Run the separate stable Prisma/SQLite compatibility probe after the development gate")
    args = parser.parse_args()
    if sum([args.arch_bootstrap, args.arch_development, args.arch_prisma]) > 1:
        parser.error("Choose one gate per invocation")
    if args.arch_prisma:
        test_class = "com.yanjiyu.terminalspike.localarch.ArchPrismaDeviceTest"
        test_flag = "localArchPrisma"
    elif args.arch_development:
        test_class = "com.yanjiyu.terminalspike.localarch.ArchDevelopmentDeviceTest"
        test_flag = "localArchDevelopment"
    elif args.arch_bootstrap:
        test_class = "com.yanjiyu.terminalspike.localarch.ArchBootstrapDeviceTest"
        test_flag = "localArchBootstrap"
    else:
        test_class, test_flag = TEST_CLASS, "localArchPty"
    primitive_gate = test_class == TEST_CLASS
    if not args.adb:
        parser.error("Put adb on PATH or provide --adb")
    serial, model = select_target(adb_output(args.adb, "devices", "-l"), args.serial, primitive_gate)
    lock_directory = ROOT / "build/local-arch-device-tests"
    lock_directory.mkdir(parents=True, exist_ok=True)
    # The endpoint is opaque; never interpret it as a path component.
    device_lock = (lock_directory / (hashlib.sha256(serial.encode()).hexdigest() + ".lock")).open("w")
    try:
        fcntl.flock(device_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.error("A Local Arch device gate already owns this phone")

    def verify_target():
        # Existing remote runners predate this lock. Avoid interrupting one of
        # their live installs/instrumentation processes on the shared phone.
        for process in Path("/proc").iterdir():
            if not process.name.isdigit() or int(process.name) == os.getpid():
                continue
            try:
                argv = (process / "cmdline").read_bytes().decode(errors="replace").split("\0")
            except (OSError, PermissionError):
                continue
            other_runner = any(Path(arg).name.startswith("run-real-") or
                               Path(arg).name == "run-terminal-task-ui-tests.py" for arg in argv)
            if other_runner or (serial in argv and "instrument" in argv):
                raise RuntimeError(f"Another terminal device runner is live (PID {process.name}); wait for it")
        verify_identity(args.adb, serial, model, primitive_gate)

    verify_target()
    env = dict(os.environ, ANDROID_SERIAL=serial)
    if not args.no_build:
        subprocess.run([str(ROOT / "gradlew"), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                        "--dependency-verification=strict", "--console=plain"], cwd=ROOT, env=env, check=True)
    for apk in [ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        verify_target()
        subprocess.run([args.adb, "-s", serial, "install", "-r", "-t", "--no-incremental", str(apk)], env=env, check=True)
    verify_target()
    result = subprocess.run(
        [args.adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
         "-e", "class", test_class, "-e", test_flag, "true",
         "com.yanjiyu.terminalspike.test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner"],
        env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=180 if primitive_gate else 3600,
    )
    log = lock_directory / (hashlib.sha256(serial.encode()).hexdigest() + ".log")
    log.write_text(result.stdout)
    print(result.stdout, end="")
    if result.returncode != 0:
        raise SystemExit("Local Arch instrumentation command failed")
    passed = verify_results(log, test_class)
    print(f"{test_flag}: {passed}/{passed} exact passes, zero skips on {serial} ({model}). Full UI/development acceptance remains separate.")


if __name__ == "__main__":
    main()
