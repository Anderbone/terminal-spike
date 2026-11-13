#!/usr/bin/env python3
"""Read-only CI and local repair status for use in any terminal, including Herdr."""
import json
import os
from pathlib import Path
import subprocess

timer = subprocess.run(["systemctl", "--user", "is-active", "terminal-spike-ci-autofix.timer"],
                       capture_output=True, text=True, timeout=10)
print("Watcher timer:", timer.stdout.strip() or "unknown")
state_path = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "terminal-spike-ci-autofix/state.json"
if state_path.is_file():
    state = json.loads(state_path.read_text())
    chain = state.get("chains", {}).get(state.get("active"), {})
    print("Automatic repair:", chain.get("phase") or ("stopped" if chain.get("stopped") else "watching"))
    print("Saved report:", (chain.get("report_summary") or state.get("status", "unknown")).split("\n\n", 1)[0])
    print("Last status change:", state.get("updated_at", "unknown"))
    print("Agent PID:", chain.get("agent_pid") or "none")
    if chain.get("origin_run"):
        print(f"Run: https://github.com/Anderbone/terminal-spike/actions/runs/{chain['origin_run']}")
    for key in ("report_path", "agent_log", "evidence_path"):
        if chain.get(key):
            print(f"{key}: {chain[key]}")
else:
    print("Local repair watcher has no saved state.")
print("\nLatest GitHub CI:", flush=True)
subprocess.run(["gh", "run", "list", "--repo", "Anderbone/terminal-spike", "--workflow", "android-ci.yml", "--limit", "5"],
               check=False, timeout=30)
