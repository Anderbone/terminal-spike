#!/usr/bin/env python3
"""Retry SDK downloads once for known transient transport failures, never tests."""
import re
import subprocess
import sys
import time


TRANSIENT_DOWNLOAD = re.compile(
    r"HTTP response code: (?:429|502|503|504)\b"
    r"|java\.net\.SocketTimeoutException"
    r"|java\.net\.SocketException: Connection reset",
    re.IGNORECASE,
)


def install(command: list[str]) -> int:
    for attempt in range(2):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, errors="replace", check=False)
        print(result.stdout, end="", flush=True)
        if result.returncode == 0:
            return 0
        if attempt or not TRANSIENT_DOWNLOAD.search(result.stdout):
            return result.returncode if result.returncode > 0 else 1
        print("Transient Android SDK download failure; retrying the same packages once in 10 seconds.",
              file=sys.stderr, flush=True)
        time.sleep(10)
    raise AssertionError("unreachable")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("Usage: install-android-sdk.py SDKMANAGER PACKAGE [PACKAGE ...]")
    sys.exit(install(sys.argv[1:]))
