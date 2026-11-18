import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
NAMES = [f'{name}.pb.{suffix}' for name in ['hostinput', 'transportinstruction', 'userinput']
         for suffix in ['cc', 'h']]


class MoshProtobufGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='owned-protobuf-generator-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.module = self.root / 'mosh-core'
        self.scripts = self.module / 'scripts'
        self.scripts.mkdir(parents=True)
        self.script = self.scripts / 'generate-protobuf.sh'
        shutil.copy2(ROOT / 'mosh-core/scripts/generate-protobuf.sh', self.script)
        self.generated = self.module / 'src/main/cpp/generated'
        self.generated.mkdir(parents=True)
        self.expected = self.root / 'expected'
        self.expected.mkdir()
        for name in NAMES:
            data = ('owned generated ' + name + '\n').encode()
            (self.generated / name).write_bytes(data)
            (self.expected / name).write_bytes(data)
        self.write_checksums()
        self.original = self.snapshot()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.trace = self.root / 'trace.jsonl'
        prelude = '''import json, os, sys
from pathlib import Path
root = Path(os.environ['OWNED_GENERATOR_ROOT'])
tool = Path(sys.argv[0]).name
with (root/'trace.jsonl').open('a') as trace:
    trace.write(json.dumps(dict(tool=tool, args=sys.argv[1:], temp=os.environ.get('TMPDIR'))) + '\\n')
'''
        self.write_tool(self.scripts / 'verify-sources.sh', prelude + '''
if os.environ.get('FAIL_STAGE') == 'verify': sys.exit(13)
''')
        self.write_tool(self.bin / 'mktemp', prelude + '''
import tempfile
template = sys.argv[-1]
parent = Path(template).parent
if parent == Path('/tmp'):
    if os.environ.get('ALLOW_LEGACY_TMP') != '1': sys.exit(28)
    parent = root/'legacy-owned-tmp'
    parent.mkdir(exist_ok=True)
workspace = tempfile.mkdtemp(prefix='terminal-spike-mosh-protobuf.', dir=parent)
with (root/'workspaces').open('a') as output: output.write(workspace + '\\n')
print(workspace)
''')
        self.write_tool(self.bin / 'tar', prelude)
        self.write_tool(self.root / 'protoc-fixture', prelude + '''
import shutil
if os.environ.get('FAIL_STAGE') == 'protoc': sys.exit(15)
output = Path(next(arg.split('=', 1)[1] for arg in sys.argv if arg.startswith('--cpp_out=')))
for source in (root/'expected').iterdir(): shutil.copy2(source, output/source.name)
''')
        self.write_tool(self.bin / 'cmake', prelude + '''
import shutil
stage = 'build' if '--build' in sys.argv else 'configure'
if os.environ.get('FAIL_STAGE') == stage: sys.exit(14)
if stage == 'configure':
    build = Path(sys.argv[sys.argv.index('-B') + 1])
    build.mkdir(parents=True)
    shutil.copy2(root/'protoc-fixture', build/'protoc')
''')

    def write_tool(self, path, source):
        path.write_text('#!' + sys.executable + '\n' + source)
        path.chmod(0o700)

    def write_checksums(self):
        (self.generated / 'SHA256SUMS').write_text(''.join(
            hashlib.sha256((self.generated / name).read_bytes()).hexdigest() + '  ' + name + '\n'
            for name in NAMES))

    def snapshot(self):
        return {p.name: p.read_bytes() for p in self.generated.iterdir()}

    def run_generator(self, *arguments, **overrides):
        environment = os.environ.copy()
        for key in ['TMPDIR', 'RUNNER_TEMP', 'XDG_CACHE_HOME']:
            environment.pop(key, None)
        environment.update(PATH=str(self.bin) + os.pathsep + environment.get('PATH', ''),
                           OWNED_GENERATOR_ROOT=str(self.root), HOME=str(self.root / 'owned-home'))
        environment.update(overrides)
        return subprocess.run(['bash', str(self.script), *arguments], cwd=self.root, env=environment,
                              capture_output=True, text=True, timeout=15)

    def commands(self):
        return [json.loads(line) for line in self.trace.read_text().splitlines()] if self.trace.exists() else []

    def assert_clean(self):
        file = self.root / 'workspaces'
        if file.exists():
            self.assertTrue(all(not Path(line).exists() for line in file.read_text().splitlines()))

    def test_disk_temp_roots_and_child_tools_avoid_system_tmp(self):
        cases = [
            (dict(TMPDIR=str(self.root / 'disk temp'), RUNNER_TEMP=str(self.root / 'runner'),
                  XDG_CACHE_HOME=str(self.root / 'cache')), self.root / 'disk temp'),
            (dict(TMPDIR='', RUNNER_TEMP=str(self.root / 'runner'),
                  XDG_CACHE_HOME=str(self.root / 'cache')), self.root / 'runner'),
            (dict(TMPDIR='', RUNNER_TEMP='', XDG_CACHE_HOME=str(self.root / 'cache')),
             self.root / 'cache/terminal-spike/tmp'),
            ({}, self.root / 'owned-home/.cache/terminal-spike/tmp'),
            (dict(TMPDIR='', RUNNER_TEMP='', XDG_CACHE_HOME=''),
             self.root / 'owned-home/.cache/terminal-spike/tmp'),
        ]
        for environment, expected in cases:
            with self.subTest(environment=environment):
                self.trace.unlink(missing_ok=True)
                result = self.run_generator(**environment)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('reproducible with protoc 3.21.12', result.stdout)
                self.assertTrue(self.commands())
                self.assertTrue(all(c['temp'] == str(expected) for c in self.commands()))
                self.assertEqual(self.original, self.snapshot())
                self.assert_clean()

    def test_comparison_failure_keeps_checked_in_outputs_and_cleans_workspace(self):
        (self.expected / NAMES[0]).write_text('owned differing compiler output\n')
        result = self.run_generator(ALLOW_LEGACY_TMP='1')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('sources are reproducible', result.stdout)
        self.assertEqual(self.original, self.snapshot())
        self.assert_clean()

    def test_checksum_failure_cannot_report_reproducible_sources(self):
        (self.generated / 'SHA256SUMS').write_text('0' * 64 + '  ' + NAMES[0] + '\n')
        before = self.snapshot()
        result = self.run_generator(ALLOW_LEGACY_TMP='1')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('sources are reproducible', result.stdout)
        self.assertEqual(before, self.snapshot())
        self.assert_clean()

    def test_write_mode_updates_all_six_outputs_and_their_checksums(self):
        for name in NAMES:
            (self.expected / name).write_text('owned replacement ' + name + '\n')
        result = self.run_generator('--write', ALLOW_LEGACY_TMP='1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Regenerated checked-in Mosh protobuf', result.stdout)
        for name in NAMES:
            self.assertEqual((self.expected / name).read_bytes(), (self.generated / name).read_bytes())
        check = subprocess.run(['sha256sum', '--check', 'SHA256SUMS'], cwd=self.generated,
                               capture_output=True, text=True, timeout=5)
        self.assertEqual(0, check.returncode, check.stderr)
        self.assert_clean()

    def test_source_verification_failure_stops_before_compiler_and_cleans_workspace(self):
        result = self.run_generator(ALLOW_LEGACY_TMP='1', FAIL_STAGE='verify')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(c['tool'] in ['tar', 'cmake', 'protoc'] for c in self.commands()))
        self.assertEqual(self.original, self.snapshot())
        self.assert_clean()

    def test_each_compiler_failure_preserves_outputs_and_cleans_workspace(self):
        for stage in ['configure', 'build', 'protoc']:
            with self.subTest(stage=stage):
                result = self.run_generator(ALLOW_LEGACY_TMP='1', FAIL_STAGE=stage)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('sources are reproducible', result.stdout)
                self.assertEqual(self.original, self.snapshot())
                self.assert_clean()

    def test_invalid_explicit_root_stops_before_any_fixture_tools(self):
        blocked = self.root / 'not-a-directory'
        blocked.write_text('owned existing sentinel')
        result = self.run_generator(TMPDIR=str(blocked), ALLOW_LEGACY_TMP='1')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.commands())
        self.assertEqual('owned existing sentinel', blocked.read_text())
        self.assertEqual(self.original, self.snapshot())


if __name__ == '__main__':
    unittest.main()
