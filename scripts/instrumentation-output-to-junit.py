#!/usr/bin/env python3
from pathlib import Path
import html
import re
import sys


def fail(message: str) -> None:
    print(f"ERROR {message}", file=sys.stderr)
    raise SystemExit(1)


if len(sys.argv) not in (3, 4) or (len(sys.argv) == 4 and sys.argv[3] != "--sharded"):
    print("ERROR expected instrumentation output and JUnit XML paths, optionally --sharded", file=sys.stderr)
    raise SystemExit(2)

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
if not source.is_file():
    fail("instrumentation output is missing")

text = source.read_text(encoding="utf-8", errors="replace")
if any(marker in text for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")):
    fail("instrumentation reported a failure")

matches = re.findall(r"\bOK \(([1-9][0-9]*) tests?\)", text)
sharded = len(sys.argv) == 4
if not matches or (not sharded and len(matches) != 1):
    expectation = "one or more" if sharded else "exactly one"
    fail(f"instrumentation did not report {expectation} nonzero OK result")

tests = sum(map(int, matches))
skipped = sum(
    text.count(f"INSTRUMENTATION_STATUS_CODE: {code}")
    for code in (-3, -4)
)
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    f'<testsuite name="android-instrumentation" tests="{tests}" failures="0" '
    f'errors="0" skipped="{skipped}">\n'
    f'  <system-out>{html.escape(text)}</system-out>\n'
    '</testsuite>\n',
    encoding="utf-8",
)
print(f"INSTRUMENTATION_RESULT tests={tests} failures=0 errors=0 skipped={skipped}")
