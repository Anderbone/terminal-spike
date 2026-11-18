from pathlib import Path
import importlib.util
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
MARKER = "LIFECYCLE_RECONNECTED_42"
SPEC = importlib.util.spec_from_file_location("lifecycle_marker_subject", ROOT / "scripts/run-app-lifecycle-e2e.py")
LIFECYCLE = importlib.util.module_from_spec(SPEC)
with patch.object(sys, "path", [str(ROOT / "scripts"), *sys.path]):
    SPEC.loader.exec_module(LIFECYCLE)
    from terminal_test_markers import has_terminal_marker, marker_input_argument


class TerminalMarkerCallerTest(unittest.TestCase):
    def lifecycle(self, xml: str):
        runner = LIFECYCLE.Runner.__new__(LIFECYCLE.Runner)
        runner.wait_for_terminal = Mock()
        runner.tap_node = Mock()
        runner.adb = Mock()
        runner.poll_attempts = 1
        runner.poll_interval = 0
        runner.dump_ui = lambda: ET.fromstring(xml)
        return runner

    def release_result(self, xml: str) -> subprocess.CompletedProcess[str]:
        source = (ROOT / "integration-tests/openssh/release-app-install-update-smoke.sh").read_text()
        helper = "send_marker_and_verify() {" + source.split("send_marker_and_verify() {", 1)[1].split("\n}\n", 1)[0] + "\n}\n"
        with tempfile.TemporaryDirectory(prefix="terminal-spike-marker-") as tmp:
            ui = Path(tmp) / "ui.xml"
            ui.write_text(xml)
            probe = (
                f"project_dir={shlex.quote(str(ROOT))}\nui_xml={shlex.quote(str(ui))}\n"
                "wait_for_terminal_ready() { :; }\nsend_marker() { :; }\ndump_ui() { :; }\n"
                "dismiss_notification_rationale_from_dump() { return 1; }\n"
                # A negative first inspection exits on its first retry; it must never return success.
                "sleep() { return 1; }\n" + helper + f"\nsend_marker_and_verify {MARKER}\n"
            )
            return subprocess.run(["sh", "-ec", probe], capture_output=True, text=True, timeout=5)

    def test_lifecycle_rejects_a_marker_that_exists_only_in_a_draft(self) -> None:
        runner = self.lifecycle(f'<hierarchy><node content-desc="Buffered terminal input" text="{MARKER}" /></hierarchy>')
        with self.assertRaises(LIFECYCLE.LifecycleFailure):
            runner.send_marker(MARKER)

    def test_lifecycle_rejects_a_native_command_echo(self) -> None:
        runner = self.lifecycle(f'<hierarchy><node content-desc="Native terminal renderer" text="$ printf {MARKER}" /></hierarchy>')
        with self.assertRaises(LIFECYCLE.LifecycleFailure):
            runner.send_marker(MARKER)

    def test_release_rejects_a_native_command_echo(self) -> None:
        result = self.release_result(f'<hierarchy><node content-desc="Native terminal renderer" text="$ printf {MARKER}" /></hierarchy>')
        self.assertNotEqual(0, result.returncode)

    def test_both_callers_accept_the_complete_native_output_line(self) -> None:
        xml = f'<hierarchy><node content-desc="Native terminal renderer" text="$ command&#10;{MARKER}&#10;$ " /></hierarchy>'
        self.lifecycle(xml).send_marker(MARKER)
        result = self.release_result(xml)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_lifecycle_submits_an_encoded_format_instead_of_the_plaintext_marker(self) -> None:
        runner = self.lifecycle(f'<hierarchy><node content-desc="Native terminal renderer" text="{MARKER}" /></hierarchy>')
        runner.send_marker(MARKER)
        submission = next(call for call in runner.adb.call_args_list if call.args[:3] == ("shell", "input", "text"))
        self.assertNotIn(MARKER, submission.args[3])
        self.assertEqual(marker_input_argument(MARKER), submission.args[3])


class TerminalMarkerProtocolTest(unittest.TestCase):
    def test_shell_input_argument_and_terminal_printf_roundtrip(self) -> None:
        for marker in (MARKER, "0_", "A" * 96):
            with self.subTest(marker=marker):
                argument = marker_input_argument(marker)
                # Model ADB's joined remote shell command, then the actual input-tool %s rule.
                input_tool = "import sys;sys.stdout.write(sys.argv[1].replace('%s',' '))"
                command = shlex.quote(sys.executable) + " -c " + shlex.quote(input_tool) + " " + argument
                typed = subprocess.run(["sh", "-c", command], capture_output=True, text=True, timeout=5)
                self.assertEqual(0, typed.returncode, typed.stderr)
                self.assertNotIn(marker, typed.stdout)
                output = subprocess.run(["sh", "-c", typed.stdout], capture_output=True, text=True, timeout=5)
                self.assertEqual(0, output.returncode, output.stderr)
                self.assertEqual(marker + "\n", output.stdout)

    def test_invalid_markers_are_rejected_before_encoding(self) -> None:
        for marker in ("", "A" * 97, "lowercase", "A B", "A;B", "$(id)", "'", "A\nB", "中文"):
            with self.subTest(marker=marker), self.assertRaises(ValueError):
                marker_input_argument(marker)

    def test_native_lines_require_the_whole_marker_and_allow_only_right_padding(self) -> None:
        for text, expected in ((MARKER, True), (MARKER + "   ", True), (" " + MARKER, False),
                               (MARKER + "X", False), ("X" + MARKER, False), ("$ printf " + MARKER, False),
                               (MARKER + "\t", False), ("", False)):
            root = ET.Element("hierarchy")
            ET.SubElement(root, "node", {"content-desc": "Native terminal renderer", "text": text})
            with self.subTest(text=text):
                self.assertEqual(expected, has_terminal_marker(root, MARKER))

    def test_other_ui_nodes_cannot_supply_native_output(self) -> None:
        root = ET.fromstring(f'<hierarchy><node content-desc="Buffered terminal input" text="{MARKER}" />'
                             f'<node text="{MARKER}" /><node content-desc="Native terminal renderer" text="waiting" /></hierarchy>')
        self.assertFalse(has_terminal_marker(root, MARKER))

    def test_cli_verifies_xml_with_spaces_in_the_path(self) -> None:
        with tempfile.TemporaryDirectory(prefix="terminal marker cli ") as tmp:
            xml = Path(tmp) / "native output.xml"
            helper = ROOT / "scripts/terminal_test_markers.py"
            for text, expected in ((MARKER, 0), ("$ printf " + MARKER, 1)):
                xml.write_text(f'<hierarchy><node content-desc="Native terminal renderer" text="{text}" /></hierarchy>')
                result = subprocess.run([sys.executable, str(helper), "verify", MARKER, str(xml)], capture_output=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
