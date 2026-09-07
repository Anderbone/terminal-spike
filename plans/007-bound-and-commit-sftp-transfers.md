# Plan 007: Bound recursive SFTP work and make transfer results failure-atomic

> **Executor instructions**: Complete Plan 006 first. Follow every verification
> step and preserve unrelated dirty work. Do not implement partial cleanup that
> can delete pre-existing user files.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/connection/SftpClient.kt app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpLocalTransfer.kt app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionController.kt app/src/test app/src/androidTest`
> Reconcile Plan 006 changes before proceeding. Stop if transfer ownership no
> longer matches the current-state description.

## Status

- **Priority**: P0
- **Effort**: L
- **Risk**: MED
- **Depends on**: Plan 006
- **Category**: security / bug / tests
- **Planned at**: commit `93d070a`, 2026-09-02

## Why this matters

Remote listings and Android document providers are untrusted boundaries, yet
recursive upload/download/copy/delete currently have no depth, entry, cycle, or
cancellation bound. Interrupted transfers can also leave truncated artifacts
under final-looking names. A public file-transfer surface must fail with a clear
bounded error and expose either the prior state or a complete new artifact.

## Current state

- `SftpClient.kt:189-231` recursively deletes, copies, and downloads directly
  from server responses.
- `SftpLocalTransfer.kt:136-185` recursively materializes an entire provider tree
  into one `MutableList` without tracking document IDs or limits.
- `SftpClient.kt:114-123,198-217` writes single uploads/downloads and remote
  copies to final paths. Recursive upload cleans its newly created root, but
  single upload and remote copy do not provide the same guarantee.
- `SftpLocalTransfer.kt:41-47` creates a final SAF document before writing.
- `SftpLocalTransfer.kt:100-123` publishes a MediaStore item on stream close,
  including close during exceptional unwinding.
- Existing project convention uses named maximum constants, injectable lower
  bounds for tests, typed safe messages, and cleanup limited to operation-owned
  data (see backup and terminal transfer limits).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| SFTP unit tests | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.connection.Sftp*' --tests 'com.yanjiyu.terminalspike.ui.sftp.Sftp*'` | exit 0 |
| SFTP instrumentation | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew :app:assembleDebugAndroidTest` | exit 0 |
| Full local gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | exit 0 |
| Hygiene | `git diff --check` | exit 0 |

## Scope

**In scope**:

- `app/src/main/java/com/yanjiyu/terminalspike/connection/SftpClient.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpLocalTransfer.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionController.kt`
- new or existing focused SFTP unit/instrumentation tests
- user-visible SFTP error strings/resources required by the bounded failures

**Out of scope**:

- background transfer services, resumable uploads, or synchronization
- arbitrary limits on a single intentionally selected large file
- deleting or renaming any pre-existing destination during rollback
- changing SSH authentication/trust or adding dependencies

## Git workflow

Preserve the current dirty tree. Do not commit/push unless asked. If later
authorized, use `fix: bound and atomically publish SFTP transfers`.

## Steps

### Step 1: Define one cancellable traversal budget

Create an internal `SftpTraversalBudget` shared by local and remote walkers with
production defaults of 64 directory levels and 10,000 entries per user
operation. Make limits injectable for tests. Do not impose a total content-byte
cap on a single explicitly selected file; keep streaming memory-bounded.
Increment/check before scheduling each child, track visited SAF document IDs and
normalized remote directory paths, and call `ensureActive()` between blocking
entries. Cycles, excess depth/count, or cancellation must terminate with a
resource-backed user-safe error.

Replace recursive call stacks with explicit iterative work lists. Preserve the
stable user-visible ordering produced by current directory listings.

**Verify**: focused tests cover a cycle, depth 65, entry 10,001, cancellation,
and exactly-at-limit success without allocating huge fixtures.

### Step 2: Stream local provider traversal

Replace the all-at-once `MutableList<SftpUploadEntry>` contract with a
single-consumption iterator/flow or callback source that opens each file only
when the remote writer is ready. Keep document IDs in the visited set and close
every cursor/input on success, rejection, and cancellation. The selected root
name remains unchanged and no provider URI enters logs or persisted UI state.

**Verify**: a fake DocumentsProvider test proves bounded memory shape, cycle
rejection, cursor closure, and no file open after cancellation.

### Step 3: Add explicit commit/abort destination semantics

Replace `SftpDownloadDestination.openFile(): OutputStream` with an operation-
owned pending artifact contract exposing `stream`, `commit()`, and `abort()`.
Closing a stream must not mean success.

- For MediaStore, retain `IS_PENDING=1` until `commit`; `abort` deletes the new
  URI. A write/close/update failure also deletes it.
- For SAF, create an operation-owned temporary document and rename only on
  commit. If the provider cannot rename, report failure and delete the temporary
  document; do not publish a partial final file.
- Track every directory/document created by a recursive download and remove only
  those operation-owned artifacts, deepest first, on failure.

Use unique opaque temporary names and never overwrite an existing user item.

**Verify**: injected failure before first byte, mid-stream, during close, and
during commit leaves no final artifact and no pending MediaStore row.

### Step 4: Make remote writes atomic where the SFTP protocol permits

For single upload and remote file copy, stream into a unique operation-owned
temporary sibling and rename to the already-selected unique final name only
after success. For directory upload/copy, populate a unique temporary root and
rename the root after every child succeeds. On failure, clean only the temporary
tree under the same traversal budget. If the server rejects same-directory
rename, surface failure and clean the temporary item; do not fall back to
publishing a partial final name.

**Verify**: an extracted fake remote-filesystem boundary injects failures at
each stage and proves exact cleanup/rename calls. Extend the real OpenSSH SFTP
test with a successful atomic upload/copy/download path.

### Step 5: Run full regression gates

Run focused tests, instrumentation assembly, the full local gate, and patch
hygiene. Run the credentialed real SFTP fixture only on a disposable emulator or
the exact model-verified authorized old phone; never on the foldable.

## Test plan

- Pure walker tests: empty, exact limits, overflow, cycle, deep tree, cancel.
- Destination tests: success commits once; every failure aborts once; abort is
  idempotent; pre-existing names are untouched.
- Remote tests: temporary sibling is unique, renamed only after success, and
  removed after injected failure.
- Real E2E: successful file and directory operations retain current behavior.

## Done criteria

- [ ] No recursive provider/server walk can overflow the JVM stack or traverse
  more than the configured entry/depth budget.
- [ ] Traversal is cooperative with coroutine cancellation.
- [ ] Failed local downloads leave no final-looking or pending artifact.
- [ ] Failed remote upload/copy leaves no final-looking artifact and cleans only
  operation-owned temporary data.
- [ ] Focused and real-fixture tests pass; full local gate is green.
- [ ] No new dependency or out-of-scope feature is introduced.

## STOP conditions

- Atomic publication would require overwriting or deleting a pre-existing item.
- A provider/server cannot support temporary creation plus rename and the only
  fallback would expose partial final content.
- Streaming traversal requires persisting provider URIs or adding a background
  sync/service feature.
- A test requires weakening current SSH/SFTP E2E assertions.

## Maintenance notes

Any future resume/progress feature must extend the pending-artifact protocol;
never infer success from stream close. Keep traversal budgets centralized so
copy, delete, upload, and download cannot drift to different safety limits.
