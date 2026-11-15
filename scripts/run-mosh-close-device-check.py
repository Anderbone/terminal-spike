#!/usr/bin/env python3
"""Opt-in S10e acceptance for native Mosh close; uses a disposable Herdr session."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--host', required=True)
    parser.add_argument('--udp-port', type=int, default=53317,
                        help='One unused UDP port already permitted on the trusted LAN')
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expect-leak', action='store_true')
    parser.add_argument('--method', choices=['closingBothPipesStillCompletesNativeShutdown',
                                           'closingInputPipeStillCompletesNativeShutdown',
                                           'closingOutputPipeStillCompletesNativeShutdown',
                                           'explicitStopCompletesNativeShutdownBeforePipesClose'],
                        default='closingBothPipesStillCompletesNativeShutdown')
    args = parser.parse_args()
    if not 1 <= args.udp_port <= 65535:
        parser.error('UDP port outside 1..65535')
    args.output.mkdir(parents=True, exist_ok=True)
    fixture_env = {key:value for key,value in os.environ.items() if not key.startswith('HERDR_')}
    fixture_env['TERM'] = 'xterm-256color'

    def adb(*command, timeout=30):
        return subprocess.check_output(['adb', '-s', args.serial, *command], timeout=timeout)

    def verify():
        inventory = subprocess.check_output(['adb', 'devices', '-l'], text=True)
        assert any(line.split()[:2] == [args.serial, 'device'] for line in inventory.splitlines())
        assert adb('shell', 'getprop', 'ro.product.model').decode().strip() == 'SM-G9708'
        assert adb('shell', 'getprop', 'ro.serialno').decode().strip() == 'R28M227XA0E'

    def alive(pid):
        if pid is None:
            return False
        try:
            return Path(f'/proc/{pid}/stat').read_text().rsplit(') ', 1)[1].split()[0] != 'Z'
        except FileNotFoundError:
            return False

    def frontend_children(pid):
        result = []
        for path in Path('/proc').iterdir():
            if not path.name.isdigit():
                continue
            try:
                fields = (path/'stat').read_text().rsplit(') ', 1)[1].split()
                if int(fields[1]) == pid and (path/'comm').read_text().strip() == 'herdr':
                    result.append(int(path.name))
            except OSError:
                continue
        return result

    def ids(session=None):
        command = ['herdr'] + (['--session', session] if session else []) + ['api', 'snapshot']
        snapshot = json.loads(subprocess.check_output(command, timeout=10,
                             env=fixture_env if session else None))['result']['snapshot']
        return {key: sorted(x[singular+'_id'] for x in snapshot[key])
                for key, singular in [('workspaces', 'workspace'), ('tabs', 'tab'), ('panes', 'pane')]}

    verify()
    test_hash = hashlib.sha256(args.test_apk.read_bytes()).hexdigest()
    installed_test = adb('shell', 'pm', 'path', 'com.yanjiyu.terminalspike.test').decode().strip()
    test_matches = (installed_test.startswith('package:/data/app/') and '\n' not in installed_test
                    and adb('shell', 'sha256sum', installed_test[8:]).decode().split()[0] == test_hash)
    if not test_matches:
        verify()  # Re-verify immediately before the install.
        install = adb('install', '-r', '-t', str(args.test_apk.resolve()), timeout=90).decode()
        assert 'Success' in install, install
    session = 'terminal-spike-close-' + str(time.time_ns())
    main_ids = ids()
    log = (args.output/'fixture-server.log').open('wb')
    server = subprocess.Popen(['herdr', '--session', session, 'server'], stdout=log, stderr=log, env=fixture_env)
    remote_pid = None
    remote_pidfd = None
    report = dict(device=args.serial, model='SM-G9708', method=args.method,
                  expect_leak=args.expect_leak, fixture_session=session)
    installed = adb('shell', 'pm', 'path', 'com.yanjiyu.terminalspike').decode().strip()
    assert installed.startswith('package:/data/app/') and '\n' not in installed
    report['installed_app_sha256'] = adb('shell', 'sha256sum', installed[8:]).decode().split()[0]
    report['test_apk_sha256'] = test_hash
    try:
        for _ in range(40):
            status = subprocess.run(['herdr', '--session', session, 'status', 'server'],
                                    capture_output=True, timeout=10, env=fixture_env)
            if b'status: running' in status.stdout:
                break
            time.sleep(.1)
        else:
            raise RuntimeError('Disposable Herdr server did not start')
        result = subprocess.run(['/usr/bin/mosh-server', 'new', '-i', args.host, '-p', str(args.udp_port),
                                 '-l', 'LANG=C.UTF-8', '--', subprocess.check_output(
                                     ['which', 'herdr'], text=True).strip(), 'session', 'attach', session],
                                capture_output=True, timeout=15, env=fixture_env)
        # Key is deliberately kept only in memory, never printed or saved in evidence.
        key_match = re.search(rb'MOSH CONNECT (\d+) ([A-Za-z0-9+/]{22})', result.stdout)
        pid_match = re.search(rb'pid = (\d+)', result.stderr)
        if not key_match or not pid_match:
            raise RuntimeError('Fixture Mosh server did not return bootstrap material')
        port, key = key_match[1].decode(), key_match[2].decode()
        remote_pid = int(pid_match[1]);remote_pidfd = os.pidfd_open(remote_pid)
        remote_start = Path(f'/proc/{remote_pid}/stat').read_text().rsplit(') ', 1)[1].split()[19]
        verify()
        command = ['am', 'instrument', '-w', '-r', '-e', 'class',
                   'com.yanjiyu.terminalspike.connection.MoshDirectRealEndToEndTest#'+args.method,
                   '-e', 'moshDirectHost', args.host, '-e', 'moshDirectPort', port,
                   '-e', 'moshDirectKey', key,
                   'com.yanjiyu.terminalspike.test/com.yanjiyu.terminalspike.TerminalSpikeTestRunner']
        frontends = set()
        stop_monitor = threading.Event()
        def monitor():
            while not stop_monitor.is_set():
                frontends.update(frontend_children(remote_pid))
                stop_monitor.wait(.05)
        monitor_thread = threading.Thread(target=monitor, daemon=True)
        monitor_thread.start()
        try:
            try:
                result = subprocess.run(['adb', '-s', args.serial, 'shell', shlex.join(command)],
                                        capture_output=True, timeout=75)
            except subprocess.TimeoutExpired:
                raise RuntimeError('Instrumented close check timed out') from None
            output = (result.stdout + result.stderr).decode().replace(key, '[redacted]')
            if result.returncode:
                raise RuntimeError('Instrumentation command failed: '+output[-1000:])
        finally:
            stop_monitor.set();monitor_thread.join(timeout=2)
        output = output.replace(key, '[redacted]')
        (args.output/'instrumentation.log').write_text(output)
        assert 'OK (1 test)' in output, output[-2000:]
        assert len(frontends) == 1, 'Fixture Herdr frontend did not attach'
        deadline = time.monotonic()+12
        while alive(remote_pid) and time.monotonic()<deadline:
            time.sleep(.1)
        report.update(remote_pid=remote_pid, remote_start=remote_start,
                      remote_alive_after_close=alive(remote_pid),
                      frontend_pids=sorted(frontends),
                      frontend_alive_after_close=any(alive(pid) for pid in frontends),
                      persistent_fixture_server_alive=server.poll() is None,
                      main_session_ids_unchanged=ids()==main_ids,
                      fixture_session_counts={k:len(v) for k,v in ids(session).items()})
        assert report['remote_alive_after_close'] == args.expect_leak, report
        assert report['frontend_alive_after_close'] == args.expect_leak, report
        assert report['persistent_fixture_server_alive'] and report['main_session_ids_unchanged'], report
        report['passed'] = True
    finally:
        # Scoped cleanup of this fixture only, including the intentionally leaked red server.
        if remote_pidfd is not None:
            try:
                signal.pidfd_send_signal(remote_pidfd, signal.SIGTERM)
            except ProcessLookupError:
                pass
            os.close(remote_pidfd)
        subprocess.run(['herdr', 'session', 'stop', session], capture_output=True, timeout=15, env=fixture_env)
        try:
            server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server.terminate();server.wait(timeout=5)
        log.close()
        subprocess.run(['herdr', 'session', 'delete', session], capture_output=True, timeout=15, env=fixture_env)
        (args.output/'evidence.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == '__main__':
    main()
