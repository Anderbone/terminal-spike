# Plan 012: Prove honest recovery across real app-process death and background pressure

> **Executor instructions**: Follow this plan exactly. This is an external host-driven E2E because
> instrumentation inside the target process cannot prove its own death and restart. Start on a
> disposable named AVD. Run optional physical acceptance only on the exact authorized old
> `SM-S911B`, after resolving and model-checking its current serial before every operation. Never
> install or test on `SM_F976B`. Update this plan and `plans/README.md` with exact evidence.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/src/main app/src/debug app/src/androidTest integration-tests/openssh scripts docs/architecture.md docs/PUBLISHING.md`
> The worktree was intentionally dirty when planned; inspect live unstaged changes too.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED
- **Depends on**: Plan 011
- **Category**: tests / correctness / lifecycle
- **Planned at**: commit `93d070a`, 2026-09-02
- **Execution status**: IN PROGRESS — implementation, fake failure matrix, and real disposable
  API 35 acceptance are green; the first hosted CI execution requires an authorized commit/push.

## Why this matters

Activity recreation and service contracts are well tested, but no current acceptance kills the
main Linux process and inspects the restarted product. The architecture intentionally does not
promise socket survival; it promises honest loss, persisted recent metadata, safe credential
behavior, and no stale foreground-service state. A public terminal app should prove that boundary
with a real SSH connection rather than infer it from JVM state-machine tests.

## Current state

- `docs/architecture.md:81-85` says the application process owns transports, process death does not
  preserve sockets, and persisted recent metadata may offer an honest reconnect.
- `docs/architecture.md:229-239` requires honest process-death and background/service verification.
- `app/src/main/java/com/yanjiyu/terminalspike/connection/SshSessionRepository.kt:323-366` owns all
  live session runtimes in application memory.
- `app/src/main/java/com/yanjiyu/terminalspike/SessionForegroundService.kt:117-136` is
  `START_NOT_STICKY`, releases foreground state, and marks sessions failed on ordinary unexpected
  service destruction; those callbacks cannot run after a kernel process kill.
- `app/src/androidTest/java/com/yanjiyu/terminalspike/SessionForegroundServiceContractTest.kt`
  covers notification denial, manifest, privacy, and platform contracts inside one process.
- `app/src/androidTest/java/com/yanjiyu/terminalspike/SshTrustAuthenticationFlowTest.kt:334,459`
  uses Activity recreation, which is not process death.
- `integration-tests/openssh/release-app-install-update-smoke.sh:310-375` is the current black-box
  UI/real-SSH exemplar, including session-only password behavior.
- No checked-in device test invokes `am kill`, kills the main app PID externally, or proves restart
  UI/notification/recent-session state.

Constraints: retain native Kotlin/Compose shell and the custom terminal renderer; never add per-cell
Compose state, analytics, crash upload, cloud services, or secret-bearing output; preserve terminal
and tmux gates.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Host tests | `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` | all pass |
| Fixture | `integration-tests/openssh/smoke.sh` | disposable SSH fixture responds |
| App artifacts | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | build successful |
| Full gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | build successful |

## Scope

**In scope**:

- a host-driven lifecycle runner under `scripts/` and Python host tests under `scripts/tests/`
- debug-only test seams under `app/src/debug` if black-box UI cannot establish or inspect exact
  state deterministically
- focused Android tests only for reusable state/semantics contracts
- reuse of `integration-tests/openssh` without changing fixture credentials/protocols
- CI workflow for a disposable-emulator lifecycle job after local proof
- lifecycle/security/publishing documentation

**Out of scope**:

- claiming live SSH or Mosh sockets survive process death
- production crash reporting, analytics, telemetry, or network services
- automatically granting unrestricted battery access
- changing OS battery policy on any personal phone
- tests or installs on the foldable `SM_F976B`
- actual-Codex/tmux or Mosh roaming acceptance

## Git workflow

Preserve unrelated dirty work. Do not commit, push, or open a PR without operator instruction.

## Steps

### Step 1: Define observable post-death invariants

For real SSH established with a saved non-secret host and session-only password, require:

1. terminal traffic works before death;
2. the external runner records the main PID, backgrounds the app, then kills that exact PID;
3. the old PID disappears and a new PID exists only after explicit relaunch;
4. no old session is represented as Connected/Reconnecting after relaunch;
5. no stale foreground-service notification remains;
6. persisted recent/non-secret host metadata remains available;
7. reconnect requires the unsaved password again and succeeds through real SSH;
8. no terminal content or credential appears in durable output.

Add a saved-password case only if it can prove Android Keystore ciphertext survives and the
authoritative saved-host action reconnects without exposing the value.

**Verify**: host tests model these assertions; a fake run missing any assertion fails.

### Step 2: Implement a disposable-AVD external runner

Own a temporary named AVD, exact emulator serial, OpenSSH fixture, APK install, and cleanup using
the guards in `run-android-emulator-tests.sh` and UI helpers in the release smoke. Use a host-side
exact-PID kill (`run-as <package> kill -9 <pid>` for the debuggable APK, or an equally specific
verified mechanism) only after proving the PID belongs to the package. Do not use `am force-stop`
as process-death proof: it has different package semantics. Do not let instrumentation in the
killed process judge the result.

Capture sanitized stage/result lines and sanitized logcat. Always remove the temporary AVD and
stop the fixture on success, failure, timeout, or signal.

**Verify**: fake-tool tests cover wrong/non-emulator identity, PID mismatch, kill failure, failure
to observe death, force-stop substitution, stale notification, reconnect failure, timeout, and
cleanup.

### Step 3: Add deterministic background-pressure cases on the AVD

Separately exercise backgrounding with the foreground service active, Doze/idle entry where the
stable API supports it, and app standby/restricted-background policy. Record the original device
state and restore it in trap cleanup. Assertions must distinguish app-controlled behavior—honest
state, no crash/ANR, service/notification contract, recoverable reconnect—from Android/OEM policy,
which may stop a transport.

Do not use sleeps as acceptance. Poll process, activity, service, notification, network marker, and
UI state with bounded deadlines.

**Verify**: every pressure case has positive and fake-tool negative tests; device-idle, standby,
and app-op state matches its recorded baseline after every exit.

### Step 4: Add CI and optional old-phone acceptance

Run deterministic process death on a disposable stable API 35 AVD in CI. Keep OEM battery mode
observation manual. Add an opt-in old-phone mode that reuses `scripts/install-wireless.sh` guards
and accepts only exact verified `SM-S911B`; it must never mutate persistent battery policy.

**Verify**: local API 35 process-death/reconnect passes; optional old-phone acceptance passes on the
re-enumerated authorized device; workflow YAML parses.

### Step 5: Reconcile product claims

Update architecture, publishing, status, and Plan 005 with exact observed cases. Leave OEM
Optimised/Restricted modes and long soak open unless actually observed.

**Verify**: `git diff --check`, all host tests, and the full Gradle gate pass.

## Test plan

- Real SSH marker before exact-PID process death.
- PID disappearance and distinct PID after explicit restart.
- No falsely live session and no stale foreground notification after restart.
- Recent/non-secret host retention and session-only password re-prompt.
- Successful real SSH marker after reconnect.
- AVD background/idle/standby cases with exact state restoration.
- Host failures for every identity, kill, poll, assertion, and cleanup boundary.

## Done criteria

- [x] A real external OS process kill—not Activity recreation or force-stop—is observed.
- [x] Pre-death and post-reconnect real SSH markers pass.
- [x] Restart UI never presents the dead transport as live.
- [x] Recent host persists, session-only password does not, and notification/service state is clean.
- [x] Background-pressure cases restore all changed AVD policy state.
- [x] Runner refuses physical devices by default and accepts only exact verified `SM-S911B` in its
  explicit physical mode.
- [ ] CI, host tests, full Gradle gate, and documentation are green/current (local host/Gradle/docs
  are green; the first hosted job remains pending).

## STOP conditions

- Exact process ownership cannot be proved before the kill.
- The available command behaves as package force-stop rather than process death.
- A battery/idle command is not reversible or its original state cannot be read.
- Physical execution would touch `SM_F976B` or any device other than exact verified `SM-S911B`.
- Passing requires persisting terminal buffers or secrets solely for the test.

## Maintenance notes

Keep the runner external to the target process. Revisit assertions whenever session persistence,
foreground-service policy, target SDK, or recent-session semantics change. OEM manual battery
evidence supplements this deterministic AVD gate; it does not replace it.

## 2026-09-02 local execution evidence

- `scripts/run-app-lifecycle-e2e.py` owns a wiped `terminal-spike-api35-lifecycle` AVD, exact
  emulator serial, loopback-only OpenSSH fixture, bounded UI/PID/state polls, private database
  snapshot, sanitized output, and unconditional cleanup. It contains no `am force-stop` path.
- Real SSH passed before pressure. PID `2682`, its foreground service, and notification remained
  live through ordinary backgrounding, deep idle, restricted standby, and Data Saver; each changed
  AVD state was restored to its recorded baseline before proceeding.
- The runner proved `/proc/2682/cmdline` belonged exactly to the app, issued `run-as ... kill -9
  2682`, observed the PID/service/notification disappear, and proved there was no automatic
  restart. A private Room/WAL snapshot reported one host, one recent session, and zero credentials
  backed by stored secrets.
- Explicit launch created PID `5293`. The current UI showed `No terminal session is open.` and no
  Connected/Reconnecting state; the saved host remained, reconnect required a password, and a
  second real SSH marker passed. Android exit-info and filtered logcat contained no crash or ANR.
- Eleven focused lifecycle host tests cover success, every pressure negative, wrong/fold/non-emulator
  identity, exact old-phone opt-in without battery mutation, PID mismatch, kill failure, failure to
  observe death, stale notification, metadata loss, secret persistence, reconnect failure, timeout,
  signal, CI routing, and cleanup. `.github/workflows/android-ci.yml` now has a required API 35
  lifecycle job retaining sanitized evidence only.
- No physical device was targeted by the real run. Hosted execution remains pending because this
  worktree has not been authorized for commit/push.
- The complete script suite passed 86 tests, and the full Gradle gate passed 475 tasks (`test`,
  `lint`, debug/release builds, Android-test APKs, and benchmark assembly).
