import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("ci_autofix", Path(__file__).resolve().parents[1] / "ci-autofix.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiAutofixTest(unittest.TestCase):
    def test_terminal_control_sequences_are_removed_without_losing_log_text(self):
        raw = '\x1b[31mFAILED\x1b[0m\n\x1b]0;malicious title\x07中文\tline\x00\x9b'
        self.assertEqual('FAILED\n中文\tline', MODULE.clean_log(raw))

    def test_operator_retry_preserves_failure_receipt_and_attempt_count(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp))
            branch = MODULE.PREFIX + "10"
            chain = {"branch": branch, "sha": "a" * 40, "pr": None, "origin_run": 10,
                     "attempts": 1, "stopped": True, "result": "Log download failed"}
            loop.state["active"] = branch
            loop.state["chains"][branch] = chain
            run = self.run_record()
            with patch.object(loop, "workspace"), patch.object(loop, "api", return_value=run), \
                    patch.object(loop, "repair") as repair:
                loop.retry_stopped()
                repair.assert_called_once_with(run, chain)
            self.assertEqual(1, chain["attempts"])
            self.assertEqual("Log download failed", loop.state["operator_retries"][0]["previous_result"])
            self.assertEqual("retry-authorized", loop.state["handled"][MODULE.run_key(run)])
            self.assertNotIn("stopped", chain)

    def test_installer_generates_valid_systemd_units_without_starting_real_services(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            checkout = root / "checkout with spaces"
            scripts = checkout / "scripts"
            scripts.mkdir(parents=True)
            source = Path(__file__).resolve().parents[1]
            for name in ["install-ci-autofix.sh", "ci-autofix.py"]:
                shutil.copy2(source / name, scripts / name)
            binaries = root / "bin"
            binaries.mkdir()
            for name in ["codex", "gh", "systemctl"]:
                executable = binaries / name
                executable.write_text('#!/bin/sh\nexit 0\n')
                executable.chmod(0o755)
            environment = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                               XDG_CONFIG_HOME=str(root / "config"), XDG_DATA_HOME=str(root / "data"),
                               XDG_STATE_HOME=str(root / "state"))
            subprocess.run(["bash", str(scripts / "install-ci-autofix.sh")], env=environment,
                           check=True, capture_output=True)
            units = root / "config/systemd/user"
            service = units / "terminal-spike-ci-autofix.service"
            timer = units / "terminal-spike-ci-autofix.timer"
            self.assertIn(f"WorkingDirectory={checkout}\n", service.read_text())
            self.assertIn('--state-dir "' + str(root / "state/terminal-spike-ci-autofix") + '"', service.read_text())
            self.assertIn("OnUnitInactiveSec=60s", timer.read_text())
            if shutil.which("systemd-analyze"):
                subprocess.run(["systemd-analyze", "--user", "verify", str(service), str(timer)],
                               check=True, capture_output=True)

    def test_checkout_cannot_redirect_repairs_to_another_repository(self):
        MODULE.validate_origin(f"https://github.com/{MODULE.REPO}.git")
        MODULE.validate_origin(f"git@github.com:{MODULE.REPO}.git")
        for url in ["https://github.com/Anderbone/cable-flow.git", "/tmp/other.git"]:
            with self.assertRaises(RuntimeError):
                MODULE.validate_origin(url)

    def run_record(self, **kwargs):
        return dict(id=10, run_attempt=1, head_branch="main", head_sha="a" * 40,
                    event="push", status="completed", conclusion="failure", **kwargs)

    def test_pending_newest_run_does_not_repeat_old_failure(self):
        old = self.run_record()
        pending = dict(old, id=11, status="queued", conclusion=None)
        newest = MODULE.latest_run([old, pending], "main", "a" * 40, "push")
        self.assertEqual(11, newest["id"])
        self.assertFalse(MODULE.eligible(newest))

    def test_other_branch_sha_and_event_are_not_repaired(self):
        run = self.run_record()
        for branch, sha, event in [("other", "a" * 40, "push"), ("main", "b" * 40, "push"),
                                   ("main", "a" * 40, "pull_request")]:
            self.assertIsNone(MODULE.latest_run([run], branch, sha, event))

    def test_cancelled_and_incomplete_runs_are_not_repaired(self):
        run = self.run_record()
        self.assertTrue(MODULE.eligible(run))
        self.assertFalse(MODULE.eligible(dict(run, conclusion="cancelled")))
        self.assertFalse(MODULE.eligible(dict(run, status="in_progress")))
        self.assertNotEqual(MODULE.run_key(run), MODULE.run_key(dict(run, run_attempt=2)))

    def test_fork_and_nonrepair_prs_are_rejected(self):
        branch = MODULE.PREFIX + "10"
        pr = {"state": "open", "base": {"ref": "main"},
              "head": {"ref": branch, "repo": {"full_name": MODULE.REPO}}}
        self.assertTrue(MODULE.trusted_pr(pr, branch))
        for repo in ["someone/terminal-spike", None]:
            bad = dict(pr, head={"ref": branch, "repo": {"full_name": repo}})
            self.assertFalse(MODULE.trusted_pr(bad, branch))
        self.assertFalse(MODULE.trusted_pr(dict(pr, state="closed"), branch))
        self.assertFalse(MODULE.trusted_pr(pr, "feature/unrelated"))

    def test_protected_files_and_version_bumps_stop_publication(self):
        for path in ["AGENTS.md", ".github/workflows/android-ci.yml", "scripts/ci-autofix.py",
                     "scripts/install-ci-autofix.sh", "store-assets/release.txt", "x.apk", "../secret"]:
            with self.subTest(path=path), self.assertRaises(RuntimeError):
                MODULE.validate_changes([path], ["0.0.24"], ["0.0.24"])
        with self.assertRaises(RuntimeError):
            MODULE.validate_changes(["app/build.gradle.kts"], ["0.0.24"], ["0.0.25"])
        with self.assertRaises(RuntimeError):
            MODULE.validate_changes([], [], [])
        MODULE.validate_changes(["app/src/test/RegressionTest.kt", "scripts/run-test.sh"], [], [])

    def test_attempt_limit_and_repeated_run_never_launch_codex(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp))
            run = self.run_record()
            chain = {"attempts": MODULE.MAX_ATTEMPTS, "started": MODULE.time.time(), "pr": None}
            with patch.object(loop, "agent") as agent:
                loop.repair(run, chain)
                agent.assert_not_called()
            self.assertTrue(chain["stopped"])
            loop.state["handled"][MODULE.run_key(run)] = "started"
            with patch.object(loop, "workspace") as workspace:
                loop.repair(run, {"attempts": 0})
                workspace.assert_not_called()

    def test_green_pr_stops_loop_without_merge_or_another_agent(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp))
            branch = MODULE.PREFIX + "10"
            chain = {"branch": branch, "sha": "a" * 40, "pr": 3, "attempts": 1, "started": MODULE.time.time()}
            loop.state["active"] = branch
            loop.state["chains"][branch] = chain
            pr = {"state": "open", "base": {"ref": "main"},
                  "head": {"ref": branch, "sha": chain["sha"], "repo": {"full_name": MODULE.REPO}}}
            green = dict(self.run_record(), head_branch=branch, event="push", conclusion="success")
            with patch.object(loop, "runs", return_value=[green]), patch.object(loop, "api", return_value=pr), \
                    patch.object(loop, "update_pr") as update, patch.object(loop, "agent") as agent:
                loop.tick()
                agent.assert_not_called()
                update.assert_called_once()
            self.assertTrue(chain["stopped"])
            self.assertIn("green", chain["result"])

    def test_failed_pr_continues_same_chain_and_pending_head_waits(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp))
            branch = MODULE.PREFIX + "10"
            chain = {"branch": branch, "sha": "a" * 40, "pr": 3, "attempts": 1, "started": MODULE.time.time()}
            loop.state["active"] = branch
            loop.state["chains"][branch] = chain
            pr = {"state": "open", "base": {"ref": "main"},
                  "head": {"ref": branch, "sha": chain["sha"], "repo": {"full_name": MODULE.REPO}}}
            failure = dict(self.run_record(), head_branch=branch, event="push")
            with patch.object(loop, "runs", return_value=[failure]), patch.object(loop, "api", return_value=pr), \
                    patch.object(loop, "repair") as repair:
                loop.tick()
                repair.assert_called_once_with(failure, chain)
            pending = dict(failure, id=11, status="in_progress", conclusion=None)
            with patch.object(loop, "runs", return_value=[failure, pending]), patch.object(loop, "api", return_value=pr), \
                    patch.object(loop, "repair") as repair:
                loop.tick()
                repair.assert_not_called()

    def test_human_branch_update_stops_automatic_edits(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp))
            branch = MODULE.PREFIX + "10"
            chain = {"branch": branch, "sha": "a" * 40, "pr": 3, "attempts": 1}
            loop.state["active"] = branch
            loop.state["chains"][branch] = chain
            pr = {"state": "open", "base": {"ref": "main"},
                  "head": {"ref": branch, "sha": "b" * 40, "repo": {"full_name": MODULE.REPO}}}
            with patch.object(loop, "runs", return_value=[]), patch.object(loop, "api", return_value=pr), \
                    patch.object(loop, "update_pr"), patch.object(loop, "repair") as repair:
                loop.tick()
                repair.assert_not_called()
            self.assertTrue(chain["stopped"])

    def test_dry_run_does_not_publish_green_status_or_launch_agent(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp))
            branch = MODULE.PREFIX + "10"
            loop.state["active"] = branch
            loop.state["chains"][branch] = {"pr": 3, "sha": "a" * 40}
            before = MODULE.json.dumps(loop.state)
            with patch.object(loop, "runs", return_value=[]), patch.object(loop, "api") as api, \
                    patch.object(loop, "update_pr") as update:
                loop.tick(dry_run=True)
                api.assert_not_called()
                update.assert_not_called()
            self.assertEqual(before, MODULE.json.dumps(loop.state))

    def test_closed_green_pr_rearms_for_next_main_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp))
            branch = MODULE.PREFIX + "10"
            loop.state["active"] = branch
            loop.state["chains"][branch] = {"stopped": True, "result": "green", "pr": 3}
            with patch.object(loop, "runs", return_value=[]), \
                    patch.object(loop, "api", return_value={"state": "closed"}):
                loop.tick()
            self.assertIsNone(loop.state["active"])

    def test_real_git_repairs_update_one_pr_and_leave_developer_worktree_untouched(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            checkout, remote = root / "developer", root / "remote.git"
            checkout.mkdir()
            MODULE.command(["git", "init", "-b", "main"], checkout)
            MODULE.command(["git", "config", "user.name", "CI Test"], checkout)
            MODULE.command(["git", "config", "user.email", "ci-test@example.invalid"], checkout)
            (checkout / "app").mkdir()
            (checkout / "app/build.gradle.kts").write_text('versionCode = 39\nversionName = "0.0.24"\n')
            (checkout / ".gitignore").write_text('build/\nlocal.properties\n')
            MODULE.command(["git", "add", "-A"], checkout)
            MODULE.command(["git", "commit", "-m", "Initial"], checkout)
            initial = MODULE.command(["git", "rev-parse", "HEAD"], checkout)
            MODULE.command(["git", "clone", "--bare", str(checkout), str(remote)])
            MODULE.command(["git", "remote", "add", "origin", str(remote)], checkout)
            (checkout / "unrelated.txt").write_text("Another developer is working here")
            loop = MODULE.Loop(checkout, root / "state")
            branch = MODULE.PREFIX + "10"
            chain = {"branch": branch, "sha": initial, "origin_run": 10,
                     "attempts": 0, "started": MODULE.time.time(), "pr": None}
            loop.state["chains"][branch] = chain
            native_command = MODULE.command
            creations = []

            def invoke(args, cwd=None, timeout=120):
                if args[:3] == ["gh", "pr", "create"]:
                    creations.append(args)
                    return f"https://github.com/{MODULE.REPO}/pull/3"
                return native_command(args, cwd, timeout)

            def agent(workspace, evidence, attempt):
                (workspace / "app/Fix.kt").write_text(f"// focused fix {attempt}\n")
                return "Focused test passed."

            run = dict(self.run_record(), html_url="https://example.invalid/run/10")
            with patch.object(MODULE, "command", side_effect=invoke), patch.object(MODULE, "gh", return_value=[]), \
                    patch.object(loop, "evidence", return_value=root / "evidence"), \
                    patch.object(loop, "agent", side_effect=agent), patch.object(loop, "update_pr"):
                loop.repair(run, chain)
                first = chain["sha"]
                loop.repair(dict(run, id=11), chain)
            self.assertEqual(1, len(creations))
            self.assertEqual(3, chain["pr"])
            self.assertEqual(2, chain["attempts"])
            self.assertNotEqual(first, chain["sha"])
            self.assertNotIn("stopped", chain)
            self.assertEqual(initial, native_command(["git", "rev-parse", "HEAD"], checkout))
            self.assertEqual("Another developer is working here", (checkout / "unrelated.txt").read_text())
            self.assertEqual("?? unrelated.txt", native_command(["git", "status", "--porcelain"], checkout))
            files = native_command(["git", "diff", "--name-only", initial, chain["sha"]], checkout)
            self.assertEqual("app/Fix.kt", files)
            self.assertEqual(chain["sha"], native_command(["git", "rev-parse", f"refs/heads/{branch}"], remote))


if __name__ == "__main__":
    unittest.main()
