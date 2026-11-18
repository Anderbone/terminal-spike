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

    def test_dot_and_parent_identities_fail_before_creating_any_archive(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "archive"
            for index in range(3):
                for part in (".", ".."):
                    identity = ["123", "1", "report"]
                    identity[index] = part
                    with self.subTest(index=index, part=part):
                        with self.assertRaisesRegex(ValueError, "Unsafe CI evidence identity"):
                            MODULE.preserve(Path(temporary) / "missing", archive, *identity)
                        self.assertFalse(archive.exists())

    def test_names_containing_dots_are_still_separate_safe_segments(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "report.txt").write_text("sanitized result")
            for name in ("..report", "report..", "api-26.shard_0"):
                destination = MODULE.preserve(source, root / "archive", "123", "1", name)
                self.assertEqual(root / "archive" / "123" / "1" / name, destination)
                self.assertEqual("sanitized result", (destination / "report.txt").read_text())
