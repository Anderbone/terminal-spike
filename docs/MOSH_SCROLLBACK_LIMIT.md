# Direct Mosh scrollback limit

> 2026-08-30 clarification: the application VT engine now correctly retains rows removed from a
> top-anchored primary scroll region even when an inline TUI reserves bottom input/status rows. This
> fixes the parser-side Codex failure for every transport frame that contains those scroll events.
> It does not change the separate Mosh protocol limitation described below: rows skipped entirely by
> Mosh state synchronization cannot be reconstructed by the Android parser.

## Current result

The 2026-08-28 Wi-Fi foldable test emitted 200 numbered rows in a fresh direct Mosh
session without tmux. Only the final visible rows, approximately 175 through 200,
were available. Increasing the Kotlin terminal buffer to 20,000 rows and removing
the experimental renderer cache did not recover rows 1 through 174.

This is not a viewport-capacity failure. The native Mosh client reads
`get_latest_remote_state().state.get_fb()` and passes that fixed-size framebuffer to
`Display::new_frame()`. Mosh's state-synchronization protocol is allowed to replace
intermediate remote states with the newest screen. If a 200-row burst completes
between transmitted states, the discarded rows never enter the native output pipe,
the Kotlin VT engine, or `TerminalBuffer`.

The Android Mosh display initializer already sets `smcup` and `rmcup` to null, so an
accidental alternate-screen transition is not the cause. Comparing successive
Kotlin frames could preserve rows from observed screen shifts, but it cannot restore
intermediate rows that Mosh never delivered. Such inference would also risk false
history during ordinary full-screen redraws.

This behavior matches upstream Mosh's long-standing scrollback limitation. Upstream
issue 122 remains the canonical report:

https://github.com/mobile-shell/mosh/issues/122

## Supported full-history baseline

- Direct SSH without tmux: the remote PTY byte stream reaches `VtTerminalEngine`, so
  completed rows can be retained in the configured 20,000-line local buffer.
- Mosh with tmux: tmux owns history on the remote host, so Mosh only needs to
  synchronize the currently requested tmux viewport.
- Direct Mosh without tmux: smooth and roaming-friendly, but it cannot guarantee
  complete history for fast output bursts with the standard Mosh 1.4.0 protocol.

## Regression proof

`SshRealEndToEndTest` now sends exactly 200 numbered lines through a real SSH PTY.
It parses the actual transport chunks with `VtTerminalEngine` and requires row 1 to
be present in completed scrollback and row 200 to remain in the retained frame. The
native interaction suite separately performs real swipe gestures over a 200-row
controller workload and requires the viewport to reach row 1.

Do not treat a synthetic 20,000-line buffer test alone as proof of remote history.
The transport, VT parser, terminal buffer, and touch viewport must each be covered.
