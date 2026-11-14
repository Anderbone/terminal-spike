# Automatic Android CI repair

This opt-in local service watches **Anderbone/terminal-spike only**. The user authorized
it on 2026-11-11. GitHub schedules CI on the existing `epee` self-hosted runner; the
repair controller uses the local Codex CLI and its existing ChatGPT login. No model
credential is uploaded to GitHub and no other repository is watched.

## Lifecycle

1. A completed, failed `Android CI` push run must match the current `main` commit.
2. The controller creates an isolated worktree and `codex/ci-fix-<run-id>` branch,
   downloads report artifacts and failed-job logs, and starts a bounded Codex session.
3. The controller reproduces affected API 26/35 test classes on the host using retained
   XML reports and the fixed `ci-host-check.py` gate. Codex reads that evidence and edits
   relevant source/tests inside its existing workspace sandbox. The controller then
   runs the shared local gate and those classes on the host. A failing host check feeds
   new evidence back to Codex within the same attempt budget. A patch is committed and
   pushed only after host verification succeeds, then one PR is opened against `main`.
   Host plans are stored outside the model worktree; class names and API levels are
   validated, no model-supplied shell command is executed, and harness/build changes
   stop host execution for review.
4. Each push to the same-repository `codex/ci-fix-*` branch runs the normal required
   CI checks, which appear on the matching PR. There are no `pull_request` or
   `pull_request_target` triggers; fork/ordinary feature PRs never reach checkout.
   The full API 26–37 compatibility sweep remains manual.
5. After a completed failure on that exact PR head, the controller updates the same
   branch and PR. It does not open a second PR, force-push, or rerun the entire workflow.
6. Green CI stops the chain and updates the PR body for human review. Closing/merging
   that PR rearms the watcher for future failures on `main`.

The limit is **5 Codex attempts per chain, 60 minutes per attempt, and 24 hours per
chain**. CI queue time does not consume a Codex attempt. Reaching a limit, no valid
patch, protected-file edits, a dirty worktree, an unexpected branch update, or an
agent error stops the chain and preserves the evidence for review. A stopped open PR
requires human attention; timer restarts do not silently reset its budget.

The controller never merges or releases, changes application versions, includes
concurrent developer changes, or operates on physical phones. CI fixes do not
authorize weakened assertions, removed scenarios, increased timeouts, or skipped
tests merely to obtain green checks. Test/harness changes remain visible for review.

## Install and observe

Requirements: Linux user systemd, Python 3, Git, authenticated `gh`, authenticated
`codex`, and the existing Android/JDK toolchain. Use a dedicated clean checkout for
the installed controller, leaving developer worktrees untouched:

```bash
scripts/install-ci-autofix.sh
systemctl --user status terminal-spike-ci-autofix.timer
journalctl --user -u terminal-spike-ci-autofix.service -f
python3 scripts/ci-autofix.py --status
python3 scripts/ci-status.py
```

The timer checks every minute when idle, with a process lock as an additional overlap
guard. It runs while the laptop is awake and the user systemd manager is running;
sleep/offline periods delay checks until reconnection. Codex sessions use
`workspace-write` with network access and the Gradle cache as an additional writable
directory. They inherit the user's model configuration. Agent logs are private local
files; summaries and normal CI logs are visible on GitHub.

Progress is visible in the repair PR's **Checks** and body, the repository's
**Actions** page, and the local journal/state. The private local state/worktrees/logs
live under `~/.local/state/terminal-spike-ci-autofix/`. The installed controller is
a pinned copy under `~/.local/share/terminal-spike-ci-autofix/`; editing a repair
branch cannot replace the running controller. Re-run the installer after reviewing
controller updates, including the pinned host-check companion. Existing signing material stays outside all repair worktrees.
The installer resolves local mise auto-update wrappers to the installed Codex/GitHub
CLI binaries; background checks do not upgrade tools or rewrite mise configuration.

```bash
# Read-only remote inspection; no agent, commit, push, PR, or CI run is started.
python3 scripts/ci-autofix.py --dry-run

# Explicitly try an existing completed ancestor-main failure against current main.
python3 scripts/ci-autofix.py --seed-run <run-id>

# Stop future checks. To stop an in-flight repair as well, stop both units.
systemctl --user stop terminal-spike-ci-autofix.timer
systemctl --user stop terminal-spike-ci-autofix.service
```

Keep the state file for deduplication and attempt accounting. Do not delete receipts
to hide failures. For a diagnosed pre-PR controller/environment blocker with a clean
worktree, an operator may explicitly run `--retry-stopped`. It records the previous
failure and retains the attempt budget; the timer never invokes this option. To retry
other stopped chains after inspecting their logs/worktree, make a
reviewed manual correction on the PR or close it and fix/push `main`; automatic
repair is deliberately paused while the stopped PR remains open.

The local Codex process must retain a valid login and available model allowance.
Authentication/quota errors stop the attempt and appear in the local journal/state;
if a PR already exists, its body also records the blocker. This service cannot
guarantee that every CI failure has an automatic fix.

## Failure visibility and evidence

`ci-status.py` is a read-only terminal view; it works inside Herdr without creating
an interactive agent session. The controller records agent PID, log/report paths,
evidence directory and a specific blocker even if no patch is produced. Repeated
idle checks update `checked_at`, not the last actual status transition `updated_at`.
The background `codex exec` process is not registered as a Herdr workspace.

Artifact collection records missing files in `collection.json` and continues with
other reports and failed-job logs. Evidence-only failures do not consume a Codex
attempt. Host KVM and emulator-tool preflight blocks before launching the agent;
the agent must still verify capabilities inside its own execution context. A
blocked environment never authorizes disabling assertions or widening permissions.

## Host verification and phone status

The controller can access KVM even when the Codex workspace sandbox cannot. Keep
that sandbox enabled: the host gate owns emulator runs and the final shared gate.
Both artifact downloads and build tools use dedicated disk temporary directories.
Host validation has bounded process groups; a timeout terminates its emulator
children, and the service has a three-hour overall execution limit.

Personal Brain observes the controller's categorical phase and activity. During an
explicit foreground repair, `operator-status.json` may supply a short activity and
`repairing`, `validating`, or `waiting_ci` phase with an ISO `checked_at` timestamp.
This is an expiring observation, not authority to launch work or bypass checks.
The observer ignores future or older-than-120-second leases. Remove the lease when
foreground ownership ends; retained failure reports and attempt budgets remain.

Linux verification uses the shared SDK selector's checksum-verified Emulator 37.2.12
cache, including runtime, lifecycle, and release-update callers. The shared SDK is
not overwritten while another job is running. This avoids the observed 37.2.13
host EGL cleanup crash without changing tests or accepting incomplete instrumentation.
See `docs/DEPENDENCIES.md` for the archive provenance and version-pin limits.
