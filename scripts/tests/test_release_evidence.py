from __future__ import annotations

from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]
PROJECT_ROOT = SCRIPTS.parent
GENERATOR = SCRIPTS / "create-release-evidence-manifest.py"
SOURCE_MANIFEST = SCRIPTS / "source-manifest.py"
DOC_VERIFIER = SCRIPTS / "verify-release-documentation.py"
sys.path.insert(0, str(SCRIPTS))
from release_evidence import documentation_summary  # noqa: E402


class ReleaseEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal-spike-release-evidence-")
        self.root = Path(self.temporary.name)
        self.evidence = self.root / "build/release-evidence"
        self.evidence.mkdir(parents=True)
        (self.root / "scripts").mkdir()
        (self.root / "docs").mkdir()
        (self.root / ".gitignore").write_text("build/\n", encoding="utf-8")
        (self.root / "scripts/android-test-contract.json").write_text(
            json.dumps({"schema": 1, "supported_apis": [35], "suites": {"full": {}}}),
            encoding="utf-8",
        )
        for relative in (
            "README.md",
            "IMPLEMENTATION_STATUS.md",
            "docs/PUBLISHING.md",
            "docs/NEXT_STEPS.md",
        ):
            (self.root / relative).write_text("# Release document\n", encoding="utf-8")
        self.git("init", "-q")
        self.git("config", "user.email", "release-evidence@example.invalid")
        self.git("config", "user.name", "Release Evidence Test")
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

        self.epoch = int(time.time()) - 2
        (self.evidence / "final-gate-start.epoch").write_text(
            f"{self.epoch}\n", encoding="utf-8"
        )
        self.report = self.evidence / "app-jvm.xml"
        self.write_report()
        self.artifact = self.evidence / "app-release.apk"
        self.artifact.write_bytes(b"signed release artifact")
        self.artifact_metadata = self.evidence / "app-release.metadata.json"
        self.write_artifact_metadata()
        self.write_source_manifest()
        self.inputs = self.valid_inputs()
        self.write_inputs()
        self.output = self.evidence / "candidate-manifest.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.check_output(
            ["git", *arguments], cwd=self.root, text=True
        ).strip()

    def write_report(self, *, tests: int = 2, failures: int = 0) -> None:
        self.report.write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="0" skipped="1">'
            '<testcase classname="Example" name="passes" />'
            '<testcase classname="Example" name="skips"><skipped /></testcase>'
            "</testsuite>\n",
            encoding="utf-8",
        )

    def run_source_manifest(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SOURCE_MANIFEST), "--repo-root", str(self.root)],
            check=False,
            capture_output=True,
            text=True,
        )

    def write_source_manifest(self) -> None:
        result = self.run_source_manifest()
        self.assertEqual(0, result.returncode, result.stderr)
        (self.evidence / "source-manifest.final.sha256").write_text(
            result.stdout.strip() + "\n", encoding="ascii"
        )

    def valid_inputs(self) -> dict:
        return {
            "schema": 1,
            "source": {
                "commit": self.git("rev-parse", "HEAD"),
                "dirty": False,
                "manifest": "build/release-evidence/source-manifest.final.sha256",
                "test_contract": "scripts/android-test-contract.json",
            },
            "fresh_after": "build/release-evidence/final-gate-start.epoch",
            "test_suites": [
                {
                    "id": "app-jvm",
                    "label": "app JVM",
                    "kind": "jvm",
                    "reports": ["build/release-evidence/app-jvm.xml"],
                    "expected": {
                        "tests": 2,
                        "failures": 0,
                        "errors": 0,
                        "skipped": 1,
                    },
                }
            ],
            "artifacts": [
                {
                    "id": "app-release",
                    "metadata": "build/release-evidence/app-release.metadata.json",
                    "path": "build/release-evidence/app-release.apk",
                    "package": "com.example.terminal",
                    "version_code": 5,
                    "version_name": "1.0.0",
                    "signer_sha256": "1" * 64,
                    "sha256": sha256(b"signed release artifact").hexdigest(),
                }
            ],
            "devices": [
                {
                    "id": "authorized-old-phone",
                    "model": "SM-S911B",
                    "api": 36,
                    "transport": "usb",
                    "scenarios": [{"id": "full-app-suite", "result": "passed"}],
                }
            ],
            "hosted_runs": [],
            "pending": [
                {
                    "id": "first-hosted-ci-run",
                    "category": "hosted_ci",
                    "reason": "Requires an authorized commit and push.",
                }
            ],
        }

    def write_artifact_metadata(self, *, version_code: int = 5) -> None:
        self.artifact_metadata.write_text(
            json.dumps(
                {
                    "schema": 1,
                    "artifact_sha256": sha256(b"signed release artifact").hexdigest(),
                    "package": "com.example.terminal",
                    "version_code": version_code,
                    "version_name": "1.0.0",
                    "signer_sha256": "1" * 64,
                    "package_verifier": "aapt2",
                    "signature_verifier": "apksigner",
                }
            ),
            encoding="utf-8",
        )

    def write_inputs(self) -> None:
        (self.evidence / "candidate-inputs.json").write_text(
            json.dumps(self.inputs), encoding="utf-8"
        )

    def run_generator(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(GENERATOR),
                "--repo-root",
                str(self.root),
                "--evidence-dir",
                "build/release-evidence",
                "--output",
                "build/release-evidence/candidate-manifest.json",
            ],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_generates_deterministic_validated_manifest(self) -> None:
        first = self.run_generator()
        self.assertEqual(0, first.returncode, first.stderr)
        first_bytes = self.output.read_bytes()
        second = self.run_generator()
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(first_bytes, self.output.read_bytes())

        manifest = json.loads(first_bytes)
        self.assertEqual(self.git("rev-parse", "HEAD"), manifest["source"]["commit"])
        self.assertEqual(2, manifest["tests"][0]["counts"]["tests"])
        self.assertEqual(1, manifest["tests"][0]["counts"]["skipped"])
        self.assertEqual(
            sha256((self.root / "scripts/android-test-contract.json").read_bytes()).hexdigest(),
            manifest["test_contract_sha256"],
        )
        self.assertNotIn(str(self.root), first_bytes.decode("utf-8"))

    def test_source_manifest_excludes_plans_and_build_but_not_source(self) -> None:
        initial = self.run_source_manifest().stdout.strip()
        (self.root / "plans").mkdir()
        (self.root / "plans/note.md").write_text("ignored plan", encoding="utf-8")
        (self.evidence / "ignored.txt").write_text("ignored output", encoding="utf-8")
        ignored_changes = self.run_source_manifest().stdout.strip()
        self.assertEqual(initial, ignored_changes)

        (self.root / "README.md").write_text("changed source\n", encoding="utf-8")
        included_change = self.run_source_manifest().stdout.strip()
        self.assertNotEqual(initial, included_change)

    def test_rejects_stale_contradictory_and_failing_test_evidence(self) -> None:
        os.utime(self.report, (self.epoch - 10, self.epoch - 10))
        stale = self.run_generator()
        self.assertNotEqual(0, stale.returncode)
        self.assertIn("stale", stale.stderr)

        self.write_report()
        self.inputs["test_suites"][0]["expected"]["tests"] = 3
        self.write_inputs()
        contradictory = self.run_generator()
        self.assertNotEqual(0, contradictory.returncode)
        self.assertIn("counts differ", contradictory.stderr)

        self.inputs = self.valid_inputs()
        self.write_inputs()
        self.write_report(failures=1)
        failing = self.run_generator()
        self.assertNotEqual(0, failing.returncode)
        self.assertIn("failures or errors", failing.stderr)

    def test_rejects_tampered_duplicate_and_private_evidence(self) -> None:
        self.artifact.write_bytes(b"tampered")
        tampered = self.run_generator()
        self.assertNotEqual(0, tampered.returncode)
        self.assertIn("SHA-256 differs", tampered.stderr)

        self.artifact.write_bytes(b"signed release artifact")
        self.write_artifact_metadata(version_code=6)
        contradictory_metadata = self.run_generator()
        self.assertNotEqual(0, contradictory_metadata.returncode)
        self.assertIn("metadata contradicts version_code", contradictory_metadata.stderr)

        self.write_artifact_metadata()
        self.inputs["test_suites"].append(dict(self.inputs["test_suites"][0]))
        self.write_inputs()
        duplicate = self.run_generator()
        self.assertNotEqual(0, duplicate.returncode)
        self.assertTrue(
            "duplicates" in duplicate.stderr or "report paths" in duplicate.stderr,
            duplicate.stderr,
        )

        self.inputs = self.valid_inputs()
        self.inputs["devices"][0]["model"] = "adb-secret-serial"
        self.write_inputs()
        private = self.run_generator()
        self.assertNotEqual(0, private.returncode)
        self.assertIn("private machine, serial, or address data", private.stderr)

        self.inputs = self.valid_inputs()
        self.inputs["test_suites"][0]["reports"] = ["../outside.xml"]
        self.write_inputs()
        escaping = self.run_generator()
        self.assertNotEqual(0, escaping.returncode)
        self.assertIn("repository-relative", escaping.stderr)

        self.inputs = self.valid_inputs()
        self.inputs["pending"] = []
        self.write_inputs()
        missing_hosted = self.run_generator()
        self.assertNotEqual(0, missing_hosted.returncode)
        self.assertIn("explicit hosted_ci pending entry", missing_hosted.stderr)

    def test_documentation_verifier_accepts_only_manifest_derived_block(self) -> None:
        generated = self.run_generator()
        self.assertEqual(0, generated.returncode, generated.stderr)
        manifest = json.loads(self.output.read_text(encoding="utf-8"))
        block = (
            "<!-- release-evidence-current:start -->\n"
            + documentation_summary(manifest)
            + "\n<!-- release-evidence-current:end -->"
        )
        for relative in (
            "docs/DEVELOPING.md",
            "IMPLEMENTATION_STATUS.md",
            "docs/PUBLISHING.md",
            "docs/NEXT_STEPS.md",
        ):
            (self.root / relative).write_text(f"# Release document\n\n{block}\n", encoding="utf-8")
        accepted = subprocess.run(
            [
                sys.executable,
                str(DOC_VERIFIER),
                "--repo-root",
                str(self.root),
                "--manifest",
                "build/release-evidence/candidate-manifest.json",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertIn("documents=4", accepted.stdout)

        (self.root / "docs/DEVELOPING.md").write_text(
            f"# Release document\n\n{block.replace('`2` tests', '`3` tests')}\n",
            encoding="utf-8",
        )
        rejected = subprocess.run(
            [
                sys.executable,
                str(DOC_VERIFIER),
                "--repo-root",
                str(self.root),
                "--manifest",
                "build/release-evidence/candidate-manifest.json",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn("differs from the candidate manifest", rejected.stderr)

        (self.root / "docs/DEVELOPING.md").write_text(
            f"# Release document\n\n{block}\n\nCurrent JVM suite has 999 tests.\n",
            encoding="utf-8",
        )
        stale_claim = subprocess.run(
            [
                sys.executable,
                str(DOC_VERIFIER),
                "--repo-root",
                str(self.root),
                "--manifest",
                "build/release-evidence/candidate-manifest.json",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(0, stale_claim.returncode)
        self.assertIn("outside the manifest-derived block", stale_claim.stderr)


if __name__ == "__main__":
    unittest.main()
