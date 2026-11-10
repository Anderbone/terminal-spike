#!/usr/bin/env python3
"""Local Codex reply excerpts for Terminal Spike, transported only over existing SSH.

Install as ~/.local/bin/terminal-task-notify. No network or model calls.
Codex notify = ["/absolute/path/terminal-task-notify", "--notify"].
"""
from contextlib import closing
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
import unicodedata

LIMIT = 256 * 1024
ID = re.compile(r"[a-zA-Z0-9_-]{1,128}\Z")


def excerpt(value, limit=400):
    if not isinstance(value, str):
        return ""
    # Preserve the author's wording, including failures/questions; never invent success.
    value = re.sub(r"\[([^\]]+)\]\([^\n)]*\)", r"\1", value)
    value = re.sub(r"(?m)^\s{0,3}(?:#{1,6}\s+|[-*+]\s+)", "", value)
    value = value.replace("**", "").replace("`", "")
    value = "".join(c if not unicodedata.category(c).startswith("C") else " " for c in value)
    value = " ".join(value.split())
    return value if len(value) <= limit else value[:limit - 1].rstrip() + "…"


def codex_home():
    return Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))).resolve()


def event_dir():
    return Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "terminal-spike/replies"


def save_event(payload):
    thread, turn = payload.get("thread-id", ""), payload.get("turn-id", "")
    if payload.get("type") != "agent-turn-complete" or not ID.fullmatch(thread) or not ID.fullmatch(turn):
        return
    message = excerpt(payload.get("last-assistant-message"))
    if not message:
        return
    directory = event_dir()
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    event = {"id": turn, "message": message}
    fd, name = tempfile.mkstemp(dir=directory)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(event, stream, ensure_ascii=False)
        os.replace(name, directory / (thread + ".json"))
        # Bounded local retention; old event files are only an optional cache.
        for path in sorted(directory.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)[256:]:
            path.unlink(missing_ok=True)
    finally:
        Path(name).unlink(missing_ok=True)


def rollout_reply(path, home):
    """Compatibility for already running Codex clients that have not loaded notify.

    Only the exact known thread's bounded tail; never scan unrelated transcripts.
    Unknown schema/missing final event fails closed to lifecycle metadata.
    """
    path = Path(path).resolve()
    if not path.is_relative_to(home / "sessions"):
        return None
    with path.open("rb") as stream:
        size = path.stat().st_size
        offset = max(0, size - LIMIT)
        stream.seek(offset)
        lines = stream.read(LIMIT).splitlines()
    if offset:
        lines = lines[1:]
    for line in reversed(lines):
        try:
            event = json.loads(line)
        except (ValueError, UnicodeDecodeError):
            continue
        payload = event.get("payload", {})
        if event.get("type") != "event_msg":
            continue
        if payload.get("type") == "task_started":
            return {}  # Known running turn; suppress the previous turn's cached reply.
        if payload.get("type") == "task_complete":
            turn = payload.get("turn_id")
            message = excerpt(payload.get("last_agent_message"))
            if isinstance(turn, str) and ID.fullmatch(turn) and message:
                return {"id": turn, "message": message}
    return None


def probe_reply(thread):
    if not isinstance(thread, str) or not ID.fullmatch(thread):
        return False, None
    home = codex_home()
    # The rollout is authoritative for a newer running/completed turn; the supported
    # notify cache is used if this Codex version doesn't expose the local index.
    for database in sorted(home.glob("state_*.sqlite"), reverse=True)[:3]:
        try:
            with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True, timeout=0.1)) as db:
                row = db.execute("SELECT rollout_path FROM threads WHERE id = ?", (thread,)).fetchone()
            if row:
                reply = rollout_reply(row[0], home)
                if reply is not None:
                    return True, reply or None
        except (sqlite3.Error, OSError, ValueError):
            continue
    try:
        path = event_dir() / (thread + ".json")
        if path.stat().st_size > 4096:
            return False, None
        event = json.loads(path.read_text())
        if ID.fullmatch(event.get("id", "")) and isinstance(event.get("message"), str):
            return True, {"id": event["id"], "message": excerpt(event["message"])}
    except (OSError, ValueError, TypeError):
        pass
    return False, None


def reply_for(thread):
    return probe_reply(thread)[1]


def enrich(root):
    agents = root["result"]["agents"]
    if len(agents) > 256:
        raise ValueError("too many agents")
    for agent in agents:
        # Never consume inherited HERDR_PANE_ID: shared Codex daemons can retain
        # another client's old environment. Join strictly by actual thread ID.
        session = agent.get("agent_session") or {}
        if session.get("agent") == "codex" and session.get("kind") == "id":
            supported, reply = probe_reply(session.get("value"))
            if supported:
                agent["task_reply_source"] = "codex"
            if agent.get("agent_status") == "blocked":
                continue
            if reply:
                agent["task_reply"] = reply
    return root


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--notify":
        save_event(json.loads(sys.argv[2]))
    elif len(sys.argv) == 4 and sys.argv[1] == "--agents":
        # File-backed stdout avoids unbounded buffering; Android independently
        # enforces the complete command's deadline and response-size limit.
        with tempfile.TemporaryFile() as output:
            result = subprocess.run([sys.argv[2], "--session", sys.argv[3], "agent", "list"],
                                    stdout=output, stderr=subprocess.DEVNULL, timeout=3, check=False)
            if result.returncode or output.tell() > LIMIT:
                return 1
            output.seek(0)
            root = json.loads(output.read(LIMIT))
        print(json.dumps(enrich(root), ensure_ascii=False))
    else:
        return 2
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
        sys.exit(1)
