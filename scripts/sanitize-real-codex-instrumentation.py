#!/usr/bin/env python3
"""Remove terminal content and arguments from real-Codex instrumentation output."""

from __future__ import annotations

import re
import sys


SAFE_STATUS_KEYS = {"class", "current", "id", "numtests", "test"}
SAFE_SIMPLE_LINES = (
    re.compile(r"^INSTRUMENTATION_STATUS_CODE: -?\d+$"),
    re.compile(r"^INSTRUMENTATION_CODE: -?\d+$"),
    re.compile(r"^Time: [0-9.]+$"),
    re.compile(r"^OK \(\d+ tests?\)$"),
    re.compile(r"^FAILURES!!!$"),
    re.compile(r"^Tests found: \d+, Tests run: \d+,  Failures: \d+$"),
)


def main() -> None:
    for raw_line in sys.stdin:
        line = raw_line.rstrip("\r\n")
        status = re.fullmatch(r"INSTRUMENTATION_STATUS: ([A-Za-z]+)=(.*)", line)
        if status is not None and status.group(1) in SAFE_STATUS_KEYS:
            value = status.group(2)
            if re.fullmatch(r"[A-Za-z0-9_.$#-]+", value):
                print(line)
            continue
        if any(pattern.fullmatch(line) for pattern in SAFE_SIMPLE_LINES):
            print(line)


if __name__ == "__main__":
    main()
