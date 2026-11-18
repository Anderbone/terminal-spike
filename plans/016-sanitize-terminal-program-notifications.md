# Plan 016: Sanitize remote terminal text before Android notifications

> **Historical record (August–September 2026).** Status, commands and evidence below
> refer to the original implementation. Use the [current workflow](README.md#current-workflow)
> before resuming a requirement; these instructions do not override current project rules.

> **Executor instructions**: Treat OSC 9 payloads as hostile remote input.
> Preserve ordinary international visible text while removing invisible control
> and Unicode-format characters that can reorder or disguise system UI.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/terminal/engine/VtTerminalEngine.kt app/src/main/java/com/yanjiyu/terminalspike/SessionForegroundService.kt app/src/main/java/com/yanjiyu/terminalspike/connection/SshSessionRepository.kt app/src/test app/src/androidTest`

## Historical status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security / tests
- **Planned at**: commit `93d070a`, 2026-09-03
- **Execution**: DONE locally on 2026-09-03

The parser already had OSC character validation. A shared
bounded sanitizer now independently protects both the engine event boundary and
the Android notification builder, preserving visible international text and
falling back for empty unsafe input. Focused API 35 notification tests, API 37
boundary coverage, and the full repository gate pass.

This is a completed implementation record. The baseline and steps below describe the original
defense-in-depth change; their test counts are historical, not a new current-build result.

## Why this matters

An SSH host controls OSC 9 text that can leave the terminal surface in an Android
notification. Invisible bidi overrides and related format characters can disguise
system UI if admitted. Alongside the parser's OSC validation, the completed change
added independent sanitization at the event and notification-builder boundaries.

## Pre-fix state at `93d070a`

- `VtTerminalEngine.enqueueTerminalNotification()` only trims
  and truncates the remote payload.
- `SessionForegroundService.buildTerminalProgramNotification()` only collapses
  whitespace and truncates it when privacy is disabled.
- `SshSessionRepository.kt` already removes ISO controls and all
  `Character.FORMAT` characters from neighboring untrusted labels/titles.
- `VtTerminalEngineTest.osc9PublishesBoundedOneShotTerminalNotificationsWithoutRenderingText`
  and `SessionNotificationActionTest` cover ordinary text, not bidi/control input.

## Scope

**In scope**:

- a small shared untrusted-display-text sanitizer in an appropriate existing package
- `VtTerminalEngine.kt`
- `SessionForegroundService.kt`
- focused parser/unit and Android notification tests

**Out of scope**:

- allowing new OSC commands
- changing notification privacy defaults or permission policy
- ASCII transliteration or removal of ordinary RTL script characters
- terminal cell rendering and title policy beyond safe helper reuse

## Historical commands (do not execute as current instructions)

| Purpose | Command | Expected on success |
|---|---|---|
| Focused JVM | `./gradlew :app:testDebugUnitTest --tests '*VtTerminalEngineTest' --tests '*Notification*Test'` | exit 0 |
| Focused emulator | `scripts/run-android-emulator-tests.sh --api 35 --suite boundary --test-filter com.yanjiyu.terminalspike.SessionNotificationActionTest` | exact class passes |
| Full gate | `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest` | `BUILD SUCCESSFUL` |

## Historical Git workflow

Preserve unrelated dirty-tree work. Do not commit, push, or open a PR unless asked.

## Historical implementation steps

### Step 1: Define one bounded sanitizer contract

Create a pure helper that removes ISO controls other than whitespace intended
for normalization and removes every `Character.FORMAT` code unit, including
LTR/RTL marks, embeddings, overrides, isolates, and zero-width format controls.
Collapse whitespace, trim, then apply the caller's visible-length bound. Preserve
ordinary Arabic, Hebrew, CJK, emoji, and accented text.

Add pure tests for bidi override/isolate payloads, NUL/BEL/ESC, multiline input,
all-format input, legitimate RTL text, and truncation after sanitization.

**Verify**:
`./gradlew :app:testDebugUnitTest --tests '*VtTerminalEngineTest' --tests '*Notification*Test'`
→ exit 0.

### Step 2: Apply defense at both boundaries

Sanitize OSC 9 events before they enter the engine's notification queue, and
sanitize again before constructing the Android notification. An all-removed
message must use the existing generic fallback. Do not interpolate raw text into
logs, titles, intents, or accessibility descriptions.

Keep the existing per-accept notification count and length bounds.

**Verify**: parser tests prove raw controls never leave `VtTerminalEngine`; an
instrumentation test inspects `Notification.EXTRA_TEXT` and finds only expected
visible text.

### Step 3: Run emulator and repository gates

**Verify**:
`scripts/run-android-emulator-tests.sh --api 35 --suite boundary --test-filter com.yanjiyu.terminalspike.SessionNotificationActionTest`
→ zero failures and unexpected skips.

**Verify**:
`./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest`
→ `BUILD SUCCESSFUL`.

## Historical acceptance checklist

- [x] OSC 9 controls and Unicode format/bidi characters cannot reach system UI.
- [x] Legitimate visible international text remains intact.
- [x] Empty-after-sanitization input uses the generic fallback.
- [x] Parser, notification, lint, and build gates pass.

## Test plan

- Pure sanitizer: C0/C1 controls, ESC/BEL/NUL, bidi embeddings/overrides/isolate
  marks, zero-width format characters, whitespace, visible RTL, emoji, and bounds.
- Parser: hostile OSC 9 produces only sanitized one-shot event text.
- Android notification: both privacy-off text and privacy-on fallback are safe.

## Historical stop conditions

- The proposed sanitizer removes visible RTL letters rather than only controls.
- Sanitization would happen only after raw remote text is persisted or logged.

## Maintenance notes

Apply this sanitizer to any future remote-origin text that leaves the terminal
surface for notifications, widgets, shortcuts, or other trusted system UI.
