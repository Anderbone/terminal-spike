import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("install_diagnostic_receiver", Path(__file__).resolve().parents[1] / "install-diagnostic-receiver.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


class InstallDiagnosticReceiverTest(unittest.TestCase):
    def test_user_configuration_is_backed_up_idempotent_and_does_not_override_explicit_tmpdir(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "checkout with spaces"
            repo.mkdir()
            config = root / "config"
            bashrc = root / ".bashrc"
            original = '# existing user configuration\n[[ $- != *i* ]] && return\n'
            bashrc.write_text(original)
            disk = root / "disk temporary"
            with patch.object(M.subprocess, "run") as run:
                hook = M.install(repo, config, bashrc, disk, "/bin/codex", "/bin/adb", start=False)
                M.install(repo, config, bashrc, disk, "/bin/codex", "/bin/adb", start=False)
                self.assertFalse(any(call.args[0][0] == "systemctl" for call in run.call_args_list))
            self.assertEqual(1, bashrc.read_text().count("# Terminal Spike debug host temporary directory"))
            self.assertTrue(bashrc.read_text().endswith(original))
            backups = list(root.glob(".bashrc.terminal-spike-backup-*"))
            self.assertEqual(1, len(backups))
            self.assertEqual(original, backups[0].read_text())
            command = 'TMPDIR=/tmp; source "$1"; printf "%s" "$TMPDIR"'
            result = subprocess.check_output(["bash", "-c", command, "bash", str(hook)], text=True)
            self.assertEqual(str(disk), result)
            result = subprocess.check_output(["bash", "-c", 'TMPDIR=/explicit; source "$1"; printf "%s" "$TMPDIR"', "bash", str(hook)], text=True)
            self.assertEqual("/explicit", result)
            unit = (config / "systemd/user/terminal-spike-diagnostics.service").read_text()
            self.assertIn('"' + str(repo / "scripts/receive-diagnostics.py") + '"', unit)
            self.assertIn("KillMode=control-group", unit)
            self.assertNotIn("publish", unit)


if __name__ == "__main__":
    unittest.main()
