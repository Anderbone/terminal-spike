import importlib.util
from pathlib import Path
import tempfile
import os
import sys
import json
import subprocess
import unittest

spec = importlib.util.spec_from_file_location('host_gate', Path(__file__).resolve().parents[1] / 'ci-host-check.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class HostPlanTest(unittest.TestCase):
    def test_only_failed_runtime_classes_can_enter_fixed_host_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'TEST-api26-full.xml').write_text('''<testsuite>
              <testcase classname="com.yanjiyu.terminalspike.ui.ImportTest"><failure/></testcase>
              <testcase classname="com.yanjiyu.terminalspike.ui.PassedTest"/>
              <testcase classname="instrumentation"><error/></testcase>
              <testcase classname="com.yanjiyu.terminalspike.Bad; touch /tmp/x"><failure/></testcase>
            </testsuite>''')
            (root / 'TEST-api37-full.xml').write_text('<testsuite><testcase classname="com.yanjiyu.terminalspike.Other"><failure/></testcase></testsuite>')
            self.assertEqual({'26': ['com.yanjiyu.terminalspike.ui.ImportTest']}, gate.failure_plan(root))

    def test_host_gate_scopes_the_emulator_port_to_runtime_checks_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'scripts').mkdir()
            for name, label in [('verify-android.sh', 'gate'), ('run-android-emulator-tests.sh', 'runtime')]:
                (root / 'scripts' / name).write_text('printf "' + label + ':%s\\n" "${TERMINAL_SPIKE_EMULATOR_PORT:-unset}" >> "$CI_ENV_CAPTURE"\n')
            plan = root / 'plan.json'
            plan.write_text(json.dumps({'26': ['com.yanjiyu.terminalspike.ui.ImportTest']}))
            capture = root / 'capture.txt'
            result = subprocess.run([sys.executable, str(Path(gate.__file__)), '--evidence', str(root),
                '--output', str(root / 'out'), '--plan-file', str(plan)], cwd=root,
                env=dict(os.environ, TERMINAL_SPIKE_EMULATOR_PORT='6666', CI_ENV_CAPTURE=str(capture)),
                capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['gate:unset', 'runtime:5588'], capture.read_text().splitlines())
            self.assertEqual([0, 0], json.loads((root / 'out/result.json').read_text())['exit_codes'])
