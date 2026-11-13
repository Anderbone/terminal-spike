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
                patch.object(MODULE, "metadata", side_effect=[{"artifacts": [artifact]}, artifact]), \
                patch.object(MODULE, "request", side_effect=[MODULE.RequestFailure(404), data]) as request, \
                patch.object(MODULE.time, "sleep"):
            MODULE.download("owner/repo", "42", "report", Path(temporary), expected_sha="verified-sha")
            self.assertEqual("exact failed-test evidence", (Path(temporary) / "report.txt").read_text())
            self.assertEqual(2, request.call_count)
            self.assertEqual(request.call_args_list[0], request.call_args_list[1])

    def test_repeated_transport_failure_stays_failed(self):
        _, artifact = self.fixture()
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(MODULE, "metadata", return_value={"artifacts": [artifact]}), \
                patch.object(MODULE, "request", side_effect=MODULE.RequestFailure(503, True)) as request, \
                patch.object(MODULE.time, "sleep"), self.assertRaises(MODULE.ArtifactFailure):
            MODULE.download("owner/repo", "42", "report", Path(temporary))
        self.assertEqual(2, request.call_count)

    def test_missing_or_wrong_revision_never_downloads(self):
        _, artifact = self.fixture()
        for listing in ([], [artifact]):
            with tempfile.TemporaryDirectory() as temporary, \
                    patch.object(MODULE, "metadata", return_value={"artifacts": listing}), \
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
