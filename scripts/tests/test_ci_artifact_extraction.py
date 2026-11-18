import errno
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "ci_artifact_extraction", Path(__file__).resolve().parents[1] / "download-ci-artifact.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiArtifactExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="owned-artifact-extraction-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.destination = self.root / "output"

    def archive(self, entries=None):
        stream = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as archive:
                for name, data in entries or [("first.txt", b"new first"), ("nested/second.txt", b"new second")]:
                    archive.writestr(name, data)
        return stream.getvalue()

    def unpack(self, data):
        MODULE.unpack(data, {"digest": "sha256:" + hashlib.sha256(data).hexdigest()}, self.destination)

    def snapshot(self):
        return {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}

    def seed_destination(self):
        self.destination.mkdir()
        (self.destination / "first.txt").write_bytes(b"old first")
        (self.destination / "keep.txt").write_bytes(b"unrelated owned evidence")
        return self.snapshot()

    def assert_no_staging(self):
        self.assertEqual([self.destination] if self.destination.exists() else [], list(self.root.iterdir()))

    def test_crc_failure_keeps_existing_destination_exact(self):
        original = self.seed_destination()
        data = bytearray(self.archive())
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            member = archive.getinfo("nested/second.txt")
            offset = member.header_offset
            name_size = int.from_bytes(data[offset + 26:offset + 28], "little")
            extra_size = int.from_bytes(data[offset + 28:offset + 30], "little")
            data[offset + 30 + name_size + extra_size] ^= 1
        # Transport SHA is valid; the second member's internal CRC is deliberately invalid.
        with self.assertRaises(MODULE.ArtifactFailure):
            self.unpack(bytes(data))
        self.assertEqual(original, self.snapshot())
        self.assert_no_staging()

    def test_interrupted_or_failed_extraction_keeps_existing_destination_exact(self):
        original = self.seed_destination()
        extract = zipfile.ZipFile._extract_member
        for failure in [MODULE.ArtifactFailure("owned total deadline"), KeyboardInterrupt(),
                        OSError(errno.ENOSPC, "owned simulated full disk")]:
            with self.subTest(failure=type(failure).__name__):
                (self.destination / "first.txt").write_bytes(b"old first")
                calls = []

                def fail_second(archive, member, target, password):
                    calls.append(member)
                    if len(calls) == 2:
                        raise failure
                    return extract(archive, member, target, password)

                with patch.object(zipfile.ZipFile, "_extract_member", fail_second), \
                        self.assertRaises(type(failure)) as caught:
                    self.unpack(self.archive())
                self.assertIs(failure, caught.exception)
                self.assertEqual(2, len(calls))
                self.assertEqual(original, self.snapshot())
                self.assert_no_staging()

    def test_failed_extraction_does_not_create_destination(self):
        with patch.object(zipfile.ZipFile, "extractall", side_effect=MODULE.ArtifactFailure("owned stop")), \
                self.assertRaises(MODULE.ArtifactFailure):
            self.unpack(self.archive())
        self.assertFalse(self.destination.exists())
        self.assert_no_staging()

    def test_success_preserves_unrelated_files_and_publishes_complete_members(self):
        self.seed_destination()
        self.unpack(self.archive())
        self.assertEqual({"output/first.txt": b"new first", "output/nested/second.txt": b"new second",
                          "output/keep.txt": b"unrelated owned evidence"}, self.snapshot())
        self.assert_no_staging()

    def test_bad_zip_header_never_changes_destination(self):
        original = self.seed_destination()
        with self.assertRaises(MODULE.ArtifactFailure):
            self.unpack(b"owned non-zip data")
        self.assertEqual(original, self.snapshot())
        self.assert_no_staging()

    def test_expanded_size_limit_is_checked_before_extracting(self):
        original = self.seed_destination()
        oversized = zipfile.ZipInfo("owned-huge")
        oversized.file_size = 512 * 1024 * 1024 + 1
        with patch.object(zipfile.ZipFile, "infolist", return_value=[oversized]), \
                patch.object(zipfile.ZipFile, "extractall") as extract, \
                self.assertRaises(MODULE.ArtifactFailure):
            self.unpack(self.archive())
        extract.assert_not_called()
        self.assertEqual(original, self.snapshot())
        self.assert_no_staging()

    def test_unsafe_members_are_rejected_before_staging_or_destination_writes(self):
        original = self.seed_destination()
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        for name in ["../escape", "/absolute", "folder\\escape", link]:
            with self.subTest(name=str(name)), self.assertRaises(MODULE.ArtifactFailure):
                self.unpack(self.archive([("first.txt", b"new first"), (name, b"target")]))
            self.assertEqual(original, self.snapshot())
            self.assert_no_staging()

    def test_existing_outward_symlink_is_rejected_without_touching_its_target(self):
        original = self.seed_destination()
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "second.txt").write_bytes(b"owned outside")
        (self.destination / "nested").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(MODULE.ArtifactFailure):
            self.unpack(self.archive())
        self.assertEqual(b"owned outside", (outside / "second.txt").read_bytes())
        self.assertEqual(original["output/first.txt"], (self.destination / "first.txt").read_bytes())
        self.assertEqual({"outside", "output"}, {p.name for p in self.root.iterdir()})

    def test_normalized_duplicate_names_keep_zip_last_entry_semantics(self):
        self.unpack(self.archive([("nested/", b""), ("nested/./file.txt", b"old"),
                                  ("nested/file.txt", b"new"), ("nested/file.txt", b"last")]))
        self.assertEqual({"output/nested/file.txt": b"last"}, self.snapshot())
        self.assert_no_staging()

    def test_publish_failure_leaves_only_complete_members_and_cleans_staging(self):
        self.seed_destination()
        replace = Path.replace
        calls = []
        failure = OSError(errno.EACCES, "owned publish failure")

        def fail_second(source, target):
            calls.append(Path(target))
            if len(calls) == 2:
                raise failure
            return replace(source, target)

        with patch.object(Path, "replace", fail_second), self.assertRaises(OSError) as caught:
            self.unpack(self.archive())
        self.assertIs(failure, caught.exception)
        self.assertEqual(2, len(calls))
        expected = {"first.txt": b"new first", "nested/second.txt": b"new second"}
        published = calls[0]
        self.assertEqual(expected[str(published.relative_to(self.destination))], published.read_bytes())
        self.assertEqual(b"unrelated owned evidence", (self.destination / "keep.txt").read_bytes())
        self.assert_no_staging()
