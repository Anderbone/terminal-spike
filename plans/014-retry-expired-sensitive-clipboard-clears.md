# Plan 014: Retry expired sensitive-clipboard clears after foreground recovery

> **Executor instructions**: Follow this plan step by step. Preserve exact-token
> ownership: Terminal Spike must never clear a newer clip or a clip written by
> another app. Run every verification command before updating `plans/README.md`.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/AppContainer.kt app/src/main/java/com/yanjiyu/terminalspike/terminal/view app/src/main/java/com/yanjiyu/terminalspike/ui app/src/test/java/com/yanjiyu/terminalspike/terminal/view app/src/androidTest/java/com/yanjiyu/terminalspike/terminal/view`
> This worktree already contains intentional uncommitted work. Compare the
> symbols below with live source; do not reset or discard anything.

## Status

- **Priority**: P0
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: security / correctness / tests
- **Planned at**: commit `93d070a`, 2026-09-03
- **Execution**: DONE locally on 2026-09-03

The scheduler now retains only an expired opaque token after temporary
foreground/focus unavailability, holds Activity-backed targets weakly, and
distinguishes success, ownership mismatch, and retryable unavailability. Resume
and window-focus hooks retry through the current writer. Focused API 35 and API
37 lifecycle runs passed, including Activity recreation; the full repository
gate is green with 956 app JVM tests.

## Why this matters

The privacy setting promises to clear the same app-owned terminal clip after a
selected delay. Today an expiry that occurs while the Activity is backgrounded
or lacks focus is attempted once, fails, and is forgotten. The clip can then
remain indefinitely even though the process is alive and later returns to the
foreground.

## Current state

- `TerminalClipboardClearScheduler.kt:51-59` calls `clearIfCurrent()` once and
  drops the job without inspecting its Boolean result.
- `TerminalClipboardWriter.kt:77-94` correctly refuses clipboard access unless
  its Activity is resumed and focused, and verifies the opaque clip token before
  clearing.
- `TerminalClipboardWriterTest.kt:95+` proves a background clear returns false
  and that a later manual call can clear the same token; production has no retry.
- `AppContainer.kt:108-118` owns one process-wide scheduler and updates its
  preference from the settings stream.
- Writers are constructed from `TerminalSpikeScreen.kt`, `SettingsScreen.kt`,
  `TerminalViewBridge.kt`, and `FastTerminalView.kt`; a retry design must not
  retain destroyed Activities.

## Scope

**In scope**:

- `app/src/main/java/com/yanjiyu/terminalspike/AppContainer.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/terminal/view/TerminalClipboardClearScheduler.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/terminal/view/TerminalClipboardWriter.kt`
- the smallest lifecycle/focus integration in `FastTerminalView.kt`,
  `TerminalViewBridge.kt`, `TerminalSpikeScreen.kt`, or `SettingsScreen.kt`
- scheduler unit tests and clipboard ActivityScenario instrumentation tests

**Out of scope**:

- durable alarms or WorkManager after process death
- reading or comparing clipboard plaintext
- changing Android's clipboard privacy restrictions
- changing clipboard content or OSC 52 approval policy

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused JVM | `./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.terminal.view.TerminalClipboardClearSchedulerTest'` | exit 0 |
| Focused emulator | `scripts/run-android-emulator-tests.sh --api 35 --suite full --test-filter com.yanjiyu.terminalspike.terminal.view.TerminalClipboardWriterTest` | exact named methods pass |
| Full gate | `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest` | `BUILD SUCCESSFUL` |

## Git workflow

Preserve unrelated dirty-tree work. Use a focused branch/commit only if the
operator asks; do not push or open a PR automatically.

## Steps

### Step 1: Make temporary unavailability an explicit scheduler state

Retain an expired pending token when `clearIfCurrent()` returns false. Distinguish
that temporary result from success and from a token that is no longer current.
If needed, replace Boolean with a small result enum so ownership mismatch can be
discarded while foreground/focus unavailability remains retryable. Never store
clipboard plaintext.

The process singleton may retain token metadata, but must hold Activity-backed
targets weakly or through attach/detach registration. A destroyed Activity must
be collectible.

**Verify**:
`./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.terminal.view.TerminalClipboardClearSchedulerTest'`
→ exit 0 with new tests for temporary failure, later success, and target replacement.

### Step 2: Retry from the current resumed and focused owner

Wire a retry signal to the current terminal/application surface when it becomes
resumed and gains window focus. Activity recreation must replace the stale
target. Do not poll indefinitely and do not use a fixed sleep loop. A newer app
write, setting the delay to Off, scheduler close, or a proven token mismatch
must cancel the expired request.

Preserve these invariants:

- only the exact token embedded in the current primary clip may be cleared;
- background clipboard access remains forbidden;
- a retry cannot clear a newer app clip even when plaintext matches;
- process death cancels all unverifiable work.

**Verify**: the scheduler unit test above passes and a heap/reference-oriented
test proves an unregistered Activity-backed target is not retained.

### Step 3: Add real lifecycle coverage

Extend `TerminalClipboardWriterTest` or add a focused ActivityScenario test that
writes through the production scheduler, moves the Activity below RESUMED before
expiry, advances/waits past expiry, resumes and focuses it, and observes the
same-token clip clear. Add recreation and newer-clip cases. Do not replace this
with direct manual calls to `clearIfCurrent()`.

Run on an emulator through the repository-owned exact-suite runner. Do not run
on either connected phone for this plan.

**Verify**:
`scripts/run-android-emulator-tests.sh --api 35 --suite full --test-filter com.yanjiyu.terminalspike.terminal.view.TerminalClipboardWriterTest`
→ every named method passes with zero unexpected skips.

### Step 4: Run repository gates

**Verify**:
`./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest`
→ `BUILD SUCCESSFUL` with no test or lint failures.

## Done criteria

- [x] An expiry during background/focus loss remains pending in-process.
- [x] Returning through a current resumed, focused Activity clears the exact clip.
- [x] Newer/foreign clips, preference disablement, and process close cancel safely.
- [x] Activity recreation does not leak or strand the retry.
- [x] Focused JVM and emulator lifecycle tests pass.
- [x] Full unit, lint, debug/release build, and Android-test assembly gates pass.

## Test plan

- Unit: expiry success, temporary unavailability, target replacement, newer
  token, preference Off, close, and no Activity-target retention.
- Instrumentation: background at expiry then foreground clear, Activity
  recreation, transient focus loss, and same-text newer/foreign clip safety.
- Preserve all existing clipboard and OSC 52 tests unchanged.

## STOP conditions

- Android requires clipboard access that cannot be made from a current resumed,
  focused Activity without weakening platform privacy checks.
- The design needs plaintext retention, a durable worker, or broad clipboard clearing.
- Lifecycle registration would introduce per-line/per-cell Compose state.

## Maintenance notes

Review target lifetime and exact-token behavior more closely than timing syntax.
Process termination remains an honest documented limitation.
