#!/usr/bin/env python3
"""Retain accurate JUnit diagnostics for failed runs without changing their exit status."""
from dataclasses import dataclass, field
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


SUMMARY = re.compile(
    r"\s*(?:OK \((?P<success>[1-9][0-9]*) tests?\)|"
    r"(?:Tests found:\s*(?P<found>[1-9][0-9]*),\s*)?"
    r"Tests run:\s*(?P<ran>[1-9][0-9]*),\s*Failures:\s*(?P<failed>[0-9]+))\s*"
)
FINAL_CODES = (0, -1, -2, -3, -4)
XML_FORBIDDEN = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\ud800-\udfff\ufffe\uffff]")


def xml_text(value: str) -> str:
    """Keep terminal controls visible without writing characters forbidden in XML 1.0."""
    return XML_FORBIDDEN.sub(lambda match: f"\\u{ord(match[0]):04x}", value)


@dataclass
class InstrumentationRun:
    records: list[tuple[dict[str, str], int]] = field(default_factory=list)
    final_codes: list[int] = field(default_factory=list)
    started: int = 0
    summary: tuple[int, int] | None = None
    invalid: bool = False

    def counts(self) -> dict[str, int]:
        if self.records:
            codes = [code for _, code in self.records]
            return dict(
                tests=len(codes), failures=codes.count(-2),
                errors=sum(code not in (0, -2, -3, -4) for code in codes),
                skipped=sum(code in (-3, -4) for code in codes),
            )
        # Older callers supply summaries and unidentified skip statuses without case records.
        tests, failures = self.summary or (0, 0)
        return dict(tests=tests, failures=failures, errors=0,
                    skipped=sum(code in (-3, -4) for code in self.final_codes))

    def consistent(self) -> bool:
        if self.invalid or self.summary is None or self.started > len(self.final_codes):
            return False
        total, failures = self.summary
        counts = self.counts()
        if counts["skipped"] > total or failures > total:
            return False
        if self.records:
            return (len(self.records) == len(self.final_codes) == total and
                    counts["failures"] + counts["errors"] == failures)
        return (len(self.final_codes) <= total and
                sum(code in (-1, -2) for code in self.final_codes) <= failures)


def parse_runs(text: str) -> list[InstrumentationRun]:
    runs: list[InstrumentationRun] = []
    run = InstrumentationRun()
    fields: dict[str, str] = {}
    stack = False
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                fields[key] = value
                stack = key == "stack"
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.split(": ", 1)[1])
            except ValueError:
                run.invalid = True
            else:
                if code == 1:
                    run.started += 1
                else:
                    run.final_codes.append(code)
                    if code not in FINAL_CODES:
                        run.invalid = True
                    if fields.get("class") and fields.get("test"):
                        run.records.append((dict(fields), code))
            fields = {}
            stack = False
        elif not fields and (match := SUMMARY.fullmatch(line)):
            total = int(match["success"] or match["ran"])
            failures = int(match["failed"] or 0)
            if match["found"] is not None and int(match["found"]) != total:
                run.invalid = True
            run.summary = total, failures
            runs.append(run)
            run = InstrumentationRun()
            fields = {}
            stack = False
        elif stack:
            fields["stack"] += "\n" + line
    if fields or run.records or run.final_codes or run.started or run.invalid:
        run.invalid = run.invalid or bool(fields)
        runs.append(run)
    return runs


def convert(text: str, *, sharded: bool) -> tuple[ET.Element, bool]:
    runs = parse_runs(text)
    counts = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
    records = []
    seen = set()
    harness_error = not runs or (not sharded and len(runs) != 1)
    for run in runs:
        harness_error = harness_error or not run.consistent()
        for name, value in run.counts().items():
            counts[name] += value
        for record, code in run.records:
            identifier = record["class"], record["test"]
            if identifier in seen:
                harness_error = True
            seen.add(identifier)
            records.append((record, code))
    if records and len(records) != counts["tests"]:
        # Keep legacy summary-only runs, but do not present partial identities as complete detail.
        harness_error = True
    # Actual crashes remain distinct from completed assertion failures, even after a summary.
    if any(marker in text for marker in ("INSTRUMENTATION_FAILED", "Process crashed")):
        harness_error = True
    if "FAILURES!!!" in text and not (counts["failures"] or counts["errors"]):
        harness_error = True
    if harness_error:
        counts["tests"] += 1
        counts["errors"] += 1
    suite = ET.Element("testsuite", name="android-instrumentation",
                       **{name: str(value) for name, value in counts.items()})
    for record, code in records:
        case = ET.SubElement(suite, "testcase", classname=xml_text(record["class"]), name=xml_text(record["test"]))
        if code in (-3, -4):
            ET.SubElement(case, "skipped").text = xml_text(record.get("stack", "assumption skip"))
        elif code != 0:
            ET.SubElement(case, "failure" if code == -2 else "error").text = xml_text(record.get("stack", "instrumentation failure"))
    if harness_error:
        case = ET.SubElement(suite, "testcase", classname="instrumentation", name="run-completion")
        ET.SubElement(case, "error").text = "Instrumentation failed or did not complete consistently; see system-out."
    ET.SubElement(suite, "system-out").text = xml_text(text)
    return suite, bool(counts["failures"] or counts["errors"])


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3) or (len(argv) == 3 and argv[2] != "--sharded"):
        print("ERROR expected instrumentation output and JUnit XML paths, optionally --sharded", file=sys.stderr)
        return 2
    source, destination = map(Path, argv[:2])
    if not source.is_file():
        print("ERROR instrumentation output is missing", file=sys.stderr)
        return 1
    text = source.read_text(encoding="utf-8", errors="replace")
    suite, failed = convert(text, sharded=len(argv) == 3)
    destination.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(destination, encoding="utf-8", xml_declaration=True)
    print("INSTRUMENTATION_RESULT " + " ".join(
        f"{name}={suite.get(name)}" for name in ("tests", "failures", "errors", "skipped")
    ))
    if failed:
        print("ERROR instrumentation reported a failure", file=sys.stderr)
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
