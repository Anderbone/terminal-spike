"""Run the shipped installer with local registry/release fixtures, never host packages."""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'app/src/main/assets/local-arch/starter-tools.sh'


class StarterToolsTest(unittest.TestCase):
    def run_installer(self, existing=False, fail=None, version='9.8.7', bad_digest=False, prerelease=False, broken_links=False, pack_json='keyed'):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            binary = base / 'bin'
            binary.mkdir()
            log = base / 'npm.log'
            tools = 'bwrap fish sudo useradd usermod git gh zoxide rg fd fzf bat eza jq nano less unzip zip curl ssh rsync make gcc node pacman'.split()
            for tool in tools:
                (binary / tool).write_text('#!/bin/bash\necho "' + tool + ' version fixture"\n')
                (binary / tool).chmod(0o755)
            for tool in 'mkdir cat chmod install mv mktemp rm tar gzip sha256sum jq'.split():
                (binary / tool).unlink(missing_ok=True)
                (binary / tool).symlink_to(shutil.which(tool))
            (binary / 'id').write_text('#!/bin/bash\necho 0\n')
            (binary / 'id').chmod(0o755)
            (binary / 'runuser').write_text('''#!/usr/bin/python3
import subprocess, sys
sys.exit(subprocess.run(sys.argv[sys.argv.index('--') + 1:]).returncode)
''')
            (binary / 'runuser').chmod(0o755)
            if existing:
                for tool in ['codex', 'claude', 'opencode', 'pi']:
                    (binary / tool).write_text('#!/bin/bash\necho old-version\n')
                    (binary / tool).chmod(0o755)
            auth = base / 'home/.codex/auth.json'
            auth.parent.mkdir(parents=True)
            auth.write_text('preserve-existing-login')
            (binary / 'npm').write_text('''#!/usr/bin/python3
import os, pathlib, sys, io, tarfile, json
if '--version' in sys.argv:
    print('npm fixture')
    sys.exit(0)
if 'view' in sys.argv:
    print(os.environ['LATEST_VERSION'])
    sys.exit(0)
spec = sys.argv[-1]
if 'pack' in sys.argv:
    name = 'claude' if spec.startswith('@anthropic-ai/') else 'opencode'
    member = 'claude' if name == 'claude' else 'bin/opencode'
    folder = pathlib.Path(sys.argv[sys.argv.index('--pack-destination') + 1])
    filename = name + '-platform.tgz'
    version = spec.rsplit('@', 1)[1]
    with tarfile.open(folder / filename, 'w:gz') as tar:
        for path, data in [('package/' + member, ('#!/bin/bash\\necho ' + version + '\\nexit ' + ('1' if name == os.environ.get('FAIL_TOOL') else '0') + '\\n').encode()), ('package/LICENSE.md', b'Native package license')]:
            info = tarfile.TarInfo(path)
            info.size = len(data)
            info.mode = 0o755
            tar.addfile(info, io.BytesIO(data))
    entry = {'filename': filename}
    print(json.dumps([entry] if os.environ.get('PACK_JSON') == 'array' else {spec.rsplit('@', 1)[0]: entry}))
    sys.exit(0)
with open(os.environ['INSTALL_LOG'], 'a') as log:
    log.write(spec + '\\n')
package = spec.rsplit('@', 1)[0]
names = {'@openai/codex': 'codex', '@anthropic-ai/claude-code': 'claude', '@opencode/cli': 'opencode', '@earendil-works/pi-coding-agent': 'pi'}
name = names[package]
if name in ('claude', 'opencode') and '--allow-scripts=' + package not in sys.argv:
    sys.exit('Missing package-specific native install permission')
path = pathlib.Path(os.environ['FIXTURE_BIN']) / name
if os.environ.get('BROKEN_NATIVE_LINKS') == '1' and name in ('claude', 'opencode'):
    path.unlink(missing_ok=True)
    path.symlink_to(path.parent / '.l2s-missing-native')
    sys.exit(0)
path.write_text('#!/bin/bash\\necho ' + name + ' ' + spec.rsplit('@', 1)[1] + '\\nexit ' + ('1' if name == os.environ.get('FAIL_TOOL') else '0') + '\\n')
path.chmod(0o755)
''')
            (binary / 'npm').chmod(0o755)
            archive = base / 'yay.tar.gz'
            with tarfile.open(archive, 'w:gz') as tar:
                for name, data in [('yay', b'#!/bin/bash\necho yay v13.1.0\n'), ('fish', b'# fixture completion\n')]:
                    info = tarfile.TarInfo('yay_13.1.0_aarch64/' + name)
                    info.size = len(data)
                    info.mode = 0o755
                    tar.addfile(info, io.BytesIO(data))
            herdr = base / 'herdr'
            herdr.write_bytes(b'#!/bin/bash\necho herdr 0.9.5\n')
            releases = {}
            for repo, tag, name, file in [('Jguer/yay', 'v13.1.0', 'yay_13.1.0_aarch64.tar.gz', archive),
                                           ('herdrdev/herdr', 'v0.9.5', 'herdr-linux-aarch64', herdr)]:
                releases[repo] = {'tag_name': tag, 'draft': False, 'prerelease': prerelease,
                    'assets': [{'name': name, 'browser_download_url': f'https://github.com/{repo}/releases/download/{tag}/{name}',
                                'digest': 'sha256:' + ('0' * 64 if bad_digest else hashlib.sha256(file.read_bytes()).hexdigest())}]}
            (base / 'releases.json').write_text(json.dumps(releases))
            (binary / 'curl').write_text('''#!/usr/bin/python3
import json, os, pathlib, sys
base = pathlib.Path(os.environ['FIXTURE_ROOT'])
url = next(a for a in sys.argv if a.startswith('https://'))
if url.startswith('https://api.github.com/repos/'):
    repo = url.removeprefix('https://api.github.com/repos/').removesuffix('/releases/latest')
    data = json.dumps(json.loads((base / 'releases.json').read_text())[repo]).encode()
elif url.startswith('https://raw.githubusercontent.com/'):
    data = b'Upstream license fixture\\n'
elif '/Jguer/yay/releases/download/' in url:
    data = (base / 'yay.tar.gz').read_bytes()
elif '/herdrdev/herdr/releases/download/' in url:
    data = (base / 'herdr').read_bytes()
else:
    sys.exit('Unexpected download origin')
flag = next((f for f in ('-o', '--output') if f in sys.argv), None)
if flag:
    pathlib.Path(sys.argv[sys.argv.index(flag) + 1]).write_bytes(data)
else:
    sys.stdout.buffer.write(data)
''')
            (binary / 'curl').chmod(0o755)
            profile = base / 'profile.sh'
            profile.write_text('# User shell configuration\n')
            fish_profile = base / 'etc/fish/conf.d/terminal-spike.fish'
            fish_profile.parent.mkdir(parents=True)
            fish_profile.write_text('# User fish configuration\n')
            script = SCRIPT.read_text().replace('/usr/local', str(base / 'usr/local'))
            script = script.replace('/etc/profile.d/terminal-spike-tools.sh', str(profile)).replace('/etc/fish', str(base / 'etc/fish')).replace('/etc/sudoers.d', str(base / 'etc/sudoers.d'))
            result = subprocess.run(['/bin/bash', '-c', script, 'starter-tools', 'guide'],
                env={**os.environ, 'PATH': str(base / 'usr/local/bin') + ':' + str(binary), 'FIXTURE_BIN': str(binary), 'HOME': str(base / 'home'), 'INSTALL_LOG': str(log),
                     'FIXTURE_ROOT': str(base), 'LATEST_VERSION': version, 'FAIL_TOOL': fail or '', 'BROKEN_NATIVE_LINKS': '1' if broken_links else '0', 'PACK_JSON': pack_json}, text=True, capture_output=True)
            self.assertEqual('# User shell configuration\n', profile.read_text())
            self.assertEqual('# User fish configuration\n', fish_profile.read_text())
            self.assertEqual('preserve-existing-login', auth.read_text())
            return result, log.read_text().splitlines() if log.exists() else []

    def test_latest_versions_are_resolved_at_install_time(self):
        for version, pack_json in [('9.8.7', 'array'), ('9.8.8', 'keyed')]:
            result, installs = self.run_installer(version=version, pack_json=pack_json)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([p + '@' + version for p in ['@openai/codex', '@anthropic-ai/claude-code', '@opencode/cli', '@earendil-works/pi-coding-agent']], installs)
            self.assertIn('herdr 0.9.5', result.stdout)
            self.assertIn('yay v13.1.0', result.stdout)
            self.assertIn('LOCAL_ARCH_TOOL_VERSIONS_BEGIN', result.stdout)
            self.assertIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)

    def test_explicit_update_upgrades_existing_clis_and_keeps_configuration(self):
        result, installs = self.run_installer(existing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(4, len(installs))
        self.assertNotIn('old-version', result.stdout)

    def test_existing_upgrade_repairs_dangling_native_hardlink_emulation(self):
        result, _ = self.run_installer(existing=True, broken_links=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Repairing claude 9.8.7 native executable', result.stdout)
        self.assertIn('Repairing opencode 9.8.7 native executable', result.stdout)
        self.assertIn('Claude Code: 9.8.7', result.stdout)
        self.assertIn('OpenCode: 9.8.7', result.stdout)
        self.assertIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)

    def test_prerelease_npm_latest_is_rejected(self):
        result, installs = self.run_installer(version='10.0.0-beta.1')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], installs)
        self.assertNotIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)

    def test_corrupt_github_binary_is_not_executed(self):
        result, _ = self.run_installer(bad_digest=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('\nyay: yay v13.1.0\n', result.stdout)
        self.assertNotIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)

    def test_prerelease_github_latest_is_rejected(self):
        result, _ = self.run_installer(prerelease=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)

    def test_broken_native_cli_prevents_success_marker(self):
        result, _ = self.run_installer(fail='opencode')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('LOCAL_ARCH_STARTER_TOOLS_OK', result.stdout)
        self.assertIn('exit 1', result.stderr)
