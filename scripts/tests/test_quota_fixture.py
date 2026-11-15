import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class QuotaFixtureTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("cc"), "Host-only fault injection requires cc")
    def test_cat_hits_real_edquot_only_in_the_selected_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bad = root / "quota"; bad.mkdir()
            good = root / "disk"; good.mkdir()
            source = Path(__file__).resolve().parents[2] / "integration-tests/quota/edquot.c"
            library = root / "edquot.so"
            subprocess.run(["cc", "-shared", "-fPIC", str(source), "-ldl", "-o", str(library)], check=True)
            environment = dict(os.environ, LD_PRELOAD=str(library), TERMINAL_SPIKE_QUOTA_ROOT=str(bad))
            with (bad / "probe").open("wb") as output:
                result = subprocess.run(["cat"], input=b"probe", stdout=output, stderr=subprocess.PIPE, env=environment)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b"Disk quota exceeded", result.stderr)
            with (good / "probe").open("wb") as output:
                result = subprocess.run(["cat"], input=b"probe", stdout=output, stderr=subprocess.PIPE, env=environment)
            self.assertEqual(0, result.returncode)
            self.assertEqual(b"probe", (good / "probe").read_bytes())


if __name__ == "__main__":
    unittest.main()
