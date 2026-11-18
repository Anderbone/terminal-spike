import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[1] / 'run-mosh-close-device-check.py'
METHODS = [
    'closingBothPipesStillCompletesNativeShutdown',
    'closingInputPipeStillCompletesNativeShutdown',
    'closingOutputPipeStillCompletesNativeShutdown',
    'explicitStopCompletesNativeShutdownBeforePipesClose',
]


class Fixture:
    """All device, Herdr, Mosh, /proc and signalling boundaries are local fakes."""

    def __init__(self, root, **options):
        self.root = root
        self.options = options
        self.done = False
        self.calls = []
        self.log = None
        self.log_was_closed = None
        self.child = Mock()
        self.child.poll.return_value = None
        self.pidfd_open = Mock(return_value=99)
        self.close = Mock()
        self.send = Mock()
        self.clock = 0
        self.key = 'A' * 22
        self.apk = root / 'test.apk'
        self.apk.write_bytes(b'owned fake APK')
        self.output = root / 'evidence'

    def now(self):
        self.clock += 20
        return self.clock

    def path(self, value):
        if not str(value).startswith('/proc'):
            return Path(value)
        fixture = self

        class ProcPath:
            def __init__(self, path):
                self.path = path
                self.name = path.rsplit('/', 1)[-1]

            def __truediv__(self, tail):
                return ProcPath(self.path + '/' + tail)

            def iterdir(self):
                return iter([] if fixture.options.get('no_frontend') else [ProcPath('/proc/321')])

            def read_text(self):
                if fixture.done and not fixture.options.get('leak'):
                    raise fixture.options.get('gone_error', FileNotFoundError)(self.path)
                if self.path.endswith('/comm'):
                    return 'herdr\n'
                parent = '123' if '/321/' in self.path else '1'
                return '123 (owned fake) ' + ' '.join(['S', parent] + ['0'] * 17 + ['9001'])

        return ProcPath(str(value))

    def check_output(self, command, **kwargs):
        self.calls.append(command)
        if command == ['adb', 'devices', '-l']:
            state = self.options.get('state', 'device')
            return 'List of devices attached\nowned-serial ' + state + '\n'
        if command[:3] == ['adb', '-s', 'owned-serial']:
            tail = command[3:]
            if tail == ['shell', 'getprop', 'ro.product.model']:
                return self.options.get('model', 'SM-G9708').encode()
            if tail == ['shell', 'getprop', 'ro.serialno']:
                return self.options.get('hardware', 'R28M227XA0E').encode()
            if tail[:3] == ['shell', 'pm', 'path']:
                if tail[-1] == 'com.yanjiyu.terminalspike.test':
                    return b'' if self.options.get('install') else b'package:/data/app/owned-test.apk'
                if self.options.get('query_failure'):
                    raise RuntimeError('owned installed-app query failed')
                return b'package:/data/app/owned-main.apk'
            if tail[:2] == ['shell', 'sha256sum']:
                if tail[-1].endswith('owned-test.apk'):
                    return (hashlib.sha256(self.apk.read_bytes()).hexdigest() + '  owned').encode()
                if self.options.get('hash_failure'):
                    raise RuntimeError('owned installed-app hash failed')
                return b'0123456789  owned'
            if tail[:1] == ['install']:
                return b'Failure [owned install failure]' if self.options.get('install_failure') else b'Success'
        if command[:1] == ['herdr'] and command[-2:] == ['api', 'snapshot']:
            ids = ['one']
            if '--session' not in command and self.done and self.options.get('main_changed'):
                ids.append('two')
            return json.dumps(dict(result=dict(snapshot={plural: [{singular + '_id': x} for x in ids]
                for plural, singular in [('workspaces', 'workspace'), ('tabs', 'tab'), ('panes', 'pane')]}))).encode()
        if command == ['which', 'herdr']:
            return '/owned/herdr\n'
        raise AssertionError('Unexpected command: ' + repr(command))

    def run_command(self, command, **kwargs):
        self.calls.append(command)
        if command[:1] == ['herdr']:
            if command[-2:] == ['status', 'server']:
                return subprocess.CompletedProcess(command, 0, b'status: running', b'')
            action = command[2]
            if self.options.get(action + '_failure'):
                if kwargs.get('check'):
                    raise subprocess.CalledProcessError(1, command)
                return subprocess.CompletedProcess(command, 1, b'', b'owned cleanup failure')
            return subprocess.CompletedProcess(command, 0, b'', b'')
        if command[:1] == ['/usr/bin/mosh-server']:
            return subprocess.CompletedProcess(command, 0, ('MOSH CONNECT 53317 ' + self.key).encode(), b'pid = 123')
        if command[:4] == ['adb', '-s', 'owned-serial', 'shell']:
            self.done = True
            if self.options.get('instrument_timeout'):
                raise subprocess.TimeoutExpired(command, 75)
            output = 'owned ' + self.key + '\n' + ('NO TEST RESULT' if self.options.get('missing_ok') else 'OK (1 test)')
            return subprocess.CompletedProcess(command, 0, output.encode(), b'')
        raise AssertionError('Unexpected run: ' + repr(command))

    def spawn(self, command, **kwargs):
        self.calls.append(command)
        self.log = kwargs['stdout']
        if self.options.get('spawn_failure'):
            raise OSError('owned spawn failed')
        return self.child

    def run(self, optimize=0, method=METHODS[0], expect_leak=False):
        module = types.ModuleType('owned_mosh_close_fixture')
        module.__file__ = str(SCRIPT)
        with patch.object(sys, 'path', [str(SCRIPT.parent), *sys.path]):
            exec(compile(SCRIPT.read_text(), str(SCRIPT), 'exec', optimize=optimize), module.__dict__)
        module.Path = self.path
        module.os = types.SimpleNamespace(environ=os.environ, pidfd_open=self.pidfd_open, close=self.close)
        module.signal = types.SimpleNamespace(SIGTERM=signal.SIGTERM, pidfd_send_signal=self.send)
        module.time = types.SimpleNamespace(time_ns=lambda: 123, monotonic=self.now, sleep=lambda _: None)

        class Event:
            stopped = False
            def is_set(self): return self.stopped
            def set(self): self.stopped = True
            def wait(self, _): self.stopped = True

        class Thread:
            def __init__(self, target, **_): self.target = target
            def start(self): self.target()
            def join(self, **_): pass

        module.threading = types.SimpleNamespace(Event=Event, Thread=Thread)
        argv = [str(SCRIPT), '--serial', 'owned-serial', '--host', 'owned.invalid',
                '--test-apk', str(self.apk), '--output', str(self.output), '--method', method]
        if expect_leak:
            argv.append('--expect-leak')
        with patch.object(sys, 'argv', argv), \
             patch.object(module.subprocess, 'check_output', side_effect=self.check_output), \
             patch.object(module.subprocess, 'run', side_effect=self.run_command), \
             patch.object(module.subprocess, 'Popen', side_effect=self.spawn), \
             contextlib.redirect_stdout(io.StringIO()):
            try:
                module.main()
            finally:
                if self.log is not None:
                    self.log_was_closed = self.log.closed
                    self.log.close()  # Reap a failed old implementation's fixture-only FD.
        return json.loads((self.output / 'evidence.json').read_text())

    def assert_clean(self, test, spawned=True):
        if self.log is not None:
            test.assertTrue(self.log_was_closed)
        if spawned:
            self.child.wait.assert_called()
            for action in ['stop', 'delete']:
                test.assertTrue(any(c[:3] == ['herdr', 'session', action] for c in self.calls))


class MoshCloseDeviceCheckTest(unittest.TestCase):
    def test_identity_checks_survive_optimized_python(self):
        for optimize in [0, 2]:
            for options in [dict(state='offline'), dict(model='SM-F976B'), dict(hardware='other')]:
                with self.subTest(optimize=optimize, options=options), tempfile.TemporaryDirectory() as d:
                    f = Fixture(Path(d), **options)
                    with self.assertRaises((RuntimeError, AssertionError)):
                        f.run(optimize)
                    self.assertFalse(any(c[:1] == ['herdr'] or 'install' in c or 'pm' in c for c in f.calls))
                    self.assertIsNone(f.log)

    def test_failed_install_cannot_enter_fixture_even_when_optimized(self):
        for optimize in [0, 2]:
            with self.subTest(optimize=optimize), tempfile.TemporaryDirectory() as d:
                f = Fixture(Path(d), install=True, install_failure=True)
                with self.assertRaisesRegex((RuntimeError, AssertionError), 'install'):
                    f.run(optimize)
                self.assertFalse(any(c[:1] == ['herdr'] for c in f.calls))
                self.assertIsNone(f.log)

    def test_spawn_failure_closes_the_acquired_log(self):
        with tempfile.TemporaryDirectory() as d:
            f = Fixture(Path(d), spawn_failure=True)
            with self.assertRaisesRegex(OSError, 'owned spawn failed'):
                f.run()
            f.assert_clean(self, spawned=False)
            self.assertFalse(any(c[:2] == ['herdr', 'session'] for c in f.calls))

    def test_post_spawn_app_queries_are_inside_cleanup(self):
        for options in [dict(query_failure=True), dict(hash_failure=True)]:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as d:
                f = Fixture(Path(d), **options)
                with self.assertRaisesRegex(RuntimeError, 'owned installed-app'):
                    f.run()
                f.assert_clean(self)
                self.assertFalse(json.loads((f.output / 'evidence.json').read_text())['passed'])

    def test_cleanup_failure_still_reaps_and_closes_other_resources(self):
        for action in ['stop', 'delete']:
            with self.subTest(action=action), tempfile.TemporaryDirectory() as d:
                f = Fixture(Path(d), **{action + '_failure': True})
                with self.assertRaisesRegex(RuntimeError, 'cleanup'):
                    f.run()
                f.assert_clean(self)
                f.close.assert_called_once_with(99)
                report = json.loads((f.output / 'evidence.json').read_text())
                self.assertFalse(report['passed'])
                self.assertTrue(report['cleanup_errors'])

    def test_primary_query_failure_survives_cleanup_failure(self):
        with tempfile.TemporaryDirectory() as d:
            f = Fixture(Path(d), query_failure=True, stop_failure=True, delete_failure=True)
            with self.assertRaisesRegex(RuntimeError, 'owned installed-app query failed'):
                f.run()
            f.assert_clean(self)

    def test_pidfd_failures_do_not_skip_other_cleanup(self):
        for failed in ['send', 'close']:
            with self.subTest(failed=failed), tempfile.TemporaryDirectory() as d:
                f = Fixture(Path(d))
                getattr(f, failed).side_effect = OSError('owned pidfd cleanup failed')
                with self.assertRaisesRegex(RuntimeError, 'cleanup'):
                    f.run()
                f.assert_clean(self)
                f.close.assert_called_once_with(99)
                self.assertFalse(json.loads((f.output / 'evidence.json').read_text())['passed'])

    def test_unresponsive_owned_fixture_child_is_killed_and_reaped(self):
        with tempfile.TemporaryDirectory() as d:
            f = Fixture(Path(d))
            f.child.wait.side_effect = [subprocess.TimeoutExpired('owned child', 15),
                                       subprocess.TimeoutExpired('owned child', 5), 0]
            self.assertTrue(f.run()['passed'])
            f.child.terminate.assert_called_once_with()
            f.child.kill.assert_called_once_with()
            self.assertEqual([c.kwargs['timeout'] for c in f.child.wait.call_args_list], [15, 5, 5])
            f.assert_clean(self)

    def test_all_four_close_methods_and_the_red_control_remain_available(self):
        for optimize in [0, 2]:
            for method in METHODS:
                for leak in [False, True]:
                    with self.subTest(optimize=optimize, method=method, leak=leak), tempfile.TemporaryDirectory() as d:
                        f = Fixture(Path(d), leak=leak)
                        report = f.run(optimize, method, expect_leak=leak)
                        self.assertTrue(report['passed'])
                        self.assertEqual(report['remote_alive_after_close'], leak)
                        self.assertEqual(report['frontend_alive_after_close'], leak)
                        f.assert_clean(self)
                        f.send.assert_called_once_with(99, signal.SIGTERM)
                        self.assertNotIn(f.key, ''.join(p.read_text() for p in f.output.iterdir()))

    def test_disappearing_proc_reads_preserve_the_lifetime_contract(self):
        for error in [FileNotFoundError, ProcessLookupError]:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as d:
                f = Fixture(Path(d), gone_error=error)
                self.assertTrue(f.run()['passed'])
                f.assert_clean(self)
        with tempfile.TemporaryDirectory() as d:
            f = Fixture(Path(d), gone_error=PermissionError)
            with self.assertRaises(PermissionError):
                f.run()
            f.assert_clean(self)

    def test_acceptance_assertions_are_enforced_when_optimized(self):
        for options in [dict(missing_ok=True), dict(no_frontend=True), dict(leak=True), dict(main_changed=True)]:
            with self.subTest(options=options), tempfile.TemporaryDirectory() as d:
                f = Fixture(Path(d), **options)
                with self.assertRaises(RuntimeError):
                    f.run(optimize=2)
                f.assert_clean(self)
                self.assertFalse(json.loads((f.output / 'evidence.json').read_text())['passed'])

    def test_instrument_timeout_is_sanitized_and_cleans_every_resource(self):
        with tempfile.TemporaryDirectory() as d:
            f = Fixture(Path(d), instrument_timeout=True)
            with self.assertRaisesRegex(RuntimeError, '^Instrumented close check timed out$') as error:
                f.run()
            self.assertIsNone(error.exception.__cause__)
            f.assert_clean(self)
            f.close.assert_called_once_with(99)
            self.assertNotIn(f.key, (f.output / 'evidence.json').read_text())


if __name__ == '__main__':
    unittest.main()
