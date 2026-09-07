from pathlib import Path
import json
import re
import subprocess
import sys
import tempfile
import unittest


VALIDATOR = Path(__file__).resolve().parents[1] / "verify-android-test-contract.py"
PROJECT_ROOT = Path(__file__).resolve().parents[2]
CHECKED_IN_CONTRACT = PROJECT_ROOT / "scripts/android-test-contract.json"
ANDROID_TEST_SOURCES = PROJECT_ROOT / "app/src/androidTest/java"
PACKAGE_PATTERN = re.compile(r"^package\s+([\w.]+)", re.MULTILINE)
TEST_METHOD_PATTERN = re.compile(
    r"@Test(?:\s*\([^\n]*\))?\s+"
    r"(?:@[\w.]+(?:\([^\n]*\))?\s+)*"
    r"fun\s+([A-Za-z_$][\w$]*)\s*\(",
)


class AndroidTestContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="terminal spike test contract ")
        self.root = Path(self.temporary.name)
        self.contract_path = self.root / "contract.json"
        self.output_path = self.root / "instrumentation.txt"
        self.contract = {
            "schema": 1,
            "supported_apis": [26, 35],
            "suites": {
                "full": {
                    "required_tests": [
                        "com.example.FirstTest#passes",
                        "com.example.SecondTest#platformGate",
                    ]
                }
            },
            "allowed_skip_rules": [
                {
                    "id": "old-platform-gate",
                    "suites": ["full"],
                    "max_api": 26,
                    "tests": ["com.example.SecondTest#platformGate"],
                    "reason": "The fixture feature exists only on newer platforms.",
                }
            ],
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_validator(
        self,
        cases: list[tuple[str, int]],
        *,
        api: int = 35,
        contract: dict | None = None,
    ) -> subprocess.CompletedProcess[str]:
        self.contract_path.write_text(
            json.dumps(contract if contract is not None else self.contract),
            encoding="utf-8",
        )
        lines: list[str] = []
        for identifier, code in cases:
            class_name, test_name = identifier.split("#", 1)
            lines.extend(
                (
                    f"INSTRUMENTATION_STATUS: class={class_name}",
                    f"INSTRUMENTATION_STATUS: test={test_name}",
                    f"INSTRUMENTATION_STATUS_CODE: {code}",
                )
            )
        lines.append(f"OK ({len(cases)} tests)")
        self.output_path.write_text("\n".join(lines), encoding="utf-8")
        return subprocess.run(
            [
                sys.executable,
                str(VALIDATOR),
                str(self.contract_path),
                str(self.output_path),
                "full",
                str(api),
            ],
            check=False,
            capture_output=True,
            text=True,
        )

    def valid_cases(self, second_code: int = 0) -> list[tuple[str, int]]:
        return [
            ("com.example.FirstTest#passes", 0),
            ("com.example.SecondTest#platformGate", second_code),
        ]

    def test_checked_in_full_suite_matches_every_android_test_source_method(self) -> None:
        discovered: set[str] = set()
        for source in sorted(ANDROID_TEST_SOURCES.rglob("*.kt")):
            text = source.read_text(encoding="utf-8")
            package_match = PACKAGE_PATTERN.search(text)
            self.assertIsNotNone(package_match, f"missing package declaration: {source}")
            class_name = f"{package_match.group(1)}.{source.stem}"
            for method_name in TEST_METHOD_PATTERN.findall(text):
                identifier = f"{class_name}#{method_name}"
                self.assertNotIn(identifier, discovered, f"duplicate source test: {identifier}")
                discovered.add(identifier)

        contract = json.loads(CHECKED_IN_CONTRACT.read_text(encoding="utf-8"))
        required = set(contract["suites"]["full"]["required_tests"])
        self.assertEqual(
            discovered,
            required,
            "Every Android @Test method must be explicitly reviewed in the full-suite contract.",
        )

    def test_accepts_exact_membership_and_exact_api_skip_identity(self) -> None:
        modern = self.run_validator(self.valid_cases(), api=35)
        old = self.run_validator(self.valid_cases(second_code=-4), api=26)

        self.assertEqual(0, modern.returncode, modern.stderr)
        self.assertIn("tests=2 passed=2 skipped=0 membership=exact", modern.stdout)
        self.assertEqual(0, old.returncode, old.stderr)
        self.assertIn("tests=2 passed=1 skipped=1 membership=exact", old.stdout)

    def test_rejects_missing_unexpected_and_duplicate_test_identity(self) -> None:
        fixtures = (
            ([self.valid_cases()[0]], "missing"),
            (self.valid_cases() + [("com.example.ThirdTest#added", 0)], "unexpected"),
            (self.valid_cases() + [self.valid_cases()[0]], "duplicate tests"),
        )
        for cases, expected in fixtures:
            with self.subTest(expected=expected):
                result = self.run_validator(cases)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)

    def test_rejects_expected_skip_that_runs_and_unexpected_skip(self) -> None:
        expected_skip_ran = self.run_validator(self.valid_cases(), api=26)
        unexpected_skip = self.run_validator(self.valid_cases(second_code=-4), api=35)

        self.assertNotEqual(0, expected_skip_ran.returncode)
        self.assertIn("expected skips that ran", expected_skip_ran.stderr)
        self.assertNotEqual(0, unexpected_skip.returncode)
        self.assertIn("unexpected skips", unexpected_skip.stderr)

    def test_rejects_malformed_contract_and_overlapping_active_skip_rules(self) -> None:
        malformed = dict(self.contract)
        malformed["schema"] = 2
        malformed_result = self.run_validator(self.valid_cases(), contract=malformed)

        overlapping = json.loads(json.dumps(self.contract))
        overlapping["allowed_skip_rules"].append(
            {
                "id": "duplicate-active-rule",
                "suites": ["full"],
                "max_api": 26,
                "tests": ["com.example.SecondTest#platformGate"],
                "reason": "Deliberate overlap for the validator test.",
            }
        )
        overlap_result = self.run_validator(
            self.valid_cases(second_code=-4),
            api=26,
            contract=overlapping,
        )

        self.assertNotEqual(0, malformed_result.returncode)
        self.assertIn("schema 1", malformed_result.stderr)
        self.assertNotEqual(0, overlap_result.returncode)
        self.assertIn("skip rules overlap", overlap_result.stderr)

    def test_rejects_unknown_suite_api_status_and_missing_identity(self) -> None:
        self.contract_path.write_text(json.dumps(self.contract), encoding="utf-8")
        self.output_path.write_text(
            "INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n",
            encoding="utf-8",
        )
        missing_identity = subprocess.run(
            [sys.executable, str(VALIDATOR), str(self.contract_path), str(self.output_path), "full", "35"],
            check=False,
            capture_output=True,
            text=True,
        )
        unknown_api = self.run_validator(self.valid_cases(), api=34)
        unsupported_status = self.run_validator(
            [("com.example.FirstTest#passes", -2), self.valid_cases()[1]],
        )

        self.assertIn("without class and method identity", missing_identity.stderr)
        self.assertIn("not covered", unknown_api.stderr)
        self.assertIn("unsupported final status code", unsupported_status.stderr)


if __name__ == "__main__":
    unittest.main()
