# Terminal smooth-scroll results

> Status: Acceptance invalidated on 2026-08-28 by the exact latest Wi-Fi-phone build. The
> benchmark results remain historical data, but the full-history product claim did not hold.

## Outcome

The native renderer was already capable of continuous local movement. In-app tmux attachment now
captures existing history over the authenticated side channel and seeds tmux's local alternate-
screen ring. The request adapts to history size and pane width, up to 200,000 rows while remaining
inside a strict 16 MiB transfer budget. In `Auto`, that history stays in the local viewport for drag
and fling, with no SSH writes or tmux redraw round trip. Ordinary shells and unrelated mouse-aware
applications keep their previous routing.

The capture is conditional on tmux's reported `history_size`. Alternate-screen applications such
as Codex may own substantial internal history while tmux itself has zero history. Those sessions
stay on remote mouse input, preventing one incidental redraw screen from becoming a false local
ceiling. A genuine tmux history hands back to remote input when its local edge is reached.

## 1. Cause of the line-by-line feeling

`Auto` previously equated negotiated mouse tracking with remote ownership. tmux commonly enables
mouse tracking, so each drag was accumulated to a row-sized threshold, encoded as wheel events,
sent over the active connection, and displayed only after tmux returned a new screen. The local
Canvas was smooth, but this default tmux path did not use it.

## 2. Changes

- Added `TerminalController.localScrollbackLineCount()` so routing can distinguish retained history
  from the live full-screen grid.
- Added a bounded, one-shot `capture-pane` snapshot for tmux sessions selected in the app. SSH and
  Mosh transfer it exactly once before terminal bytes are read; the controller stages it until tmux
  enters its alternate screen.
- Query `history_size` before capture and keep alternate-screen programs remote when tmux has no
  actual history, while allowing local-to-remote handoff at the edge of a real tmux snapshot.
- Changed `TerminalScrollGestureRouter` to prefer cached tmux history in `Auto` and latch the
  decision until the gesture ends. Other mouse-aware programs retain remote scrolling.
- Passed local-history availability from `FastTerminalView` for drag and fling.
- Added a bounded 256-row display-list cache for immutable scrollback lines. It replays retained
  Canvas commands during movement, invalidates on width/profile changes, and reuses evicted
  `Picture` objects instead of allocating through long scrollback.
- Kept explicit local/remote modes and the two-finger local override.
- Updated the Settings explanation and added controller/router regression tests.
- Corrected the `tmux-status` benchmark fixture so it contains a top-anchored alternate-screen pane
  scroll region, retained history, status repaint, and negotiated mouse tracking. Fixture loading is
  outside the measured block, leaving the two swipe journeys as the measured region.

## 3. Preserved modules and behavior

The SSH/Mosh transports, tmux session selector, terminal engine, terminal buffer, renderer, Canvas
drawing, `OverScroller`, Compose shell, key deck, software/hardware keyboard input, composer,
selection, copy/paste, links, reconnect, resize, alternate-screen handling, mouse protocol encoding,
and all existing buttons remain in place.

## 4. Before/after performance evidence

| Property | Before | After |
|---|---|---|
| Cached tmux drag destination in `Auto` | Remote wheel threshold, SSH write, remote redraw | Local `Float` viewport update |
| Visual granularity | Remote row/wheel response | Touch-distance pixels |
| Fling driver | Remote wheel sequence and redraws | Android `OverScroller` over local pixels |
| SSH writes for cached history | One or more bounded wheel reports after each threshold | Zero by construction |
| Renderer work | Visible rows remeasure/rerecord glyph runs every frame | Visible rows plus overscan; immutable rows replay bounded cached display lists |
| Compose recomposition per frame | None | None |
| Cache allocation behavior | None | At most 256 retained `Picture` objects; eviction reuses them |

The corrected `tmux-status` Macrobenchmark was run for three iterations on the same Gradle-managed
API 35 x86_64 Pixel 6 emulator (2 virtual CPU cores, unlocked clocks, emulator warning explicitly
suppressed for this local comparison). It is not representative physical-device evidence, but it
is useful as a same-environment directional comparison:

| CPU frame metric | Before display-list cache | After final reusable cache | Change |
|---|---:|---:|---:|
| P50 | 19.8 ms | 7.6 ms | 61.6% lower |
| P90 | 37.4 ms | 17.0 ms | 54.5% lower |
| P95 | 43.7 ms | 18.9 ms | 56.8% lower |

The final emulator frame-overrun distribution was P50 -7.9 ms, P90 3.0 ms, P95 3.4 ms, and P99
54.2 ms. That rare tail and emulator variance mean a physical 60/120 Hz run is still required before
shipping a device-level smoothness claim. Perfetto traces for all three iterations are retained in
the local benchmark build outputs.

## 5. Is scrolling genuinely local?

Yes for history already present in the primary or retained alternate-screen buffer. The selected
local branch has no call to `TerminalController.send`, `sendMouseWheel`, a connection, SSH, or tmux.
It updates `TerminalViewport.scrollY` and invalidates the native View.

## 6. Current limitations

- The initial snapshot is limited by the 200,000-row model ceiling, a 16 MiB transfer budget, the
  configured terminal profile (which may retain fewer rows), and tmux's own `history-limit`.
- tmux launched manually inside an ordinary shell is not known to the connection layer, so the
  one-shot pre-attachment snapshot applies to sessions chosen through the app's tmux selector.
- `OverScroller` exposes integer `currY` during inertial animation. Direct finger tracking retains
  floating-point pixels; fling remains pixel-based rather than line-based but is integer-pixel.
- Numeric jank, input-to-display latency, power, and thermal results need an authorized physical
  device, ideally at both 60 Hz and 120 Hz.

These limitations do not justify a renderer replacement or tmux control mode. The bounded snapshot
is fetched outside the gesture/render loop and remains ordinary immutable terminal history once
staged.

## 7. Further renderer work

None is currently justified. The renderer supports fractional drag coordinates, clipped overscan,
bounded and cached visible-row work, frame-coalesced invalidation, stable local scrollback, and
native fling physics. Ghostty, tmux control mode, and a new terminal engine remain out of scope.

## Verification record

- Focused unit tests for tmux capture/transfer, gesture routing, and controller workflows passed.
- Full multi-module unit tests: `test` passed.
- Full debug lint: `lintDebug` passed.
- Full debug build: `assembleDebug` passed.
- Corrected tmux-history Macrobenchmark: passed, 1 test / 3 measured iterations on the managed API
  35 emulator; final P50/P90/P95 CPU frame durations were 7.6/17.0/18.9 ms.
- Physical-device 60/120 Hz measurement: unavailable in the current environment.
- Finished adaptive-scroll debug APK installed successfully on Wi-Fi foldable `SM-F976B`; the
  explicit `MainActivity` launch succeeded and was verified as `topResumedActivity`. No tests ran
  on that phone.
