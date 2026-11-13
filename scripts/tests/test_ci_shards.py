import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("ci_shards", Path(__file__).resolve().parents[1] / "check-ci-shards.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiShardsTest(unittest.TestCase):
    def jobs(self):
        return [dict(name=f"required-runtime-shard ({api}, {index})", status="completed",
                     conclusion="success", html_url=f"job/{api}/{index}")
                for api in (26, 35) for index in (0, 1)]

    def test_other_api_failure_does_not_misattribute_this_api(self):
        jobs = self.jobs()
        jobs[0]["conclusion"] = "failure"
        self.assertFalse(MODULE.check(jobs, 26, 2, "required-runtime-shard")[0])
        self.assertTrue(MODULE.check(jobs, 35, 2, "required-runtime-shard")[0])

    def test_missing_cancelled_pending_and_duplicate_shards_never_pass(self):
        jobs = self.jobs()
        self.assertFalse(MODULE.check(jobs[1:], 26, 2, "required-runtime-shard")[0])
        for state in ("cancelled", "failure", None):
            jobs[0]["conclusion"] = state
            self.assertFalse(MODULE.check(jobs, 26, 2, "required-runtime-shard")[0])
        with self.assertRaises(ValueError):
            MODULE.check(jobs + [jobs[0]], 26, 2, "required-runtime-shard")
