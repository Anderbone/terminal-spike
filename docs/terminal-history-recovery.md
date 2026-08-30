# Terminal history recovery record

Date: 2026-08-28

## Renderer-cache rollback after device evidence

The Wi-Fi foldable showed the newest screen (`173` through `200`) while the UI had already left
auto-follow and offered `Jump to latest`. SSH also failed to expose the expected history. This
invalidated the synthetic buffer-only acceptance evidence and the experimental immutable-row
`Picture` cache as a safe baseline.

The renderer now draws every visible primary or alternate row directly from the controller again.
The cache benchmark remains historical only. Physical USB coverage now sends 200 separately
delivered numbered VT rows, waits for frame coalescing, performs real native swipe gestures, and
requires the first visible row to be `1. test text here`.

## Ordinary-shell one-screen history fix

Device feedback established a distinct failure mode: tmux retained long history but was not smooth,
while a direct shell was smooth but exposed only about one screen of history. The direct-shell
session was accepting a stored terminal-profile scrollback value of `0` and coercing it to a
capacity of `1` line.

Runtime scrollback resolution now treats a non-positive stored value as invalid or legacy data and
falls back to the canonical 20,000-line capacity. Positive values remain unchanged up to the model
limit. This repairs existing affected profiles without a destructive database migration or changing
the settings choices.

A newly connected direct-shell session is required because buffer capacity is fixed when the
terminal session is constructed. Tmux smoothness remains a separate follow-up after direct-shell
history is confirmed on the foldable.

## Decision

The adaptive tmux-history experiment is not an acceptable baseline. Restore the last simple,
bounded terminal-history path first, prove that full retained history is reachable on the target
phone, and only then improve native pixel scrolling without changing history ownership.

This is a recovery decision, not a renderer-replacement decision. Keep the project-owned VT
engine, `TerminalBuffer`, `TerminalViewport`, `FastTerminalView`, Canvas rendering, frame batching,
workload fixtures, and existing smoke/performance coverage.

## Device evidence

The Wi-Fi foldable is `SM-F976B`, exact ADB serial
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`.

On 2026-08-28 its installed main application was:

- package `com.yanjiyu.terminalspike`;
- debug build `versionName 0.0.2`, `versionCode 5`;
- installed at `2026-08-26 23:23:34`;
- APK SHA-256 `cdbba6ecc0e74d1c36866cb64121f5450875bae4f9311145c44dfb79077ec429`.

The latest local `app/build/outputs/apk/debug/app-debug.apk`, built at
`2026-08-26 23:22:42`, has the same SHA-256. The phone is therefore running the exact latest local
debug APK. The missing scrollback is not caused by an old installation.

No test, install, or launch was performed on the Wi-Fi phone during this check.

## What the current experiment changed

The experiment attempted to make managed tmux scrolling smooth and local by:

- querying tmux history and transferring a bounded `capture-pane` snapshot over an authenticated
  side channel;
- staging that snapshot in `LocalTmuxHistoryBuffer` and switching between live and history modes;
- replacing gesture-time remote wheel routing with local `TerminalViewport` drag and fling;
- adding a bounded 256-row `Picture` cache for immutable history rows;
- discarding a visible snapshot when returning to the live terminal and showing a new-output badge;
- expanding repository, selector, connection, benchmark, and regression coverage around capture.

The design intentionally did not merge live output into a captured snapshot. It also made the
snapshot available only to tmux sessions selected through the application.

## Why the current version fails the product requirement

Before the experiment, the controller retained bounded alternate-screen rows in its own history
buffer when the terminal profile allowed it. In the current experiment:

- alternate-screen completed rows are no longer added to the ordinary controller history;
- an alternate screen normally reports only the current screen row count;
- older managed-tmux content is reachable only through the separate capture result;
- a missed, empty, stale, rejected, truncated, or unavailable capture leaves only the live screen;
- manually entered tmux and other alternate-screen applications do not receive the managed capture.

This makes full retained history conditional on the new capture path. The Wi-Fi-phone result proves
that this condition is not reliably met in the real workflow. Unit tests and emulator frame metrics
recorded in `docs/terminal-smooth-scroll-results.md` did not establish the user-visible acceptance
criterion and must not be treated as evidence that full history works.

## Useful work to preserve as reference

The experiment still established several useful constraints:

- local drag already supports floating-point pixel offsets;
- native fling already uses `OverScroller` and does not require Compose state;
- rendering only visible rows plus overscan is the correct hot-path boundary;
- a bounded immutable-row display-list cache may be reconsidered independently after correctness;
- no capture, snapshot merge, network write, or remote wheel request belongs in `ACTION_MOVE`;
- performance evidence must use a workload whose complete history is first proven reachable.

These are lessons, not requirements to retain the failed capture architecture.

## Recovery sequence

1. Preserve this record and the earlier audit/results as historical evidence.
2. Remove the managed tmux capture/history-mode experiment without reverting unrelated application
   work in the dirty worktree.
3. Restore the original bounded primary and alternate-screen history ownership and the original
   explicit/automatic gesture routing behavior.
4. Keep the native renderer, pixel viewport, batching, bounded queues, fixtures, and tests.
5. Add or restore regression coverage that proves oldest, middle, and newest retained rows are
   reachable before measuring smoothness.
6. Run unit tests, lint, and a debug build.
7. Only after the complete feature is green, install and launch the latest debug APK on the exact
   Wi-Fi serial, without running tests there, and verify `MainActivity` is foreground.

## Acceptance gate before smoothness work

- A long ordinary shell transcript can scroll from the live bottom to the oldest retained row.
- A long alternate-screen/tmux transcript can scroll from the live bottom to the oldest retained
  row under the configured bounded-history policy.
- New output while scrolled back does not jump the viewport to the bottom.
- Returning to the bottom resumes follow mode without losing retained rows.
- History capacity and trim anchoring remain bounded and deterministic.
- No per-cell or per-line Compose state is introduced.

Only after all of these pass should display-list caching, fling tuning, or other smoothness work be
reintroduced one isolated change at a time.

## Recovery implementation and evidence

The failed managed-tmux capture/history mode was removed and the committed bounded primary and
alternate-screen history ownership plus original gesture routing were restored. A controller
regression gate now proves that the oldest, middle, and newest rows of both primary and alternate
retained history are reachable through `TerminalViewport`.

One independent smoothness change was then introduced: a bounded identity-keyed native `Picture`
cache for immutable assigned history rows. It does not change line retention, history capacity,
alternate-screen policy, viewport physics, mouse routing, terminal input, or Compose state. The
cache clears on width or renderer-profile changes and reuses evicted `Picture` objects. A same-device
USB benchmark increased the bound from 256 to 512 only after measuring both configurations:

| Cache | CPU P50 | CPU P90 | CPU P95 | CPU P99 |
|---|---:|---:|---:|---:|
| 256 history rows | 5.64 ms | 9.30 ms | 13.21 ms | 14.92 ms |
| 512 history rows | 4.87 ms | 6.85 ms | 8.07 ms | 9.48 ms |

Evidence collected on 2026-08-28:

- full multi-module unit tests passed;
- debug lint passed;
- debug build passed;
- focused USB terminal interaction suite passed, 7/7;
- a USB-only native gesture gate repeatedly dispatched real `MotionEvent` drags through
  `FastTerminalView`, reached viewport row zero, left follow mode, and verified `history-0` as the
  first visible retained row;
- physical USB macrobenchmark passed, 6/6 scenarios;
- the complete USB Android suite ran 293 tests and reported 13 failures in unrelated existing
  Compose navigation/data tests; no terminal history, viewport, native renderer, or cache test
  failed;
- no tests or incremental builds ran on the Wi-Fi foldable during these iterations.

Final acceptance still requires the user-visible long-history workflow on the completed Wi-Fi
build. Automated gates prove retained-row ownership and reachability but cannot prove the exact
remote server/tmux interaction without reproducing that user session.

## Final Wi-Fi handoff

The completed debug APK was installed once on Wi-Fi foldable `SM-F976B` using exact serial
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`. Installation returned `Success`; an explicit cold
launch returned `Status: ok`; Android reported `com.yanjiyu.terminalspike/.MainActivity` as both
`topResumedActivity` and `ResumedActivity`. The installed `base.apk` and local completed
`app-debug.apk` both have SHA-256
`34a891d7c2f9c3f96ad9ef7695683ccb4214f7d05af10a4c99db5ae93334431f`.

No device test or benchmark ran on the Wi-Fi foldable.

## 2026-08-30 inline Codex scrollback correction

The remaining direct-SSH failure was not caused by viewport capacity, zoom, gesture direction, or
tmux. Codex runs as an inline TUI and reserves bottom rows for its input and status UI. Its output
uses a primary-screen scroll region beginning at row zero but ending above those reserved rows. The
VT engine previously promoted removed primary rows to scrollback only when the scroll region filled
the entire screen. As a result, the final Codex viewport contained rows around 175 through 200 while
rows 1 through 174 never entered `TerminalBuffer`; dragging farther reached pre-Codex shell history.

`VtTerminalEngine.scrollUp` now promotes a removed row whenever the active scroll region begins at
row zero. This is the terminal-history boundary that matters: the removed row leaves the top of the
display. The bottom margin may legitimately exclude an inline application's input/status rows.
Regions beginning below row zero still do not become generic terminal history.

The regression gate has three independent layers:

- A parser test reproduces the actual Codex `CSI 1;19r`, row-19 output sequence and requires all 200
  numbered rows in order while rows 20 and 24 remain unchanged.
- A second parser test sends the same bytes one at a time and requires the identical history, so SSH
  or Mosh read boundaries cannot affect correctness.
- The opt-in USB E2E opens actual Codex through a production SSH session, waits for the real input
  box, asks Codex itself for `CODEX_SCROLL_001` through `CODEX_SCROLL_200`, checks every marker in
  order, proves row 001 is initially off-screen, then dispatches real native drag events until row
  001 is visible.

The green USB run on 2026-08-30 reported `lineCount=316`, `historyLines=286`, and an initial visible
Codex range of 175 through 200. After real drag events, the visible range included 001 through 025.
The same USB target also passed the real SSH plus tmux regression and the real Mosh extension 200-row
transport regression. Full unit tests, lint, and debug assembly completed successfully with 167
Gradle tasks.

Do not accept a future test that generates the numbered rows in the shell before Codex opens. That
proves ordinary shell scrollback only and does not exercise the partial-height primary scroll region
that caused this failure. See `terminal-codex-scrollback-regression.md` for the repeatable gate.
