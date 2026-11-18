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
    def test_artifact_failure_does_not_hide_remaining_reports_or_job_logs(self):
        with tempfile.TemporaryDirectory() as temporary:
            loop = MODULE.Loop(Path(temporary), Path(temporary) / "state")
            responses = [{"jobs": [{"id": 42, "conclusion": "failure"}]},
                         {"artifacts": [{"name": "broken", "expired": False},
                                        {"name": "available", "expired": False}]}]
            with patch.object(loop, "api", side_effect=responses), patch.object(MODULE, "command", side_effect=[
                    RuntimeError("artifact download 404"), "downloaded", "failed job details"]):
                evidence = loop.evidence(self.run_record(), Path(temporary))
            self.assertEqual("failed job details", (evidence / "job-42.log").read_text())
            receipt = MODULE.json.loads((evidence / "collection.json").read_text())
            self.assertEqual(2, receipt["collected"])
            self.assertEqual("broken", receipt["missing"][0]["artifact"])

    def test_no_patch_retains_agent_blocker_in_state_without_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "app").mkdir()
            (root / "app/build.gradle.kts").write_text('versionName = "0.0.24"\n')
            loop = MODULE.Loop(root, root / "state")
            chain = {"branch": "codex/ci-fix-10", "sha": "a" * 40, "attempts": 0,
                     "started": MODULE.time.time(), "pr": None}
            with patch.object(loop, "workspace", return_value=root), \
                    patch.object(loop, "evidence", return_value=root / "evidence"), \
                    patch.object(loop, "agent", return_value="Blocked: emulator has no KVM access"), \
                    patch.object(MODULE, "command", side_effect=["a" * 40, ""]) as command:
                loop.repair(self.run_record(), chain)
            self.assertTrue(chain["stopped"])
            self.assertIn("no KVM access", chain["result"])
            self.assertIn("report_path", chain)
            self.assertEqual(2, command.call_count)

    def test_missing_kvm_does_not_launch_agent_or_consume_attempt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "app").mkdir()
            (root / "app/build.gradle.kts").write_text('versionName = "0.0.24"\n')
            loop = MODULE.Loop(root, root / "state")
            chain = {"branch": "codex/ci-fix-10", "sha": "a" * 40, "attempts": 0,
                     "started": MODULE.time.time(), "pr": None}
            with patch.object(loop, "workspace", return_value=root), \
                    patch.object(loop, "evidence", return_value=root / "evidence"), \
                    patch.object(MODULE.os, "access", return_value=False), \
                    patch.object(MODULE.subprocess, "Popen") as launch:
                loop.repair(self.run_record(), chain)
            launch.assert_not_called()
            self.assertEqual(0, chain["attempts"])
            self.assertEqual("blocked_environment", chain["phase"])

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
            for name in ["install-ci-autofix.sh", "ci-autofix.py", "ci-status.py", "ci-host-check.py"]:
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

    def test_successful_retry_of_same_main_rearms_a_pre_pr_blocker(self):
        with tempfile.TemporaryDirectory() as temp:
            loop = MODULE.Loop(Path(temp), Path(temp) / 'state')
            branch = MODULE.PREFIX + '10'
            loop.state['active'] = branch
            loop.state['chains'][branch] = {'stopped': True, 'base_sha': 'a' * 40,
                                           'pr': None, 'result': 'Old environment blocker'}
            run = dict(self.run_record(), conclusion='success', run_attempt=2)
            with patch.object(loop, 'runs', return_value=[run]), patch.object(loop, 'api', return_value={'object': {'sha': 'a' * 40}}), patch.object(loop, 'repair') as repair:
                loop.tick()
                repair.assert_not_called()
            self.assertIsNone(loop.state['active'])
            self.assertTrue(loop.state['chains'][branch]['stopped'])

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

            def invoke(args, cwd=None, timeout=120, **kwargs):
                if args[:3] == ["gh", "pr", "create"]:
                    creations.append(args)
                    return f"https://github.com/{MODULE.REPO}/pull/3"
                return native_command(args, cwd, timeout, **kwargs)

            def agent(workspace, evidence, attempt):
                (workspace / "app/Fix.kt").write_text(f"// focused fix {attempt}\n")
                return "Focused test passed."

            host_checks = []
            def verify_host(workspace, evidence, baseline=False):
                host_checks.append(chain['attempts'])
                if len(host_checks) == 1:
                    with self.assertRaises(RuntimeError):
                        native_command(['git', 'rev-parse', f'refs/heads/{branch}'], remote)
                    raise MODULE.HostVerificationFailed('focused test failed; retry from new evidence')

            run = dict(self.run_record(), html_url="https://example.invalid/run/10")
            with patch.object(MODULE, "command", side_effect=invoke), patch.object(MODULE, "gh", return_value=[]), \
                    patch.object(loop, "evidence", return_value=root / "evidence"), \
                    patch.object(loop, "agent", side_effect=agent), patch.object(loop, "host_check", side_effect=verify_host), patch.object(loop, "update_pr"):
                loop.repair(run, chain)
                first = chain["sha"]
                loop.repair(dict(run, id=11), chain)
            self.assertEqual(1, len(creations))
            self.assertEqual(3, chain["pr"])
            self.assertEqual(3, chain["attempts"])
            self.assertEqual([1, 2, 3], host_checks)
            self.assertNotEqual(first, chain["sha"])
            self.assertNotIn("stopped", chain)
            self.assertEqual(initial, native_command(["git", "rev-parse", "HEAD"], checkout))
            self.assertEqual("Another developer is working here", (checkout / "unrelated.txt").read_text())
            self.assertEqual("?? unrelated.txt", native_command(["git", "status", "--porcelain"], checkout))
            files = native_command(["git", "diff", "--name-only", initial, chain["sha"]], checkout)
            self.assertEqual("app/Fix.kt", files)
            self.assertEqual(chain["sha"], native_command(["git", "rev-parse", f"refs/heads/{branch}"], remote))


class CiAutofixPathBoundaryTest(unittest.TestCase):
    """Run both actual repair collectors with local Git, never a real agent or publisher."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="CI raw path boundary ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.native_command = MODULE.command
        self.git("init", "-b", "main")
        self.git("config", "user.name", "CI path fixture")
        self.git("config", "user.email", "ci-path@example.invalid")
        self.git("config", "core.hooksPath", "/dev/null")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.quotePath", "true")
        self.write(".gitignore", "build/\nstate/\n")
        self.write("app/build.gradle.kts", 'versionCode = 39\nversionName = "0.0.24"\n')
        self.write("app/existing.kt", "// baseline\n")
        self.write("AGENTS.md", "fixture protected instructions\n")
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture baseline")
        self.head = self.git("rev-parse", "HEAD")
        self.loop = MODULE.Loop(self.root, self.root / "state")
        branch = MODULE.PREFIX + "101"
        self.chain = {"branch": branch, "sha": self.head, "base_sha": self.head, "origin_run": 101,
                      "attempts": 0, "started": MODULE.time.time(), "pr": None}
        self.loop.state["active"] = branch
        self.loop.state["chains"][branch] = self.chain
        self.run = dict(id=101, run_attempt=1, status="completed", conclusion="failure",
                        html_url="https://example.invalid/fixture", head_branch="main",
                        head_sha=self.head, event="push")
        self.commands = []
        self.path_batches = []
        self.host_calls = []

    def git(self, *args):
        return self.native_command(["git", *args], self.root)

    def write(self, relative, content="// fixture change\n"):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def exercise(self, edit, *, retry=False):
        self.loop.state["handled"].clear()
        self.chain.update(stopped=False, attempts=0)
        native_validate = MODULE.validate_changes
        def validate(paths, before, after):
            self.path_batches.append(list(paths))
            native_validate(paths, before, after)
            if not retry or len(self.path_batches) > 1:
                raise RuntimeError("fixture: validation completed; stop before host/publication")
        def commands(args, cwd=None, timeout=120, **kwargs):
            self.commands.append(args)
            if args[:2] in (["git", "add"], ["git", "commit"], ["git", "push"]) or args[0] == "gh":
                raise AssertionError("fixture forbids controller Git writes and external calls")
            return self.native_command(args, cwd, timeout, **kwargs)
        def host(*args, **kwargs):
            self.host_calls.append(args)
            raise MODULE.HostVerificationFailed("fixture: first focused check failed")
        with patch.object(self.loop, "workspace", return_value=self.root), \
             patch.object(self.loop, "evidence", return_value=self.root / "build/evidence"), \
             patch.object(self.loop, "agent", side_effect=edit) as agent, \
             patch.object(self.loop, "host_check", side_effect=host), \
             patch.object(MODULE, "validate_changes", side_effect=validate), \
             patch.object(MODULE, "command", side_effect=commands), \
             patch.object(MODULE, "gh") as gh, patch("builtins.print"):
            self.loop.repair(self.run, self.chain)
        self.assertEqual(2 if retry else 1, agent.call_count)
        gh.assert_not_called()
        self.assertEqual(self.head, self.git("rev-parse", "HEAD"))
        self.assertTrue(self.chain["stopped"])
        return self.chain["result"]

    def assert_no_controller_writes(self):
        self.assertFalse(any(args[:2] in (["git", "add"], ["git", "commit"], ["git", "push"])
                             or args[0] == "gh" for args in self.commands))

    def test_untracked_protected_names_with_controls_unicode_or_quotes_are_rejected(self):
        names = (".github/workflows/line\nbreak.yml", "store-assets/中文.txt",
                 "scripts/ci-autofix\tfixture.py", "AGENTS.md\nextra", "LICENSE\"extra",
                 ".github/workflows/\x1b[31mfixture.yml", ".github/\u202efixture.txt")
        for name in names:
            with self.subTest(name=name):
                self.commands.clear(); self.path_batches.clear(); self.host_calls.clear()
                for prior in names:
                    (self.root / prior).unlink(missing_ok=True)
                def edit(*args):
                    self.write(name)
                    return "fixture report"
                result = self.exercise(edit)
                self.assertIn("protected path", result)
                self.assertEqual([[name]], self.path_batches)
                self.assertEqual([], self.host_calls)
                self.assert_no_controller_writes()
                (self.root / name).unlink()
                self.chain.update(stopped=False, attempts=0)
                self.loop.state["handled"].clear()

    def test_binary_and_signing_suffixes_are_checked_after_raw_name_decoding(self):
        names = ("app/line\nname.apk", "app/秘密.keystore", "app/quote\"name.aab", "app/tab\tname.jks")
        for name in names:
            with self.subTest(name=name):
                self.commands.clear(); self.path_batches.clear(); self.host_calls.clear()
                for prior in names:
                    (self.root / prior).unlink(missing_ok=True)
                result = self.exercise(lambda *args: (self.write(name), "fixture report")[1])
                self.assertIn("protected path", result)
                self.assertEqual([[name]], self.path_batches)
                self.assert_no_controller_writes()
                (self.root / name).unlink()
                self.chain.update(stopped=False, attempts=0)
                self.loop.state["handled"].clear()

    def test_permitted_names_are_preserved_without_stripping_splitting_or_git_quotes(self):
        names = [" leading.kt", "trailing.kt ", "app/模块.kt", "app/quote\"name.kt",
                 "app/tab\tname.kt", "app/back\\slash.kt", "app/multi\nline.kt"]
        def edit(*args):
            for name in names:
                self.write(name)
            return "fixture report"
        result = self.exercise(edit)
        self.assertIn("fixture: validation completed", result)
        self.assertEqual(1, len(self.path_batches))
        self.assertCountEqual(names, self.path_batches[0])
        self.assert_no_controller_writes()

    def test_modified_and_deleted_tracked_paths_are_still_collected(self):
        self.write("app/deleted.kt", "// tracked\n")
        self.git("add", "app/deleted.kt")
        self.git("commit", "-qm", "fixture tracked deletion")
        self.head = self.git("rev-parse", "HEAD")
        self.chain["sha"] = self.head
        def edit(*args):
            self.write("app/existing.kt", "// modified\n")
            (self.root / "app/deleted.kt").unlink()
            return "fixture report"
        result = self.exercise(edit)
        self.assertIn("fixture: validation completed", result)
        self.assertEqual(1, len(self.path_batches))
        self.assertCountEqual(["app/existing.kt", "app/deleted.kt"], self.path_batches[0])
        self.assert_no_controller_writes()

    def test_an_unstaged_rename_preserves_both_delete_and_new_path(self):
        renamed = "app/renamed\nfile.kt"
        def edit(*args):
            (self.root / "app/existing.kt").rename(self.root / renamed)
            return "fixture report"
        result = self.exercise(edit)
        self.assertIn("fixture: validation completed", result)
        self.assertCountEqual(["app/existing.kt", renamed], self.path_batches[0])
        self.assert_no_controller_writes()

    def test_staged_protected_changes_cannot_hide_behind_an_allowed_unstaged_patch(self):
        def edit(*args):
            self.write("AGENTS.md", "changed protected fixture\n")
            self.git("add", "AGENTS.md")
            self.write("app/allowed.kt")
            return "fixture report"
        result = self.exercise(edit)
        self.assertIn("staged", result)
        self.assertEqual([], self.path_batches)
        self.assertEqual([], self.host_calls)
        self.assertEqual("AGENTS.md", self.git("diff", "--cached", "--name-only"))
        self.assertEqual("changed protected fixture\n", (self.root / "AGENTS.md").read_text())
        self.assert_no_controller_writes()

    def test_staging_only_permitted_changes_also_stops_for_manual_review(self):
        def edit(*args):
            self.write("app/allowed.kt")
            self.git("add", "app/allowed.kt")
            return "fixture report"
        result = self.exercise(edit)
        self.assertIn("staged", result)
        self.assertEqual([], self.path_batches)
        self.assertEqual("app/allowed.kt", self.git("diff", "--cached", "--name-only"))
        self.assert_no_controller_writes()

    def test_second_attempt_rechecks_raw_protected_paths_before_another_host_gate(self):
        name = ".github/workflows/late\nfixture.yml"
        def edit(workspace, evidence, attempt):
            self.write("app/allowed.kt", f"// attempt {attempt}\n")
            if attempt == 2:
                self.write(name)
            return "fixture report"
        result = self.exercise(edit, retry=True)
        self.assertIn("protected path", result)
        self.assertEqual(1, len(self.host_calls))
        self.assertEqual(2, len(self.path_batches))
        self.assertIn(name, self.path_batches[1])
        self.assert_no_controller_writes()

    def test_second_attempt_rechecks_staging_without_resetting_the_index(self):
        def edit(workspace, evidence, attempt):
            self.write("app/allowed.kt", f"// attempt {attempt}\n")
            if attempt == 2:
                self.write("AGENTS.md", "staged on retry\n")
                self.git("add", "AGENTS.md")
            return "fixture report"
        result = self.exercise(edit, retry=True)
        self.assertIn("staged", result)
        self.assertEqual(1, len(self.host_calls))
        self.assertEqual(1, len(self.path_batches))
        self.assertEqual("AGENTS.md", self.git("diff", "--cached", "--name-only"))
        self.assert_no_controller_writes()

    def test_rejected_filename_cannot_inject_terminal_controls_into_the_status(self):
        path = ".github/workflows/\x1b[31mfixture\nname.yml"
        with self.assertRaises(RuntimeError) as raised:
            MODULE.validate_changes([path], [], [])
        message = str(raised.exception)
        self.assertIn("protected path", message)
        self.assertNotIn("\x1b", message)
        self.assertNotIn("\n", message)


if __name__ == "__main__":
    unittest.main()
