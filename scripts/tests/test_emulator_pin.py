import hashlib
import importlib.util
import io
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('sdk_selector', Path(__file__).resolve().parents[1] / 'install-android-sdk.py')
sdk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sdk)


class EmulatorPinTest(unittest.TestCase):
    def payload(self, extra=None):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            binary = zipfile.ZipInfo('emulator/emulator')
            binary.external_attr = (stat.S_IFREG | 0o755) << 16
            archive.writestr(binary, b'#!/bin/sh\nexit 0\n')
            archive.writestr('emulator/source.properties', 'Pkg.Revision=37.2.12\n')
            if extra:
                archive.writestr(*extra)
        return output.getvalue()

    def run_pin(self, root, payload, checksum=None):
        location = root / 'sdk'
        (location / 'emulator').mkdir(parents=True, exist_ok=True)
        (location / 'emulator/source.properties').write_text('Pkg.Revision=37.2.13\n')
        with patch.dict(os.environ, {'TERMINAL_SPIKE_EMULATOR_BIN': ''}), \
                patch('platform.system', return_value='Linux'), patch('platform.machine', return_value='x86_64'), \
                patch.object(Path, 'home', return_value=root), \
                patch.object(sdk, 'PIN_SIZE', len(payload)), \
                patch.object(sdk, 'PIN_SHA256', checksum or hashlib.sha256(payload).hexdigest()), \
                patch('urllib.request.urlopen', return_value=io.BytesIO(payload)) as fetch:
            first = sdk.emulator_path(location)
            second = sdk.emulator_path(location)
            self.assertEqual(first, second)
            fetch.assert_called_once()
            self.assertTrue(os.access(first, os.X_OK))
        self.assertEqual('Pkg.Revision=37.2.13\n', (location / 'emulator/source.properties').read_text())
        return first

    def test_verified_archive_is_cached_and_sdk_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = self.run_pin(Path(tmp), self.payload())
            self.assertTrue(path.is_file())
            self.assertEqual('Pkg.Revision=37.2.12\n', path.with_name('source.properties').read_text())

    def test_checksum_mismatch_cannot_fall_back_to_crashing_sdk(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, 'checksum'):
                self.run_pin(Path(tmp), self.payload(), checksum='0' * 64)

    def test_archive_paths_cannot_escape_the_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, 'Unsafe'):
                self.run_pin(Path(tmp), self.payload(('../escape', 'no')))
            self.assertFalse((Path(tmp) / 'escape').exists())

    def test_other_platform_uses_its_sdk_without_linux_download(self):
        with tempfile.TemporaryDirectory() as tmp, patch('platform.system', return_value='Darwin'), patch('urllib.request.urlopen') as fetch:
            root = Path(tmp)
            with patch.dict(os.environ, {'TERMINAL_SPIKE_EMULATOR_BIN': ''}):
                self.assertEqual(root / 'emulator/emulator', sdk.emulator_path(root))
            fetch.assert_not_called()
