#!/usr/bin/env python3
"""Run the task indicator UI contract only on the explicitly selected old phone."""
import argparse
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

from device_test_safety import APP, TEST_APP, RUNNER, verify_device, verify_apks

EXPECTED = {"topTabsReflectBackgroundTaskState", "appSwitcherReflectsBackgroundTaskState",
            "tmuxSwitcherReflectsInactiveRemoteTaskState", "startupPickerShowsRemoteTaskState"}


def main():
    parser = argparse.ArgumentParser(description="Requires APKs built with -PterminalSpikeDeviceVerify=true; never installs the normal app.")
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
        return verify_device(adb, args.serial)

    # Verify both artifacts before touching either installed package.
    verify_apks(adb, root / args.app_apk, root / args.test_apk)
    for apk in (args.app_apk, args.test_apk):
        verify()
        verify_apks(adb, root / args.app_apk, root / args.test_apk)
        subprocess.run([adb, '-s', args.serial, 'install', '-r', '-t', str(root / apk)], check=True, stdout=subprocess.DEVNULL)
    model = verify()
    # These four component cases own a fresh Compose Rule and local fixtures;
    # package/process clearing between cases is unnecessary in the isolated app.
    command = ('am instrument -w -r '
               '-e class com.yanjiyu.terminalspike.TerminalTaskIndicatorTest '
               f'{TEST_APP}/{RUNNER}')
    out = root / args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    result = subprocess.run([adb, '-s', args.serial, 'shell', command], capture_output=True, text=True, timeout=240)
    report = out / 'instrumentation.txt'
    report.write_text(result.stdout + result.stderr)
    subprocess.run(['python3', str(root / 'scripts/instrumentation-output-to-junit.py'), str(report), str(out / 'TEST-task-ui.xml')], check=True)
    xml = ET.parse(out / 'TEST-task-ui.xml').getroot()
    cases = list(xml.iter('testcase'))
    valid_cases = (len(cases) == 4 and {case.get('name') for case in cases} == EXPECTED
                   and all(case.get('classname') == 'com.yanjiyu.terminalspike.TerminalTaskIndicatorTest' for case in cases)
                   and all(case.find(tag) is None for case in cases for tag in ('failure', 'error', 'skipped')))
    if result.returncode != 0 or not valid_cases or 'OK (4 tests)' not in result.stdout:
        raise SystemExit('The exact four task-indicator UI tests were not green; see the output directory.')
    print(f'TERMINAL_TASK_UI_RESULT tests=4 failures=0 skips=0 model={model} package={APP}')


if __name__ == '__main__':
    main()
