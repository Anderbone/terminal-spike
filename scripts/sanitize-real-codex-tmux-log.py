#!/usr/bin/env python3
"""Emit only whitelisted, non-terminal-text evidence from the real Codex/tmux log."""

from __future__ import annotations

import re
import sys


PREFIX = "SshRealScrollE2E: "
STATE_FIELDS = (
    "confirmed",
    "outerAlternate",
    "historyActive",
    "metadataKnown",
    "metadataFresh",
    "remoteMousePassthrough",
    "mouseAny",
    "paneInMode",
    "historyLines",
    "pane",
    "archivedHistory",
    "capturedStart",
    "oldestAvailable",
    "remoteHistory",
    "truncatedBefore",
    "pendingSnapshots",
    "outerExited",
)
GESTURE_FIELDS = (
    "gestureId",
    "destination",
    "reason",
    "firstReason",
    "localScrollUpdates",
    "remoteWheelReports",
)
SAFE_VALUE = re.compile(r"[A-Za-z0-9_.%+-]+")


def values(message: str, fields: tuple[str, ...]) -> list[str]:
    result: list[str] = []
    for field in fields:
        match = re.search(rf"(?:^|[,(; ]){re.escape(field)}=([^,;) ]+)", message)
        if match is None or SAFE_VALUE.fullmatch(match.group(1)) is None:
            continue
        result.append(f"{field}={match.group(1)}")
    return result


def main() -> None:
    emitted: set[str] = set()
    for raw_line in sys.stdin:
        if PREFIX not in raw_line:
            continue
        message = raw_line.split(PREFIX, 1)[1].strip()
        if message.startswith("tmux smooth test started"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=start"
        elif message.startswith("app-selected tmux connected"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=connected"
        elif message.startswith("actual Codex output stable before first gesture;"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=pre_gesture " + " ".join(
                values(message, STATE_FIELDS)
            )
        elif message.startswith("first actual-Codex tmux gesture="):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=first_gesture " + " ".join(
                values(message, GESTURE_FIELDS + STATE_FIELDS)
            )
        elif message.startswith("actual Codex new output preserved reader anchor"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=reader_anchor " + " ".join(
                values(message, ("autoFollow", "pixelDelta", "fractional"))
            )
        elif message.startswith("actual Codex reader position established"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=reader_position " + " ".join(
                values(message, ("autoFollow", "historyAnchor", "fractional", "flings"))
            )
        elif message.startswith("actual Codex reader output observed"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=reader_output " + " ".join(
                values(message, ("autoFollow",) + STATE_FIELDS)
            )
        elif message.startswith("actual Codex live bottom restored"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=live_bottom " + " ".join(
                values(message, ("autoFollow", "markerVisible"))
            )
        elif message.startswith("tmux sub-row drag and fling passed"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=complete subrow=pass fling=pass catch=pass"
        elif message.startswith("real less ready"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_ready " + " ".join(
                values(message, ("mouseTracking", "visibleCount", "top"))
            )
        elif message.startswith("real less gesture"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_gesture " + " ".join(
                values(
                    message,
                    ("destination", "reason", "reports", "local", "visibleCount", "top"),
                )
            )
        elif message.startswith("real less remote mouse passed"):
            fields = values(message, ("destination", "reason", "reports"))
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application app=less " + " ".join(fields)
        elif message.startswith("real less exited before vim"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_handoff from=less to=vim"
        elif message.startswith("real vim readiness"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_readiness app=vim " + " ".join(
                values(message, ("ready", "mouseTracking", "visibleCount", "exitSeen"))
            )
        elif message.startswith("real vim ready"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_ready app=vim " + " ".join(
                values(message, ("mouseTracking", "visibleCount", "top"))
            )
        elif message.startswith("real vim gesture"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_gesture app=vim " + " ".join(
                values(
                    message,
                    ("destination", "reason", "reports", "local", "visibleCount", "top"),
                )
            )
        elif message.startswith("real vim remote mouse passed"):
            fields = values(message, ("destination", "reason", "reports"))
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application app=vim " + " ".join(fields)
        elif message.startswith("real htop readiness"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_readiness app=htop " + " ".join(
                values(message, ("ready", "mouseTracking", "visibleCount", "top", "exitSeen"))
            )
        elif message.startswith("real htop gesture"):
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application_gesture app=htop " + " ".join(
                values(
                    message,
                    (
                        "destination",
                        "reason",
                        "reports",
                        "local",
                        "swipes",
                        "visibleCount",
                        "top",
                    ),
                )
            )
        elif message.startswith("real htop remote mouse passed"):
            fields = values(message, ("destination", "reason", "reports", "swipes"))
            line = "REAL_CODEX_TMUX_EVIDENCE stage=mouse_application app=htop " + " ".join(fields)
        else:
            continue
        if line not in emitted:
            print(line)
            emitted.add(line)


if __name__ == "__main__":
    main()
