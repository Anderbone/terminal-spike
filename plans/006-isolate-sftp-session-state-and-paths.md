# Plan 006: Isolate SFTP session state and preserve remote path identity

> **Executor instructions**: Follow this plan in order. Preserve all unrelated
> worktree changes. Do not weaken existing SSH trust or SFTP E2E assertions.
> Update the plan status in `plans/README.md` only after every done criterion is
> satisfied.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/connection/SftpClient.kt app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionController.kt app/src/test/java/com/yanjiyu/terminalspike/connection/SftpPathTest.kt app/src/test/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionControllerTest.kt`
> Compare the current-state excerpts below with the live files. Stop on a
> semantic mismatch instead of applying this plan mechanically.

## Status

- **Priority**: P0
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug / tests
- **Planned at**: commit `93d070a`, 2026-09-02

## Why this matters

`SftpSessionController` allows an older blocking connection job to publish
prompts, browsing state, or failure after a newer connection, authentication
request, or close. Separately, the path normalizer translates legal POSIX
backslashes into separators, so a destructive operation can target a different
remote object than the row the user selected. Both are release correctness
defects with small, well-contained fixes.

## Current state

- `SftpSessionController.kt:60-87` replaces/clears the client but does not cancel
  the prior `operation`; the old job writes `_state` unconditionally.
- `SftpSessionController.kt:218-237` cancels only during `close()` or before a
  browsing mutation, and catches `Exception`, including coroutine cancellation.
- `SftpClient.kt:256-265` begins normalization with
  `path.replace('\\', '/').split('/')`.
- `SftpClient.kt:281-285` already rejects `/`, NUL, dot, and dot-dot names while
  allowing a literal backslash, which is correct for POSIX/SFTP filenames.
- The existing deterministic testing style uses `TestScope`, injected
  factories, and state assertions in `SftpSessionControllerTest.kt`; path
  contracts live in `SftpPathTest.kt`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused tests | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.connection.SftpPathTest' --tests 'com.yanjiyu.terminalspike.ui.sftp.SftpSessionControllerTest'` | exit 0; all focused tests pass |
| Full local gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | exit 0 |
| Patch hygiene | `git diff --check` | exit 0, no output |

## Scope

**In scope**:

- `app/src/main/java/com/yanjiyu/terminalspike/connection/SftpClient.kt`
- `app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionController.kt`
- `app/src/test/java/com/yanjiyu/terminalspike/connection/SftpPathTest.kt`
- `app/src/test/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionControllerTest.kt`

**Out of scope**:

- transfer limits or atomic destination changes (Plan 007)
- changing the SSH trust/authentication protocol
- changing SFTP UI layout or adding dependencies
- changing generic remote-shell path behavior outside the SFTP boundary

## Git workflow

- Work on the current branch unless the operator requests a branch.
- Do not reset, clean, commit, push, or overwrite unrelated dirty files.
- If committing is later authorized, use `fix: isolate SFTP operations and paths`.

## Steps

### Step 1: Introduce a narrow testable SFTP session boundary

Add an internal interface containing only the operations used by
`SftpSessionController` (connect, prompt answers, list/mutations/transfers, and
close). Make `SftpClient` implement it and change the controller factory to
return the interface. Keep `SftpClient` construction and public behavior
unchanged at `TerminalSpikeViewModel` and in real E2E tests.

**Verify**: compile and run `SftpSessionControllerTest`; existing test passes.

### Step 2: Make every asynchronous state write generation-owned

Before authentication requests, connects, browsing mutations, and close,
cancel the superseded operation where appropriate and advance a monotonically
increasing generation. Capture both generation and client identity in each job.
Every prompt callback and every success/failure state publication must first
prove it still owns the current generation and client. Treat cancellation as
control flow: catch `CancellationException` first and rethrow it. A blocking
client call that returns after cancellation must still fail the generation
check before touching state.

Add deterministic fake-client tests for:

- connection A completing after connection B starts;
- connection A emitting a host-key or keyboard-interactive prompt after B starts;
- a connection returning or failing after `requestAuthentication`;
- a connection returning or failing after `close()`;
- cancellation never becoming a visible failure message.

**Verify**: focused controller tests pass repeatedly three times.

### Step 3: Preserve literal backslashes in SFTP protocol paths

Remove backslash-to-slash translation from `normalizeAbsolutePath`. Continue to
treat `/` as the sole protocol separator, collapse dot segments, prevent escape
above root, and reject `/` and NUL inside a child name. Add tests proving:

- `normalizeAbsolutePath("/home/a\\b") == "/home/a\\b"`;
- `childPath("/home", "a\\b")` preserves one child filename;
- parent and filename operations round-trip that path;
- slash traversal remains rejected and root clamping remains unchanged.

If a caller is discovered that intentionally supplies Windows-local paths to
this SFTP normalizer, stop and report it; do not retain the lossy translation.

**Verify**: focused path tests pass.

### Step 4: Run regression gates

Run the full local gate and `git diff --check`. If the deterministic OpenSSH
fixture is already running and its existing credentials are available, run the
focused SFTP real E2E on an emulator or the authorized old phone under the exact
device rules; otherwise record that external fixture run for the final release
gate without weakening the unit proof.

## Test plan

- Extend the two existing focused test files only.
- Use `CompletableDeferred`/test dispatchers, never sleeps, for stale-job races.
- Assert the final state belongs to the newest host and remains `Closed` after
  close.
- Assert literal backslashes survive every pure path helper involved in list,
  rename, delete, copy, and download addressing.

## Done criteria

- [ ] No superseded SFTP job or callback can publish UI state.
- [ ] `CancellationException` is rethrown rather than shown as an SFTP failure.
- [ ] Legal POSIX backslash filenames retain their exact identity.
- [ ] Focused tests pass three consecutive runs.
- [ ] Full local gate and `git diff --check` pass.
- [ ] No out-of-scope files changed, except `plans/README.md` status.

## STOP conditions

- The live controller no longer has the cited factory/state ownership shape.
- Correct isolation requires changing SSH trust semantics or exposing secrets.
- Any proposed test needs real timing sleeps instead of controlled completion.
- The path helper is proven to be a documented cross-platform local-path parser.

## Maintenance notes

Keep generation checks at the final state-write boundary even if future clients
gain cooperative cancellation. Cancellation alone cannot stop a completed
blocking JSch call from returning stale data. SFTP paths are remote POSIX paths;
local SAF display names must remain data, not separator syntax.
