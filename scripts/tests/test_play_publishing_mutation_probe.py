"""Safety probes: fake Play only; exercise mutations with every real network path blocked."""
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("play_existing_fixtures", Path(__file__).with_name("test_play_publishing.py"))
FIXTURES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURES)
PLAY = FIXTURES.PLAY


class PlayPublishingMutationProbe(unittest.TestCase):
    def setUp(self):
        self.fixture = FIXTURES.PlayPublishingTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        # Defense in depth: even an accidentally missed request mock cannot use HTTP.
        self.network = patch.object(PLAY.urllib.request, "urlopen", side_effect=AssertionError("Real API/network is forbidden in this probe"))
        self.network.start()
        self.addCleanup(self.network.stop)

    def track_writes(self):
        return [url for method, url, _ in self.fixture.calls if method == "PUT" or url.endswith(":commit")]

    def run_promotion(self, *, token=None, api=None, git=None):
        f = self.fixture
        f.promotion_upload_receipt()
        with patch.object(PLAY, "command", side_effect=git or f.promotion_git), patch.object(
            PLAY, "access_token", side_effect=token or (lambda credential: "fake-token")
        ), patch.object(PLAY, "request", side_effect=api or f.promotion_api), patch("builtins.print"):
            try:
                PLAY.promote(f.args)
            except RuntimeError:
                pass

    def test_publish_head_change_during_token_stops_before_creating_an_edit(self):
        f = self.fixture
        changed = False
        def git(args, **kwargs):
            if changed and args[1] == "rev-parse":
                return "b" * 40
            return f.git(args, **kwargs)
        def token(credential):
            nonlocal changed
            changed = True
            return "fake-token"
        with patch.object(PLAY, "command", side_effect=git), patch.object(PLAY, "access_token", side_effect=token), patch.object(PLAY, "request") as api:
            with self.assertRaisesRegex(RuntimeError, "changed"):
                PLAY.publish(f.args, {9})
            api.assert_not_called()

    def test_head_change_after_validation_stops_before_commit(self):
        f = self.fixture
        changed = False
        def git(args, **kwargs):
            if changed and args[1] == "rev-parse":
                return "b" * 40
            return f.promotion_git(args, **kwargs)
        def api(method, url, *args, **kwargs):
            nonlocal changed
            result = f.promotion_api(method, url, *args, **kwargs)
            if url.endswith(":validate"):
                changed = True
            return result
        self.run_promotion(api=api, git=git)
        self.assertFalse(any(url.endswith(":commit") for _, url, _ in f.calls))
        self.assertEqual("started", json.loads((f.state.parent / "promotion-receipt.json").read_text())["state"])

    def test_ignored_inputs_changed_during_preflight_are_not_rebaselined(self):
        f = self.fixture
        notes = f.root / "store-assets/google-play/release-notes/main-en-US.txt"
        upload = f.state.parent / "publish-receipt.json"
        originals = {path: path.read_bytes() for path in (f.state, f.bundle, notes)}
        for selected in (f.state, f.bundle, notes, upload):
            with self.subTest(path=selected.name):
                f.calls.clear()
                for path, content in originals.items():
                    path.write_bytes(content)
                def api(method, url, *args, **kwargs):
                    result = f.promotion_api(method, url, *args, **kwargs)
                    if method == "GET" and url.endswith("/bundles"):
                        selected.write_bytes(b"concurrently changed ignored input")
                    return result
                self.run_promotion(api=api)
                self.assertEqual([], self.track_writes())

    def test_foreign_receipt_during_journal_persistence_is_preserved(self):
        f = self.fixture
        receipt = f.state.parent / "promotion-receipt.json"
        actual_replace = PLAY.os.replace
        foreign = {"state": "started", "edit": "another-promotion", "versionCode": 10}
        def replace(source, destination):
            actual_replace(source, destination)
            if destination.name.startswith("operation-"):
                receipt.write_text(json.dumps(foreign))
        with patch.object(PLAY.os, "replace", side_effect=replace):
            self.run_promotion()
        self.assertEqual([], self.track_writes())
        self.assertEqual(foreign, json.loads(receipt.read_text()))

    def test_foreign_write_immediately_after_atomic_replace_is_not_adopted(self):
        f = self.fixture
        receipt = f.state.parent / "promotion-receipt.json"
        actual_replace = PLAY.os.replace
        foreign = {"state": "started", "edit": "another-promotion", "versionCode": 10}
        def replace(source, destination):
            actual_replace(source, destination)
            if destination == receipt:
                receipt.write_text(json.dumps(foreign))
        with patch.object(PLAY.os, "replace", side_effect=replace):
            self.run_promotion()
        self.assertEqual([], self.track_writes())
        self.assertEqual(foreign, json.loads(receipt.read_text()))

    def test_publish_and_promote_share_an_actual_exclusive_operation_lock(self):
        with (self.fixture.state.parent / ".publisher.lock").open("a+b") as stream:
            PLAY.fcntl.flock(stream, PLAY.fcntl.LOCK_EX | PLAY.fcntl.LOCK_NB)
            with patch.object(PLAY, "command") as git, patch.object(PLAY, "request") as request:
                with self.assertRaisesRegex(RuntimeError, "operation is running"):
                    PLAY.publish(self.fixture.args, {9})
                with self.assertRaisesRegex(RuntimeError, "operation is running"):
                    PLAY.promote(self.fixture.args)
                git.assert_not_called()
                request.assert_not_called()

    def test_foreign_receipt_cannot_erase_the_owned_ambiguous_edit_journal(self):
        f = self.fixture
        foreign = {"state": "started", "edit": "another-promotion", "versionCode": 10}
        def api(method, url, *args, **kwargs):
            result = f.promotion_api(method, url, *args, **kwargs)
            if url.endswith(":validate"):
                (f.state.parent / "promotion-receipt.json").write_text(json.dumps(foreign))
            return result
        self.run_promotion(api=api)
        journals = list(f.state.parent.glob("operation-*.json"))
        self.assertEqual(1, len(journals))
        journal = json.loads(journals[0].read_text())
        self.assertEqual("started", journal["state"])
        self.assertEqual("promotion-edit", journal["edit"])
        self.assertTrue(journal["operation"])
        # Removing the canonical receipt still must not permit a blind retry.
        (f.state.parent / "promotion-receipt.json").unlink()
        with patch.object(PLAY, "request") as request:
            with self.assertRaisesRegex(RuntimeError, "already started"):
                PLAY.promote(f.args)
            request.assert_not_called()

    def test_head_and_application_changes_during_token_must_block_promotion(self):
        f = self.fixture
        changed = False
        def git(args, **kwargs):
            if changed and args[1] == "rev-parse":
                return "b" * 40
            if changed and args[1] == "status":
                return b" M app/src/main/AndroidManifest.xml"
            return f.promotion_git(args, **kwargs)
        def token(credential):
            nonlocal changed
            changed = True
            return "fake-token"
        self.run_promotion(token=token, git=git)
        self.assertEqual([], self.track_writes(), "Promotion wrote all tracks after its reviewed HEAD/application changed during token retrieval")

    def test_head_changes_during_play_preflight_must_block_promotion(self):
        f = self.fixture
        changed = False
        def git(args, **kwargs):
            if changed and args[1] == "rev-parse":
                return "b" * 40
            if changed and args[1] == "diff":
                return "app/src/main/AndroidManifest.xml\n"
            return f.promotion_git(args, **kwargs)
        def api(method, url, *args, **kwargs):
            nonlocal changed
            result = f.promotion_api(method, url, *args, **kwargs)
            if method == "GET" and url.endswith("/bundles"):
                changed = True
            return result
        self.run_promotion(api=api, git=git)
        self.assertEqual([], self.track_writes(), "Promotion committed after HEAD/application changed during Play bundle preflight")

    def test_another_promotion_receipt_during_preflight_must_not_be_overwritten(self):
        f = self.fixture
        receipt = f.state.parent / "promotion-receipt.json"
        foreign = {"state": "started", "edit": "another-promotion", "versionCode": 10}
        def api(method, url, *args, **kwargs):
            result = f.promotion_api(method, url, *args, **kwargs)
            if method == "GET" and url.endswith("/tracks") and not any(verb == "PUT" for verb, _, _ in f.calls):
                receipt.write_text(json.dumps(foreign))
            return result
        self.run_promotion(api=api)
        self.assertEqual([], self.track_writes(), "Promotion ignored a concurrent started receipt created while its edit was being inspected")
        self.assertEqual(foreign, json.loads(receipt.read_text()))

    def test_receipt_ownership_lost_at_validate_must_block_commit(self):
        f = self.fixture
        foreign = {"state": "started", "edit": "another-promotion", "versionCode": 10}
        def api(method, url, *args, **kwargs):
            result = f.promotion_api(method, url, *args, **kwargs)
            if url.endswith(":validate"):
                (f.state.parent / "promotion-receipt.json").write_text(json.dumps(foreign))
            return result
        self.run_promotion(api=api)
        commits = [url for _, url, _ in f.calls if url.endswith(":commit")]
        self.assertEqual([], commits, "Promotion committed and replaced a receipt it no longer owned after validation")
        self.assertEqual(foreign, json.loads((f.state.parent / "promotion-receipt.json").read_text()))

    def test_upload_receipt_created_during_token_must_block_upload(self):
        f = self.fixture
        receipt = f.state.parent / "publish-receipt.json"
        foreign = {"state": "started", "edit": "another-upload", "versionCode": 10}
        def token(credential):
            receipt.write_text(json.dumps(foreign))
            return "fake-token"
        track = {"track": "internal", "releases": [{"versionCodes": ["10"], "status": "completed"}]}
        with patch.object(PLAY, "command", side_effect=f.git), patch.object(PLAY, "access_token", side_effect=token), patch.object(
            PLAY, "request", side_effect=f.api
        ), patch.object(PLAY, "inspect_play", return_value=({10}, track)), patch("builtins.print"):
            try:
                PLAY.publish(f.args, {9})
            except RuntimeError:
                pass
        uploads = [url for _, url, _ in f.calls if "uploadType=media" in url]
        self.assertEqual([], uploads, "Publish uploaded and overwrote another started upload receipt created during token retrieval")
        self.assertEqual(foreign, json.loads(receipt.read_text()))


if __name__ == "__main__":
    unittest.main()
