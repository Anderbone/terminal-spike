# Terminal scroll architecture

> Status: Superseded on 2026-08-28 by `terminal-history-recovery.md` after the exact latest
> Wi-Fi-phone build failed the full-history acceptance requirement. Retained as experiment history.

## Decision

Normal SSH and Mosh use one local terminal path:

```text
transport bytes -> VT engine -> TerminalBuffer -> TerminalViewport -> FastTerminalView
                                      ^                    |
                                      +---- pixel scroll ---+
```

Touch movement changes only `TerminalViewport.scrollY` and schedules a native View redraw. The hot
path has no history-source selection, remote wheel fallback, transport write, snapshot, merge,
deduplication, epoch, or stable-row reconciliation. Mosh retains only the scrollback its local VT
engine actually observes; the renderer does not claim to reconstruct framebuffer states Mosh never
delivered.

Alternate-screen redraw rows are not promoted into generic local scrollback. Full-screen programs
remain live terminal screens rather than synthetic history sources.

## Managed tmux exception

Only a tmux session selected through the app receives a separate local-history mode:

```text
LIVE
  -> drag toward older content beyond 8dp
  -> authenticated side-channel capture-pane
  -> immutable LocalTmuxHistoryBuffer
  -> TMUX_HISTORY

TMUX_HISTORY
  -> local float-pixel drag/fling only
  -> live output updates the hidden live terminal and sets one NEW OUTPUT flag
  -> reach the snapshot bottom or tap RETURN TO LIVE
  -> discard the snapshot
  -> LIVE
```

The capture contains tmux history and, for an alternate screen without application mouse tracking,
tmux's saved primary-screen grid. It is bounded by the configured local terminal capacity, 200,000
rows, and a 16 MiB side-channel transfer budget. Capture never runs in `ACTION_MOVE`, never writes a
wheel event to the pane, and never blocks the UI thread. Tmux copy mode or `mouse_any_flag` keeps
the gesture remote; a capture failure leaves the terminal in live mode.

The snapshot and live screen are mutually exclusive presentation modes. Live output is never
appended to the snapshot, and returning to live makes no attempt to fill, merge, or prove continuity
between the captured pane and the current pane.

## Renderer invariants

- `FastTerminalView` remains the only terminal renderer.
- `TerminalViewport.scrollY` remains a floating-point pixel coordinate driven by `OverScroller`.
- Terminal cells and lines never become Compose state.
- Ordinary touch scrolling performs no network write.
- Managed tmux capture runs through the existing authenticated SSH side channel.
- At most one capture is in flight for a controller.
- A late capture result is rejected after disconnect, transport replacement, mode cancellation, or
  session switch.
- Live output causes at most one badge invalidation while an immutable snapshot is visible.
