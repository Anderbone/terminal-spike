#!/usr/bin/env python3
"""Run the task indicator UI contract only on the explicitly selected old phone."""
import argparse
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--app-apk', default='app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--test-apk', default='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    parser.add_argument('--output-dir', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    adb = shutil.which('adb')
    if not adb:
        sdk = next(line.split('=', 1)[1] for line in (root / 'local.properties').read_text().splitlines() if line.startswith('sdk.dir='))
        adb = str(Path(sdk) / 'platform-tools/adb')

    def verify():
        inventory = subprocess.check_output([adb, 'devices', '-l'], text=True)
        lines = [line.split() for line in inventory.splitlines() if line.split() and line.split()[0] == args.serial]
        if len(lines) != 1 or lines[0][1] != 'device' or 'model:SM_S911B' not in lines[0]:
            raise RuntimeError('Refusing unavailable, unauthorized, or non-SM_S911B target')
        model = subprocess.check_output([adb, '-s', args.serial, 'shell', 'getprop', 'ro.product.model'], text=True).strip()
        if model != 'SM-S911B':
            raise RuntimeError('Refusing a target whose model is not SM-S911B')

    for apk in (args.app_apk, args.test_apk):
        verify()
        subprocess.run([adb, '-s', args.serial, 'install', '-r', str(root / apk)], check=True, stdout=subprocess.DEVNULL)
    verify()
    command = ('CLASSPATH=$(pm path androidx.test.services) app_process / '
               'androidx.test.services.shellexecutor.ShellMain am instrument -w -r '
               '-e class com.yanjiyu.terminalspike.TerminalTaskIndicatorTest '
               '-e targetInstrumentation com.yanjiyu.terminalspike.test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner '
               '-e clearPackageData true androidx.test.orchestrator/.AndroidTestOrchestrator')
    out = root / args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    result = subprocess.run([adb, '-s', args.serial, 'shell', command], capture_output=True, text=True, timeout=240)
    report = out / 'instrumentation.txt'
    report.write_text(result.stdout + result.stderr)
    subprocess.run(['python3', str(root / 'scripts/instrumentation-output-to-junit.py'), str(report), str(out / 'TEST-task-ui.xml')], check=True)
    if 'OK (4 tests)' not in result.stdout:
        raise SystemExit('The exact four task-indicator UI tests were not green; see the output directory.')
    print('TERMINAL_TASK_UI_RESULT tests=4 failures=0 skips=0 model=SM-S911B')


if __name__ == '__main__':
    main()
