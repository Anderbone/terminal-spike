import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "install_android_sdk", Path(__file__).resolve().parents[1] / "install-android-sdk.py")
sdk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sdk)


class InstallAndroidSdkTest(unittest.TestCase):
    def run_install(self, results):
        command = ["/sdk/sdkmanager", "emulator", "platform-tools"]
        with patch.object(sdk.subprocess, "run", side_effect=results) as run, \
                patch.object(sdk.time, "sleep") as sleep, patch("builtins.print"):
            status = sdk.install(command)
        for call in run.call_args_list:
            self.assertEqual(command, call.args[0])
        return status, run.call_count, sleep.call_count

    def result(self, status, output):
        return subprocess.CompletedProcess([], status, output)

    def test_success_is_not_retried(self):
        self.assertEqual((0, 1, 0), self.run_install([self.result(0, "installed")]))

    def test_known_transport_failure_retries_identical_packages_once(self):
        for message in ("Server returned HTTP response code: 502 for URL: https://dl.google.com/x",
                        "HTTP response code: 429", "HTTP response code: 503", "HTTP response code: 504",
                        "java.net.SocketTimeoutException: Read timed out",
                        "java.net.SocketException: Connection reset"):
            with self.subTest(message=message):
                self.assertEqual((0, 2, 1), self.run_install([
                    self.result(1, message), self.result(0, "installed")]))

    def test_repeated_transport_failure_stays_failed(self):
        failure = self.result(1, "HTTP response code: 502")
        self.assertEqual((1, 2, 1), self.run_install([failure, failure]))

    def test_configuration_and_unknown_failures_are_not_retried(self):
        for message in ("Failed to find package emulator", "License not accepted",
                        "HTTP response code: 403", "HTTP response code: 404", "unknown error"):
            with self.subTest(message=message):
                self.assertEqual((2, 1, 0), self.run_install([self.result(2, message)]))
