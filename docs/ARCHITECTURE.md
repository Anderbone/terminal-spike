# Architecture

## Compose shell

`TerminalSpikeScreen` owns shell routing between exactly Connections, Terminal, and Settings. Connections is the main catalogue for saved hosts, keys, and snippets; Terminal owns active sessions. The Settings destination contains the terminal, keyboard, session, security, backup, Mosh, and About/notices categories. The About surface reads the same canonical third-party notice file that the build packages into each APK's assets. Product-authored UI copy crosses presentation boundaries as resource-backed `UiText`, and Settings landing summaries are projected from committed `SettingsUiState`. Terminal content and scrolling never travel through Compose state. The Activity uses `adjustResize` and the terminal scaffold consumes IME insets so the complete terminal workspace moves above the software keyboard and restores when it closes.

## Native renderer

`TerminalViewBridge` is the isolation boundary around `FastTerminalView`. The View draws only calculated visible rows plus two overscan rows using a hardware-accelerated `Canvas`, cached `Paint` metrics, and cached monospace typefaces. It owns touch scrolling, `OverScroller` flings, focus, cursor drawing, keyboard entry, and pixel scroll position through the retained viewport. Engine dirty rows are coalesced through `TerminalController` and invalidate exact visible row rectangles; viewport, selection, cursor-geometry, theme, typeface, resize, alternate-screen, and trim changes still force a full invalidation. During IME animation the View updates local geometry immediately but debounces the remote PTY resize until the dimensions settle.

The View is intentionally not a `RecyclerView`, `SurfaceView`, `WebView`, Compose `Text`, or `LazyColumn`. No bitmap of the complete scrollback is retained.

Touch routing is profile-driven and latched per gesture. In Auto, a confirmed app-selected tmux
session reserves classified vertical scrolling for Android's local pixel viewport; pending history
buffers that gesture instead of falling back to a remote wheel. A pre-existing tmux copy mode is
left active but is neither driven nor exited. Explicit Remote mouse is the intentional route to a
mouse-aware inner application or tmux copy mode, and its optional two-finger override remains
local. Taps, long-press selection, and link actions do not enter the vertical-scroll router.

## Terminal model

`TerminalBuffer` is an Android-independent bounded ring: the debug benchmark uses 100,000 lines and remote runtimes use 20,000. Appending over capacity replaces one slot and moves the head; it never copies the complete buffer. IDs are assigned monotonically and are not reused after clear. A `TerminalLine` contains sanitized Unicode and contiguous `TerminalRun` values. `TerminalStyle` represents foreground, background, bold, italic, underline, and inverse attributes.

`TerminalViewport` is also platform-independent. It calculates pixel bounds and visible rows, switches auto-follow only at the exact bottom tolerance, and compensates the scroll coordinate when old stable line IDs are trimmed.

## Input and sessions

`AppContainer.sshSessionRepository` is the application-process owner of every live SSH and Mosh connection, parser, controller, retry job, and bounded 20,000-line remote buffer. Activities and ViewModels observe immutable snapshots and issue commands; their lifecycle does not own a transport. A visible user action must promote `SessionForegroundService` before transport startup. The service observes the same repository for the ongoing privacy-aware notification, CPU-awake policy, actions, and fail-closed service loss. Release UI hides the debug-only 100,000-line benchmark workload and shows an explicit no-session state instead. The app does not impose a fixed remote-runtime count; transports may still enforce their own advertised capacity, including the separately installed Mosh extension.

`TerminalInputConnection` sends committed UTF-8 immediately, maps deletion to DEL, handles common terminal keys, and advertises a direct-input editor with correction and personalized learning disabled. A two-page Compose strip keeps the shortcut deck as the default and offers a separate bounded `TextFieldValue` editor with ordinary IME composition, correction, and selection. Its explicit Send action uses the active controller's bracketed-paste-aware path without adding Enter, requires an extra confirmation for multiline drafts, clears only after dispatch, and hands focus back to the native View when the user returns to direct input. Hardware keys and the Compose extra-key toolbar also enter through the active controller's byte sink. Terminal size changes are derived from cached native-renderer cell geometry and propagated without Compose terminal-content state.

Direct input also advertises bounded PNG, JPEG, WebP, and GIF rich content. An accepted phone clipboard image never enters the renderer or terminal byte stream: a short-lived SFTP channel on the exact authenticated SSH session streams at most 20 MiB into `~/.cache/terminal-spike/pasted-images`, then the controller bracket-pastes the absolute remote path. Codex consumes that pasted path as its normal `[Image #N]` attachment. A Mosh connection retains its already authenticated SSH bootstrap transport as this private upload side channel for the Mosh session lifetime; terminal bytes continue to use Mosh. Local benchmark sessions reject image paste.

## SSH transport

`Connection` is the transport-neutral lifecycle/input/resize boundary. Transport-specific configuration is owned by the implementation rather than exposed by the interface. `JschSshConnection` is the first implementation and runs blocking network reads away from the main thread. Outgoing keyboard data uses a bounded 256-item single-writer queue so a slow connection cannot block the UI or grow without limit. Result-bearing sends reject a full or stopped queue; legacy direct sends turn rejection into one terminal failure state instead of silently dropping bytes. Writer, reader, EOF, connect, and explicit-close races publish exactly one terminal state. A snippet plus its optional Enter is offered as one batch, so it cannot be split by queue pressure. Sessions use connect/channel timeouts, SSH keepalives, and the selected/default profile's validated `TERM` value for PTY negotiation. A saved startup command is user-authored shell input: it is UTF-8 encoded and queued once, with one terminal Enter, only after each fresh interactive shell reaches Connected. Reconnect and duplicate each create a fresh shell and therefore dispatch their own one-shot input; Test Connection opens the selected PTY but explicitly does not run saved startup input. Password, passphrase, keyboard-interactive response, and queued input byte arrays stay within their bounded owners and are wiped on terminal paths.

For Mosh, terminal bytes and lifecycle events arrive on separate PFD and Binder paths. After a
terminal-pipe EOF, the connection waits at most one second for an already-concurrent authoritative
terminal callback so isolated-worker or broker death cannot be mislabeled as a clean disconnect.
An eventless EOF still resolves to `Disconnected`; explicit close remains immediate and idempotent.

JSch resolves standard authentication, key-exchange, cipher, MAC, hash, signature, compression, and key-decoding implementations from class-name strings. Release R8 rules preserve the supported standard-JCA implementation seam while leaving unbundled Bouncy Castle and GSSAPI integrations shrinkable. The release packaging verifier derives the covered classes from the resolved pinned JSch JAR, requires every one to retain its original mapping name, and verifies the canonical legal notice asset byte-for-byte before `assembleRelease` succeeds.

`KnownHostManager` never accepts a key silently. A new host pauses before authentication and exposes its SHA-256 fingerprint for Reject, Trust once, or Trust and save. Saved trust is committed through the process-singleton Room store, and the post-confirmation transaction rechecks host identity so concurrent first-contact decisions cannot overwrite one another. Any non-matching key for a known host—including an algorithm change—is a hard failure; replacement or removal is a separate confirmed Security action. The retained OpenSSH-style file is a legacy migration input, not the production trust authority.

`VtTerminalEngine` is an Android-independent, bounded screen engine selected by ADR-002. It incrementally decodes UTF-8 and owns primary/alternate cell grids, cursor and saved state, scroll margins, common CSI/DEC modes, SGR/256/true-colour styles, bracketed paste/application-cursor/focus/mouse modes, and bounded CSI/OSC recovery. Only immutable visible-row snapshots and completed scrollback lines cross into `TerminalController`; terminal cells never become Compose state. Primary and alternate history use separate bounded buffers so tmux-style output can be scrolled without polluting the shell transcript. Device/status queries return bounded response bytes through the active connection.

## Local data and encryption

Room schema v3 is the relational authority for host, credential metadata, key identity, known-host, snippet, terminal/custom-theme, keyboard, and recent-session data. Proto DataStore owns typed global preferences. `AppContainer` provides one process-wide database, repository set, authority gate, and application I/O scope; startup migration and destructive backup recovery must resolve before catalog mutation. Legacy encrypted settings, password, private-key, and known-host files are retained as read-only migration/recovery inputs and cannot silently repopulate authoritative Room state.

`AesGcmCredentialStore` encrypts saved passwords, private-key payloads, and explicitly saved key passphrases with non-exportable app-specific Android Keystore AES-256-GCM keys and immutable UUID/kind associated data. Room contains authenticated ciphertext plus non-secret metadata, never plaintext. Reads return bounded mutable bytes to narrowly scoped transport or backup operations and wipe them on terminal paths. Missing, invalidated, corrupt, or unavailable keys preserve ciphertext and expose typed recovery; clearing credentials or all app data is separately confirmed. Imported fonts live in bounded app-private storage, while portable encrypted backup uses repository snapshots rather than copying Room, DataStore, or Keystore files directly.

Disk, crypto, migration, backup, and transport work run away from the main thread. Presentation state never enters `TerminalBuffer`, `TerminalViewport`, or the native renderer hot path.

## Output batching

Transport parsers publish bounded frames into `TerminalController`, which accepts batches into a bounded queue and applies at most one batch per Choreographer callback. A latest-value slot coalesces full-screen replacements; sorted dirty-row unions survive coalescing for precise native invalidation. Debug-only workload generators use structured coroutines and are not reachable from release UI.

## Terminal engine decision

ADR-002 retains the project-owned custom renderer and selects the project-owned bounded VT engine. This avoids adding GPL/AGPL code to the commercial product path and keeps parser/model tests runnable on the JVM. The decision can be revisited with measured evidence and a separate licensing decision, but SSH and future transports now share the same screen contract.
