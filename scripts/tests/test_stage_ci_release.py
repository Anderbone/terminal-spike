from pathlib import Path
import hashlib
import json
import runpy
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "stage-ci-release.py"
STAGE = runpy.run_path(str(SCRIPT))["stage"]


class StageCiReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike staging ")
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name) / "checkout"
        self.project.mkdir()
        self.git("init", "--quiet", "--initial-branch=main")
        (self.project / ".gitignore").write_text("build/\napp/build/\nlocal.properties\n")
        self.source = self.project / "app/src/main/Fixture.kt"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("class Fixture\n")
        self.git("add", ".")
        self.git("commit", "--quiet", "-m", "Fixture source")
        self.inputs = {
            "app-release-unsigned.apk": self.project / "app/build/outputs/apk/release/app-release-unsigned.apk",
            "app-release.aab": self.project / "app/build/outputs/bundle/release/app-release.aab",
        }
        for name, path in self.inputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("verified fixture " + name).encode())
        self.destination = self.project / "build/ci-release"

    def git(self, *args: str) -> str:
        # These identities/config overrides apply only to the disposable fixture repository.
        return subprocess.check_output(
            ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
             "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
             "-C", str(self.project), *args], text=True, stderr=subprocess.PIPE,
        ).strip()

    def snapshot(self) -> dict[str, bytes]:
        return {path.name: path.read_bytes() for path in self.destination.iterdir()}

    def assert_staged(self, destination: Path | None = None) -> None:
        destination = destination or self.destination
        self.assertEqual({*self.inputs, "manifest.json"}, {p.name for p in destination.iterdir()})
        expected_hashes = {}
        for name, source in self.inputs.items():
            self.assertEqual(source.read_bytes(), (destination / name).read_bytes())
            expected_hashes[name] = hashlib.sha256(source.read_bytes()).hexdigest()
        self.assertEqual(
            {"schema": 1, "revision": self.git("rev-parse", "HEAD"), "sha256": expected_hashes},
            json.loads((destination / "manifest.json").read_text()),
        )

    def test_clean_checkout_stages_exact_artifacts_hashes_and_revision(self) -> None:
        STAGE(self.project, self.destination)
        self.assert_staged()
        self.assertEqual("", self.git("status", "--porcelain"))

    def test_ignored_build_outputs_and_machine_settings_do_not_make_source_dirty(self) -> None:
        (self.project / "local.properties").write_text("sdk.dir=/fixture/android\n")
        cache = self.project / "build/cache/ignored.bin"
        cache.parent.mkdir(parents=True)
        cache.write_bytes(b"cache fixture")
        STAGE(self.project, self.destination)
        self.assert_staged()

    def test_untracked_source_is_rejected_before_any_output_is_created(self) -> None:
        private_name = "untracked-private-fixture\nname.kt"
        untracked = self.source.with_name(private_name)
        untracked.write_text("class UntrackedFixture\n")
        with self.assertRaises(RuntimeError) as error:
            STAGE(self.project, self.destination)
        self.assertFalse(self.destination.exists())
        self.assertTrue(untracked.exists(), "Staging must preserve concurrent source files")
        self.assertNotIn(private_name, str(error.exception))

    def test_unstaged_tracked_source_is_still_rejected(self) -> None:
        self.source.write_text("class ChangedFixture\n")
        with self.assertRaises((RuntimeError, subprocess.CalledProcessError)):
            STAGE(self.project, self.destination)
        self.assertFalse(self.destination.exists())

    def test_staged_tracked_source_is_still_rejected(self) -> None:
        self.source.write_text("class ChangedFixture\n")
        self.git("add", str(self.source))
        with self.assertRaises((RuntimeError, subprocess.CalledProcessError)):
            STAGE(self.project, self.destination)
        self.assertFalse(self.destination.exists())

    def test_missing_or_empty_artifacts_do_not_create_a_partial_destination(self) -> None:
        for name, path in self.inputs.items():
            original = path.read_bytes()
            for missing in (False, True):
                with self.subTest(name=name, missing=missing):
                    if missing:
                        path.unlink()
                    else:
                        path.write_bytes(b"")
                    try:
                        with self.assertRaises(RuntimeError):
                            STAGE(self.project, self.destination)
                        self.assertFalse(self.destination.exists())
                    finally:
                        path.write_bytes(original)
                        # Permit independent subTests to run on the unpatched baseline too.
                        if self.destination.exists():
                            shutil.rmtree(self.destination)

    def test_invalid_new_inputs_leave_the_previous_complete_stage_untouched(self) -> None:
        STAGE(self.project, self.destination)
        original = self.snapshot()
        self.inputs["app-release-unsigned.apk"].write_bytes(b"new fixture APK")
        self.inputs["app-release.aab"].unlink()
        with self.assertRaises(RuntimeError):
            STAGE(self.project, self.destination)
        self.assertEqual(original, self.snapshot())

    def test_source_changes_during_copy_are_rejected_without_changing_the_destination(self) -> None:
        STAGE(self.project, self.destination)
        original = self.snapshot()
        self.inputs["app-release-unsigned.apk"].write_bytes(b"new fixture APK")
        untracked = self.source.with_name("ConcurrentFixture.kt")
        original_copy = shutil.copyfile
        changed = False

        def copy(source, target):
            nonlocal changed
            result = original_copy(source, target)
            if not changed:
                changed = True
                untracked.write_text("class ConcurrentFixture\n")
            return result

        with patch.object(shutil, "copyfile", side_effect=copy):
            with self.assertRaises(RuntimeError):
                STAGE(self.project, self.destination)
        self.assertEqual(original, self.snapshot())
        self.assertTrue(untracked.exists())

    def test_a_new_clean_commit_during_copy_cannot_be_attested_as_the_old_revision(self) -> None:
        STAGE(self.project, self.destination)
        original = self.snapshot()
        original_copy = shutil.copyfile
        changed = False

        def copy(source, target):
            nonlocal changed
            result = original_copy(source, target)
            if not changed:
                changed = True
                self.git("commit", "--quiet", "--allow-empty", "-m", "Concurrent fixture revision")
            return result

        with patch.object(shutil, "copyfile", side_effect=copy):
            with self.assertRaises(RuntimeError):
                STAGE(self.project, self.destination)
        self.assertEqual(original, self.snapshot())
        self.assertEqual("", self.git("status", "--porcelain"))

    def test_copy_failure_preserves_previous_artifacts_and_cleans_owned_temporary_files(self) -> None:
        STAGE(self.project, self.destination)
        original = self.snapshot()
        parent_entries = set(self.destination.parent.iterdir())
        self.inputs["app-release-unsigned.apk"].write_bytes(b"new fixture APK")
        original_copy = shutil.copyfile
        copied = 0

        def copy(source, target):
            nonlocal copied
            copied += 1
            if copied == 2:
                raise OSError("fixture copy failure")
            return original_copy(source, target)

        with patch.object(shutil, "copyfile", side_effect=copy):
            with self.assertRaises(OSError):
                STAGE(self.project, self.destination)
        self.assertEqual(original, self.snapshot())
        self.assertEqual(parent_entries, set(self.destination.parent.iterdir()))

    def test_destination_outside_the_checkout_remains_supported(self) -> None:
        destination = Path(self.temporary.name) / "external artifacts"
        STAGE(self.project, destination)
        self.assert_staged(destination)

    def test_a_successful_repeat_replaces_both_artifacts_and_their_manifest(self) -> None:
        STAGE(self.project, self.destination)
        for path in self.inputs.values():
            path.write_bytes(path.read_bytes() + b" rebuilt fixture")
        STAGE(self.project, self.destination)
        self.assert_staged()


if __name__ == "__main__":
    unittest.main()
