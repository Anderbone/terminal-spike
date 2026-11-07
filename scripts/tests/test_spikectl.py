"""Host checks for the shipped agent CLI's typed argument contract."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest


CLI = Path(__file__).resolve().parents[2] / "app/src/main/assets/local-arch/control/spikectl.cjs"


@unittest.skipUnless(shutil.which("node"), "Node is required for the Local Arch CLI checks")
class SpikectlTest(unittest.TestCase):
    def parse(self, args):
        code = "const {parse} = require(process.argv[1]); process.stdout.write(JSON.stringify(parse(JSON.parse(process.argv[2]))));"
        return subprocess.run(["node", "-e", code, str(CLI), json.dumps(args)], text=True, capture_output=True, check=False)

    def test_multiline_values_and_typed_flags_remain_data(self):
        command = 'echo "$(touch never-run)"\n# 中文 `literal`'
        result = self.parse(["snippets", "add", "--name", "中文", "--command", command, "--append-enter", "false"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {"api": 1, "method": "snippets.add", "params": {
            "name": "中文", "command": command, "appendEnter": False}})

    def test_revisions_and_decks_have_protocol_types(self):
        result = self.parse(["keyboard", "update", "--id", "id", "--revision", "123", "--actions", "escape,tab", "--haptic-feedback-enabled", "true"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["params"], {"id": "id", "revision": 123, "actions": ["escape", "tab"], "hapticFeedbackEnabled": True})

    def test_invalid_and_ambiguous_flags_fail(self):
        for args in (["keyboard"], ["status", "--unknown"], ["keyboard", "update", "--revision", "1.2"],
                     ["keyboard", "update", "--revision", "9007199254740993"],
                     ["snippets", "add", "--append-enter", "yes"],
                     ["snippets", "get", "--id", "a", "--id", "b"],
                     ["snippets", "add", "--json", "[]"],
                     ["snippets", "add", "--json", "{}", "--name", "mixed"]):
            with self.subTest(args=args):
                self.assertNotEqual(self.parse(args).returncode, 0)

    def test_appearance_flags_keep_fractional_and_negative_numbers_and_booleans(self):
        result = self.parse(["terminal", "update", "--id", "id", "--revision", "123", "--font-size-sp", "17.5",
                             "--line-height-multiplier", "1.2", "--letter-spacing-em", "-0.1", "--cursor-blink-enabled", "false"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {"api": 1, "method": "terminal.update", "params": {
            "id": "id", "revision": 123, "fontSizeSp": 17.5, "lineHeightMultiplier": 1.2, "letterSpacingEm": -0.1, "cursorBlinkEnabled": False}})
        for value in ["NaN", "Infinity", "1e999", "17px", ""]:
            with self.subTest(value=value):
                self.assertNotEqual(self.parse(["terminal", "update", "--font-size-sp", value]).returncode, 0)

    def test_theme_colours_remain_strings_and_ansi_is_an_array(self):
        result = self.parse(["themes", "add", "--name", "My theme", "--base", "nord", "--background", "#000000", "--ansi16", "#000000,#FFFFFF"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["params"], {"name": "My theme", "base": "nord", "background": "#000000", "ansi16": ["#000000", "#FFFFFF"]})

    def test_help_does_not_require_a_running_phone(self):
        result = subprocess.run(["node", str(CLI), "--help"], text=True, capture_output=True, check=False)
        self.assertEqual(result.returncode, 0)
        self.assertIn("next connection", result.stdout)
        self.assertIn("--json-file", result.stdout)


if __name__ == "__main__":
    unittest.main()
