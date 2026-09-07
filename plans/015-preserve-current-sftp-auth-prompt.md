# Plan 015: Preserve the current SFTP keyboard-interactive prompt after stale answers

> **Executor instructions**: Implement only the token-state correction and its
> tests. Preserve mutable-response wiping on every accepted, rejected, and
> missing-client path.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionController.kt app/src/main/java/com/yanjiyu/terminalspike/connection/SftpClient.kt app/src/test/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionControllerTest.kt`

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug / security / tests
- **Planned at**: commit `93d070a`, 2026-09-03
- **Execution**: DONE locally on 2026-09-03

The controller now binds an answer to its client generation and exact challenge
token, preserves rejected or superseding prompts, and wipes missing-client
responses. Focused race tests and the full 956-test app JVM/repository gate pass.

## Why this matters

A delayed response from keyboard-interactive round one can arrive after round
two is displayed. The transport rejects the stale token, but the SFTP controller
unconditionally hides the current round-two dialog. Authentication then waits
for an answer the user can no longer submit.

## Current state

- `SftpSessionController.answerKeyboardInteractive()` at lines 119-123 ignores
  the client's Boolean acceptance result and always sets the visible challenge
  to null.
- `SftpSession.answerKeyboardInteractive()` and `SftpClient.kt:295-298` expose
  exact-token acceptance and wipe responses when no live lease exists.
- `KeyboardInteractiveBridgeTest` already proves a stale token is rejected while
  a newer round remains valid.
- `SftpSessionControllerTest` uses a fake that always returns true, so the
  controller race is absent from coverage.

## Scope

**In scope**:

- `app/src/main/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionController.kt`
- `app/src/test/java/com/yanjiyu/terminalspike/ui/sftp/SftpSessionControllerTest.kt`
- `SftpSession` ownership documentation only if needed to make wiping explicit

**Out of scope**:

- SSH terminal or Mosh prompt flows
- host-identity prompt redesign
- authentication timeouts, prompt count limits, or UI restyling

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused test | `./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.ui.sftp.SftpSessionControllerTest'` | exit 0 |
| Full gate | `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest` | `BUILD SUCCESSFUL` |

## Git workflow

Preserve unrelated dirty-tree work. Do not commit, push, or open a PR unless asked.

## Steps

### Step 1: Honor acceptance and current-token identity

Capture the current client and ownership generation. If the client is absent,
wipe every response array in the
controller. Otherwise transfer ownership exactly once to
`answerKeyboardInteractive()`. Clear the displayed challenge only when the call
returns true, the client/generation is still owned, **and** the currently
displayed challenge still has the submitted token. If a newer callback or
replacement client arrived during the call, preserve its state even if token
numbers happen to collide across transports.

Do not clear a prompt on false and do not wipe response arrays a second time
after ownership was transferred to a live client.

**Verify**:
`./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.ui.sftp.SftpSessionControllerTest'`
→ exit 0.

### Step 2: Add the two-round race regression

Make the fake session publish keyboard-interactive challenges and return a
configurable acceptance result. Cover:

- stale round-one submit returns false and round two remains visible;
- round two can then be answered and clears only itself;
- a missing client wipes all mutable response arrays;
- a newer challenge published during an accepted answer is not cleared.
- a superseding client with the same numeric token is not affected by the old client.

Assert tokens, not just prompt presence.

**Verify**: the focused command above passes with the new named cases.

### Step 3: Run repository gates

**Verify**:
`./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest`
→ `BUILD SUCCESSFUL`.

## Done criteria

- [x] A rejected stale answer never dismisses the current challenge.
- [x] An accepted answer clears only the matching current token.
- [x] Mutable response arrays are wiped when no client accepts ownership.
- [x] Focused and full gates pass.

## Test plan

- Extend the existing fake rather than adding a second controller harness.
- Assert exact challenge token and client generation for rejected, accepted,
  callback-race, and replacement-client cases.
- Inspect mutable response arrays after missing-client rejection to prove wiping.

## STOP conditions

- The live `SftpSession` implementation does not consume/wipe responses after
  accepting ownership; fix that contract explicitly before changing the controller.
- The fix requires weakening token checks in `KeyboardInteractiveBridge`.

## Maintenance notes

Prompt tokens are only meaningful inside an owning transport generation. Future
prompt APIs must preserve both identities rather than treating a numeric token
as process-global.
