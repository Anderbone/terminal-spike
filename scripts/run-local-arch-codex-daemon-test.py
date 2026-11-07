#!/usr/bin/env python3
"""Reproduce the Android ps failure and verify real Codex daemon startup on the authorized S10e."""

import argparse
import base64
import fcntl
import hashlib
import lzma
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
APP = "com.yanjiyu.terminalspike"
TEST = "com.yanjiyu.terminalspike.localarch.ArchCodexDaemonDeviceTest"
ARCH_URL = "https://github.com/gwitko/conduit-rootfs/releases/download/rootfs-pd-v4.37.0/archlinux-aarch64-pd-v4.37.0.tar.xz"
ARCH_SHA256 = "718151cc4adad701223c689a7e4690cb7710b7b16e9b23617b671856ff04d563"
CODEX_URL = "https://registry.npmjs.org/@openai/codex/-/codex-0.166.0-linux-arm64.tgz"
CODEX_SHA512 = "w0ScAsBr9aZKtIFrh6HN6KU2yjKKeSCzYfbb7DB0Ccj4BYPzeZtUeQprbycLCwuX3AFQdbdjapmLho2iot74jQ=="


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact currently connected S10e ADB serial")
    parser.add_argument("--no-build", action="store_true", help="Reuse APKs from the passing shared verification gate")
    parser.add_argument("--app-apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--keep-fixture", action="store_true", help="Retain the separate guest for focused diagnosis")
    args = parser.parse_args()
    work = ROOT / "build/local-arch-codex-daemon"
    work.mkdir(parents=True, exist_ok=True)
    lock = (work / "device.lock").open("w")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def adb(*words, **kwargs):
        return subprocess.run(["adb", "-s", args.serial, *words], check=True, **kwargs)

    def verify():
        listing = subprocess.check_output(["adb", "devices", "-l"], text=True)
        rows = [line.split() for line in listing.splitlines() if line.split()[:1] == [args.serial]]
        if len(rows) != 1 or rows[0][1] != "device" or "model:SM_G9708" not in rows[0]:
            raise RuntimeError("The selected authorized S10e is unavailable; never substitute the foldable")
        model = subprocess.check_output(["adb", "-s", args.serial, "shell", "getprop", "ro.product.model"], text=True).strip()
        hardware = subprocess.check_output(["adb", "-s", args.serial, "shell", "getprop", "ro.serialno"], text=True).strip()
        if model != "SM-G9708" or hardware != "R28M227XA0E":
            raise RuntimeError(f"Refusing device {model} / {hardware}")

    def run_as(*words, **kwargs):
        return adb("shell", "-T", shlex.join(["run-as", APP, *words]), **kwargs)

    def download(url, name, algorithm, expected):
        path = work / name
        if not path.exists():
            pending = path.with_suffix(path.suffix + ".part")
            urllib.request.urlretrieve(url, pending)
            pending.replace(path)
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, algorithm).digest()
        actual = digest.hex() if algorithm == "sha256" else base64.b64encode(digest).decode()
        if actual != expected:
            path.unlink()
            raise RuntimeError(f"Checksum mismatch: {name}")
        return path

    verify()
    if not args.no_build:
        with (work / "build.log").open("w") as log:
            subprocess.run([str(ROOT / "gradlew"), "--dependency-verification=strict", "--console=plain",
                            "--max-workers=2", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    apks = [args.app_apk.resolve(), args.test_apk.resolve()]
    for apk in apks:
        if not apk.is_file():
            raise RuntimeError(f"Missing verified APK: {apk}")
    for apk in apks:
        verify()
        adb("install", "-r", "-t", str(apk))

    archive = download(ARCH_URL, "archlinux-aarch64.tar.xz", "sha256", ARCH_SHA256)
    package = download(CODEX_URL, "codex-0.166.0-linux-arm64.tgz", "sha512", CODEX_SHA512)
    fixture = "files/codex-daemon-fixture"
    temporary = "files/codex-test-tmp"
    verify()
    run_as("rm", "-rf", fixture, temporary)
    run_as("mkdir", "-p", fixture, temporary)
    try:
        # Android's tar has no xz executable. Decompress on the host and stream
        # into a dedicated guest, retaining the reviewed archive's root prefix.
        with lzma.open(archive) as source:
            process = subprocess.Popen(["adb", "-s", args.serial, "shell", "-T",
                                        shlex.join(["run-as", APP, "tar", "-xof", "-", "-C", fixture])],
                                       stdin=subprocess.PIPE)
            try:
                shutil.copyfileobj(source, process.stdin, 1024 * 1024)
            finally:
                process.stdin.close()
            if process.wait() != 0:
                raise RuntimeError("Arch fixture extraction failed")
        runtime = work / "codex-runtime.tar"
        with tarfile.open(package, "r|gz") as source, tarfile.open(runtime, "w") as destination:
            prefix = "package/vendor/aarch64-unknown-linux-musl/"
            for member in source:
                if not member.name.startswith(prefix):
                    continue
                member.name = member.name[len(prefix):]
                if member.name:
                    destination.addfile(member, source.extractfile(member) if member.isfile() else None)
        runtime_dir = fixture + "/archlinux-aarch64/opt/codex"
        run_as("mkdir", "-p", runtime_dir)
        with runtime.open("rb") as source:
            run_as("tar", "-xof", "-", "-C", runtime_dir, stdin=source)
        runtime.unlink()
        verify()
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", TEST,
                     "-e", "localArchCodexDaemon", "true",
                     APP + ".test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner",
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (work / "instrumentation.log").write_text(result.stdout)
        if "OK (1 test)" not in result.stdout or "ARCH_CODEX_DAEMON_OK" not in result.stdout:
            raise RuntimeError(f"Codex daemon regression failed; inspect {work / 'instrumentation.log'}")
        print(f"Codex daemon: 1/1 passed on SM-G9708 ({args.serial}); log={work / 'instrumentation.log'}")
    finally:
        verify()
        if not args.keep_fixture:
            run_as("rm", "-rf", fixture, temporary)


if __name__ == "__main__":
    main()
