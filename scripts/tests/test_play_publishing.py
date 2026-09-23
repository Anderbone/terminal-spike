import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("play_publish", Path(__file__).resolve().parents[1] / "publish-play-internal.py")
PLAY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PLAY)


class PlayPublishingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / "build/play-release/manifest.json"
        self.state.parent.mkdir(parents=True)
        self.bundle = self.state.parent / "app-release.aab"
        self.bundle.write_bytes(b"verified bundle")
        self.manifest = {"schema": 1, "package": PLAY.PACKAGE, "track": "internal", "versionCode": 10,
                         "versionName": "0.0.7", "source": "fingerprint",
                         "sha256": hashlib.sha256(self.bundle.read_bytes()).hexdigest()}
        self.state.write_text(json.dumps(self.manifest))
        self.args = argparse.Namespace(require_green_ci=False, credential=self.root / "external.json",
                                       signing_dir=self.root / "signing", version_name="0.0.7")
        self.patches = [patch.object(PLAY, "ROOT", self.root), patch.object(PLAY, "STATE", self.state),
                        patch.object(PLAY, "source_fingerprint", return_value="fingerprint"),
                        patch.object(PLAY, "verify_bundle"), patch.object(PLAY, "access_token", return_value="secret-token")]
        for item in self.patches:
            item.start()
        self.calls = []

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.temporary.cleanup()

    def git(self, args, **kwargs):
        if args[1] == "status":
            return b""
        if args[1] == "rev-parse":
            return "a" * 40
        if args[1] == "ls-remote":
            return "a" * 40 + "\trefs/heads/main\n"
        raise AssertionError(args)

    def api(self, method, url, data=None, token=None, content_type=None):
        self.calls.append((method, url, data))
        if url.endswith("/edits"):
            return {"id": "edit-1"}
        if "uploadType=media" in url:
            return {"versionCode": 10, "sha256": self.manifest["sha256"]}
        return {}

    def test_consumed_codes_include_all_tracks_and_unassigned_bundles(self):
        self.assertEqual({3, 9, 12}, PLAY.consumed_codes(
            {"bundles": [{"versionCode": 12}]},
            {"tracks": [{"track": "internal", "releases": [{"versionCodes": ["9"]}]},
                        {"track": "alpha", "releases": [{"versionCodes": ["3"]}]}]},
        ))

    def test_publishes_once_then_requires_completed_track_readback(self):
        track = {"track": "internal", "releases": [{"versionCodes": ["10"], "status": "completed"}]}
        with patch.object(PLAY, "command", side_effect=self.git), patch.object(PLAY, "request", side_effect=self.api), \
                patch.object(PLAY, "inspect_play", return_value=({10}, track)), patch("builtins.print"):
            PLAY.publish(self.args, {9})
        self.assertEqual(1, sum("uploadType=media" in url for _, url, _ in self.calls))
        self.assertTrue(any(url.endswith(":validate") for _, url, _ in self.calls))
        self.assertTrue(any(url.endswith(":commit") for _, url, _ in self.calls))
        self.assertFalse(any("production" in url for _, url, _ in self.calls))
        receipt = json.loads((self.state.parent / "publish-receipt.json").read_text())
        self.assertEqual("completed", receipt["state"])

    def test_changed_source_artifact_consumed_code_and_unpushed_commit_block_upload(self):
        for case in ("source", "artifact", "consumed", "dirty", "unpushed"):
            with self.subTest(case=case):
                self.bundle.write_bytes(b"verified bundle" if case != "artifact" else b"changed")
                def git(args, **kwargs):
                    if case == "dirty" and args[1] == "status":
                        return b" M app/build.gradle.kts"
                    if case == "unpushed" and args[1] == "ls-remote":
                        return "b" * 40 + "\trefs/heads/main"
                    return self.git(args, **kwargs)
                with patch.object(PLAY, "source_fingerprint", return_value="changed" if case == "source" else "fingerprint"), \
                        patch.object(PLAY, "command", side_effect=git), patch.object(PLAY, "request") as api:
                    with self.assertRaises(RuntimeError):
                        PLAY.publish(self.args, {10} if case == "consumed" else {9})
                    api.assert_not_called()

    def test_ambiguous_upload_is_recorded_and_never_automatically_retried(self):
        def failed_upload(method, url, *args, **kwargs):
            if "uploadType=media" in url:
                raise RuntimeError("timeout")
            return self.api(method, url, *args, **kwargs)
        with patch.object(PLAY, "command", side_effect=self.git), patch.object(PLAY, "request", side_effect=failed_upload):
            with self.assertRaisesRegex(RuntimeError, "timeout"):
                PLAY.publish(self.args, {9})
        with patch.object(PLAY, "command", side_effect=self.git), patch.object(PLAY, "request") as api:
            with self.assertRaisesRegex(RuntimeError, "already started"):
                PLAY.publish(self.args, {9})
            api.assert_not_called()

    def test_prepare_bumps_only_main_and_records_verified_source(self):
        (self.root / "app").mkdir()
        gradle = self.root / "app/build.gradle.kts"
        gradle.write_text('versionCode = 9\nversionName = "0.0.6"\n')
        self.args.signing_dir.mkdir()
        (self.args.signing_dir / "terminal-spike-upload.password").write_text("test-only")
        artifact = self.root / "app/build/outputs/bundle/release/app-release.aab"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"prepared")
        with patch.object(PLAY.subprocess, "run") as build, patch("builtins.print"):
            PLAY.prepare(self.args, {12})
        self.assertIn("versionCode = 13", gradle.read_text())
        self.assertEqual(1, build.call_count)
        self.assertEqual(13, json.loads(self.state.read_text())["versionCode"])


if __name__ == "__main__":
    unittest.main()
