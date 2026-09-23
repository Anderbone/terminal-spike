import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class AndroidVerificationTest(unittest.TestCase):
    def test_unsigned_and_signed_builds_keep_checks_but_do_not_cache_secrets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            runner = root / "scripts/verify-android.sh"
            shutil.copy2(Path(__file__).resolve().parents[1] / "verify-android.sh", runner)
            wrapper = root / "gradlew"
            wrapper.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > arguments.txt\n')
            wrapper.chmod(0o755)
            for signed in (False, True):
                with self.subTest(signed=signed):
                    environment = dict(os.environ)
                    environment.pop("TERMINAL_SPIKE_RELEASE_STORE_FILE", None)
                    if signed:
                        environment["TERMINAL_SPIKE_RELEASE_STORE_FILE"] = "/external/test-only.p12"
                    result = subprocess.run([str(runner), "build"], env=environment, capture_output=True, text=True)
                    self.assertEqual(0, result.returncode, result.stderr)
                    arguments = (root / "arguments.txt").read_text().splitlines()
                    for task in (":app:testDebugUnitTest", ":app:lintDebug", ":app:lintRelease",
                                 ":app:assembleDebug", ":app:assembleRelease", ":app:bundleRelease",
                                 ":app:assembleDebugAndroidTest", ":benchmark:assemble"):
                        self.assertIn(task, arguments)
                    self.assertIn("--dependency-verification=strict", arguments)
                    self.assertEqual(signed, "--no-configuration-cache" in arguments)
                    self.assertEqual(signed, "--no-daemon" in arguments)
                    self.assertNotIn("clean", arguments)
                    self.assertNotIn("-Pkotlin.incremental=false", arguments)


if __name__ == "__main__":
    unittest.main()
