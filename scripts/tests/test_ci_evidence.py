import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("ci_evidence", Path(__file__).resolve().parents[1] / "preserve-ci-evidence.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiEvidenceTest(unittest.TestCase):
    def test_reports_survive_workspace_cleanup_and_attempts_are_separate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "workspace/results"
            source.mkdir(parents=True)
            (source / "instrumentation.txt").write_text("failed test evidence")
            destination = MODULE.preserve(source, root / "archive", "123", "1", "api-26-shard-0")
            shutil.rmtree(source.parent)
            self.assertEqual("failed test evidence", (destination / "instrumentation.txt").read_text())
            other = MODULE.preserve(source, root / "archive", "123", "2", "api-26-shard-0")
            self.assertNotEqual(destination, other)

    def test_invalid_identity_cannot_escape_archive(self):
        with self.assertRaises(ValueError):
            MODULE.preserve(Path("missing"), Path("archive"), "../escape", "1", "report")
