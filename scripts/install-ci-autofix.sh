#!/usr/bin/env bash
# Install a repository-specific watcher; it never enables automatic merge or release.
set -euo pipefail
checkout_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for tool in python3 codex gh git systemctl; do
    command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
# Resolve mise auto-updating shell wrappers once; never run those wrappers in the timer.
tool_paths="$(python3 - <<'PY'
from pathlib import Path
import shutil
import subprocess
for name in ('codex', 'gh'):
    selected = Path(shutil.which(name)).resolve()
    if selected.stat().st_size < 8192 and b'mise use -g' in selected.read_bytes():
        selected = Path(subprocess.check_output(['mise', 'which', name], text=True).strip()).resolve()
    print(selected)
PY
)"
codex_bin="${tool_paths%%$'\n'*}"
gh_bin="${tool_paths#*$'\n'}"
"$codex_bin" login status
"$gh_bin" auth status >/dev/null 2>&1
state_dir="${XDG_STATE_HOME:-$HOME/.local/state}/terminal-spike-ci-autofix"
install_dir="${XDG_DATA_HOME:-$HOME/.local/share}/terminal-spike-ci-autofix"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$install_dir" "$state_dir" "$unit_dir"
install -m 0755 "$checkout_dir/scripts/ci-autofix.py" "$install_dir/ci-autofix.py"
python3 - "$checkout_dir" "$install_dir" "$unit_dir" "$state_dir" "$codex_bin" "$gh_bin" <<'PY'
from pathlib import Path
import os
import sys
checkout, installed, units, state, codex_bin, gh_bin = map(Path, sys.argv[1:])
if any('\n' in str(path) or '\r' in str(path) for path in (checkout, installed, units, state)):
    raise SystemExit('Installation paths cannot contain line breaks')
def quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'
# Pin the installed controller copy. Agent branch edits cannot replace it.
service = f'''[Unit]
Description=Terminal Spike bounded Codex CI repair
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory={str(checkout).replace('%', '%%')}
Environment={quote('PATH=' + str(codex_bin.parent) + ':' + str(gh_bin.parent) + ':' + os.environ['PATH'])}
ExecStart=/usr/bin/python3 {quote(installed / 'ci-autofix.py')} --checkout {quote(checkout)} --state-dir {quote(state)}
TimeoutStartSec=75min
KillMode=control-group
UMask=0077
Nice=10
'''
timer = '''[Unit]
Description=Check Terminal Spike CI every minute when idle

[Timer]
OnBootSec=2min
OnUnitInactiveSec=60s
Unit=terminal-spike-ci-autofix.service

[Install]
WantedBy=timers.target
'''
(units / 'terminal-spike-ci-autofix.service').write_text(service)
(units / 'terminal-spike-ci-autofix.timer').write_text(timer)
PY
systemctl --user daemon-reload
systemctl --user enable --now terminal-spike-ci-autofix.timer
echo "Installed for Anderbone/terminal-spike only. State: $state_dir/state.json"
echo 'Progress: journalctl --user -u terminal-spike-ci-autofix.service -f'
echo 'Pause: systemctl --user stop terminal-spike-ci-autofix.timer'
