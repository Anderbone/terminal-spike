# Plan 002: Make SSH runtime settings, trust, and keyboard-interactive authentication truthful

> **Executor instructions**: Execute this plan only after Plan 001 is DONE.
> Read the complete plan first, preserve unrelated dirty changes, and run every
> gate. Never weaken strict host-key checking or convert secrets to immutable
> strings for convenience. Update the status row in `plans/README.md`.
>
> **Drift check (run first)**: Plan 001 legitimately changes the original
> baseline. First confirm Plan 001 is marked DONE, record the current HEAD plus
> tracked diff/status with `git rev-parse HEAD`,
> `git diff --binary -- . ':(exclude)plans' | sha256sum`, and
> `git status --short` in this plan's execution notes, then run
> `git diff --stat ff8435bc100f381f6925202259017e7834f7233d..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/connection app/src/main/java/com/yanjiyu/terminalspike/ui/TerminalSpikeViewModel.kt app/src/main/java/com/yanjiyu/terminalspike/ui/SshConnectionBar.kt app/src/main/java/com/yanjiyu/terminalspike/core/model`.
> If any Current-state contract differs semantically, stop and report.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: `plans/001-crash-safe-authoritative-restore.md`
- **Category**: security / correctness / feature completion
- **Planned at**: commit `ff8435bc100f381f6925202259017e7834f7233d`, 2026-08-09
- **Execution status**: DONE, 2026-08-09
- **Execution baseline**: HEAD
  `ff8435bc100f381f6925202259017e7834f7233d`; tracked diff excluding
  `plans/` SHA-256
  `ec01a36b238a05f3468257c169075b1c005bd85c00863d285fa25905d5d04105`;
  `git status --short` SHA-256
  `8ac867cc0e7dd783d877f1ffec28be260703b65d98e097c26c1437b0a1a02ea6`.

Execution evidence: the focused SSH/KBI/trust/TERM/startup/reconnect and
Connections JVM gate passed, Android-test compilation and `lintDebug` passed,
and the required `test lint assembleDebug assembleRelease
assembleDebugAndroidTest` gate passed (408 tasks; 665 app JVM tests, zero
failures). Five Room trust-store tests passed on authorized USB target
`RZCW81JZ9CP`. The secure keyguard owned the Bouncer/Notification Shade, so nine
Activity-based trust UI cases could not obtain a Compose hierarchy; this is
recorded for the unlocked-device evidence pass in Plan 005. Both the current app
debug APK and separately built native Mosh extension debug APK installed
successfully. MainActivity is the resumed and focused app behind the secure
keyguard. No authorized Wi-Fi target was present. Final tracked diff excluding
`plans/` SHA-256: `111e6c96c4a1e8e418de3e993d205f41b510a5e063c58d44d422c541251a728e`.

## Why this matters

SSH currently presents three capabilities that are not truthful at the transport
boundary. First-contact trust can only be persisted, changed keys cannot be
reviewed/replaced, and keyboard-interactive is treated as a password. Separately,
saved TERM and startup-command fields never reach the shell. These are protocol
and security contracts, so fixing only the UI would be unsafe.

The result of this plan is a typed host-identity decision state machine, genuine
bounded keyboard-interactive challenges with wipeable responses, and exact TERM
and startup behavior shared by normal connections and Test Connection.

## Current state

- `connection/Connection.kt:17-19` exposes `answerPrompt(Boolean)` and
  `HostKeyPrompt` only contains the newly offered key.
- `connection/VerifyingHostKeyRepository.kt:42-84` rejects a changed key without
  prompting; accepting an unknown key always persists it.
- `ui/SshConnectionBar.kt:133-157` offers only “Trust and connect” or Cancel.
- `connection/JschAuthenticatedSession.kt:37-51` configures JSch with strict
  host-key checking and calls one shared authentication boundary. Preserve this
  useful single boundary.
- `connection/JschAuthenticatedSession.kt:124-158` supports password,
  stored-password, and private-key only; it has no JSch `UIKeyboardInteractive`.
- `ui/TerminalSpikeViewModel.kt:1915-1925,2010-2021` turns a stored
  keyboard-interactive response into a password and creates immutable Strings.
- Core metadata correctly models `SshAuthentication.KeyboardInteractive` in
  `core/model/ConnectionModels.kt:127-130`; do not delete or collapse it.
- `core/model/ProfileModels.kt:50-98` validates a bounded `termValue`.
- `core/model/ConnectionModels.kt:19-77` validates a bounded optional
  `startupCommand` on the host.
- `ui/TerminalSpikeViewModel.runtimeProfileSelectionFor` at `:2309-2345`
  resolves the terminal profile but does not carry TERM/startup.
- `ui/TerminalSpikeViewModel.startRemoteSession` at `:2415-2421` creates an
  `SshConnectionConfig` without either value.
- `connection/JschSshConnection.kt:70-90,254-260` uses a hard-coded
  `xterm-256color` shell and never dispatches the saved command.
- Authentication arrays are currently cleared in `applyAuthentication` finally
  blocks. Preserve and extend that ownership discipline.

## Commands you will need

```bash
export JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk
```

| Purpose | Command | Expected on success |
|---|---|---|
| SSH JVM gate | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.connection.*' --tests 'com.yanjiyu.terminalspike.ui.TerminalSpikeUiStateTest'` | exit 0; selected tests pass |
| UI compile | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false :app:compileDebugAndroidTestKotlin` | exit 0 |
| Focused device UI | run the block below | the new trust/auth class passes, or no-device skip is reported |
| Lint slice | `./gradlew --no-daemon --max-workers=1 :app:lintDebug` | exit 0, no new errors |
| Required full gate | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false test lint assembleDebug assembleRelease assembleDebugAndroidTest` | exit 0 |
| Static check | `git diff --check` | no output |

Focused device command:

```bash
serial=$(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1; exit }')
if test -n "$serial"; then
  ANDROID_SERIAL="$serial" ./gradlew --no-daemon --max-workers=1 \
    :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.yanjiyu.terminalspike.SshTrustAuthenticationFlowTest
else
  echo 'No authorized Android debugging phone; focused installation/test skipped.'
fi
```

After the required full gate, run this exact update/foreground handoff and report
offline/unauthorized rows from the first command:

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

- `app/src/main/java/com/yanjiyu/terminalspike/connection/Connection.kt`
- `JschAuthenticatedSession.kt`, `JschSshConnection.kt`,
  `VerifyingHostKeyRepository.kt`, `KnownHostManager.kt`, and trust-store types
- `connection/MoshBootstrap.kt` only where it must consume the same typed host
  trust and authentication contract
- `core/model/ConnectionModels.kt` only for bounded authentication metadata
- `ui/TerminalSpikeViewModel.kt`, `ui/SshConnectionBar.kt`, and the existing
  SSH-connect/editor dialogs required for typed prompts
- `ui/connections/HostConnectionTester.kt`
- related JVM and Compose/instrumentation tests
- localized string resources for the new prompt text

**Out of scope**:

- weakening `StrictHostKeyChecking=yes`
- adding password/key logging, persistence, or SavedState serialization
- retaining OTP/challenge responses for reconnect without explicit reusable
  credential semantics
- changing Mosh native transport/AIDL or public extension licensing
- adding an arbitrary remote command runner; only the saved startup-command
  contract is in scope
- terminal chrome/session-action redesign (Plan 003)

## Git workflow

- Preserve the shared dirty worktree; never reset or overwrite sibling changes.
- If commits are authorized, use focused conventional messages such as
  `feat: add explicit ssh host trust decisions`.
- Do not push, publish, or include credentials/build outputs.

## Steps

### Step 1: Replace Boolean host trust with typed decisions

Introduce protocol-level types with exhaustive branches:

- `HostIdentityPrompt.FirstContact(endpoint, algorithm, newFingerprint)`;
- `HostIdentityPrompt.Changed(endpoint, algorithm, previousFingerprint,
  newFingerprint)`;
- `HostIdentityDecision.Reject`, `TrustOnce`, `TrustAndSave`, and
  `ReplaceSavedKey`.

Use canonical endpoint aliases that include non-default ports. Fingerprints must
remain SHA-256 public metadata; never expose raw key bytes. Changed-key prompts
must be created from one authoritative known-host read and must revalidate the
expected previous fingerprint before replacement to prevent a prompt/write race.

`TrustOnce` is session-local and must not mutate Room. `TrustAndSave` is valid
only for first contact. `ReplaceSavedKey` is valid only for a changed-key prompt
and must call the existing transactional replacement boundary.

**Verify**: extend `VerifyingHostKeyRepositoryTest` for first-contact reject,
trust-once, save, changed reject, explicit replace, stale expected-old-key race,
non-default port, and concurrent prompts. The focused SSH JVM gate passes.

### Step 2: Render complete, safe trust dialogs

Update the terminal/session prompt and staged Test Connection UI to render typed
states:

- first contact: endpoint, algorithm, new fingerprint, Trust once, Trust and
  save, Cancel;
- changed key: strong warning, previous and new fingerprints in selectable text,
  Cancel by default, and a distinct explicit Replace saved key confirmation;
- no endpoint username, password, command, or secret in SavedState/logs.

Activity recreation must preserve only a stable non-secret prompt token or the
repository-owned pending prompt, never a process-local presentation ID that can
be rebound after process death.

**Verify**: Compose tests assert exact actions and that changed-key replacement
cannot occur with one generic confirm tap. Instrumentation recreates the Activity
while a prompt is shown and confirms it targets the same repository session.

### Step 3: Add a bounded keyboard-interactive challenge bridge

Extend connection-layer `SshAuthentication` with genuine session-only and
reusable-response keyboard-interactive variants. Add JSch `UIKeyboardInteractive`
at the shared authenticated-session factory with explicit bounds:

- maximum prompts per challenge and maximum total challenges;
- maximum UTF-8 prompt/name/instruction lengths;
- per-prompt echo flag carried to UI;
- a timeout/cancellation path;
- application-owned responses kept as mutable `ByteArray`/`CharArray` and wiped on acceptance,
  rejection, timeout, disconnect, replacement, and failure.

JSch 2.28.3 unavoidably requires `UIKeyboardInteractive.promptKeyboardInteractive`
to return `String[]`. Treat those returned Strings as a narrow third-party API
boundary exception: construct them at the last possible callback line, never
store/log/publish them, return them once, and immediately wipe every mutable
source buffer in `finally`. Document that JVM Strings cannot be wiped. Replacing
JSch solely to remove this API limitation is outside this plan.

Bridge challenges through repository-owned session state so Activity recreation
does not lose transport ownership. Do not put prompts/responses in Proto,
Room, SavedState, recents, notifications, or logs. A saved reusable response may
be applied only when the challenge shape is the supported single reusable secret;
otherwise prompt interactively.

**Verify**: JVM tests cover single hidden prompt, multiple mixed-echo prompts,
OTP then password, unsupported reusable shape, timeout, cancellation, stale
session response, Activity recreation, and byte-array wiping. Existing password
and private-key tests remain green.

### Step 4: Carry TERM to the PTY and Test Connection

Add a validated `terminalType` to `SshConnectionConfig`. Resolve it from the
host-selected terminal profile or default profile in
`runtimeProfileSelectionFor`; default only when no profile is selected. Set the
PTY type before shell connection. Use the same value in `HostConnectionTester`.

Mosh bootstrap/extension term behavior must be explicit: if its API cannot
negotiate the selected TERM in this plan, document and surface that Mosh uses its
supported fixed type rather than silently pretending the profile value applied.

**Verify**: fake-channel tests capture `setPtyType` ordering/value; profile and
default cases pass; invalid/stale profile references block before handshake.

### Step 5: Execute the saved startup command exactly once

Carry the bounded host `startupCommand` through the session start request. For
interactive SSH, define it as user-authored shell input sent once only after the
shell reaches Connected, encoded UTF-8 with one normalized terminal Enter. It is
not a locally interpreted command and must not be concatenated into the Mosh
bootstrap shell command.

Required behavior:

- empty/null sends nothing;
- reconnect creates a fresh shell and sends once with an honest fresh-shell
  notice;
- duplicate sends once in the duplicate only;
- a rejected/closed input queue does not report success or retry the command;
- no command appears in notices, recents, notifications, or logs;
- cancellation wipes any temporary encoded bytes.

If the product brief instead requires a JSch exec channel rather than shell
input, STOP and request a decision; do not silently choose both.

**Verify**: repository/connection tests assert exact bytes, timing after
Connected, no duplicate on repeated callbacks, reconnect semantics, rejection,
and no command leakage in public session snapshots.

### Step 6: Keep every entry path consistent

Apply the same typed trust/auth/TERM/startup contracts to:

- Workspace one-tap saved-host launch;
- Connections row launch and Test Connection;
- Quick Connect;
- reconnect and double-tap duplicate;
- SSH bootstrap used by Mosh.

No path may fall back to the old Boolean prompt or password-like KBI conversion.

**Verify**: the following commands find no old compatibility path, and focused
tests/lint pass:

```bash
set -euo pipefail
assert_no_match() {
  set +e
  rg "$@"
  status=$?
  set -e
  if ((status == 0)); then
    echo 'Unexpected legacy path matched.' >&2
    return 1
  fi
  if ((status != 1)); then
    echo "rg failed with status $status" >&2
    return "$status"
  fi
}
assert_no_match -n -U 'HostAuthenticationMethod\.KEYBOARD_INTERACTIVE(?s:.{0,800})SshAuthentication\.(Password|StoredPassword)' app/src/main/java/com/yanjiyu/terminalspike/ui/TerminalSpikeViewModel.kt
assert_no_match -n 'onHostKeyAnswer:.*Boolean|answerPrompt\([^)]*Boolean' app/src/main/java
assert_no_match -n 'private const val TERMINAL_TYPE|setPtyType\(TERMINAL_TYPE' app/src/main/java
```

## Test plan

- Extend `VerifyingHostKeyRepositoryTest`, `JschAuthenticatedSessionTest`,
  `JschSshConnectionIntegrityTest`, `MoshBootstrapTest`,
  `TerminalSpikeUiStateTest`, and host-connection tester tests.
- Add one Compose class for first-contact/changed-key UI and one lifecycle test
  for prompt recreation.
- Use injected JSch/channel seams; never require production credentials in JVM
  tests.
- Add a local OpenSSH integration fixture later in Plan 005, but make protocol
  behavior deterministic at this layer first.

## Done criteria

- [x] First-contact prompts support Reject, Trust once, and Trust and save.
- [x] Changed-key prompts show previous/new SHA-256 fingerprints and require an
      explicit race-safe replacement decision.
- [x] All entry paths use genuine bounded keyboard-interactive callbacks.
- [x] All application-owned prompt/response arrays are wiped on every terminal
      path; no secrets enter immutable UI/session metadata.
- [x] Selected/default TERM reaches the SSH PTY before connect.
- [x] Saved startup command runs exactly once per fresh SSH shell and never leaks
      to logs, history metadata, or Mosh bootstrap syntax.
- [x] Focused JVM tests, Android-test compile, lintDebug, and diff check pass.
- [x] Required full unit/lint/build gate passes; current APK is installed/launched
      on every authorized phone and MainActivity is foreground (or no-device skip
      is explicitly reported).

## STOP conditions

Stop and report if:

- strict host-key checking would need to be disabled;
- JSch cannot expose the previous key or interactive challenge without a global
  mutable callback that crosses sessions;
- a proposed KBI design stores immutable Strings anywhere beyond the unavoidable
  one-shot JSch `String[]` callback return boundary, or stores any response in
  StateFlow/SavedState/Room/logs;
- host-key replacement cannot atomically revalidate the expected old key;
- startup-command semantics conflict between the brief and the model;
- Mosh bootstrap cannot consume the new trust/auth API without changing AIDL or
  native code outside scope;
- any test requires committing a real endpoint, password, private key, or OTP.

## Maintenance notes

- Every future SSH/Mosh bootstrap path must use `JschAuthenticatedSessionFactory`
  and the same typed prompt/auth contracts.
- Reviewers should focus on secret ownership, stale prompt/session IDs, trust
  compare-and-replace, and reconnect duplication.
- General local OpenSSH/tmux acceptance belongs to Plan 005; this plan must still
  provide exhaustive injected tests.
