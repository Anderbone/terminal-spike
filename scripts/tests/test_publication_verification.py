import hashlib
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("publication", Path(__file__).resolve().parents[1] / "verify-publication.py")
PUBLICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLICATION)


class PublicationVerificationTest(unittest.TestCase):
    def tracks(self):
        return [{"track": name, "releases": [{"name": "0.0.20", "versionCodes": ["35"], "status": "completed"}]}
                for name in ("production", "internal", "beta", "alpha")]

    def test_internal_only_or_stale_production_cannot_pass(self):
        tracks = self.tracks()
        PUBLICATION.verify_tracks(tracks, "0.0.20", 35)
        for change in ("missing", "old", "draft"):
            tracks = self.tracks()
            if change == "missing":
                tracks.pop(0)
            elif change == "old":
                tracks[0]["releases"][0]["versionCodes"] = ["22"]
            else:
                tracks[0]["releases"][0]["status"] = "draft"
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                PUBLICATION.verify_tracks(tracks, "0.0.20", 35)

    def test_readme_and_both_locales_must_have_unpinned_downloads(self):
        current = f'{PUBLICATION.DOWNLOAD} {PUBLICATION.PLAY_URL} {PUBLICATION.GITHUB}/releases/latest'
        PUBLICATION.verify_links(current, {url: current for url in PUBLICATION.WEBSITES})
        for stale in (current.replace(PUBLICATION.DOWNLOAD, "old.apk"),
                      current + f" {PUBLICATION.GITHUB}/releases/download/v0.0.4/old.apk"):
            with self.assertRaises(RuntimeError):
                PUBLICATION.verify_links(stale, {url: current for url in PUBLICATION.WEBSITES})
            with self.assertRaises(RuntimeError):
                PUBLICATION.verify_links(current, {PUBLICATION.WEBSITES[1]: stale})

    def test_latest_prerelease_missing_assets_wrong_tag_or_download_fail(self):
        apk = b"verified signed apk"
        digest = hashlib.sha256(apk).hexdigest()
        names = ["terminal-spike-0.0.20.apk", "terminal-spike-latest.apk", "terminal-spike-source-commit.tar.gz"]
        assets = [{"name": name, "state": "uploaded", "digest": f"sha256:{digest}",
                   "browser_download_url": name} for name in names]
        assets.append({"name": "SHA256SUMS", "state": "uploaded", "browser_download_url": "sums"})
        release = {"tag_name": "v0.0.20", "draft": False, "prerelease": False, "assets": assets}
        sums = "".join(f"{digest}  {name}\n" for name in names).encode()
        def fetch(url):
            return sums if url == "sums" else apk
        with patch.object(PUBLICATION, "fetch", side_effect=fetch):
            PUBLICATION.verify_release(release, "head", "head", "0.0.20", digest)
            for change in ({"prerelease": True}, {"assets": assets[:1]}, {"tag_name": "v0.0.19"}):
                with self.subTest(change=change), self.assertRaises(RuntimeError):
                    PUBLICATION.verify_release(release | change, "head", "head", "0.0.20", digest)
            with self.assertRaises(RuntimeError):
                PUBLICATION.verify_release(release, "other", "head", "0.0.20", digest)
        with patch.object(PUBLICATION, "fetch", side_effect=lambda url: sums if url == "sums" else b"old apk"):
            with self.assertRaisesRegex(RuntimeError, "public Latest APK"):
                PUBLICATION.verify_release(release, "head", "head", "0.0.20", digest)


if __name__ == "__main__":
    unittest.main()
