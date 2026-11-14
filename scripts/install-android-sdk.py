#!/usr/bin/env python3
"""Install SDK packages and select a verified stable emulator for host tests."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time


PIN_VERSION = "37.2.12"
PIN_FILENAME = "emulator-linux_x64-16428233.zip"
PIN_SHA256 = "b08fc43d8608d2955f607f1b287beb525041e730086bdca3af152accac3af9c1"
PIN_SIZE = 349654171


TRANSIENT_DOWNLOAD = re.compile(
    r"HTTP response code: (?:429|502|503|504)\b"
    r"|java\.net\.SocketTimeoutException"
    r"|java\.net\.SocketException: Connection reset",
    re.IGNORECASE,
)


def install(command: list[str], input_text: str | None = None) -> int:
    for attempt in range(2):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, errors="replace", check=False, input=input_text)
        print(result.stdout, end="", flush=True)
        if result.returncode == 0:
            return 0
        if attempt or not TRANSIENT_DOWNLOAD.search(result.stdout):
            return result.returncode if result.returncode > 0 else 1
        print("Transient Android SDK download failure; retrying the same packages once in 10 seconds.",
              file=sys.stderr, flush=True)
        time.sleep(10)
    raise AssertionError("unreachable")


def install_image(tools: Path, image: str) -> int:
    android = tools / "android"
    sdkmanager = tools / "sdkmanager"
    if os.access(android, os.X_OK):
        status = install([str(android), "sdk", "install", image], "y\n")
        if status == 0:
            return 0
        print("Android CLI installation failed; trying sdkmanager for the same image.", flush=True)
    if not os.access(sdkmanager, os.X_OK):
        print("Android SDK installer is unavailable", file=sys.stderr)
        return 1
    return install([str(sdkmanager), image], "y\n")


def emulator_path(sdk_root: Path) -> Path:
    """Keep the observed Linux 37.2.13 GL-cleanup crash off required test runs."""
    override = os.environ.get("TERMINAL_SPIKE_EMULATOR_BIN")
    if override:
        return Path(override)
    default = sdk_root / "emulator/emulator"
    import platform
    if platform.system() != "Linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        return default
    properties = sdk_root / "emulator/source.properties"
    revision = re.search(r"^Pkg\.Revision\s*=\s*(\S+)\s*$", properties.read_text(), re.M) if properties.is_file() else None
    if revision and revision[1] == PIN_VERSION:
        return default
    import fcntl
    import hashlib
    import shutil
    import stat
    import tempfile
    import zipfile
    from urllib.request import urlopen

    # Official stable archive and checksum: developer.android.com/studio/emulator_archive.
    version, filename, digest, size = PIN_VERSION, PIN_FILENAME, PIN_SHA256, PIN_SIZE
    cache = Path.home() / ".cache/terminal-spike-tools"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / f"emulator-{version}-{digest[:8]}"
    binary = target / "emulator/emulator"
    marker = target / ".verified.sha256"
    with (cache / f"emulator-{version}.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if marker.is_file() and marker.read_text().strip() == digest and os.access(binary, os.X_OK):
            return binary
        if target.exists():
            raise RuntimeError(f"Incomplete pinned emulator cache: {target}")
        archive = cache / filename
        if not archive.exists():
            temporary = archive.with_suffix(".download")
            try:
                start = time.monotonic()
                with urlopen("https://dl.google.com/android/repository/" + filename, timeout=30) as response, temporary.open("wb") as output:
                    total = 0
                    while chunk := response.read(1024 * 1024):
                        total += len(chunk)
                        if total > size or time.monotonic() - start > 180:
                            raise RuntimeError("Pinned emulator download exceeded its size/time bound")
                        output.write(chunk)
                temporary.replace(archive)
            finally:
                temporary.unlink(missing_ok=True)
        actual = hashlib.sha256()
        with archive.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                actual.update(chunk)
        if archive.stat().st_size != size or actual.hexdigest() != digest:
            raise RuntimeError("Pinned emulator archive checksum/size mismatch")
        with tempfile.TemporaryDirectory(prefix=".emulator-", dir=cache) as temporary:
            stage = Path(temporary)
            with zipfile.ZipFile(archive) as bundle:
                if sum(info.file_size for info in bundle.infolist()) > 4 * 1024**3:
                    raise RuntimeError("Pinned emulator archive expands beyond its bound")
                for info in bundle.infolist():
                    destination = stage / info.filename
                    if not destination.resolve().is_relative_to(stage.resolve()) or stat.S_ISLNK(info.external_attr >> 16):
                        raise RuntimeError("Unsafe pinned emulator archive member")
                    if info.is_dir():
                        destination.mkdir(parents=True, exist_ok=True)
                    else:
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        with bundle.open(info) as source, destination.open("wb") as output:
                            shutil.copyfileobj(source, output)
                        destination.chmod((info.external_attr >> 16) & 0o777 or 0o644)
            if not re.search(rf"^Pkg\.Revision\s*=\s*{re.escape(version)}\s*$", (stage / "emulator/source.properties").read_text(), re.M):
                raise RuntimeError("Pinned emulator revision mismatch")
            (stage / ".verified.sha256").write_text(digest + "\n")
            stage.rename(target)
        print(f"Using verified Android Emulator {version} for the Linux GL cleanup regression", file=sys.stderr)
    return binary


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--emulator-path":
        print(emulator_path(Path(sys.argv[2])))
        sys.exit(0)
    if len(sys.argv) == 4 and sys.argv[1] == "--system-image":
        sys.exit(install_image(Path(sys.argv[2]), sys.argv[3]))
    if len(sys.argv) < 3:
        sys.exit("Usage: install-android-sdk.py SDKMANAGER PACKAGE [PACKAGE ...]")
    sys.exit(install(sys.argv[1:]))
