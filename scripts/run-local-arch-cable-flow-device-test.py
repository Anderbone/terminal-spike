#!/usr/bin/env python3
"""Probe Cable Flow in an already UI-verified isolated Arch app on USB SM-S911B."""
import argparse
import hashlib
import io
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

APP = "com.yanjiyu.terminalspike.archverify"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verification-checkout", required=True, type=Path)
    parser.add_argument("--source-repo", required=True, type=Path)
    parser.add_argument("--adb", default=shutil.which("adb"))
    args = parser.parse_args()
    if not args.adb:
        parser.error("Provide --adb or put adb on PATH")
    checkout = args.verification_checkout.resolve()
    if f'applicationId = "{APP}"' not in (checkout / "app/build.gradle.kts").read_text():
        parser.error("Use an isolated .archverify checkout; the normal app is forbidden")

    def verify():
        rows = subprocess.check_output([args.adb, "devices", "-l"], text=True).splitlines()
        targets = [r.split()[0] for r in rows if "device usb:" in r and "model:SM_S911B " in r]
        if len(targets) != 1:
            raise RuntimeError("Exactly one authorized USB SM_S911B is required")
        serial = targets[0]
        model = subprocess.check_output([args.adb, "-s", serial, "shell", "getprop", "ro.product.model"], text=True).strip()
        if model != "SM-S911B":
            raise RuntimeError("Refusing non-SM-S911B target")
        return serial

    verify()
    # Do not interrupt a live installer or another instrumentation run.
    for proc in Path("/proc").iterdir():
        try:
            argv = (proc / "cmdline").read_bytes().split(b"\0")
        except OSError:
            continue
        if b"instrument" in argv and args.adb.encode() in argv:
            raise RuntimeError("Another device test is running; wait for it to finish")
    subprocess.run([str(checkout / "gradlew"), ":app:assembleDebugAndroidTest"], cwd=checkout, check=True)
    apk = checkout / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    print("Test APK SHA256:", hashlib.sha256(apk.read_bytes()).hexdigest(), flush=True)
    subprocess.run([args.adb, "-s", verify(), "install", "-r", "-t", str(apk)], check=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.source_repo, text=True).strip()
    print("Cable Flow source commit:", commit, flush=True)
    raw = subprocess.check_output(["git", "archive", "HEAD"], cwd=args.source_repo)
    with tempfile.TemporaryDirectory(prefix="phone-cable-flow-") as temporary:
        archive = Path(temporary) / "source.tar"
        with tarfile.open(fileobj=io.BytesIO(raw)) as source, tarfile.open(archive, "w") as target:
            for member in source:
                name = Path(member.name)
                # Never follow external vault links or transfer host settings/auth/license material.
                if (member.issym() or member.islnk() or name.is_absolute() or ".." in name.parts or
                    any(p.startswith(".env") or p in (".codex", ".claude") for p in name.parts) or
                    name.suffix in (".lic", ".pem", ".key")):
                    continue
                if member.isfile() or member.isdir():
                    target.addfile(member, source.extractfile(member) if member.isfile() else None)
        print("Filtered archive SHA256:", hashlib.sha256(archive.read_bytes()).hexdigest(), flush=True)
        # A non-PTY adb shell writes directly to private app storage; no world-readable /sdcard copy.
        with archive.open("rb") as data:
            subprocess.run([args.adb, "-s", verify(), "shell", "-T", "run-as", APP, "sh", "-c",
                            "'cat > files/cable-flow-source.tar'"], stdin=data, check=True)
        size = subprocess.check_output([args.adb, "-s", verify(), "shell", "run-as", APP,
                                        "stat", "-c", "%s", "files/cable-flow-source.tar"], text=True)
        if int(size.strip()) != archive.stat().st_size:
            raise RuntimeError("Private source archive transfer was incomplete")
    result = subprocess.run([args.adb, "-s", verify(), "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.yanjiyu.terminalspike.localarch.ArchCableFlowDeviceTest#runsCableFlowAndCodexInTheInstalledGuest",
        "-e", "localArchCableFlow", "true", APP + ".test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner"],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, end="")
    if result.returncode or "OK (1 test)" not in result.stdout:
        raise SystemExit("Cable Flow/Codex probe failed; inspect the isolated guest's /root/cable-flow-probe.log")


if __name__ == "__main__":
    main()
