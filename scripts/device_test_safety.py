"""Fail closed before device tests can install or clear the user's normal app."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

APP = "com.yanjiyu.terminalspike.deviceverify"
TEST_APP = APP + ".test"
RUNNER = "com.yanjiyu.terminalspike.TerminalSpikeTestRunner"
MODELS = {"SM_S911B": "SM-S911B", "SM_G9708": "SM-G9708"}


def output(*command):
    return subprocess.check_output(command, text=True).strip()


def verify_device(adb, serial):
    rows = [line.split() for line in output(adb, "devices", "-l").splitlines()]
    selected = [row for row in rows if row and row[0] == serial]
    if len(selected) != 1 or len(selected[0]) < 2 or selected[0][1] != "device":
        raise RuntimeError("Refusing unavailable or unauthorized target")
    row = selected[0]
    models = [token.removeprefix("model:") for token in row if token.startswith("model:")]
    if len(models) != 1 or models[0] not in MODELS:
        raise RuntimeError("Refusing unsupported target (including foldable phones)")
    if not any(token.startswith("usb:") for token in row):
        if any(len(other) > 1 and other[1] == "device" and any(token.startswith("usb:") for token in other)
               and any("model:" + model in other for model in MODELS) for other in rows):
            raise RuntimeError("Use the available authorized USB old phone")
    model = output(adb, "-s", serial, "shell", "getprop", "ro.product.model")
    if model != MODELS[models[0]]:
        raise RuntimeError("Inventory and actual device model disagree")
    if model == "SM-G9708" and output(adb, "-s", serial, "shell", "getprop", "ro.serialno") != "R28M227XA0E":
        raise RuntimeError("Refusing an unrecognized S10e hardware serial")
    return model


def find_aapt(adb):
    executable = os.environ.get("AAPT") or shutil.which("aapt")
    if executable:
        return executable
    sdk = Path(adb).resolve().parent.parent
    versions = sorted((p for p in (sdk / "build-tools").glob("*") if re.fullmatch(r"\d+\.\d+\.\d+", p.name)),
                      key=lambda p: tuple(map(int, p.name.split("."))), reverse=True)
    for version in versions:
        if (version / "aapt").is_file():
            return str(version / "aapt")
    raise RuntimeError("aapt is required to verify isolated APK identities")


def verify_apks(adb, app, test):
    aapt = find_aapt(adb)
    for apk, expected in ((app, APP), (test, TEST_APP)):
        badging = output(aapt, "dump", "badging", str(apk))
        names = re.findall(r"^package: name='([^']+)'", badging, re.MULTILINE)
        if names != [expected]:
            raise RuntimeError("Refusing APK outside the isolated .deviceverify application")
    tree = output(aapt, "dump", "xmltree", str(test), "AndroidManifest.xml")
    instrumentation = []
    attributes = None
    for line in tree.splitlines():
        element = re.match(r"(\s*)E: (\S+)", line)
        if element:
            if attributes is not None and len(element[1]) <= attributes[0]:
                attributes = None
            if element[2] == "instrumentation":
                attributes = (len(element[1]), {})
                instrumentation.append(attributes[1])
        if attributes is not None:
            match = re.search(r'A: android:(name|targetPackage)(?:\([^)]*\))?="([^"]+)"', line)
            if match:
                if match[1] in attributes[1]:
                    raise RuntimeError("Ambiguous instrumentation manifest")
                attributes[1][match[1]] = match[2]
    if instrumentation != [{"name": RUNNER, "targetPackage": APP}]:
        raise RuntimeError("Refusing instrumentation that does not target the isolated app")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("device", "apks"))
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial")
    parser.add_argument("--app-apk")
    parser.add_argument("--test-apk")
    args = parser.parse_args()
    if args.action == "device":
        if not args.serial:
            parser.error("--serial is required")
        print(verify_device(args.adb, args.serial))
    else:
        if not args.app_apk or not args.test_apk:
            parser.error("both APK paths are required")
        verify_apks(args.adb, args.app_apk, args.test_apk)


if __name__ == "__main__":
    main()
