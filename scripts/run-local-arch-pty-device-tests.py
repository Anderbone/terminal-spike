#!/usr/bin/env python3
"""Run the opt-in Local Arch PTY primitive gate on the authorized old USB phone."""

import argparse
import fcntl
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TEST_CLASS = "com.yanjiyu.terminalspike.localarch.NativePtyDeviceTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--arch-bootstrap", action="store_true", help="Download/install the pinned Arch image in isolated test storage")
    parser.add_argument("--arch-development", action="store_true", help="Run real pacman/Node/native-npm workloads after the bootstrap gate")
    parser.add_argument("--arch-prisma", action="store_true", help="Run the separate stable Prisma/SQLite compatibility probe after the development gate")
    args = parser.parse_args()
    if sum([args.arch_bootstrap, args.arch_development, args.arch_prisma]) > 1:
        parser.error("Choose one gate per invocation")
    if args.arch_prisma:
        test_class = "com.yanjiyu.terminalspike.localarch.ArchPrismaDeviceTest"
        test_flag, expected_tests = "localArchPrisma", 1
    elif args.arch_development:
        test_class = "com.yanjiyu.terminalspike.localarch.ArchDevelopmentDeviceTest"
        test_flag, expected_tests = "localArchDevelopment", 1
    elif args.arch_bootstrap:
        test_class = "com.yanjiyu.terminalspike.localarch.ArchBootstrapDeviceTest"
        test_flag, expected_tests = "localArchBootstrap", 1
    else:
        test_class, test_flag, expected_tests = TEST_CLASS, "localArchPty", 4
    if not args.adb:
        parser.error("Put adb on PATH or provide --adb")
    listing = subprocess.check_output([args.adb, "devices", "-l"], text=True)
    matches = [line.split()[0] for line in listing.splitlines()
               if "device usb:" in line and "model:SM_S911B " in line]
    if len(matches) != 1:
        parser.error("Exactly one authorized USB SM_S911B is required; no wireless/foldable fallback")
    serial = matches[0]
    lock_directory = ROOT / "build/local-arch-device-tests"
    lock_directory.mkdir(parents=True, exist_ok=True)
    device_lock = (lock_directory / f"{serial}.lock").open("w")
    try:
        fcntl.flock(device_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.error("A Local Arch device gate already owns this phone")

    def verify_target():
        # Existing remote runners predate this lock. Avoid interrupting one of
        # their live installs/instrumentation processes on the shared USB phone.
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
        current = subprocess.check_output([args.adb, "devices", "-l"], text=True)
        rows = [line for line in current.splitlines() if line.split()[:1] == [serial]]
        if len(rows) != 1 or "device usb:" not in rows[0] or "model:SM_S911B " not in rows[0]:
            raise RuntimeError("Authorized old USB phone is no longer available")
        model = subprocess.check_output([args.adb, "-s", serial, "shell", "getprop", "ro.product.model"], text=True).strip()
        if model != "SM-S911B":
            raise RuntimeError(f"Refusing device model {model}")

    verify_target()
    env = dict(os.environ, ANDROID_SERIAL=serial)
    subprocess.run([str(ROOT / "gradlew"), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                    "--console=plain"], cwd=ROOT, env=env, check=True)
    for apk in [ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
        verify_target()
        subprocess.run([args.adb, "-s", serial, "install", "-r", "-t", str(apk)], env=env, check=True)
    verify_target()
    result = subprocess.run(
        [args.adb, "-s", serial, "shell", "am", "instrument", "-w", "-r",
         "-e", "class", test_class, "-e", test_flag, "true",
         "com.yanjiyu.terminalspike.test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner"],
        env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    print(result.stdout, end="")
    expected = f"OK ({expected_tests} test" + ("s)" if expected_tests != 1 else ")")
    if result.returncode != 0 or expected not in result.stdout or "FAILURES" in result.stdout:
        raise SystemExit(f"Local Arch device gate did not pass all {expected_tests} tests")
    print(f"{test_flag}: {expected_tests}/{expected_tests} passed on {serial} (USB SM-S911B). Full UI/development acceptance remains separate.")


if __name__ == "__main__":
    main()
