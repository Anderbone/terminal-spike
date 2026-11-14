#!/usr/bin/env python3
"""Run fixed Android gates outside the model sandbox; accept no shell commands."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def failure_plan(root):
    plan = {}
    for path in root.rglob('TEST-api*-full.xml'):
        match = re.fullmatch(r'TEST-api(26|35)-full.xml', path.name)
        if not match:
            continue
        for case in ET.parse(path).getroot().findall('testcase'):
            name = case.get('classname', '')
            if case.find('failure') is not None or case.find('error') is not None:
                if re.fullmatch(r'com\.yanjiyu\.terminalspike\.[A-Za-z0-9_.$]+', name):
                    plan.setdefault(match[1], set()).add(name)
    return {api: sorted(classes) for api, classes in plan.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--plan-file', type=Path, required=True)
    args = parser.parse_args()
    root = Path.cwd().resolve()
    plan_path = args.plan_file
    plan = json.loads(plan_path.read_text())
    if not plan or len(plan) > 2 or any(api not in ('26', '35') or len(classes) > 20 or
            not classes or any(not re.fullmatch(r'com\.yanjiyu\.terminalspike\.[A-Za-z0-9_.$]+', c) for c in classes)
            for api, classes in plan.items()):
        raise SystemExit('No bounded API 26/35 failure-class plan is available; manual diagnosis required.')
    args.output.mkdir(parents=True, exist_ok=True)
    temporary = args.output.resolve() / 'tmp'; temporary.mkdir(exist_ok=True)
    env = dict(os.environ, TMPDIR=str(temporary))
    env.pop('TERMINAL_SPIKE_EMULATOR_PORT', None)
    env['JAVA_TOOL_OPTIONS'] = '-Djava.io.tmpdir="' + str(temporary) + '"'
    commands = [[str(root / 'gradlew'), '-Pkotlin.daemon.jvmargs=-Djava.io.tmpdir=' + str(temporary),
                 ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:stageRuntimeTestUtilities']]
    if not args.baseline:
        commands = [['bash', str(root / 'scripts/verify-android.sh')]]
    for api, classes in sorted(plan.items()):
        commands.append(['bash', str(root / 'scripts/run-android-emulator-tests.sh'), '--api', api,
                         '--suite', 'full', '--test-filter', ','.join(classes), '--output-dir',
                         str((args.output / ('api' + api)).resolve())])
    results = []
    for i, command in enumerate(commands):
        with (args.output / f'check-{i}.log').open('w') as log:
            command_env = dict(env, TERMINAL_SPIKE_EMULATOR_PORT='5588') if i > 0 else env
            result = subprocess.run(command, cwd=root, env=command_env, stdout=log, stderr=subprocess.STDOUT, timeout=2400)
        results.append(result.returncode)
        if result.returncode and i == 0:
            break
    (args.output / 'result.json').write_text(json.dumps({'plan': plan, 'exit_codes': results}))
    raise SystemExit(int(any(results)))


if __name__ == '__main__':
    main()
