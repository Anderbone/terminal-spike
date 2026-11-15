#!/usr/bin/env python3
"""Install the explicitly authorized local debug receiver and a disk-backed temporary directory."""
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import time


def unit_quote(value):
    if any(char in value for char in "\n\r\0"):
        raise ValueError("Invalid systemd argument")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("%", "%%") + '"'


def install(repo, config, bashrc, temporary, codex, adb, start=True):
    repo, config, bashrc, temporary = map(lambda value: Path(value).resolve(), (repo, config, bashrc, temporary))
    temporary.mkdir(parents=True, exist_ok=True, mode=0o700)
    subprocess.run([sys.executable, "-c", "import os,tempfile; f,p=tempfile.mkstemp(dir=os.sys.argv[1]); os.write(f,b'x'*4096); os.close(f); os.unlink(p)", str(temporary)], check=True)
    app = config / "terminal-spike"
    app.mkdir(parents=True, exist_ok=True)
    environment = app / "debug-host-env.sh"
    environment.write_text("# Large development and shell temporary files belong on disk, not quota-limited /tmp.\n"
        'if [ -z "${TMPDIR:-}" ] || [ "${TMPDIR:-}" = /tmp ] || [ "${TMPDIR:-}" = /tmp/ ]; then\n'
        f"  mkdir -p -- {shlex.quote(str(temporary))}\n"
        f"  export TMPDIR={shlex.quote(str(temporary))}\nfi\n"
        'export TERMINAL_SPIKE_GRADLE_WORKERS="${TERMINAL_SPIKE_GRADLE_WORKERS:-1}"\n'
        'export TERMINAL_SPIKE_JVM_PROCESSORS="${TERMINAL_SPIKE_JVM_PROCESSORS:-1}"\n')
    original = bashrc.read_text() if bashrc.exists() else ""
    marker = "# Terminal Spike debug host temporary directory"
    if marker not in original:
        if bashrc.exists():
            shutil.copy2(bashrc, bashrc.with_name(bashrc.name + f".terminal-spike-backup-{time.time_ns()}"))
        hook = f"{marker}\nif [ -r {shlex.quote(str(environment))} ]; then . {shlex.quote(str(environment))}; fi\n\n"
        bashrc.write_text(hook + original)
    environment_dir = config / "environment.d"
    environment_dir.mkdir(exist_ok=True)
    (environment_dir / "70-terminal-spike-tmp.conf").write_text("TMPDIR=" + str(temporary) + "\n")
    units = config / "systemd/user"
    units.mkdir(parents=True, exist_ok=True)
    command = [sys.executable, str(repo / "scripts/receive-diagnostics.py"), "--repo", str(repo), "--codex", str(codex), "--adb", str(adb)]
    (units / "terminal-spike-diagnostics.service").write_text(
        "[Unit]\nDescription=Terminal Spike local diagnostic receiver and read-only analysis\n\n"
        "[Service]\nType=simple\n" + "WorkingDirectory=" + str(repo).replace("%", "%%") + "\n"
        + "Environment=" + unit_quote("TMPDIR=" + str(temporary)) + "\n"
        + "ExecStart=" + " ".join(unit_quote(arg) for arg in command) + "\n"
        + "Restart=on-failure\nRestartSec=10\nKillMode=control-group\nTimeoutStopSec=20\nUMask=0077\nNoNewPrivileges=true\n"
        + "Nice=19\nCPUQuota=50%\nCPUWeight=1\nIOWeight=10\nIOSchedulingClass=idle\nMemoryHigh=512M\nMemoryMax=2G\n\n"
        + "[Install]\nWantedBy=default.target\n")
    if start:
        subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
        subprocess.run(["systemctl", "--user", "set-environment", "TMPDIR=" + str(temporary)], check=True)
        subprocess.run(["systemctl", "--user", "enable", "--now", "terminal-spike-diagnostics.service"], check=True)
    return environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--config-dir", type=Path, default=Path.home() / ".config")
    parser.add_argument("--bashrc", type=Path, default=Path.home() / ".bashrc")
    parser.add_argument("--temporary", type=Path, default=Path.home() / ".cache/terminal-spike/tmp")
    parser.add_argument("--codex", default=shutil.which("codex"))
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--no-start", action="store_true")
    args = parser.parse_args()
    if not args.codex or not args.adb:
        parser.error("Existing Codex authentication/installation and authorized ADB are required")
    install(args.repo, args.config_dir, args.bashrc, args.temporary, args.codex, args.adb, not args.no_start)
    print("Local receiver configured. No application release or Git operation was performed.")


if __name__ == "__main__":
    main()
