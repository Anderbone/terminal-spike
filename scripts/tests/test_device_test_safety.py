import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import device_test_safety as safety


class DeviceSafetyTest(unittest.TestCase):
    def test_s10e_requires_inventory_model_actual_model_and_hardware_serial(self):
        for hardware, accepted in (("R28M227XA0E", True), ("OTHER", False)):
            with self.subTest(hardware=hardware), patch.object(safety, "output", side_effect=[
                "phone device model:SM_G9708", "SM-G9708", hardware,
            ]) as command:
                if accepted:
                    self.assertEqual("SM-G9708", safety.verify_device("adb", "phone"))
                else:
                    with self.assertRaises(RuntimeError):
                        safety.verify_device("adb", "phone")
                self.assertEqual(("adb", "-s", "phone", "shell", "getprop", "ro.serialno"), command.call_args.args)

    def test_rejects_fold_unauthorized_duplicate_and_model_prefix_before_shell(self):
        for rows in ("phone device model:SM_F976B", "phone unauthorized model:SM_S911B",
                     "phone device model:SM_S911B\nphone device model:SM_S911B", "phone device model:SM_S911B_extra"):
            with self.subTest(rows=rows), patch.object(safety, "output", return_value=rows) as command:
                with self.assertRaises(RuntimeError):
                    safety.verify_device("adb", "phone")
                self.assertEqual(1, command.call_count)

    def test_wireless_refused_when_supported_usb_phone_available(self):
        with patch.object(safety, "output", return_value="wifi device model:SM_G9708\nusb device usb:1 model:SM_S911B"):
            with self.assertRaisesRegex(RuntimeError, "USB"):
                safety.verify_device("adb", "wifi")

    def test_mismatched_actual_model_is_rejected(self):
        with patch.object(safety, "output", side_effect=["phone device model:SM_S911B", "SM-F976B"]):
            with self.assertRaises(RuntimeError):
                safety.verify_device("adb", "phone")

    def test_apk_and_instrumentation_identities_are_all_required(self):
        manifest = ('  E: instrumentation (line=1)\n'
                    f'    A: android:name(0x1)="{safety.RUNNER}"\n'
                    f'    A: android:targetPackage(0x2)="{safety.APP}"\n')
        for app, test, tree, accepted in (
            (safety.APP, safety.TEST_APP, manifest, True),
            ("com.yanjiyu.terminalspike", safety.TEST_APP, manifest, False),
            (safety.APP, "com.yanjiyu.terminalspike.test", manifest, False),
            (safety.APP, safety.TEST_APP, manifest.replace(safety.APP + '"', 'com.yanjiyu.terminalspike"'), False),
            (safety.APP, safety.TEST_APP, manifest.replace(safety.RUNNER, "other.Runner"), False),
            (safety.APP, safety.TEST_APP, manifest + manifest, False),
        ):
            with self.subTest(app=app, test=test, tree=tree), patch.object(safety, "find_aapt", return_value="aapt"), patch.object(
                safety, "output", side_effect=[f"package: name='{app}'", f"package: name='{test}'", tree]
            ):
                if accepted:
                    safety.verify_apks("adb", "app.apk", "test.apk")
                else:
                    with self.assertRaises(RuntimeError):
                        safety.verify_apks("adb", "app.apk", "test.apk")


if __name__ == "__main__":
    unittest.main()
