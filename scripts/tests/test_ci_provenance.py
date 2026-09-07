from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class CiProvenanceTest(unittest.TestCase):
    def test_wrapper_and_dependency_metadata_are_strictly_checksum_pinned(self) -> None:
        wrapper = (PROJECT_ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(
            encoding="utf-8"
        )
        self.assertRegex(wrapper, r"(?m)^distributionSha256Sum=[0-9a-f]{64}$")

        root = ET.parse(PROJECT_ROOT / "gradle/verification-metadata.xml").getroot()
        namespace = {"v": "https://schema.gradle.org/dependency-verification"}
        self.assertEqual("true", root.findtext("v:configuration/v:verify-metadata", namespaces=namespace))
        self.assertGreater(len(root.findall(".//v:sha256", namespace)), 0)
        self.assertEqual([], root.findall(".//v:trusted-artifact", namespace))
        self.assertEqual([], root.findall(".//v:ignored-key", namespace))

    def test_workflow_uses_only_immutable_external_actions_and_strict_gradle(self) -> None:
        workflow = (PROJECT_ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
        uses = re.findall(r"(?m)^\s*uses:\s*([^\s#]+)", workflow)
        self.assertTrue(uses)
        self.assertTrue(
            all(
                reference.startswith("./")
                or re.fullmatch(r"[^/@\s]+/[^@\s]+@[0-9a-f]{40}", reference)
                for reference in uses
            ),
            uses,
        )
        self.assertIn("--dependency-verification=strict", workflow)
        self.assertIn("api: [28, 29, 32, 33, 37]", workflow)
        self.assertIn("api: [26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37]", workflow)
        backup_job = workflow.split("  backup-clean-install:", 1)[1].split(
            "\n  boundary-runtime:", 1
        )[0]
        self.assertIn("--suite backup-clean-install", backup_job)
        self.assertIn("--api 35", backup_job)
        self.assertIn("if: always()", backup_job)


if __name__ == "__main__":
    unittest.main()
