import argparse
import base64
import errno
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import urllib.parse
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
        notes = self.root / "store-assets/google-play/release-notes/main-en-US.txt"
        notes.parent.mkdir(parents=True)
        notes.write_text("Reviewed release notes.\n")
        self.manifest = {"schema": 1, "package": PLAY.PACKAGE, "track": "internal", "versionCode": 10,
                         "versionName": "0.0.7", "source": "fingerprint",
                         "sha256": hashlib.sha256(self.bundle.read_bytes()).hexdigest()}
        self.state.write_text(json.dumps(self.manifest))
        self.args = argparse.Namespace(track="internal", require_green_ci=False, with_store_assets=False, credential=self.root / "external.json",
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

    def test_production_requires_matching_prepared_track_and_includes_release_notes(self):
        self.args.track = "production"
        with patch.object(PLAY, "request") as api:
            with self.assertRaisesRegex(RuntimeError, "prepared source/artifact changed"):
                PLAY.publish(self.args, {9})
            api.assert_not_called()
        self.manifest["track"] = "production"
        self.state.write_text(json.dumps(self.manifest))
        notes = self.root / "store-assets/google-play/release-notes/main-en-US.txt"
        notes.parent.mkdir(parents=True, exist_ok=True)
        notes.write_text("New browser, guides and terminal improvements.\n")
        track = {"track": "production", "releases": [{"versionCodes": ["10"], "status": "completed"}]}
        with patch.object(PLAY, "command", side_effect=self.git), patch.object(PLAY, "request", side_effect=self.api), \
                patch.object(PLAY, "inspect_play", return_value=({10}, track)) as inspect, patch("builtins.print"):
            PLAY.publish(self.args, {9})
        inspect.assert_called_once_with("secret-token", "production")
        updates = [(url, data) for method, url, data in self.calls if method == "PUT"]
        self.assertEqual(1, len(updates))
        self.assertTrue(updates[0][0].endswith("/tracks/production"))
        self.assertEqual("production", updates[0][1]["track"])
        self.assertEqual("completed", updates[0][1]["releases"][0]["status"])
        self.assertEqual(notes.read_text().strip(), updates[0][1]["releases"][0]["releaseNotes"][0]["text"])
        self.assertEqual(1, sum("uploadType=media" in url for _, url, _ in self.calls))

    def test_missing_or_oversized_production_notes_block_upload(self):
        self.args.track = "production"
        self.manifest["track"] = "production"
        self.state.write_text(json.dumps(self.manifest))
        notes = self.root / "store-assets/google-play/release-notes/main-en-US.txt"
        notes.unlink()
        for content in (None, "", "x" * 501):
            if content is not None:
                notes.write_text(content)
            with self.subTest(content=content), patch.object(PLAY, "command", side_effect=self.git), \
                    patch.object(PLAY, "request") as api:
                with self.assertRaises((OSError, RuntimeError)):
                    PLAY.publish(self.args, {9})
                api.assert_not_called()

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

    def test_store_assets_preserve_upload_order_and_verify_readback(self):
        listing = {"title": "Terminal Spike", "shortDescription": "tmux and Herdr"}
        images = {"phoneScreenshots": [b"first picture", b"second picture"], "featureGraphic": [b"graphic"]}
        uploaded = {kind: [] for kind in images}
        calls = []

        def api(method, url, data=None, token=None, content_type=None):
            calls.append((method, url, data))
            if method == "GET" and url.endswith("en-US"):
                return listing
            kind = url.split("/")[-1].split("?")[0]
            if method == "POST":
                image = {"sha256": hashlib.sha256(data).hexdigest()}
                uploaded[kind].append(image)
                self.assertEqual("image/png", content_type)
                return {"image": image}
            if method == "GET":
                return {"images": uploaded[kind]}
            return {}

        with patch.object(PLAY, "request", side_effect=api):
            hashes = PLAY.upload_store_assets("token", "edit", listing, images)
        self.assertEqual([hashlib.sha256(content).hexdigest() for content in images["phoneScreenshots"]],
                         hashes["phoneScreenshots"])
        self.assertEqual(listing, calls[0][2])
        self.assertEqual("PATCH", calls[0][0])
        self.assertTrue(all("/listings/en-US" in url for _, url, _ in calls))
        self.assertEqual(2, sum(method == "DELETE" for method, _, _ in calls))

    def test_listing_or_image_order_mismatch_blocks_readback(self):
        listing = {"title": "Terminal Spike"}
        images = {"phoneScreenshots": [b"first", b"second"]}
        reversed_images = [{"sha256": hashlib.sha256(content).hexdigest()} for content in reversed(images["phoneScreenshots"])]
        for actual_listing, actual_images in (({}, reversed_images), (listing, reversed_images)):
            with self.subTest(listing=actual_listing), patch.object(PLAY, "request", side_effect=[
                    actual_listing, {"images": actual_images}]):
                with self.assertRaises(RuntimeError):
                    PLAY.verify_store_assets("token", "edit", listing, images)

    def test_store_upload_failure_cannot_commit_bundle_or_retry(self):
        self.args.with_store_assets = True
        with patch.object(PLAY, "command", side_effect=self.git), patch.object(PLAY, "request", side_effect=self.api), \
                patch.object(PLAY, "store_assets", return_value=({}, {})), \
                patch.object(PLAY, "upload_store_assets", side_effect=RuntimeError("image upload failed")):
            with self.assertRaisesRegex(RuntimeError, "image upload failed"):
                PLAY.publish(self.args, {9})
            with self.assertRaisesRegex(RuntimeError, "already started"):
                PLAY.publish(self.args, {9})
        self.assertFalse(any(url.endswith(":commit") or url.endswith(":validate") for _, url, _ in self.calls))
        self.assertEqual(1, sum("uploadType=media" in url for _, url, _ in self.calls))

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

    def promotion_upload_receipt(self):
        (self.state.parent / "publish-receipt.json").write_text(json.dumps({
            "state": "completed", "versionCode": 10, "commit": "a" * 40,
        }))
        self.args.track = "all"

    def promotion_api(self, method, url, data=None, token=None, content_type=None):
        self.calls.append((method, url, data))
        if url.endswith("/edits"):
            return {"id": "promotion-edit"}
        if method == "GET" and url.endswith("/bundles"):
            return {"bundles": [{"versionCode": 10, "sha256": self.manifest["sha256"]}]}
        if method == "GET" and url.endswith("/tracks"):
            updates = [payload for verb, _, payload in self.calls if verb == "PUT"]
            return {"tracks": updates or [{"track": name} for name in ("production", "internal", "beta", "alpha")]}
        return {}

    def promotion_git(self, args, **kwargs):
        if args[1] == "diff":
            return "AGENTS.md\ndocs/CI-AND-RELEASING.md\n"
        return self.git(args, **kwargs)

    def test_promotion_updates_all_tracks_in_one_commit_without_upload(self):
        self.promotion_upload_receipt()
        with patch.object(PLAY, "command", side_effect=self.promotion_git), \
                patch.object(PLAY, "request", side_effect=self.promotion_api), patch("builtins.print"):
            PLAY.promote(self.args)
        updates = [data for method, _, data in self.calls if method == "PUT"]
        self.assertEqual({"production", "internal", "beta", "alpha"}, {item["track"] for item in updates})
        self.assertTrue(all(item["releases"][0]["versionCodes"] == ["10"] for item in updates))
        self.assertEqual(1, sum(url.endswith(":commit") for _, url, _ in self.calls))
        self.assertFalse(any("uploadType" in url for _, url, _ in self.calls))
        receipt = json.loads((self.state.parent / "promotion-receipt.json").read_text())
        self.assertEqual("completed", receipt["state"])
        self.assertEqual(4, len(receipt["confirmedTracks"]))

    def test_promotion_rejects_application_changes_before_api_access(self):
        self.promotion_upload_receipt()
        def changed_git(args, **kwargs):
            return "app/src/main/AndroidManifest.xml\n" if args[1] == "diff" else self.git(args, **kwargs)
        with patch.object(PLAY, "command", side_effect=changed_git), patch.object(PLAY, "request") as api:
            with self.assertRaisesRegex(RuntimeError, "application inputs changed"):
                PLAY.promote(self.args)
            api.assert_not_called()

    def test_promotion_rejects_wrong_remote_artifact_and_new_track(self):
        self.promotion_upload_receipt()
        for case in ("hash", "track"):
            def api(method, url, *args, **kwargs):
                result = self.promotion_api(method, url, *args, **kwargs)
                if case == "hash" and url.endswith("/bundles"):
                    result["bundles"][0]["sha256"] = "wrong"
                if case == "track" and url.endswith("/tracks"):
                    result["tracks"].append({"track": "unexpected"})
                return result
            self.calls.clear()
            with self.subTest(case=case), patch.object(PLAY, "command", side_effect=self.promotion_git), \
                    patch.object(PLAY, "request", side_effect=api):
                with self.assertRaises(RuntimeError):
                    PLAY.promote(self.args)
            self.assertFalse(any(method == "PUT" for method, _, _ in self.calls))
            self.assertTrue(any(method == "DELETE" for method, _, _ in self.calls))

    def test_ambiguous_promotion_commit_blocks_retry(self):
        self.promotion_upload_receipt()
        def api(method, url, *args, **kwargs):
            if url.endswith(":commit"):
                raise RuntimeError("timeout")
            return self.promotion_api(method, url, *args, **kwargs)
        with patch.object(PLAY, "command", side_effect=self.promotion_git), \
                patch.object(PLAY, "request", side_effect=api):
            with self.assertRaisesRegex(RuntimeError, "timeout"):
                PLAY.promote(self.args)
        with patch.object(PLAY, "command", side_effect=self.promotion_git), patch.object(PLAY, "request") as retry:
            with self.assertRaisesRegex(RuntimeError, "already started"):
                PLAY.promote(self.args)
            retry.assert_not_called()

    def test_promotion_does_not_claim_success_on_stale_track_readback(self):
        self.promotion_upload_receipt()
        def api(method, url, *args, **kwargs):
            result = self.promotion_api(method, url, *args, **kwargs)
            if method == "GET" and url.endswith("/tracks") and any(verb == "PUT" for verb, _, _ in self.calls):
                result["tracks"] = [{"track": "internal"}]
            return result
        with patch.object(PLAY, "command", side_effect=self.promotion_git), \
                patch.object(PLAY, "request", side_effect=api):
            with self.assertRaisesRegex(RuntimeError, "readback differs"):
                PLAY.promote(self.args)
        self.assertEqual("started", json.loads((self.state.parent / "promotion-receipt.json").read_text())["state"])


class PlayAccessTokenTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="owned-play-auth-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.credential = self.root / "account.json"
        self.account = {"client_email": "owned@example.invalid", "private_key": "owned dummy text, not a cryptographic key"}
        self.credential.write_text(json.dumps(self.account))
        self.real_temporary_directory = tempfile.TemporaryDirectory
        self.created = []
        self.key_paths = []

    def authenticate(self, environment, *, allow_legacy=False, sign_error=None, request_error=None,
                     directory_error=None):
        def directory(*args, **kwargs):
            if directory_error:
                raise directory_error
            if str(kwargs.get("dir")) == "/tmp":
                if not allow_legacy:
                    raise OSError(errno.ENOSPC, "owned simulated /tmp quota")
                # Positive cleanup controls exercise old behavior only inside our owned tree.
                kwargs["dir"] = self.root / "legacy-owned-tmp"
                kwargs["dir"].mkdir(exist_ok=True)
            result = self.real_temporary_directory(*args, **kwargs)
            self.created.append(Path(result.name))
            return result

        signature = b"\xfb\x00\xf1-owned-signature"

        def sign(arguments, **kwargs):
            self.assertEqual(["openssl", "dgst", "-sha256", "-sign"], arguments[:-1])
            key = Path(arguments[-1])
            self.key_paths.append(key)
            self.assertEqual(self.created[-1], key.parent)
            self.assertEqual(self.account["private_key"], key.read_text())
            self.assertEqual(0o600, key.stat().st_mode & 0o777)
            self.assertEqual(0o700, key.parent.stat().st_mode & 0o777)
            self.assertNotIn(self.account["private_key"], " ".join(arguments))
            message = kwargs["input"]
            header, claims = [json.loads(base64.urlsafe_b64decode(value + b"=" * (-len(value) % 4)))
                              for value in message.split(b".")]
            self.assertEqual({"alg": "RS256", "typ": "JWT"}, header)
            self.assertEqual({"iss": self.account["client_email"],
                              "scope": "https://www.googleapis.com/auth/androidpublisher",
                              "aud": PLAY.TOKEN_URL, "iat": 1234, "exp": 4834}, claims)
            if sign_error:
                raise sign_error
            return signature

        def exchange(method, url, data, **kwargs):
            self.assertEqual(("POST", PLAY.TOKEN_URL), (method, url))
            self.assertEqual("application/x-www-form-urlencoded", kwargs["content_type"])
            self.assertTrue(self.created)
            self.assertTrue(all(not path.exists() for path in self.created))
            payload = urllib.parse.parse_qs(data.decode(), strict_parsing=True)
            self.assertEqual({"grant_type", "assertion"}, set(payload))
            self.assertEqual(["urn:ietf:params:oauth:grant-type:jwt-bearer"], payload["grant_type"])
            encoded = payload["assertion"][0].split(".")
            self.assertEqual(sign_mock.call_args.kwargs["input"], ".".join(encoded[:2]).encode())
            self.assertEqual(signature, base64.urlsafe_b64decode(encoded[2] + "=" * (-len(encoded[2]) % 4)))
            if request_error:
                raise request_error
            return {"access_token": "owned-token"}

        with patch.dict(PLAY.os.environ, environment, clear=True), \
             patch.object(PLAY.Path, "home", return_value=self.root / "owned-home") as home, \
             patch.object(PLAY.time, "time", return_value=1234), \
             patch.object(PLAY.tempfile, "TemporaryDirectory", side_effect=directory) as workspace, \
             patch.object(PLAY, "command", side_effect=sign) as sign_mock, \
             patch.object(PLAY, "request", side_effect=exchange) as request_mock:
            self.workspace = workspace
            self.sign = sign_mock
            self.exchange = request_mock
            result = PLAY.access_token(self.credential)
            if environment.get("TMPDIR") or environment.get("RUNNER_TEMP") or environment.get("XDG_CACHE_HOME"):
                home.assert_not_called()
            return result

    def test_disk_temp_policy_survives_a_quota_limited_system_tmp(self):
        cases = [
            ({"TMPDIR": str(self.root / "explicit temp"), "RUNNER_TEMP": str(self.root / "runner"),
              "XDG_CACHE_HOME": str(self.root / "cache")}, self.root / "explicit temp"),
            ({"TMPDIR": "", "RUNNER_TEMP": str(self.root / "runner"),
              "XDG_CACHE_HOME": str(self.root / "cache")}, self.root / "runner"),
            ({"TMPDIR": "", "RUNNER_TEMP": "", "XDG_CACHE_HOME": str(self.root / "cache")},
             self.root / "cache/terminal-spike/tmp"),
            ({}, self.root / "owned-home/.cache/terminal-spike/tmp"),
            ({"TMPDIR": "", "RUNNER_TEMP": "", "XDG_CACHE_HOME": ""},
             self.root / "owned-home/.cache/terminal-spike/tmp"),
        ]
        for environment, expected in cases:
            with self.subTest(environment=environment):
                self.assertEqual("owned-token", self.authenticate(environment))
                self.assertEqual(expected, self.created[-1].parent)
                self.assertFalse(self.created[-1].exists())

    def test_invalid_explicit_temp_root_fails_before_signing_or_http(self):
        blocked = self.root / "not-a-directory"
        blocked.write_text("pre-existing sentinel")
        with self.assertRaises(FileExistsError):
            self.authenticate({"TMPDIR": str(blocked)})
        self.sign.assert_not_called()
        self.exchange.assert_not_called()
        self.workspace.assert_not_called()
        self.assertEqual("pre-existing sentinel", blocked.read_text())

    def test_signing_failure_removes_only_its_owned_key_and_skips_http(self):
        failure = RuntimeError("owned signer failure")
        with self.assertRaises(RuntimeError) as caught:
            self.authenticate({"TMPDIR": str(self.root)}, allow_legacy=True, sign_error=failure)
        self.assertIs(failure, caught.exception)
        self.exchange.assert_not_called()
        self.assertEqual(1, len(self.key_paths))
        self.assertTrue(all(not p.exists() for p in self.created))
        self.assertTrue(self.credential.exists())

    def test_http_failure_happens_after_private_workspace_is_removed(self):
        failure = RuntimeError("owned HTTP failure")
        with self.assertRaises(RuntimeError) as caught:
            self.authenticate({"TMPDIR": str(self.root)}, allow_legacy=True, request_error=failure)
        self.assertIs(failure, caught.exception)
        self.sign.assert_called_once()
        self.exchange.assert_called_once()
        self.assertTrue(all(not p.exists() for p in self.created))
        self.assertTrue(self.credential.exists())

    def test_workspace_creation_failure_never_signs_or_requests_a_token(self):
        failure = OSError(errno.ENOSPC, "owned disk-full failure")
        with self.assertRaises(OSError) as caught:
            self.authenticate({"TMPDIR": str(self.root)}, directory_error=failure)
        self.assertIs(failure, caught.exception)
        self.sign.assert_not_called()
        self.exchange.assert_not_called()
        self.assertEqual([], self.created)

    def test_invalid_credentials_are_rejected_before_creating_private_files(self):
        self.credential.write_text("not JSON")
        with self.assertRaises(json.JSONDecodeError):
            self.authenticate({"TMPDIR": str(self.root)})
        self.workspace.assert_not_called()
        self.sign.assert_not_called()
        self.exchange.assert_not_called()


if __name__ == "__main__":
    unittest.main()
