# Architecture

## Compose shell

`TerminalSpikeScreen` owns the scaffold, app bar, workload controls, diagnostic overlay, lifecycle forwarding, and extra-key toolbar. Its state is a small immutable `TerminalSpikeUiState`. The controller's diagnostic flow updates at low frequency. Terminal content and scrolling never travel through Compose state.

## Native renderer

`TerminalViewBridge` is the isolation boundary around `FastTerminalView`. The View draws only calculated visible rows plus two overscan rows using a hardware-accelerated `Canvas`, cached `Paint` metrics, and cached monospace typefaces. It owns touch scrolling, `OverScroller` flings, focus, cursor drawing, keyboard entry, and pixel scroll position through the retained viewport.

The View is intentionally not a `RecyclerView`, `SurfaceView`, `WebView`, Compose `Text`, or `LazyColumn`. No bitmap of the complete scrollback is retained.

## Terminal model

`TerminalBuffer` is an Android-independent 100,000-line ring. Appending over capacity replaces one slot and moves the head; it never copies the complete buffer. IDs are assigned monotonically and are not reused after clear. A `TerminalLine` contains sanitized Unicode and contiguous `TerminalRun` values. `TerminalStyle` already represents foreground, background, bold, italic, underline, and inverse attributes.

`TerminalViewport` is also platform-independent. It calculates pixel bounds and visible rows, switches auto-follow only at the exact bottom tolerance, and compensates the scroll coordinate when old stable line IDs are trimmed.

## Fake session and input

`FakeTerminalSession` implements `TerminalInputSink` and echoes an unobtrusive description of local input. `TerminalInputConnection` sends committed UTF-8, maps deletion to DEL, and handles common terminal keys. Hardware keys and the Compose extra-key toolbar enter through the same byte sink.

## Output batching

Generators run in structured ViewModel coroutines on `Dispatchers.Default`. `TerminalController` accepts batches into a bounded queue and applies at most one batch per Choreographer callback. A latest-value slot coalesces full-screen replacements. The View receives one content notification per applied frame batch and calls `postInvalidateOnAnimation()`.

## Deferred parser and transport

Phase 0 modifies the model directly so rendering costs can be measured independently. No parser, PTY, SSH transport, Mosh transport, host model, or credential design is implied. A later terminal engine should adapt parsed model changes to `TerminalRendererController`; a future connection sends incoming bytes to that engine and receives outgoing bytes through `TerminalInputSink`.

That boundary allows comparison with ConnectBot termlib and Termux terminal-view/emulator before selecting or building real VT/xterm semantics.
