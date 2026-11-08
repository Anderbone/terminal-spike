"""Exercise starter tool installation without touching host packages or profiles."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'app/src/main/assets/local-arch/starter-tools.sh'


class StarterToolsTest(unittest.TestCase):
    def run_installer(self, existing=False, fail=None):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            binary = base / 'bin'
            binary.mkdir()
            log = base / 'npm.log'
            tools = 'git gh zoxide rg fd fzf bat eza jq nano less unzip zip curl ssh rsync make gcc node pacman'.split()
            if existing:
                tools += ['codex', 'claude', 'opencode', 'pi']
            for tool in tools:
                (binary / tool).write_text('#!/bin/bash\nexit 0\n')
                (binary / tool).chmod(0o755)
            for tool in ['mkdir', 'cat']:
                (binary / tool).symlink_to('/bin/' + tool)
            (binary / 'npm').write_text('''#!/usr/bin/python3
import os, pathlib, sys
spec = sys.argv[-1]
with open(os.environ['INSTALL_LOG'], 'a') as log:
    log.write(spec + '\\n')
names = {'@openai/codex': 'codex', '@anthropic-ai/claude-code': 'claude', '@opencode/cli': 'opencode', '@earendil-works/pi-coding-agent': 'pi'}
name = names[spec.rsplit('@', 1)[0]]
path = pathlib.Path(os.environ['PATH']) / name
path.write_text('#!/bin/bash\\nexit ' + ('1' if name == os.environ.get('FAIL_TOOL') else '0') + '\\n')
path.chmod(0o755)
''')
            (binary / 'npm').chmod(0o755)
            profile = base / 'profile.sh'
            profile.write_text('# User shell configuration\n')
            script = SCRIPT.read_text().replace('/usr/local/share/terminal-spike', str(base / 'share')).replace('/etc/profile.d/terminal-spike-tools.sh', str(profile))
            result = subprocess.run(['/bin/bash', '-c', script, 'starter-tools', 'guide'],
                env={**os.environ, 'PATH': str(binary), 'INSTALL_LOG': str(log), 'FAIL_TOOL': fail or ''},
                text=True, capture_output=True)
            self.assertEqual('# User shell configuration\n', profile.read_text())
            return result, log.read_text().splitlines() if log.exists() else []

    def test_missing_clis_install_pinned_official_packages(self):
        result, installs = self.run_installer()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['@openai/codex@0.153.4', '@anthropic-ai/claude-code@2.1.319', '@opencode/cli@2.2.6', '@earendil-works/pi-coding-agent@1.5.0'], installs)
        self.assertIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)

    def test_existing_commands_are_preserved(self):
        result, installs = self.run_installer(existing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], installs)

    def test_broken_native_cli_prevents_success_marker(self):
        result, _ = self.run_installer(fail='opencode')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)
