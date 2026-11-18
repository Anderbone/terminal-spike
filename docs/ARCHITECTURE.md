# Architecture

> The separate-APK packaging described in historical sections is superseded by
> [ADR-005](ADR-005-BUNDLED-MOSH.md): one app now includes the private Mosh broker and workers.

## Compose shell

`TerminalSpikeScreen` owns shell routing between exactly Connections, Terminal, and Settings. Connections is the main catalogue for saved hosts, keys, and snippets; Terminal owns active sessions. The Settings destination contains the terminal, keyboard, session, security, backup, Mosh, and About/notices categories. The About surface reads the same canonical third-party notice file that the build packages into each APK's assets. Product-authored UI copy crosses presentation boundaries as resource-backed `UiText`, and Settings landing summaries are projected from committed `SettingsUiState`. Terminal content and scrolling never travel through Compose state. The Activity uses `adjustResize` and the terminal scaffold consumes IME insets so the complete terminal workspace moves above the software keyboard and restores when it closes.

Settings separates Activity/VM wiring (`SettingsDestination.kt`) from navigation and category
routing (`SettingsScreen.kt`). Appearance, terminal, keyboard, background, and security pages live
in their own files and reuse module-internal controls in `SettingsComponents.kt`; search and its
language menu remain in `SettingsSearchUi.kt`. These are application UI boundaries and do not
change terminal rendering or transport ownership.

Snippet component and Connections screen tests use `IsolatedPreferencesRule` to redirect toolbar or connection-order preference lookups into a UUID-backed fixture. The rule wraps the Compose lifetime, flushes pending writes after disposal, and deletes that fixture. These tests do not clear the application's real toolbar or connection-order preferences.

Snippet editing and key renaming freeze every draft-changing control while persistence is pending. A successful save dismisses the dialog; a failed save keeps the submitted draft and restores editing for retry.

Nearby SSH discovery retires each search or resolution callback generation when it ends, before stopping the platform boundary. Callbacks whose generation is already retired are ignored. Controller tests cover stable timeout, failure, dismissed-picker and selected-endpoint state. The main-thread-owned Android adapter dispatches every framework discovery and resolution callback through its main executor, then checks listener identity at execution time. Retired listeners, queued callbacks after stop/restart/close, and duplicate resolution completions cannot update its cache or notify a retired owner. A synchronous platform exception retires the partially started listener before propagating the error. Its service cache shares the controller's 32-candidate limit, with normalized SSH service-type identity; AndroidNsdPlatform contains only SDK-specific calls. Instrumented boundary tests use real framework service-info objects and the main Looper with recorded platform calls; they do not exercise actual mDNS browsing or the API 37 picker. An in-app search can retain its final results for explicit selection; duplicate selection while resolving and selection after completion are ignored. Starting another search retires the previous resolution and requests boundary cancellation; the adapter path below API 34 retires its listener without relying on a platform resolution-cancellation method or extension version. The system picker still resolves its first selected service automatically.

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

Before terminal-program messages or task titles enter Android notifications,
`sanitizeUntrustedDisplayText` removes controls and Unicode format characters by code point,
collapses whitespace, and bounds the output while constructing it. Its UTF-16 limit preserves
complete surrogate pairs; malformed surrogate code units become replacement characters. This
normalization does not alter renderer text, selection contents, or terminal byte flow.

Optional terminal clipboard clearing uses an opaque, app-assigned token, so an older copy cannot
clear a newer identical value. `TerminalClipboardClearScheduler` validates its generation after
dispatching to the main thread and serializes that check, the exact-token platform operation and
consumption. Disabling/changing the delay, replacing a scheduled write, or closing the scheduler
invalidates queued clears. An unavailable foreground owner retains the expired token for a later
resumed/focused Activity; completed or superseded tokens are consumed once.

## Input and sessions

`AppContainer.sshSessionRepository` is the application-process owner of every live SSH and Mosh connection, parser, controller, retry job, and bounded 20,000-line remote buffer. Activities and ViewModels observe immutable snapshots and issue commands; their lifecycle does not own a transport. A visible user action must promote `SessionForegroundService` before transport startup. The service observes the same repository for the ongoing privacy-aware notification, CPU-awake policy, actions, and fail-closed service loss. Release UI hides the debug-only 100,000-line benchmark workload and shows an explicit no-session state instead. The app does not impose a fixed remote-runtime count; transports may still enforce their own advertised capacity, including the built-in Mosh broker.

`TerminalInputConnection` sends committed UTF-8 immediately, maps deletion to DEL, handles common terminal keys, and advertises a direct-input visible-password editor with suggestions disabled. A two-page Compose strip keeps the shortcut deck as the default and offers a separate bounded `TextFieldValue` editor with ordinary IME composition, correction, and selection. Its explicit Send action uses the active controller's bracketed-paste-aware path without adding Enter, requires an extra confirmation for multiline drafts, clears only after dispatch, and hands focus back to the native View when the user returns to direct input. Hardware keys and the Compose extra-key toolbar also enter through the active controller's byte sink. Terminal size changes are derived from cached native-renderer cell geometry and propagated without Compose terminal-content state.

Accessory byte dispatches own their returned arrays: the ViewModel wipes them after sending,
including rejected sends. Shift+Tab and configured control prefixes therefore copy shared key
constants before dispatch. This ownership rule does not change the chord syntax or terminal
byte sequences. `BufferedInputDraftState.kt` owns the existing draft, recovery copy, persistence
callback and send-eligibility helpers; `ExtraKeysBar.kt` handles their UI composition.

IME-sent Ctrl/Alt/Meta key-down events cancel staged composition through the same boundary as native hardware input. A late commit of that cancelled value is suppressed, while a new composing value is accepted normally. Key-up events leave pending composition intact.

Both direct and buffered input advertise `*/*` rich content. The image button opens the system photo picker showing recent images and videos by default, without the old app-defined 20-item cap; the system photo picker may impose its own selection limit. Long-pressing that same button opens Android's multiple-document picker with `*/*` for arbitrary files, without an app-defined selection count, file-size or format limit. Picker files, clipboard URI items (including videos and documents), and IME rich content share the same streaming pipeline. Up to four IO workers transfer files concurrently without loading whole files into memory, and paths are inserted in selection order. Failed files do not discard successful files; temporary IME grants are released on completion or cancellation. SSH and Mosh use independent SFTP channels on the authenticated SSH transport and store files in the host's private `~/.cache/terminal-spike/pasted-images` directory. Mosh can reopen its upload side channel with reloadable credentials if the bootstrap TCP connection has expired. Local Arch imports files into `/root/.cache/terminal-spike/pasted-images` under an installation lease; the local renderer demo stores files in app-private storage and inserts that path (it has no shell to consume attachments). File bytes never enter the renderer or terminal byte stream; the receiving tool decides what a pasted path means. System document-provider access, available storage and receiving-tool limits still apply. Buffered drafts and text clipboard paste have no app-defined character cap; local PTY writes split large pastes into native-sized frames.

`transferTerminalAttachments` alone closes each original provider stream. Its cancellation-aware
wrapper is a non-closing loan to nested session/SFTP transports, so their `use` blocks cannot
close the provider prematurely or repeatedly. Healthy and freshly authenticated Mosh uploads use
the same borrowed-stream boundary. Success, failure, and cancellation release the provider and
its temporary grant at the outer pipeline boundary.

## File browser

`SftpSessionController` owns an independent SFTP client and publishes immutable browsing state.
Blocking directory listings always run on its injected operation context, including the refresh
following an upload initiated by the UI. Transfer completion, failure, and cancellation release the
busy state only while the same client and generation still own the operation. A cancelled older
transfer cannot overwrite a newly connected host or reopen a closed file browser.
Multi-file upload metadata is also read inside this transfer boundary on the operation context:
provider failures become browsing errors, and cancellation releases busy state without becoming
a reported transfer failure.

## SSH transport

`TmuxCommandRunner.kt` owns the JSch exec adapter, limits and stream handling; the selector keeps catalog, startup and capture policy. Its monotonic elapsed-time budget covers setup, connection and reads, including discarded stderr, and checks interruption between reads. Connect and poll waits are capped by the remaining budget with millisecond rounding; a sub-millisecond remainder never becomes JSch's unlimited zero timeout. The stdout caps remain 64 KiB for ordinary commands and 16 MiB for history capture, with unchanged 5/30-second total limits; stderr does not consume the retained stdout quota. Partial stream-acquisition failures close acquired streams and disconnect the owned channel. Scratch bytes are wiped, and a disconnect failure is suppressed beneath the original execution failure. Successful stdout bytes, exit status, capture commands, history ownership and gesture routing are unchanged. These are transport resource/deadline checks, not new physical tmux/Codex scroll acceptance.

`RemoteSessionModels.kt` holds the existing request, snapshot, foreground and result contracts.
`RemoteSessionReliability.kt` owns the pure reconnect policy: each online attempt doubles the
delay exactly until its configured ceiling, including odd ceilings and the full positive Long
range. It saturates before multiplication can overflow and stops doubling at the ceiling;
offline waits and intentional disconnects retain their existing retry-budget behavior.

`RemoteSessionMetadata.kt` reuses the code-point-aware display sanitizer for session labels and
terminal titles before publishing snapshots or Recent records. Labels retain their 96-unit
UTF-16 limit and control-removal policy; titles retain their 128-unit limit and whitespace
normalization. Both preserve complete surrogate pairs and remove supplementary format
characters. The existing hostname/username title checks run after this normalization, so a
format character cannot split a matching endpoint fragment. Renderer content and transport
bytes do not pass through these metadata helpers.

`Connection` is the transport-neutral lifecycle/input/resize boundary. Transport-specific configuration is owned by the implementation rather than exposed by the interface. `JschSshConnection` is the first implementation and runs blocking network reads away from the main thread. Outgoing keyboard data uses a bounded 256-item single-writer queue so a slow connection cannot block the UI or grow without limit. Result-bearing sends reject a full or stopped queue; legacy direct sends turn rejection into one terminal failure state instead of silently dropping bytes. Writer, reader, EOF, connect, and explicit-close races publish exactly one terminal state. A snippet plus its optional Enter is offered as one batch, so it cannot be split by queue pressure. Sessions use connect/channel timeouts, SSH keepalives, and the selected/default profile's validated `TERM` value for PTY negotiation. A saved startup command is user-authored shell input: it is UTF-8 encoded and queued once, with one terminal Enter, only after each fresh interactive shell reaches Connected. Reconnect and duplicate each create a fresh shell and therefore dispatch their own one-shot input; Test Connection opens the selected PTY but explicitly does not run saved startup input. Password, passphrase, keyboard-interactive response, and queued input byte arrays stay within their bounded owners and are wiped on terminal paths.

For Mosh, terminal bytes and lifecycle events arrive on separate PFD and Binder paths. After a
terminal-pipe EOF, the connection waits at most one second for an already-concurrent authoritative
terminal callback so isolated-worker or broker death cannot be mislabeled as a clean disconnect.
An eventless EOF still resolves to `Disconnected`; explicit close remains immediate and idempotent.

`AndroidMoshExtensionClient.startSession` owns the transferred key descriptor from method entry,
even if cancellation prevents IO dispatch. It closes the key before returning from IO. If prompt
cancellation discards a successful handle on the caller's dispatcher, the client closes both terminal
pipes and stops only that session under non-cancellable cleanup; delivered handles remain caller-owned.
Cancelling an accepted service binding while awaiting its callback unbinds that candidate so a later
connect can acquire a fresh binding.
The outer `AndroidMoshConnectionExtension` applies the same ownership boundary to its session-key
array and constructed terminal transport: it wipes keys even before IO dispatch and closes/stops a
transport discarded on return. Cleanup never shuts down the process-wide client or another session.

Each native worker owns transferred descriptors from admission through one identity-checked
finalizer. Stops, callback death, destruction and rejected scheduling dispose queued sessions,
unlink their callback and prevent old tasks from completing a replacement session. The default
executor has one worker and one waiting slot; cancellation removes its queued task before the
slot can be reused. Once native work starts, Java retains its descriptors until JNI returns.
JNI rechecks the worker's cancellation flag after registering native control, covering stops that
arrive before registration; a failing check clears its exception and releases native control.

JSch resolves standard authentication, key-exchange, cipher, MAC, hash, signature, compression, and key-decoding implementations from class-name strings. Release R8 rules preserve the supported standard-JCA implementation seam while leaving unbundled Bouncy Castle and GSSAPI integrations shrinkable. The release packaging verifier derives the covered classes from the resolved pinned JSch JAR, requires every one to retain its original mapping name, and verifies the canonical legal notice asset byte-for-byte before `assembleRelease` succeeds.

`KnownHostManager` never accepts a key silently. A new host pauses before authentication and exposes its SHA-256 fingerprint for Reject, Trust once, or Trust and save. Saved trust is committed through the process-singleton Room store, and the post-confirmation transaction rechecks host identity so concurrent first-contact decisions cannot overwrite one another. Any non-matching key for a known host—including an algorithm change—is a hard failure; replacement or removal is a separate confirmed Security action. The retained OpenSSH-style file is a legacy migration input, not the production trust authority.

File-backed trust writers and adapters live only in `app/src/sharedTest/java`, shared by JVM and Android transport tests through `fileKnownHostManager`. SSH and SFTP transport constructors take the assembled trust manager rather than a file supplier. The retained-file migration reader and parser remain in production.

`VtTerminalEngine` is an Android-independent, bounded screen engine selected by ADR-002. It incrementally decodes UTF-8 and owns primary/alternate cell grids, cursor and saved state, scroll margins, common CSI/DEC modes, SGR/256/true-colour styles, bracketed paste/application-cursor/focus/mouse modes, and bounded CSI/OSC recovery. Only immutable visible-row snapshots and completed scrollback lines cross into `TerminalController`; terminal cells never become Compose state. Primary and alternate history use separate bounded buffers so tmux-style output can be scrolled without polluting the shell transcript. Device/status queries return bounded response bytes through the active connection.

Each parser cell retains at most 4,096 UTF-16 units, including its base character. Crossing that limit replaces the entire cell with U+FFFD, padded with one space when the cell occupies two columns. The discarded cell text is no longer available for copying or history. Smaller cells remain exact, including complete surrogate pairs. A constant-size logical tail retains ZWJ and regional-indicator pairing state independently of the replacement, so discarded text cannot swallow following ordinary cells or change their columns. Further discarded tail updates do not invalidate an unchanged row snapshot. This per-cell storage/work bound is separate from row-count limits and the renderer's smaller shaping limit; it is not a global heap or end-to-end performance guarantee.

Completed OSC payloads are validated by Unicode code point before dispatch, after their
bounded incremental parse. Controls, format characters (including supplementary-plane
characters), replacement characters and malformed UTF-16 are rejected as a whole; visible
international text remains intact. `TerminalLinkPolicy` reuses that validation before its
existing whitespace, absolute-host and scheme checks. Display normalization remains a
separate policy; renderer text and the OSC 52 decoded-clipboard rules are unchanged.

## Local data and encryption

Room schema v6 is the relational authority for host, credential metadata, key identity, known-host, snippet, terminal/custom-theme, keyboard, and recent-session data. Proto DataStore owns typed global preferences. `AppContainer` provides one process-wide database, repository set, authority gate, and application I/O scope; startup migration and destructive backup recovery must resolve before catalog mutation. Legacy encrypted settings, password, private-key, and known-host files are retained as read-only migration/recovery inputs and cannot silently repopulate authoritative Room state.

`SettingsViewModel` routes global preferences, terminal/keyboard profiles, custom themes, imported
fonts and its one-time shipped-keyboard repair through the process-singleton
`SettingsMutationCoordinator`. It awaits recovery and migration readiness and holds the same
`AuthoritativeDataGate` as backup apply/rollback. A successful first durable write advances the
catalog generation even if a later store fails; subsequent writes in that operation share that
generation. Each started write finishes commit bookkeeping despite UI cancellation, while a
cancelled operation cannot begin its next write. This serializes the stores without claiming
cross-store atomicity. Credential clearing retains the already-gated `TerminalDataRepository`
entry point and does not recursively acquire the gate.

Runtime settings from `TerminalSpikeViewModel` use the same coordinator: AI command configuration,
input-button placement, notification education and the startup shipped-keyboard upgrade. The
upgrade reads its selected profile while holding the gate and checks that its update affected a
row. `ReleaseNotesUpdateHost` receives the process-owned coordinator for both its initial baseline
and explicit acknowledgement; unavailable recovery leaves unread notes unacknowledged and offers
the existing save-failure UI. Cancellation still propagates. `AppSettingsRepository.update` stays
the low-level DataStore primitive so backup/recovery owners can write without reacquiring their
own authority lock.

Production retains the existing-key-only `LegacyUserSettingsReader` and `LegacySecretReaders`.
The retired `SecureUserSettingsStore`, `SecureSshPasswordStore`, `SecureSshIdentityStore`, and their
historical recovery exceptions live only in `androidTest`, where they generate and verify legacy
fixtures. Active compatibility results and password AAD identities remain in `SettingsLoadResult.kt`
and `SshPasswordScope.kt`; legacy byte limits are owned by the production reader. Moving fixture
writers does not delete or migrate any on-device file, key, Room row, or DataStore preference.

`AesGcmCredentialStore` encrypts saved passwords, private-key payloads, and explicitly saved key passphrases with non-exportable app-specific Android Keystore AES-256-GCM keys and immutable UUID/kind associated data. Room contains authenticated ciphertext plus non-secret metadata, never plaintext. Reads return bounded mutable bytes to narrowly scoped transport or backup operations and wipe them on terminal paths. Missing, invalidated, corrupt, or unavailable keys preserve ciphertext and expose typed recovery; clearing credentials or all app data is separately confirmed. Imported fonts live in bounded app-private storage, while portable encrypted backup uses repository snapshots rather than copying Room, DataStore, or Keystore files directly.

Disk, crypto, migration, backup, and transport work run away from the main thread. Presentation state never enters `TerminalBuffer`, `TerminalViewport`, or the native renderer hot path.

`MoshSshSideChannel` owns the authenticated SSH companion used by attachments and user-requested
project operations after bootstrap. Its name replaces the narrower `MoshImageUploadSession` name.
Fresh-session cleanup removes the pending authentication lease from process tracking, attempts both
returned and registered handles, and preserves an operation/authentication failure with close
failures suppressed. If the handles are identical, normal completion closes them once. Direct
upload cancellation or interruption does not trigger fresh authentication; a consumed upload or
workspace creation is never replayed. The caller continues to own the upload input stream.

`MainScreenSmokeTest` resolves visible text and accessibility descriptions from the current
Activity resources, including formatted navigation/key labels. It does not assume English.
An outer rule saves the current buffered draft, prepares an empty draft before Activity/ViewModel
construction, and restores the original only after the Activity closes. Typing tests therefore
cannot choose a later test's initial input mode or overwrite an existing user draft.

SSH key import uses `launchSshIdentityImport` to release its document-grant callback from job
completion, including cancellation before the IO worker starts. The worker retains its existing
private-key byte cleanup; completion releases the grant after that cleanup. Imported identity
labels remove controls, Unicode format characters and malformed surrogate units by code point,
then retain complete characters within the existing 48-unit UTF-16 label limit. Basename
selection, edge trimming and internal whitespace are preserved. Private-key contents are not
rewritten by this display-label normalization.

## Output batching

Transport parsers publish bounded frames into `TerminalController`, which accepts batches into a bounded queue and applies at most one batch per Choreographer callback. A latest-value slot coalesces full-screen replacements; sorted dirty-row unions survive coalescing for precise native invalidation. Debug-only workload generators use structured coroutines and are not reachable from release UI.

## Terminal engine decision

ADR-002 retains the project-owned custom renderer and selects the project-owned bounded VT engine. This keeps the parser and renderer independent of third-party terminal-app source and keeps parser/model tests runnable on the JVM. The decision can be revisited with measured evidence and a separate licensing decision, but SSH and future transports now share the same screen contract.

## Project reading and browser

Two independent project tools sit alongside terminal sessions. `ProjectReader` uses
bounded authenticated SSH exec channels to query Git and read source, including a
Mosh session's SSH companion. Its native Compose workspace supports working-tree,
index and branch-merge-base comparisons, file trees, source search and review
drafts. `BrowserController` instead opens a user-configured code-server address in
Android's system WebView, using the phone's existing network/Tailscale routing.
The embedded browser lives inside MainActivity's security boundary and retains at
most three application-context WebViews. Neither project tool changes the terminal
hot path. See [project review](PROJECT-REVIEW.md) and
[browser lifecycle and network policy](EMBEDDED-BROWSER.md).
