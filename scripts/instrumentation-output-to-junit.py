#!/usr/bin/env python3
"""Retain accurate JUnit diagnostics for failed runs without changing their exit status."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


def fail(message: str) -> None:
    print(f"ERROR {message}", file=sys.stderr)
    raise SystemExit(1)


if len(sys.argv) not in (3, 4) or (len(sys.argv) == 4 and sys.argv[3] != "--sharded"):
    print("ERROR expected instrumentation output and JUnit XML paths, optionally --sharded", file=sys.stderr)
    raise SystemExit(2)
source, destination = map(Path, sys.argv[1:3])
if not source.is_file():
    fail("instrumentation output is missing")
text = source.read_text(encoding="utf-8", errors="replace")
failed = any(marker in text for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))
matches = re.findall(r"\bOK \(([1-9][0-9]*) tests?\)", text)
valid_summary = bool(matches) and (len(sys.argv) == 4 or len(matches) == 1)
failed_summaries = re.findall(r"Tests found: ([1-9][0-9]*), Tests run: ([1-9][0-9]*),\s+Failures: ([0-9]+)", text)
complete_failure = bool(failed_summaries) and all(found == ran for found, ran, _ in failed_summaries) and (len(sys.argv) == 4 or len(failed_summaries) == 1)
records = []
fields = {}
stack = False
for line in text.splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
        if separator:
            fields[key] = value
            stack = key == "stack"
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.split(": ", 1)[1])
        if code != 1 and fields.get("class") and fields.get("test"):
            records.append((dict(fields), code))
        fields = {}
        stack = False
    elif stack:
        fields["stack"] += "\n" + line
failures = sum(code == -2 for _, code in records)
errors = sum(code not in (0, -2, -3, -4) for _, code in records)
skipped = sum(text.count(f"INSTRUMENTATION_STATUS_CODE: {code}") for code in (-3, -4))
# A crash, failure marker or inconsistent summary must remain red even if all
# completed individual records happened to pass before the process stopped.
harness_error = not (valid_summary or complete_failure) or (failed and not (failures or errors))
if valid_summary and records and len(records) != sum(map(int, matches)):
    harness_error = True
failed = failed or bool(failures or errors or harness_error)
tests = len(records) if records else sum(map(int, matches))
if harness_error:
    tests += 1
    errors += 1
suite = ET.Element("testsuite", name="android-instrumentation", tests=str(tests),
                   failures=str(failures), errors=str(errors), skipped=str(skipped))
for record, code in records:
    case = ET.SubElement(suite, "testcase", classname=record["class"], name=record["test"])
    if code in (-3, -4):
        ET.SubElement(case, "skipped").text = record.get("stack", "assumption skip")
    elif code != 0:
        ET.SubElement(case, "failure" if code == -2 else "error").text = record.get("stack", "instrumentation failure")
if harness_error:
    case = ET.SubElement(suite, "testcase", classname="instrumentation", name="run-completion")
    ET.SubElement(case, "error").text = "Instrumentation failed or did not complete consistently; see system-out."
ET.SubElement(suite, "system-out").text = text
destination.parent.mkdir(parents=True, exist_ok=True)
ET.ElementTree(suite).write(destination, encoding="utf-8", xml_declaration=True)
print(f"INSTRUMENTATION_RESULT tests={tests} failures={failures} errors={errors} skipped={skipped}")
if failed:
    fail("instrumentation reported a failure")
