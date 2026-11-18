#!/usr/bin/env python3
"""Real forwarding acceptance on the authorized USB S23; requires prebuilt debug APKs."""
import argparse
import hashlib
import ipaddress
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "integration-tests/openssh"
PACKAGE = "com.yanjiyu.terminalspike"
TEST_CLASSES = (
    PACKAGE + ".connection.PortForwardRealEndToEndTest",
    PACKAGE + ".ui.connections.PortForwardEditorTest",
    PACKAGE + ".core.data.db.AppDatabaseMigrationTest",
)


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def expected_tests():
    validator = runpy.run_path(str(ROOT / "scripts/verify-android-test-contract.py"))
    contract = validator["load_contract"](ROOT / "scripts/android-test-contract.json")
    required = contract["suites"].get("full", {}).get("required_tests", [])
    expected = {test for test in required if test.split("#", 1)[0] in TEST_CLASSES}
    if any(not any(test.startswith(test_class + "#") for test in expected)
           for test_class in TEST_CLASSES):
        raise RuntimeError("Forwarding gate requires a non-empty contract for every selected class")
    return expected


def verify_results(log, expected):
    text = log.read_text(encoding="utf-8", errors="replace")
    lines = [line for line in text.splitlines() if line.strip()]
    completions = [line for line in lines if line.startswith("INSTRUMENTATION_CODE:")]
    if completions != ["INSTRUMENTATION_CODE: -1"] or lines[-1] != completions[0]:
        raise RuntimeError("Forwarding instrumentation did not complete normally; inspect its saved output")
    converter = runpy.run_path(str(ROOT / "scripts/instrumentation-output-to-junit.py"))
    suite, failed = converter["convert"](text, sharded=False)
    actual = [f"{case.get('classname')}#{case.get('name')}" for case in suite.findall("testcase")]
    if failed or suite.get("skipped") != "0" or len(actual) != len(expected) or set(actual) != expected:
        raise RuntimeError("Forwarding gate did not pass every exact required test without skips; inspect its saved output")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--host-address", required=True, help="Workstation IPv4 on the trusted test LAN")
    parser.add_argument("--trusted-lan", action="store_true", required=True)
    options = parser.parse_args()
    address = ipaddress.IPv4Address(options.host_address)
    if address.is_loopback or address.is_unspecified or not address.is_private:
        parser.error("Use one private trusted-LAN IPv4 address")
    expected = expected_tests()
    out = ROOT / "build/port-forward-device-results"
    out.mkdir(parents=True, exist_ok=True)

    def adb(*args):
        inventory = run("adb", "devices", "-l", capture_output=True).stdout
        rows = [line.split() for line in inventory.splitlines() if line.split() and line.split()[0] == options.serial]
        if len(rows) != 1 or len(rows[0]) < 2 or rows[0][1] != "device" or "model:SM_S911B" not in rows[0] or not any(x.startswith("usb:") for x in rows[0]):
            raise RuntimeError("Target must be the connected, authorized USB SM_S911B")
        model = run("adb", "-s", options.serial, "shell", "getprop", "ro.product.model", capture_output=True).stdout.strip()
        if model != "SM-S911B":
            raise RuntimeError("Target model changed")
        return run("adb", "-s", options.serial, *args, capture_output=True)

    compose = ["docker", "compose", "--project-directory", str(FIXTURE), "-f", str(FIXTURE / "compose.yaml")]
    if run(*compose, "ps", "-q", capture_output=True).stdout.strip():
        raise RuntimeError("Disposable SSH fixture is already running; preserve it and stop this run")
    adb("get-state")
    env = os.environ | {
        "TERMINAL_SPIKE_SSH_BIND_ADDRESS": str(address),
        "TERMINAL_SPIKE_SSH_VERIFY_HOST": str(address),
        "TERMINAL_SPIKE_TEST_FORWARDING": "yes",
    }
    with (out / "fixture.log").open("w") as fixture_log:
        try:
            run(str(FIXTURE / "smoke.sh"), env=env, stdout=fixture_log, stderr=subprocess.STDOUT)
            for suffix, path in [
                ("", "app/build/outputs/apk/debug/app-debug.apk"),
                (".test", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
            ]:
                artifact = ROOT / path
                adb("install", "-r", str(artifact))
                installed = adb("shell", "pm", "path", PACKAGE + suffix).stdout.strip().removeprefix("package:")
                expected_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()
                actual_hash = adb("shell", "sha256sum", installed).stdout.split()[0]
                if actual_hash != expected_hash:
                    raise RuntimeError("Installed APK does not match tested build")
                print(f"APK_SHA256 package={PACKAGE + suffix} sha256={expected_hash}", flush=True)
            filters = ",".join(TEST_CLASSES)
            result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", filters,
                         "-e", "portForwardE2e", "true", "-e", "forwardHost", str(address),
                         PACKAGE + ".test/" + PACKAGE + ".TerminalSpikeTestRunner")
            log = out / "instrumentation.txt"
            log.write_text(result.stdout + result.stderr, encoding="utf-8")
            verify_results(log, expected)
            print(f"PORT_FORWARD_DEVICE_RESULT model=SM-S911B serial={options.serial} tests={len(expected)} failures=0 skips=0", flush=True)
        finally:
            run(*compose, "down", stdout=fixture_log, stderr=subprocess.STDOUT)


if __name__ == "__main__":
    main()
