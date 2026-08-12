# Plan 003: Complete the terminal workflow without restoring cluttered chrome

> **Executor instructions**: Execute after Plans 001 and 002. Preserve the
> user's authoritative terminal layout: a short, horizontally scrollable tab
> strip and one fixed right-edge `+`; do not add Back, status badges, a large
> Connections button, or permanent action icons to that strip. Preserve the
> custom Canvas renderer and never add per-cell/per-line Compose state. Run all
> gates and update `plans/README.md`.
>
> **Drift check (run first)**: confirm Plans 001 and 002 are DONE, record the
> current HEAD plus `git diff --binary -- . ':(exclude)plans' | sha256sum` and
> `git status --short` as this plan's post-dependency baseline, then
> run `git diff --stat ff8435bc100f381f6925202259017e7834f7233d..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/terminal app/src/main/java/com/yanjiyu/terminalspike/ui app/src/main/java/com/yanjiyu/terminalspike/core/model`. If `SessionChrome`, `ExtraKeysBar`, `TerminalController`, or the renderer contracts differ from Current state, stop and report.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED
- **Depends on**: Plans 001 and 002
- **Category**: feature completion / accessibility / correctness
- **Planned at**: commit `ff8435bc100f381f6925202259017e7834f7233d`, 2026-08-09
- **Execution status**: DONE, 2026-08-09
- **Post-dependency baseline**: HEAD
  `ff8435bc100f381f6925202259017e7834f7233d`; tracked diff excluding
  `plans/` SHA-256
  `111e6c96c4a1e8e418de3e993d205f41b510a5e063c58d44d422c541251a728e`;
  `git status --short` SHA-256
  `a260de55d7579a10fa51c43692aee508964f8cd4ae82eaf495402df58768a0c0`.

## Why this matters

The terminal's primary surface now matches the user's compact phone layout, and
the default deck is already 18 keys in two rows of nine. Several important
behaviors nevertheless remain unreachable or inert: find, local clear,
transcript export, snippets, details, explicit input-mode switching, deck
collapse, bell policy, and local accessory actions. Locked modifiers are also
indistinguishable from one-shot state.

This plan completes those workflows using a tab context/bottom sheet and a
compact accessory control, not a return to the rejected large header.

## Current state

- `ui/SshConnectionBar.kt:62-131` renders the intended top strip: bounded short
  tabs plus fixed `+`. Double-click duplicates, and long-click currently jumps
  straight to a close/disconnect dialog.
- `ui/TerminalSpikeScreen.kt:557+` renders SessionChrome, custom terminal view,
  Jump to latest, and ExtraKeysBar; it has no session action coordinator.
- `terminal/TerminalController.kt:294-312` already offers safe local-scrollback
  clear and a bounded transcript snapshot.
- `terminal/TerminalTranscript.kt` already contains bounded text/find helpers.
- `terminal/view/FastTerminalView.kt` already owns native selection, OSC 8 link
  actions, exact cell geometry, and the Canvas hot path. Do not mirror rows or
  cells into Compose.
- `ui/ExtraKeysBar.kt:173-235` selects Raw/Text through pager swipes and anonymous
  dots only; no labeled click target exists.
- `ui/ExtraKeysBar.kt:424-540` receives only Ctrl/Alt armed booleans. ViewModel
  state already distinguishes `ctrlLocked`/`altLocked`.
- `core/model/ModelEnums.kt` models Shift, Paste, Snippets, Keyboard settings,
  and tmux prefix, while `TerminalDataRepository.kt:1260-1265` filters those
  local actions out of the runtime projection.
- `terminal/engine/VtTerminalEngine.kt:211-216` consumes BEL without publishing
  a user-visible event. Terminal profiles already persist `BellSettings`.
- Default `TerminalExtraKey.DEFAULT_ORDER` and shipped migration are 18 keys,
  with the phone layout targeting nine columns. Custom layouts are intentionally
  flexible; do not impose an 18-key invariant on them.
- Existing security boundaries centralize sensitive clipboard writes in
  `TerminalClipboardWriter` and multiline confirmation in `ExtraKeysBar`.

## Commands you will need

```bash
export JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk
```

| Purpose | Command | Expected on success |
|---|---|---|
| Terminal JVM | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.terminal.*' --tests 'com.yanjiyu.terminalspike.ui.*'` | exit 0; all selected tests pass |
| Android compile | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false :app:compileDebugAndroidTestKotlin` | exit 0 |
| Focused UI | run the block below | both new classes pass, or no-device skip is reported |
| Required full gate | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false test lint assembleDebug assembleRelease assembleDebugAndroidTest` | exit 0 |
| Static | `git diff --check` | no output |

Focused device command:

```bash
serial=$(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1; exit }')
if test -n "$serial"; then
  ANDROID_SERIAL="$serial" ./gradlew --no-daemon --max-workers=1 \
    :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.yanjiyu.terminalspike.TerminalSessionActionsTest,com.yanjiyu.terminalspike.TerminalAccessoryWorkflowTest
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

- `ui/SshConnectionBar.kt`, `TerminalSpikeScreen.kt`,
  `TerminalSpikeViewModel.kt`, and `ExtraKeysBar.kt`
- a new `ui/terminal/TerminalSessionActions.kt` (or equivalent bounded package)
- `terminal/TerminalController.kt`, `TerminalTranscript.kt`, and narrow renderer
  APIs needed for find highlighting or visual bell
- `terminal/engine/VtTerminalEngine.kt` and terminal frame/event models for BEL
- `terminal/view/TerminalExtraKey.kt`, `TerminalClipboardWriter.kt`, and existing
  accessory/selection bridge types
- `core/model` keyboard/bell profile models only where a missing runtime type is
  required
- Settings profile mapping for already-persisted bell/accessory fields
- localized strings and focused JVM/Compose/instrumentation tests

**Out of scope**:

- adding icons/buttons/status text to the top terminal strip
- replacing the custom terminal view with Compose rows/cells
- changing the default deck away from exactly 18 keys, nine per row
- forcing custom user decks to exactly 18 keys
- renderer micro-optimization without Plan 005 measurements
- new terminal fonts/custom-theme schema or third-party assets (Plan 005)
- host trust/KBI protocol changes (Plan 002)

## Git workflow

- Work with the dirty tree, patch narrow symbols, and re-read adjacent changes
  before each patch.
- Use conventional focused commits only if authorized, for example
  `feat: add terminal session action sheet`.
- Do not push or commit build outputs, transcripts, endpoints, or credentials.

## Steps

### Step 1: Turn tab long-press into the session action surface

Keep single-tap selection, double-tap duplication, tab width/ellipsis, and fixed
`+` unchanged. Change long-press on a remote tab to open a modal bottom sheet (or
accessible context menu) for that captured session ID. Include only actions
valid for current state:

- reconnect, duplicate, disconnect, and close;
- find;
- clear local scrollback;
- export transcript;
- enter/leave focus fullscreen;
- open Snippets;
- open the active terminal profile/keyboard Settings;
- show sanitized connection details.

Destructive close/clear/disconnect retain explicit confirmation. Capture the
target session ID when the sheet opens and reject actions if that session was
closed/replaced before confirmation. Never retarget to whichever tab later
becomes active.

**Verify**: compact Compose tests assert the top strip still contains only tabs
and `+`, long-press opens actions for the captured tab, switching active tabs
does not retarget, and stale targets perform no action.

### Step 2: Wire bounded find and local clear

Use `controller.transcriptSnapshot()` off the main thread and
`TerminalTranscript` for literal/case options. Add bounded query length/result
count, next/previous navigation, and a controller/view selection/highlight API
based on stable line IDs and cell ranges. Do not copy the transcript into
long-lived Compose state; keep only query, match references, index, and revision.

`Clear local scrollback` calls `clearLocalScrollback()` only after confirmation.
It must retain the live screen and remote transport and invalidate stale
selection/find references.

**Verify**: tests cover Unicode, wrapped lines, trimmed history, alternate screen,
new output invalidating revisions, zero results, navigation, and clear preserving
connection/live rows.

### Step 3: Add streaming SAF transcript export

Register a `CreateDocument` launcher at the Screen boundary with a text MIME and
safe suggested filename that contains no username/host/IP. The action captures a
bounded controller snapshot/revision, streams UTF-8 through the existing
transcript writer on IO, closes the descriptor on every path, and shows a
generic success/failure notice. Do not stage plaintext in shared storage and do
not request broad storage permission.

**Verify**: fake contract tests cover cancel, provider open failure, write
failure, truncation/bounds, UTF-8, close, and no endpoint in filename/notices.

### Step 4: Add explicit Raw/Text and deck collapse controls

Add a compact labeled Raw/Text segmented control or tappable mode label bound to
the existing pager. Swiping remains optional. The control must expose role,
selected state, and localized content descriptions.

Add a per-session runtime-only collapsed-deck state and a 44dp accessible handle.
Collapse reclaims the deck height without hiding the terminal or closing the
IME unexpectedly; Back still dismisses IME/composed state before navigation.
Restore each open tab's collapse choice while the session remains alive.

Keep default live layout exactly 9×2 at normal phone widths. At high font scale,
allow bounded label scaling and stable abbreviations with full spoken labels;
do not divide text size by `fontScale` to neutralize user settings.

**Verify**: Compose tests at 320dp/360dp, 2× font scale, landscape short height,
IME visible/hidden, and TalkBack semantics prove 9×2 default, explicit mode
selection, and collapse/expand without clipping.

### Step 5: Model modifier state explicitly and dispatch local actions

Replace armed Booleans at the accessory presentation boundary with
`Off`, `Armed`, and `Locked` for Ctrl/Alt (and Shift if modeled). Render Locked
with a distinct lock marker/color/stateDescription. Clear one-shot modifiers
only after the target input sink accepts bytes; retain locked modifiers until
explicitly toggled off.

Introduce an accessory action type that separates byte-emitting terminal keys
from local UI actions. Map persisted actions as follows:

- Paste: read the current clipboard only while foreground/focused, then use the
  existing bracketed-paste/multiline confirmation path;
- Snippets: open a session-targeted chooser, where Insert populates the buffered
  draft and Send obeys confirmation policy;
- Keyboard settings: navigate to the active keyboard profile without closing the
  session;
- tmux prefix: encode the validated configured chord;
- Shift: use xterm modifier parameter `2` for navigation keys (`Shift+Tab` =
  `ESC [ Z`; arrows = `CSI 1;2 A/B/C/D`; Home/End = `CSI 1;2 H/F`;
  Insert/Delete/PageUp/PageDown = `CSI 2;2~`, `3;2~`, `5;2~`, `6;2~`). For
  accessory printable ASCII, apply the explicit US-ASCII shift-pair table
  (`a`→`A`, `1`→`!`, `/`→`?`, and the other standard digit/punctuation pairs).
  Direct precomposed control chords such as `^C` ignore Shift. Unsupported
  combinations remain visible but disabled with an explanation rather than
  silently disappearing.

Preserve physical keyboard and direct terminal input paths.

Replace the Settings editor's step-only ordering with drag reordering backed by
a stable action ID and accessible custom actions “Move before”/“Move after”. Keep
the buttons as a keyboard/TalkBack fallback if useful; drag must never delete,
duplicate, or silently filter an action.

**Verify**: exact-byte tests cover accepted/rejected one-shot clearing, locked
state, tmux prefix, Shift navigation/printable cases, bracketed Paste, snippet
target capture, local actions emitting zero network bytes themselves, and drag/
accessible reorder preserving the exact action set.

### Step 6: Publish and apply bounded bell events

Extend terminal frame output with a bounded/coalesced bell event count or
sequence. Do not play sound or haptics in the parser. Carry the active terminal
profile's `BellSettings` into the repository-owned runtime and apply:

- visual bell as a short Canvas/overlay flash without content replacement;
- haptic bell only while appropriate foreground UI owns the session;
- audible bell through a lifecycle-owned platform audio primitive;
- per-session rate limiting/coalescing to prevent escape-sequence abuse: at most
  one presented bell per 250 ms and four per rolling second; coalesce additional
  BEL bytes into one pending event and drop it when the session closes;
- no notification, endpoint, or background wakeup unless separately enabled by
  an explicit policy.

**Verify**: parser tests cover BEL across chunks and floods; policy tests prove
each toggle, rate limiting, background suppression, session replacement, and no
event replay after Activity recreation.

### Step 7: Complete details, snippets, Settings, and focus-fullscreen routing

Connection details initially show only sanitized friendly name, protocol, state,
and safe profile references. A separate explicit “Show endpoint” action may
reveal selectable username/host/port inside that modal because the user requested
connection details; it resets to hidden when the modal closes and never writes
the endpoint to SavedState, logs, notices, recents, or clipboard without another
explicit Copy action. Never show secrets or startup commands.

Focus fullscreen is opt-in and reversible: it may hide the accessory deck and
tab strip temporarily, but normal terminal mode still shows the authoritative
tab strip. System Back exits focus mode before leaving the terminal. Route
Settings/Snippets back to the same live session/tab; transport remains owned by
the application repository.

**Verify**: navigation tests cover terminal → settings/snippets → same terminal,
focus mode Back/cancel/recreation, two simultaneous tabs, and unchanged buffers.

Add a small localized Terminal Settings help section with copyable tmux mouse
and history-limit snippets and an explicit statement that the app never mutates
remote configuration. Copy uses the same sensitive/token-scheduled clipboard
boundary as other terminal text.

## Test plan

- Extend `SessionDestructiveConfirmationTest`, `PasteModeDispatchTest`,
  `BufferedInputPagerTest`, `ExtraKeysBarShortcutTest`,
  `TerminalSpikeUiStateTest`, and terminal controller/engine tests.
- Add focused `TerminalSessionActionsTest`, transcript SAF contract tests, and
  bell policy tests.
- Test semantics rather than screenshots alone; no sleeps and no production
  endpoints.
- Keep the renderer hot-path tests and benchmark fixtures intact.

## Done criteria

- [x] Top terminal chrome still contains only short tabs and fixed `+`.
- [x] Long-press exposes every required session action without target races.
- [x] Find, local clear, and streaming SAF transcript export work and are bounded.
- [x] Raw/Text is explicitly operable; the deck can collapse; default remains
      exactly 18 keys at 9×2 on phone widths.
- [x] Off/Armed/Locked modifiers are visually and semantically distinct.
- [x] Paste, Snippets, Keyboard settings, Shift, and tmux prefix have truthful
      runtime behavior.
- [x] Keyboard actions support stable drag reorder plus accessible move actions,
      and tmux configuration guidance is localized/copyable.
- [x] Bell profile toggles affect live sessions with bounded rate and lifecycle.
- [x] Settings/snippet/focus-mode navigation preserves all open sessions/tabs.
- [x] Focused JVM, Android compile, focused device UI tests, and diff check pass.
- [x] Required full unit/lint/build gate passes; current APK is installed/launched
      on every authorized phone with foreground proof, or no-device skip is
      explicitly reported.

Completion evidence: the focused terminal/UI JVM suite and Android-test compile
passed; `TerminalSessionActionsTest` plus `TerminalAccessoryWorkflowTest` passed
5/5 on USB Android 16 `RZCW81JZ9CP`; the exact required full command `test lint
assembleDebug assembleRelease assembleDebugAndroidTest` passed 408 tasks after
697 app JVM tests. The accepted main and separate Mosh debug APKs installed on
the sole authorized target in the final fresh `adb devices -l` snapshot, and
`MainActivity` was both `topResumedActivity` and `mFocusedApp`. The previously
known Wi-Fi phone was not connected or advertised and therefore was not a
current authorized target for this gate.

## STOP conditions

Stop and report if:

- a proposed design adds any permanent item besides tabs and `+` to top chrome;
- find/selection would require per-line or per-cell Compose state;
- transcript export requires broad storage permission or plaintext staging;
- a local accessory action would be serialized as network bytes unintentionally;
- a bell implementation can wake/play in background without explicit policy;
- the 9×2 phone layout cannot meet the supported accessibility font scale after
  two design attempts—report the exact geometry instead of shrinking below
  accessibility minimums;
- a session action cannot capture a stable target ID across confirmation.

## Maintenance notes

- Future tab actions belong in the contextual sheet, not the top strip.
- Local accessory actions and byte keys must stay distinct in types so new UI
  actions cannot accidentally write to the transport.
- Dirty-row renderer optimization, bundled assets, custom themes, and broad
  device/performance evidence are deliberately handled by Plan 005.
