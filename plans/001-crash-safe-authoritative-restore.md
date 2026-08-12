# Plan 001: Make backup import and crash recovery one authoritative mutation path

> **Executor instructions**: Follow every step and verification gate in order.
> Preserve all unrelated uncommitted work. Do not reset, clean, checkout, or
> mass-format the repository. If a STOP condition occurs, stop and report rather
> than inventing another persistence model. Update this plan's row in
> `plans/README.md` when complete.
>
> **Drift check (run first)**: verify `git rev-parse HEAD` is
> `ff8435bc100f381f6925202259017e7834f7233d`, then run the script in
> “Commands you will need”. If either worktree hash differs, inspect every
> in-scope symbol against “Current state”; any semantic mismatch is a STOP.

## Status

- **Priority**: P0
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: none
- **Category**: correctness / data safety / migration
- **Planned at**: commit `ff8435bc100f381f6925202259017e7834f7233d`, 2026-08-09
- **Execution status**: DONE, 2026-08-09

Execution evidence: focused authority/repository/backup JVM tests passed three
consecutive gates; Android-test compilation passed; seven Room recovery tests
passed on `RZCW81JZ9CP`; and `test lint assembleDebug assembleRelease
assembleDebugAndroidTest` passed (408 tasks, 2026-08-09). The two recovery-only
Compose cases could not create an Activity hierarchy while that phone's secure
keyguard owned `NotificationShade`; the identical pre-existing app-lock fixture
failed under the same condition. The fresh debug APK installed successfully and
MainActivity became the focused app behind the keyguard. No authorized Wi-Fi
target was present. Re-run those UI cases and foreground confirmation when a
phone is unlocked; this environmental acceptance item remains tracked by Plan
005 rather than reopening the authoritative-data implementation.

## Why this matters

A Replace restore spans Room and Proto DataStore, which cannot share one native
transaction. The recovery marker makes that survivable only if the process
checks it before exposing normal reads and writes. Today recovery is a manual
Settings action, while the compatibility repository also caches pre-import Room
truth. A kill between stores can therefore expose split state, and a successful
import can leave stale hosts active until restart.

This plan establishes one process authority gate, mandatory startup recovery,
cache generation/invalidation, and exact optional-field restoration. It must
land before adding more persistence writers.

## Current state

- `core/backup/BackupImportCoordinator.kt:303-367` writes a recovery snapshot,
  applies data, and clears the marker only afterward.
- `core/backup/BackupImportCoordinator.kt:424-477` calls `settings.update` inside
  `database.withTransaction`. DataStore may commit even if Room has not yet
  committed; rollback is best-effort in the same live process.
- `TerminalSpikeApplication.kt:10-13` only constructs `AppContainer`; it does not
  inspect or recover a pending marker.
- `ui/settings/SettingsBackupWorkflow.kt:517-543` is the only production caller
  of recovery and requires the user to reach Settings.
- `core/data/repository/TerminalDataRepository.kt:178-202,805-830` keeps one
  process-local `records` snapshot and reloads only when it is null.
- `TerminalDataRepository.authorizedMutation` at `:765-803` correctly invalidates
  and reloads after its own writes, but backup import writes through DAOs and
  does not cross that boundary.
- `BackupGlobalSettings.toRestoredAppSettings` at
  `BackupImportCoordinator.kt:803-853` starts from the destination builder and
  only sets optional accent/default-profile/last-mode values when non-null. A
  null source value therefore leaves the destination value behind.
- `MainActivity.kt:60-95` already has a fail-closed composition gate for Settings
  loading and app lock. Reuse that pattern; do not show the normal app beneath a
  recovery/error overlay.
- Repository error/state flows use sealed states and coroutine `Mutex` gates.
  Match `AppLockUiState`/`AppLockGate` and the non-cancellable reconciliation in
  `TerminalDataRepository.authorizedMutation`.

## Commands you will need

Run from the repository root with:

```bash
export JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk
```

| Purpose | Command | Expected on success |
|---|---|---|
| Tracked drift | run the `sha256sum` block below | prints the tracked baseline before edits |
| Focused JVM | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.core.backup.*' --tests 'com.yanjiyu.terminalspike.core.data.repository.TerminalDataRepositoryTest'` | exit 0; all selected tests pass |
| Android compile | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false :app:compileDebugAndroidTestKotlin` | exit 0 |
| Room tests | run the focused-device block below | both new classes pass on the resolved exact serial, or no-device skip is reported |
| Required full gate | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false test lint assembleDebug assembleRelease assembleDebugAndroidTest` | exit 0 |
| Static check | `git diff --check` | no output, exit 0 |

Use this Python snippet for the untracked baseline before edits; it must print
`efdc698050cc6ac9c5f6f114abf76a34df761c0750bb57980b7004a8afc99689`:

```bash
python3 - <<'PY'
import hashlib, subprocess
files = subprocess.check_output(
    ['git', 'ls-files', '--others', '--exclude-standard', '-z']
).split(b'\0')
h = hashlib.sha256()
for raw in sorted(x for x in files if x):
    path = raw.decode()
    if (path.startswith('plans/') or '/build/' in path or path.startswith(
        ('build/', '.gradle/', '.kotlin/', '.idea/')
    )):
        continue
    h.update(path.encode() + b'\0')
    with open(path, 'rb') as source:
        h.update(hashlib.sha256(source.read()).digest())
print(h.hexdigest())
PY
```

Tracked baseline command (must print
`982ea9ef1ea8991a619880575ad8632d21ac640bac692e7262a795e86d932da5` before edits):

```bash
git diff --binary -- . ':(exclude)plans' | sha256sum | awk '{print $1}'
```

Focused device command:

```bash
serial=$(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1; exit }')
if test -n "$serial"; then
  ANDROID_SERIAL="$serial" ./gradlew --no-daemon --max-workers=1 \
    :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.yanjiyu.terminalspike.BackupStartupRecoveryTest,com.yanjiyu.terminalspike.core.backup.RoomBackupAuthorityIntegrationTest
else
  echo 'No authorized Android debugging phone; focused installation/test skipped.'
fi
```

After the required full gate, run this exact non-destructive update/foreground
handoff. Report every offline/unauthorized row printed by the first command:

```bash
set -euo pipefail
adb devices -l
readarray -t terminal_targets < <(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1 }')
if ((${#terminal_targets[@]} == 0)); then
  echo 'No authorized Android debugging phone; installation skipped.'
else
  for serial in "${terminal_targets[@]}"; do
    adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s "$serial" shell am start -W -n com.yanjiyu.terminalspike/.MainActivity
    adb -s "$serial" shell dumpsys activity activities |
      rg 'topResumedActivity.*com\.yanjiyu\.terminalspike/.MainActivity'
  done
fi
```

## Scope

**In scope**:

- `app/src/main/java/com/yanjiyu/terminalspike/TerminalSpikeApplication.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/AppContainer.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/MainActivity.kt`
- new `app/src/main/java/com/yanjiyu/terminalspike/core/data/repository/AuthoritativeDataGate.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/core/data/repository/TerminalDataRepository.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/core/backup/BackupImportCoordinator.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/ui/settings/SettingsBackupWorkflow.kt`
- a small new recovery-only Compose gate under `ui/`
- focused JVM and Room/instrumentation tests for those classes
- `plans/README.md` status only

**Out of scope**:

- changing the encrypted archive format, KDF, AEAD, or recovery-marker key
- adding cloud/network backup or Android auto-backup
- changing Merge/Replace/Keep-both conflict semantics
- changing the Room schema unless the generation design truly cannot work
- deleting user data or silently discarding a pending recovery marker
- broad repository refactors or UI visual redesign

## Git workflow

- Work on the current branch unless the operator explicitly requests another.
- Make small logical commits only if authorized; use conventional messages such
  as `fix: gate startup on backup recovery`.
- Do not push or open a PR.
- Never include APKs, build outputs, `local.properties`, SDK/JDK paths, signing
  material, or test credentials.

## Steps

### Step 1: Add one process authority/generation gate

Create `AuthoritativeDataGate` as an application singleton with:

- a `Mutex` serializing authoritative catalog mutations and backup apply/recover;
- a monotonically increasing in-memory generation incremented only after a
  committed mutation or completed recovery;
- a startup state such as `Checking`, `Ready(generation)`,
  `RecoveryRequired`, and `RecoveryFailed(redactedMessage)`;
- `awaitReady()` for every repository boundary and `withMutation` for mutation
  owners;
- no Activity, URI, secret, or decrypted payload references.

Inject the same instance through `AppContainer` into `TerminalDataRepository`
and `BackupImportCoordinator`. Do not add independent locks: two locks recreate
the race.

**Verify**: add pure coroutine tests proving reads wait while startup is
checking/recovering, one mutation increments exactly once, cancellation before
commit does not increment, and concurrent mutation callers serialize. Run the
focused JVM command; it exits 0.

### Step 2: Make a pending marker a fail-closed startup condition

On application startup, launch one application-scope recovery check before
normal data repositories can become Ready:

1. inspect `AndroidBackupRecoveryMarkerStore.hasPendingRecovery()`;
2. when absent, mark the authority gate Ready;
3. when present, run the existing exact recovery path automatically, or expose
   a recovery-only action if user authentication is genuinely required;
4. clear the marker only after exact recovery and authoritative reload succeed;
5. on failure, keep all normal repositories/mutations gated and show a generic
   recovery screen with Retry—never the Workspace behind it.

Integrate the startup state with `MainActivity` alongside Settings/app-lock
loading. App lock must still protect the recovery surface; `FLAG_SECURE` remains
set while startup is unresolved.

**Verify**: an instrumentation test writes a valid pending marker, recreates the
process/activity fixture, and asserts normal Workspace semantics never appear
before recovery completes. Failure injection asserts only the recovery gate and
Retry appear.

### Step 3: Make import/recovery invalidate and republish Room authority

Run `BackupImportCoordinator.capture`, `apply`, and `recoverPendingImport`
through the shared authority mutation gate. Extend `TerminalDataRepository` to
remember the generation represented by `records`; `ensureLoaded()` must reload
when its cached generation differs from the shared generation.

After import/recovery commits:

- increment the generation under the same gate;
- reload/reconcile presentation UUID mappings before releasing normal readers;
- publish fresh Workspace/Connections/Settings state through existing ViewModel
  collectors or an explicit `reloadCatalog()` callback;
- do not reuse retired process-local presentation IDs for different UUIDs.

Do not let cancellation open a commit/publication gap. Follow the existing
`NonCancellable` post-commit reconciliation pattern.

**Verify**: load Workspace first, apply a Merge/Replace/Keep-both fixture, and
assert the same live ViewModel shows new truth without process restart. Then
perform a compatibility mutation and assert it cannot resurrect a deleted UUID.

### Step 4: Restore optional settings exactly

Before applying imported nullable values, explicitly clear:

- accent preset;
- default terminal profile ID;
- default keyboard profile ID;
- last backup mode.

Then conditionally set each imported non-null value. Preserve current protobuf
unknown fields only where the serializer contract explicitly requires them; do
not preserve destination values that the authenticated backup says are absent.

**Verify**: JVM tests start with every optional destination field populated,
restore a snapshot with all null, and assert all are absent. Repeat through
exact recovery and through a mixed-null snapshot.

### Step 5: Prove cross-store crash recovery

Add deterministic failure seams at these boundaries:

- after recovery-marker commit but before any imported write;
- after DataStore replacement but before Room transaction commit;
- after Room commit but before marker clear;
- after marker clear but before UI refresh.

Use process-recreation style instrumentation where possible and pure coordinator
tests elsewhere. In every case, restart must either expose exact pre-import state
or exact completed imported state—never a mixture—and no newer mutation may run
before resolution.

**Verify**: new tests pass three consecutive runs; focused JVM, Android compile,
and Room test commands exit 0.

## Test plan

- Extend `BackupImportCoordinatorTest` for optional-null restoration, marker
  order, cancellation, and generation publication.
- Extend `RoomBackupImportCoordinatorTest` for Room/DataStore split-failure and
  exact recovery.
- Extend `TerminalDataRepositoryTest` for stale-generation reload and UUID
  tombstone preservation.
- Add an Activity/startup instrumentation test patterned after
  `AppLockRuntimeTest`, using deterministic fake gates rather than sleeps.
- Add a live-ViewModel import test proving Workspace and Connections refresh
  without restart.

## Done criteria

- [x] A pending recovery marker prevents all normal catalog/settings mutation.
- [x] Startup automatically resolves a valid marker or shows only a fail-closed
      recovery surface.
- [x] Backup apply/recovery and compatibility writes share one mutation gate.
- [x] A live, already-loaded catalog refreshes after every import strategy and
      exact recovery.
- [x] Null optional backup values clear populated destination values.
- [x] Cross-store crash seams prove only exact-before or exact-after state.
- [x] Focused JVM tests, Android-test compilation, focused Room/device tests,
      the required full gate, exact-device install/foreground verification, and
      `git diff --check` pass (with the secure-keyguard UI limitation explicitly
      reported above).
- [x] No file outside Scope changed, except pre-existing shared-tree changes and
      the status row in `plans/README.md`.

## STOP conditions

Stop and report if:

- the recovery marker cannot be inspected/decrypted without user input;
- a second independent mutation lock already landed in an in-scope writer;
- exact recovery would require clearing a marker before authoritative reload;
- the implementation would expose Workspace while recovery is unresolved;
- any proposed fix silently drops a marker or destructive-import snapshot;
- a failure gate is flaky after two deterministic attempts;
- the change requires modifying the archive/KDF/AEAD wire format.

## Maintenance notes

- Every future writer that bypasses repository APIs must use the same authority
  gate or explicitly invalidate its generation.
- Reviewers should scrutinize marker clear order, cancellation windows, lock
  ordering, DataStore rollback, and presentation-ID reconciliation.
- A schema migration registry for genuinely older backup payloads remains a
  Plan 005 acceptance item; do not mix it into this transactional fix.
