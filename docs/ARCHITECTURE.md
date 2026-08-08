# Architecture

## Compose shell

`TerminalSpikeScreen` owns page-level navigation between the local workspace, terminal, and local-tools destinations, plus the SSH connection dialog, compact session chrome, workload controls, diagnostic overlay, lifecycle forwarding, and extra-key toolbar. `LocalWorkspaceScreen` presents device-local resources, while `LocalToolsScreen` keeps hosts, security, snippets, and terminal-key settings on a dedicated full-screen surface. The UI state remains a small immutable `TerminalSpikeUiState`, and the active controller's diagnostic flow updates at low frequency. Terminal content and scrolling never travel through Compose state. The Activity uses `adjustResize` and the terminal scaffold consumes IME insets so the complete terminal workspace moves above the software keyboard and restores when it closes.

## Native renderer

`TerminalViewBridge` is the isolation boundary around `FastTerminalView`. The View draws only calculated visible rows plus two overscan rows using a hardware-accelerated `Canvas`, cached `Paint` metrics, and cached monospace typefaces. It owns touch scrolling, `OverScroller` flings, focus, cursor drawing, keyboard entry, and pixel scroll position through the retained viewport. During IME animation it updates local geometry immediately but debounces the remote PTY resize until the dimensions settle.

The View is intentionally not a `RecyclerView`, `SurfaceView`, `WebView`, Compose `Text`, or `LazyColumn`. No bitmap of the complete scrollback is retained.

## Terminal model

`TerminalBuffer` is an Android-independent 100,000-line ring. Appending over capacity replaces one slot and moves the head; it never copies the complete buffer. IDs are assigned monotonically and are not reused after clear. A `TerminalLine` contains sanitized Unicode and contiguous `TerminalRun` values. `TerminalStyle` already represents foreground, background, bold, italic, underline, and inverse attributes.

`TerminalViewport` is also platform-independent. It calculates pixel bounds and visible rows, switches auto-follow only at the exact bottom tolerance, and compensates the scroll coordinate when old stable line IDs are trimmed.

## Input and sessions

Each visible tab selects one retained `TerminalController`. The benchmark controller keeps the original 100,000-line buffer and fake workload; every SSH runtime owns an isolated connection, decoder, controller, and bounded 20,000-line buffer. The UI permits at most four simultaneous SSH runtimes, and closing a tab cancels its job and connection. `TerminalInputConnection` sends committed UTF-8, maps deletion to DEL, and handles common terminal keys. Hardware keys and the Compose extra-key toolbar enter through the active controller's byte sink. Terminal size changes are derived from the native renderer's cached cell geometry and propagated to that connection without Compose terminal-content state.

## SSH transport

`Connection` is the transport-neutral lifecycle/input/resize boundary. Transport-specific configuration is owned by the implementation rather than exposed by the interface. `JschSshConnection` is the first implementation and runs blocking network reads away from the main thread. Outgoing keyboard data uses a bounded 256-item writer queue so a slow connection cannot block the UI or grow without limit. Sessions use connect/channel timeouts, SSH keepalives, a PTY, modern JSch algorithm defaults, and explicit password or private-key authentication. Password and passphrase byte arrays are zeroed after connection setup exits; an opt-in saved password is loaded only on the connection worker.

`VerifyingHostKeyRepository` never accepts a key silently. A new host pauses before authentication and exposes its SHA-256 fingerprint for explicit confirmation. Accepted keys are written atomically to an app-private OpenSSH-style `known_hosts` file. Sessions share one synchronized store for that canonical file so concurrent first-contact decisions cannot overwrite one another. A changed key of the same algorithm is a hard failure; trusted keys can be reviewed and deliberately removed from the Security tools.

`VtTerminalEngine` is an Android-independent, bounded screen engine selected by ADR-002. It incrementally decodes UTF-8 and owns primary/alternate cell grids, cursor and saved state, scroll margins, common CSI/DEC modes, SGR/256/true-colour styles, bracketed paste/application-cursor/focus/mouse modes, and bounded CSI/OSC recovery. Only immutable visible-row snapshots and completed scrollback lines cross into `TerminalController`; terminal cells never become Compose state. Primary and alternate history use separate bounded buffers so tmux-style output can be scrolled without polluting the shell transcript. Device/status queries return bounded response bytes through the active connection.

## Local encrypted tools

`SecureUserSettingsStore` persists host profiles, command snippets, and the ordered visible extra-key list in one small versioned binary document. Counts and strings are bounded before objects reach UI or input paths. AES-256-GCM uses a non-exportable app-specific Android Keystore key, authenticates a fixed format identifier as associated data, and wraps the ciphertext in a bounded envelope. `AtomicFile` provides replace-or-rollback writes. An authentication, key, format, or bounds failure discards the unreadable file and reports a warning rather than attempting partial recovery.

Host profiles contain label, host, port, username, and only a Boolean indicating whether a separate password ciphertext exists. With explicit opt-in, `SecureSshPasswordStore` encrypts each password under its own app-specific Keystore AES-256-GCM key and authenticates the profile ID, host, port, and username as associated data. Changing the endpoint or deleting/forgetting the profile deletes the secret. Imported private keys are validated, size-bounded, separately AES-GCM encrypted with another Android Keystore key, and stored in app-private files; only non-secret identity metadata enters `UserSettings`. Private-key passphrases remain temporary Compose input. Snippets are encrypted but are not treated as a safe place for credentials; the UI warns against secrets and requires an explicit Send action to a connected SSH tab. The manifest disables backup and the data-extraction rules exclude both cloud backup and device transfer.

Loading and writing run on `Dispatchers.IO`; writes are conflated to the latest complete immutable snapshot. Persistence state stays in the ViewModel and Compose tool chrome. It never enters `TerminalBuffer`, `TerminalViewport`, or the native renderer hot path.

## Output batching

Generators run in structured ViewModel coroutines on `Dispatchers.Default`. `TerminalController` accepts batches into a bounded queue and applies at most one batch per Choreographer callback. A latest-value slot coalesces full-screen replacements. The View receives one content notification per applied frame batch and calls `postInvalidateOnAnimation()`.

## Terminal engine decision

ADR-002 retains the project-owned custom renderer and selects the project-owned bounded VT engine. This avoids adding GPL/AGPL code to the commercial product path and keeps parser/model tests runnable on the JVM. The decision can be revisited with measured evidence and a separate licensing decision, but SSH and future transports now share the same screen contract.
