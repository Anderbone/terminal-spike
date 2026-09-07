# Plan 018: Enforce a bounded per-frame budget for tmux history reconciliation

> **Executor instructions**: Before changing any tmux history, touch routing,
> viewport, or fling code, read the entire canonical living record at
> `/home/jiyu/Documents/Jiyu-obsidian/plannow/2026-08-31-tmux-native-smooth-scroll-investigation.md`.
> Its current Definition of Solved is the acceptance contract. Update its
> required evidence sections during this work.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/terminal app/src/main/java/com/yanjiyu/terminalspike/connection/TmuxSessionSelector.kt app/src/test/java/com/yanjiyu/terminalspike/terminal benchmark docs/PERFORMANCE.md`

## Status

- **State**: DONE (2026-09-03; final fold/user acceptance remains owned by Plan 005 and the living record)
- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: none
- **Category**: performance / architecture / tests
- **Planned at**: commit `93d070a`, 2026-09-03

## Why this matters

Ordinary controller queues are limited to 2,000 lines per display frame, but
tmux snapshot reconciliation bypasses that budget and runs while holding the
controller queue lock in the Choreographer callback. A legal snapshot can contain
roughly 20,480 rows (4,096 history rows plus a saved 16,384-row primary screen),
and up to eight snapshots can be pending. This is bounded—not an unbounded
200,000-row scan—but it can still turn one smooth-scroll frame into substantial
comparison/allocation/mutation work.

## Current state

- `TerminalController.kt:805-853` drains every pending snapshot and applies all
  of them under `queueLock` in one frame callback.
- `reconcileGrowingTmuxHistory()` at lines 1071-1113 compares the snapshot
  overlap sequentially and replaces a suffix there.
- `reconcileResetTmuxHistory()` and `largestTmuxSuffixPrefixOverlap()` at lines
  1121-1174 allocate prefix storage and scan at most the incoming snapshot-sized
  tail there.
- `MAX_PENDING_TMUX_SNAPSHOTS = 8` while `MAX_LINES_PER_FRAME = 2_000` at lines
  1293-1297.
- `TmuxSessionSelector.kt:434-441,714-715` bounds ordinary history capture to
  4,096 rows and a saved primary screen to at most 16,384 rows.
- `TerminalFixtureMacrobenchmark.tmuxStatusFrames()` only swipes a prebuilt
  fixture; it does not inject overlap, page prepend, or coordinate-reset work
  during measured frames.

## Scope

**In scope**:

- `TerminalController.kt`, a focused pure reconciliation helper/state if needed,
  and `TerminalBuffer.kt` only for safe bounded commit APIs
- controller workflow tests and deterministic benchmark harnesses
- non-production benchmark fixture/activity and macrobenchmark journeys
- `docs/PERFORMANCE.md` and the mandatory tmux living record

**Out of scope**:

- changing history ownership or dropping verified transcript rows
- remote-wheel fallback for app-selected tmux Auto mode
- per-cell/per-line Compose state
- copying source from another terminal application
- claiming success from only synthetic or emulator evidence

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused JVM | `./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.terminal.*'` | exit 0 |
| Benchmark assembly | `./gradlew :benchmark:assemble` | exit 0 |
| Full gate | `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | `BUILD SUCCESSFUL` |

Physical benchmark and real-terminal commands must come from the current living
record and guarded repository entrypoints after exact serial/model resolution;
do not paste a stale serial into this plan.

## Git workflow

Preserve unrelated dirty-tree work. Do not commit, push, or open a PR unless asked.

## Steps

### Step 1: Add a deterministic red work-budget test

Instrument reconciliation through a test-only observer or extracted pure helper
so tests count payload comparisons and committed rows per frame without relying
on wall-clock timing. Cover the maximum legal saved-primary snapshot, multiple
queued snapshots, a 4,096-row growing overlap, coordinate reset, and older-page
prepend. Demonstrate that current code can exceed the 2,000-line frame budget.

Also add a benchmark scenario that stages a live snapshot/reset inside the
measured journey rather than merely swiping prebuilt history.

**Verify**: the new regression is red on the old implementation for the explicit
budget reason, while existing history-order tests remain green.

### Step 2: Separate preparation from the display-frame commit

Extract immutable reconciliation preparation from mutation. Prefer a serial
off-frame worker operating on versioned immutable row references; alternatively
use an incremental state machine with a hard per-frame comparison budget. The
frame callback may commit only a small, already-decided delta under `queueLock`.

Required concurrency rules:

- every preparation is tied to pane ID, history epoch/range, and controller generation;
- stale results are discarded, never partially committed;
- newer metadata-only snapshots may coalesce, but authoritative history and
  older-page boundaries cannot be silently dropped;
- reset/clear/session close cancels pending work;
- rendering sees either the old coherent history or the new coherent history,
  never a partially reconciled transcript;
- stable row IDs, selection anchors, fractional viewport position, and active
  fling state survive the same cases as today.

Do not move a full `TerminalBuffer.snapshot()` onto another thread if acquiring
its monitor can still block the Choreographer callback for the same work duration.

**Verify**: the deterministic work-budget suite is green and Thread/StrictMode
tests show no blocking worker join on the main thread.

### Step 3: Preserve the complete tmux regression lattice

Retain and extend `TerminalControllerWorkflowTest`, `TmuxHistoryCaptureTest`,
viewport, selection, and parser/controller workload tests. Required cases include
growing overlap, no overlap, coordinate epoch reset, page boundary mismatch,
capacity trim, pane switch, close during preparation, rapid newer snapshots,
reader anchor, sub-row drag, fling/catch, and new output while reading/live bottom.

**Verify**:
`./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.terminal.*'`
→ all focused tests pass.

### Step 4: Measure release-like frame behavior

Run the new live-reconciliation macrobenchmark and existing six terminal fixture
journeys on the authorized old `SM_S911B` only. Before every install/test command,
resolve `adb devices -l`, verify the exact model, and use its exact serial. Never
test on `SM_F976B`. Record frame percentiles, snapshot shape/count, build/source
hash, route/reason, wheel count, device, refresh rate, and thermal state.

**Verify**: measured live reconciliation no longer creates a single frame that
performs more than the declared deterministic budget, and frame evidence improves
or stays within the explicit budget recorded in `docs/PERFORMANCE.md`.

### Step 5: Run the real first-gesture tmux/Codex gate

Use the exact guarded procedure in the living record. Actual Codex must be open
inside app-selected tmux before the first gesture; do not wait for local-scroll
availability. Require zero outbound wheel reports, sub-row drag, post-release
motion, ordered history, reader anchoring, and the existing Codex markers. Run
the Mosh recreation path if controller lifecycle code changed.

Update the living record's `updated_at`, Current State / Evidence, Experiment
Log, Open Questions, and Next Step with exact red and green evidence.

**Verify**: all real old-phone acceptance assertions pass; no test runs on the fold.

### Step 6: Run full gates

**Verify**:
`./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble`
→ `BUILD SUCCESSFUL`.

## Done criteria

- [x] Snapshot comparison/mutation work has an explicit enforced per-frame bound.
- [x] No stale or partial reconciliation can become visible.
- [x] History, row IDs, viewport, selection, drag, fling, and live-bottom behavior survive.
- [x] Live reconciliation and existing macrobenchmarks have current old-phone evidence.
- [x] Actual-Codex first-gesture and zero-wheel gates pass on `SM_S911B`.
- [x] The tmux living record and performance docs contain exact evidence.
- [x] Full tests, lint, builds, and benchmark assembly pass.

## Completion evidence

The controller now prepares tmux replacement buffers incrementally and publishes only a complete
coherent replacement. Payload comparisons and rebuilt rows share a hard 2,000-row budget per
Choreographer callback. Deterministic tests cover the maximum 20,480-row snapshot, growing and
reset epochs, a full 4,096-row older page, capacity trimming, stable IDs, cancellation on newer
snapshots/clear/detach, and randomized reconciliation against simple reference implementations.

On the model-verified old `SM-S911B`, all seven release-like terminal journeys passed. The new
20,480-row live-reconciliation/reset journey measured CPU-frame P50/P90/P95/P99 of
2.2/3.6/4.8/5.8 ms across three iterations at 60 Hz with thermal status 0. The guarded real-Codex
matrix passed 3/3 with actual Codex in app-selected tmux selecting `LOCAL_SCROLLBACK` /
`AUTO_TMUX_LOCAL_READY`, zero remote wheels, 5,000-row paging, exact reader anchoring, sub-row drag,
fling, catch, and live-bottom transition. The real-Mosh matrix passed 5 password/lifecycle methods
plus one private-key method. The final host gate passed 89 script tests and 475 Gradle tasks;
fresh JVM totals were app `971/0/0/0`, Mosh API `11/0/0/0`, and extension `8/0/0/0`.
The final process-isolated old-phone app suite also matched the exact API-36 contract: 329 methods,
307 passes, 22 expected skips, and zero failures/errors.

## Test plan

- Deterministic: comparison/commit budget, growing/reset/page reconciliation,
  stale generations, close/reset, capacity, row IDs, selection and viewport.
- Macrobenchmark: snapshot application occurs inside the measured block.
- Physical E2E: actual Codex first gesture in app-selected tmux, zero wheels,
  sub-row drag/fling/catch, complete ordered history, recreation when applicable.

## STOP conditions

- A design would discard real rows, weaken ordered-history assertions, or route
  app-selected Auto gestures to remote wheel input.
- A background worker can race buffer mutation without a generation/atomic-commit proof.
- Only synthetic/emulator evidence is available for the final tmux claim.
- The authorized old phone cannot be resolved and model-verified.

## Maintenance notes

The incoming snapshot is currently bounded by the 4,096-row page plus saved
primary screen; update the budget tests whenever either bound changes.
