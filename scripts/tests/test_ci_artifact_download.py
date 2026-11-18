import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("ci_artifact", Path(__file__).resolve().parents[1] / "download-ci-artifact.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiArtifactDownloadTest(unittest.TestCase):
    def fixture(self, name="report.txt"):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr(name, "exact failed-test evidence")
        data = buffer.getvalue()
        artifact = dict(id=7, name="report", expired=False,
                        digest="sha256:" + hashlib.sha256(data).hexdigest(),
                        workflow_run={"id": 42, "head_sha": "verified-sha"})
        return data, artifact

    def test_existing_exact_artifact_404_retries_once_and_verifies_bytes(self):
        data, artifact = self.fixture()
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", side_effect=[{"total_count": 1, "artifacts": [artifact]}, artifact]), \
                patch.object(MODULE, "request", side_effect=[MODULE.RequestFailure(404), data]) as request, \
                patch.object(MODULE.time, "sleep"):
            MODULE.download("owner/repo", "42", "report", Path(temporary), expected_sha="verified-sha")
            self.assertEqual("exact failed-test evidence", (Path(temporary) / "report.txt").read_text())
            self.assertEqual(2, request.call_count)
            self.assertEqual(request.call_args_list[0], request.call_args_list[1])

    def test_repeated_transport_failure_stays_failed(self):
        _, artifact = self.fixture()
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", return_value={"total_count": 1, "artifacts": [artifact]}), \
                patch.object(MODULE, "request", side_effect=MODULE.RequestFailure(503, True)) as request, \
                patch.object(MODULE.time, "sleep"), self.assertRaises(MODULE.ArtifactFailure):
            MODULE.download("owner/repo", "42", "report", Path(temporary))
        self.assertEqual(2, request.call_count)

    def test_missing_or_wrong_revision_never_downloads(self):
        _, artifact = self.fixture()
        for listing in ([], [artifact]):
            with tempfile.TemporaryDirectory() as temporary, \
                    patch.object(MODULE, "metadata", return_value={"total_count": len(listing), "artifacts": listing}), \
                    patch.object(MODULE, "request") as request, self.assertRaises(MODULE.ArtifactFailure):
                MODULE.download("owner/repo", "42", "report", Path(temporary), expected_sha="different-sha")
            request.assert_not_called()

    def test_bad_digest_and_unsafe_zip_never_extract(self):
        for name, corrupt in (("report.txt", True), ("../escape", False)):
            data, artifact = self.fixture(name)
            if corrupt:
                data += b"changed"
            with tempfile.TemporaryDirectory() as temporary, self.assertRaises(MODULE.ArtifactFailure):
                MODULE.unpack(data, artifact, Path(temporary))


class CiArtifactPaginationTest(unittest.TestCase):
    fixture = CiArtifactDownloadTest.fixture

    def listing(self, *artifacts, total):
        return {"total_count": total, "artifacts": list(artifacts)}

    def other_artifacts(self, count):
        _, artifact = self.fixture()
        return [dict(artifact, id=100 + i, name=f"unrelated-{i}") for i in range(count)]

    def test_exact_match_on_second_page_downloads_verified_bytes(self):
        data, artifact = self.fixture()
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", side_effect=[
                    self.listing(*self.other_artifacts(100), total=101),
                    self.listing(artifact, total=101)]) as metadata, \
                patch.object(MODULE, "request", return_value=data) as request:
            MODULE.download("owner/repo", "42", "report", Path(temporary), expected_sha="verified-sha")
            self.assertEqual("exact failed-test evidence", (Path(temporary) / "report.txt").read_text())
            self.assertEqual([
                (("repos/owner/repo/actions/runs/42/artifacts?per_page=100",), {}),
                (("repos/owner/repo/actions/runs/42/artifacts?per_page=100&page=2",), {})],
                metadata.call_args_list)
            request.assert_called_once_with("repos/owner/repo/actions/artifacts/7/zip")

    def test_duplicate_name_on_later_page_never_downloads(self):
        data, artifact = self.fixture()
        pages = [self.listing(artifact, *self.other_artifacts(99), total=101),
                 self.listing(dict(artifact, id=8), total=101)]
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", side_effect=pages), \
                patch.object(MODULE, "request", return_value=data) as request, self.assertRaises(MODULE.ArtifactFailure):
            MODULE.download("owner/repo", "42", "report", Path(temporary))
        request.assert_not_called()

    def test_pattern_selects_all_pages_before_downloading(self):
        data, artifact = self.fixture()
        pages = [self.listing(dict(artifact, name="report-one"), *self.other_artifacts(99), total=101),
                 self.listing(dict(artifact, id=8, name="report-two"), total=101)]
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", side_effect=pages) as metadata, \
                patch.object(MODULE, "request", return_value=data) as request:
            MODULE.download("owner/repo", "42", "report-*", Path(temporary), pattern=True)
            for name in ["report-one", "report-two"]:
                self.assertEqual("exact failed-test evidence", (Path(temporary) / name / "report.txt").read_text())
            self.assertEqual(2, metadata.call_count)
            self.assertEqual(2, request.call_count)

    def test_exactly_full_final_page_needs_no_extra_request(self):
        data, artifact = self.fixture()
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", return_value=self.listing(
                    artifact, *self.other_artifacts(99), total=100)) as metadata, \
                patch.object(MODULE, "request", return_value=data):
            MODULE.download("owner/repo", "42", "report", Path(temporary))
        metadata.assert_called_once()

    def test_three_pages_keep_incrementing_page_number(self):
        data, artifact = self.fixture()
        others = self.other_artifacts(200)
        pages = [self.listing(*others[:100], total=201), self.listing(*others[100:], total=201),
                 self.listing(artifact, total=201)]
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", side_effect=pages) as metadata, \
                patch.object(MODULE, "request", return_value=data):
            MODULE.download("owner/repo", "42", "report", Path(temporary))
        self.assertTrue(metadata.call_args_list[-1].args[0].endswith("&page=3"))

    def test_empty_complete_listing_fails_membership_without_extra_request(self):
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", return_value=self.listing(total=0)) as metadata, \
                patch.object(MODULE, "request") as request, self.assertRaises(MODULE.ArtifactFailure):
            MODULE.download("owner/repo", "42", "report", Path(temporary))
        metadata.assert_called_once()
        request.assert_not_called()

    def test_incomplete_or_changing_listing_never_downloads_partial_membership(self):
        data, artifact = self.fixture()
        first = self.listing(artifact, *self.other_artifacts(99), total=101)
        for later in [self.listing(total=101), self.listing(dict(artifact, id=8), total=102),
                      self.listing(artifact, total=101),
                      self.listing(dict(artifact, id=8), dict(artifact, id=9), total=101)]:
            with self.subTest(later=later):
                with tempfile.TemporaryDirectory() as temporary, \
                    patch.object(MODULE, "metadata", side_effect=[first, later]), \
                    patch.object(MODULE, "request", return_value=data) as request, self.assertRaises(MODULE.ArtifactFailure):
                    MODULE.download("owner/repo", "42", "report", Path(temporary))
                request.assert_not_called()

    def test_invalid_listing_count_never_downloads(self):
        data, artifact = self.fixture()
        for total in [None, -1, True, "1", 0]:
            with self.subTest(total=total):
                with tempfile.TemporaryDirectory() as temporary, \
                    patch.object(MODULE, "metadata", return_value=self.listing(artifact, total=total)), \
                    patch.object(MODULE, "request", return_value=data) as request, self.assertRaises(MODULE.ArtifactFailure):
                    MODULE.download("owner/repo", "42", "report", Path(temporary))
                request.assert_not_called()
