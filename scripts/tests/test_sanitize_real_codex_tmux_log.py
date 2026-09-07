from pathlib import Path
import subprocess
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "sanitize-real-codex-tmux-log.py"


class SanitizeRealCodexTmuxLogTest(unittest.TestCase):
    def test_retains_only_whitelisted_structured_evidence(self) -> None:
        raw = """I SshRealScrollE2E: tmux smooth test started
I SshRealScrollE2E: app-selected tmux connected
I SshRealScrollE2E: actual Codex output stable before first gesture; confirmed=true, outerAlternate=true, historyActive=true, metadataKnown=true, metadataFresh=false, remoteMousePassthrough=false, mouseAny=false, paneInMode=false, historyLines=4096, pane=%61, capturedStart=1175, oldestAvailable=0, remoteHistory=5271, truncatedBefore=true, pendingSnapshots=0, outerExited=false lineCount=4126 visibleHead=[SECRET_ROW] tail=[jiyu@private-host]
I SshRealScrollE2E: first actual-Codex tmux gesture=TmuxScrollGestureDiagnostic(gestureId=1, destination=LOCAL_SCROLLBACK, reason=AUTO_TMUX_LOCAL_READY, firstReason=AUTO_TMUX_LOCAL_READY, controllerStateAtDecision=confirmed=true, outerAlternate=true, historyActive=true, metadataKnown=true, metadataFresh=false, remoteMousePassthrough=false, mouseAny=false, paneInMode=false, historyLines=4096, pane=%61, capturedStart=1175, oldestAvailable=0, remoteHistory=5271, truncatedBefore=true, pendingSnapshots=0, outerExited=false, scrollYAtDecision=241631.0, localScrollUpdates=2, remoteWheelReports=0); visibleHead=[SECRET_ROW]
I SshRealScrollE2E: actual Codex reader position established autoFollow=false historyAnchor=true fractional=true flings=2 visibleHead=[SECRET_ROW]
I SshRealScrollE2E: actual Codex reader output observed autoFollow=false; confirmed=true, outerAlternate=true, historyActive=true, metadataKnown=true, metadataFresh=false, remoteMousePassthrough=false, mouseAny=false, paneInMode=false, historyLines=5300, pane=%61, archivedHistory=5070, capturedStart=0, oldestAvailable=0, remoteHistory=230, truncatedBefore=false, pendingSnapshots=0, outerExited=false visibleHead=[SECRET_ROW]
I SshRealScrollE2E: actual Codex new output preserved reader anchor autoFollow=false pixelDelta=0.0 fractional=true visibleHead=[SECRET_ROW]
I SshRealScrollE2E: actual Codex live bottom restored autoFollow=true markerVisible=true visibleTail=[jiyu@private-host]
I SshRealScrollE2E: tmux sub-row drag and fling passed visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real less ready mouseTracking=true visibleCount=27 top=1 visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real less gesture destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=4 local=0 visibleCount=27 top=13 visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real less remote mouse passed destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=4 visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real less exited before vim visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real vim readiness ready=true mouseTracking=true visibleCount=28 exitSeen=false visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real vim ready mouseTracking=true visibleCount=28 top=1 visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real vim gesture destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=3 local=0 visibleCount=28 top=7 visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real vim remote mouse passed destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=3 visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real htop readiness ready=true mouseTracking=true visibleCount=20 top=1 exitSeen=false visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real htop gesture destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=3 local=0 swipes=5 visibleCount=20 top=4 visibleTail=[jiyu@private-host]
I SshRealScrollE2E: real htop remote mouse passed destination=REMOTE_MOUSE reason=EXPLICIT_REMOTE_MOUSE reports=3 swipes=5 visibleTail=[jiyu@private-host]
E AndroidRuntime: password=never-copy-this
"""
        result = subprocess.run(
            [str(SCRIPT)], input=raw, text=True, capture_output=True, check=False
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(20, len(result.stdout.splitlines()))
        self.assertIn("stage=pre_gesture confirmed=true", result.stdout)
        self.assertIn("pane=%61", result.stdout)
        self.assertIn("stage=first_gesture gestureId=1 destination=LOCAL_SCROLLBACK", result.stdout)
        self.assertIn("remoteWheelReports=0", result.stdout)
        self.assertIn(
            "stage=reader_position autoFollow=false historyAnchor=true fractional=true flings=2",
            result.stdout,
        )
        self.assertIn(
            "stage=reader_output autoFollow=false confirmed=true outerAlternate=true "
            "historyActive=true",
            result.stdout,
        )
        self.assertIn("archivedHistory=5070", result.stdout)
        self.assertIn(
            "stage=reader_anchor autoFollow=false pixelDelta=0.0 fractional=true",
            result.stdout,
        )
        self.assertIn(
            "stage=live_bottom autoFollow=true markerVisible=true",
            result.stdout,
        )
        self.assertIn("stage=complete subrow=pass fling=pass catch=pass", result.stdout)
        self.assertIn(
            "stage=mouse_application app=less destination=REMOTE_MOUSE "
            "reason=EXPLICIT_REMOTE_MOUSE reports=4",
            result.stdout,
        )
        self.assertIn("stage=mouse_application_ready mouseTracking=true visibleCount=27 top=1", result.stdout)
        self.assertIn(
            "stage=mouse_application_gesture destination=REMOTE_MOUSE "
            "reason=EXPLICIT_REMOTE_MOUSE reports=4 local=0 visibleCount=27 top=13",
            result.stdout,
        )
        self.assertIn(
            "stage=mouse_application app=vim destination=REMOTE_MOUSE "
            "reason=EXPLICIT_REMOTE_MOUSE reports=3",
            result.stdout,
        )
        self.assertIn("stage=mouse_application_handoff from=less to=vim", result.stdout)
        self.assertIn(
            "stage=mouse_application_readiness app=vim ready=true mouseTracking=true "
            "visibleCount=28 exitSeen=false",
            result.stdout,
        )
        self.assertIn(
            "stage=mouse_application app=htop destination=REMOTE_MOUSE "
            "reason=EXPLICIT_REMOTE_MOUSE reports=3 swipes=5",
            result.stdout,
        )
        for forbidden in ("SECRET_ROW", "private-host", "password", "scrollYAtDecision"):
            self.assertNotIn(forbidden, result.stdout)

    def test_unrelated_or_partial_lines_emit_nothing(self) -> None:
        result = subprocess.run(
            [str(SCRIPT)],
            input="I Other: tmux sub-row drag and fling passed\nI SshRealScrollE2E: unrelated\n",
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
