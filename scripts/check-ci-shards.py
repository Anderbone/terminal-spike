#!/usr/bin/env python3
"""Report each API's actual shard results; incomplete or failed membership stays red."""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path


def check(jobs, api, count, prefix):
    selected = {}
    for job in jobs:
        match = re.fullmatch(re.escape(prefix) + r" \((\d+), (\d+)\)", job["name"])
        if match and int(match[1]) == api:
            index = int(match[2])
            if index in selected:
                raise ValueError("Duplicate shard identity")
            selected[index] = job
    lines = [f"API {api} {prefix}:"]
    for index in range(count):
        job = selected.get(index)
        lines.append(f"- shard {index}: {job['conclusion'] if job else 'missing'}" +
                     (f" ({job['html_url']})" if job else ""))
    passed = set(selected) == set(range(count)) and all(
        job["status"] == "completed" and job["conclusion"] == "success" for job in selected.values())
    return passed, "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--scheduled", action="store_true")
    args = parser.parse_args()
    endpoint = f"repos/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}/jobs?per_page=100"
    jobs = json.loads(subprocess.check_output(["gh", "api", endpoint], timeout=120))["jobs"]
    passed, summary = check(jobs, args.api, 4 if args.scheduled else 2,
                            "scheduled-runtime-shard" if args.scheduled else "required-runtime-shard")
    print(summary, flush=True)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as stream:
            stream.write(summary + "\n")
    raise SystemExit(0 if passed else 1)
