#!/usr/bin/env python3
"""Real forwarding acceptance on the authorized USB S23; requires prebuilt debug APKs."""
import argparse
import hashlib
import ipaddress
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "integration-tests/openssh"
PACKAGE = "com.yanjiyu.terminalspike"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--host-address", required=True, help="Workstation IPv4 on the trusted test LAN")
    parser.add_argument("--trusted-lan", action="store_true", required=True)
    options = parser.parse_args()
    address = ipaddress.IPv4Address(options.host_address)
    if address.is_loopback or address.is_unspecified or not address.is_private:
        parser.error("Use one private trusted-LAN IPv4 address")
    out = ROOT / "build/port-forward-device-results"
    out.mkdir(parents=True, exist_ok=True)

    def adb(*args):
        inventory = run("adb", "devices", "-l", capture_output=True).stdout
        rows = [line.split() for line in inventory.splitlines() if line.split() and line.split()[0] == options.serial]
        if len(rows) != 1 or rows[0][1] != "device" or "model:SM_S911B" not in rows[0] or not any(x.startswith("usb:") for x in rows[0]):
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
                expected = hashlib.sha256(artifact.read_bytes()).hexdigest()
                actual = adb("shell", "sha256sum", installed).stdout.split()[0]
                if actual != expected:
                    raise RuntimeError("Installed APK does not match tested build")
                print(f"APK_SHA256 package={PACKAGE + suffix} sha256={expected}", flush=True)
            filters = ",".join([
                PACKAGE + ".connection.PortForwardRealEndToEndTest",
                PACKAGE + ".ui.connections.PortForwardEditorTest",
                PACKAGE + ".core.data.db.AppDatabaseMigrationTest",
            ])
            result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", filters,
                         "-e", "portForwardE2e", "true", "-e", "forwardHost", str(address),
                         PACKAGE + ".test/" + PACKAGE + ".TerminalSpikeTestRunner")
            (out / "instrumentation.txt").write_text(result.stdout + result.stderr)
            if "OK (7 tests)" not in result.stdout or "INSTRUMENTATION_STATUS_CODE: -3" in result.stdout or "INSTRUMENTATION_STATUS_CODE: -4" in result.stdout:
                raise RuntimeError("Device gate failed or skipped tests; inspect build/port-forward-device-results/instrumentation.txt")
            print(f"PORT_FORWARD_DEVICE_RESULT model=SM-S911B serial={options.serial} tests=7 failures=0 skips=0", flush=True)
        finally:
            run(*compose, "down", stdout=fixture_log, stderr=subprocess.STDOUT)


if __name__ == "__main__":
    main()
