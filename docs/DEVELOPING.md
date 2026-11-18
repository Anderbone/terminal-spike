# Developer guide

For installation and screenshots, see the [README](../README.md).

The verification narratives below are historical evidence for their named builds, not a guarantee
about the newest release. See [GitHub releases](https://github.com/Anderbone/terminal-spike/releases)
for version-specific checks and limitations.

Terminal Spike is an open-source, local-first native Android SSH terminal release candidate. It opens on a device-only workspace for active sessions, saved connections, identities, snippets, trusted host keys, and terminal configuration. A dedicated Compose application shell surrounds a custom hardware-accelerated Android `View` that draws terminal rows directly with `Canvas` and `Paint`.

Password- or private-key-authenticated SSH, opt-in device-bound saved passwords, protected private-key material, SFTP file management, a customizable extra-key bar, bounded VT/xterm screen semantics, and simultaneous remote-session tabs are implemented. Live SSH and Mosh terminals can paste a phone clipboard image into tools such as Codex: the app streams the image over the session's authenticated SSH side channel to a private cache directory on the connected host and bracket-pastes its remote path. Genuine Mosh 1.4.0 transport is built into the same APK. Ten private worker processes isolate concurrent native transports. There is no sync, analytics, advertising, bundled AI, or subscription code.

## Source and licence

This is the canonical repository for the main Android app, Mosh API, native Mosh implementation,
tests and build tooling. Development and issues for the former standalone Mosh repository move
here. One APK is built from this checkout: `com.yanjiyu.terminalspike`, including SSH and Mosh.
No companion installation is required. Existing standalone extensions are no longer selected.

Project-owned code is **GPL-3.0-or-later**, with no warranty. `mosh-api` retains Apache-2.0;
third-party code, fonts and artwork retain their component licences. See [LICENSE](../LICENSE),
[LICENSING.md](../LICENSING.md), [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and the
[Mosh notices](../mosh-core/THIRD_PARTY_NOTICES.md). Mosh is a registered trademark;
Terminal Spike is not affiliated with or endorsed by the Mosh project.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for changes and [SECURITY.md](../SECURITY.md) for vulnerability
reports. The [consolidation record](../docs/OPEN_SOURCE_MIGRATION.md) preserves source provenance;
older release and architecture records below describe their original verification context.

## Prerequisites

- A stable Android Studio version compatible with Android Gradle Plugin 9.3.1, or command-line Gradle prerequisites
- JDK 25 for the Gradle daemon (the application source and bytecode toolchain remains JDK 17)
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer stable build tools supported by AGP 9.3.1
- Optional: an API 26+ device or emulator for installation and the instrumentation smoke test
- Native Mosh build: Android NDK r29 revision `29.0.14206865` plus the host tools listed in [mosh-core/BUILDING.md](../mosh-core/BUILDING.md)

The Gradle wrapper downloads Gradle 9.5.0. SDK paths belong in an ignored `local.properties` file or the normal `ANDROID_HOME`/`ANDROID_SDK_ROOT` environment variables; no SDK path is committed.

AndroidX Baseline Profile Gradle plugin 1.5.0 and Protocol Buffers Gradle plugin
0.10.1 use AGP's public DSL. The legacy `android.newDsl=false` override is no
longer required. Baseline/Startup Profiles remain checked in; ordinary verification
does not regenerate them or run device benchmarks.

## Open and build

Open this directory in Android Studio, use its bundled JBR 25 (or another JDK 25) as the Gradle runtime, allow Gradle sync to finish, and choose the `app` run configuration. The checked-in daemon criteria keeps command-line and IDE builds on the same runtime while Android source compatibility remains Java 17. This also avoids the current lint parser's use of Java collection APIs that are unavailable when lint itself is hosted on JDK 17.

From a shell:

```bash
scripts/verify-android.sh
```

See [CI and releasing](CI-AND-RELEASING.md) for focused UI checks, shared artifacts,
CI timing and repeatable internal publishing.

Snippet component tests use `IsolatedPreferencesRule("snippet-toolbar")` outside the
Compose/Activity rule and provide its wrapped context through `LocalContext`. They
never modify the installed app's toolbar preferences, including when a component
uses `applicationContext`. That wrapped application context has a stable identity.
Composition disposal finishes
before the unique fixture is flushed and deleted, including after assertion failure.
`IsolatedPreferencesRuleTest` checks that order and preservation using only owned,
UUID-named preference files.

Native Mosh fixtures use the shared-test `readOwnedInputWithTimeout` for blocking output. Its input owner closes the stream before joining a cancelled reader; read failures return to that owner before being thrown, preserving suppressed close errors under coroutine stack recovery. JVM probes and independent Android PFD tests cover the deadline and cancellation paths. Real SSH/Mosh/Codex acceptance remains a separate required scenario.

Key imports persist a device-local incomplete marker before acquiring a document grant or reading key bytes. One process owner retains persisted read grants until every active import has finished; startup releases grants left by an interrupted process. Interrupted imports preserve saved data and request explicit file reselection or pasting instead of replaying a restored picker result. Check the key list first, because a commit can complete just before process death. Acknowledging the recovery dialog is persisted separately from active import ownership.

`IdentityImportRecoveryTest` covers restart, concurrent ownership, provider failures and marker-write failures; `IdentityPickerRequestOwnerTest` covers retained picker targets and stale results. `IdentityImportRecoveryProcessTest` uses a non-exported debug service in its own private process and a generated preferences file to prove marker recovery and acknowledgement across actual Binder-observed process death. It never kills the main app, opens real keys, changes the catalog or exercises a real document grant.

VT protocol regression tests distinguish erasing the viewport from resetting the terminal: ED2 preserves cursor position and scrolling margins, while RI clamps at physical row zero outside a nonzero top margin and clears pending wrap. `VtTerminalScreenControlTest` runs whole-buffer and one-byte transport variants, including origin mode, rows outside margins and alternate-screen reset controls. Existing complete 200-row Codex scrollback tests remain required. These parser tests do not establish physical tmux scrolling quality. Recovery preference acquisition is deferred into guarded store reads/writes; owned acquisition-failure tests cover startup recovery and refusing an unmarked import.

The real-Codex host runner checks the generated public key before appending it and registers revocation before any append can partially succeed. Revocation matches the generated key type and public blob, preserving unrelated comments and refusing a preexisting copy of the same key. Every filter, permission and replacement failure is checked explicitly; the runner reports failed revocation while still attempting private-workspace cleanup. Owned host tests exercise partial writes and cleanup faults with fake ADB/SSH commands. Coordination with concurrent authorized-keys writers, supported-target changes and device-data isolation remain separate requirements before using this harness on the S10e.

Voice recognition uses a fresh platform recognizer and an attempt-bound listener for each request. Retired attempts reject all later callbacks; cancellation and destruction invalidate ownership before calling the provider, and cleanup still attempts destruction if cancellation fails. Creation, availability and start/stop failures become existing UI failures. Normal stop still accepts the final transcript. The buffered editor captures the original target and language for microphone permission, consumes that request once, and ignores results after target changes, disabling or recreation. No old permission result automatically starts recording on a replacement target. Pure lifecycle/target tests and a controlled framework substitute exercise these paths; the component regression does not record audio or verify a real speech provider.

Terminal paste rejects an embedded bracketed-paste ending marker (7-bit ESC/CSI or C1 CSI form) before encoding or calling the input sink. Rejection preserves the original draft, selection and persisted text; ordinary multiline Unicode, literal escaped notation and optional Enter retain their existing behavior. Buffered input and saved snippets already validate control characters; the controller guard also protects direct callers. The ViewModel reports the specific paste rejection for buffered input, snippet dispatch and attachment-path insertion. Attachment uploads keep their existing concurrent, ordered and partial-success semantics: rejecting a path insertion does not roll back files already transferred.

`TerminalPasteSafetyTest` covers raw/bracketed mode and Enter on/off, including zero sink calls and no input-accepted side effects; `TerminalPasteNoticeTest` checks the existing UI validation boundary. The three prior encoding tests remain unchanged. An owned Bash/readline PTY probe demonstrates that equivalent unguarded wire framing can end protected pasting early; it is not proof of a buffered-editor bypass, whose control validation already existed. The native component regression exercises ordinary multiline confirmation, cancellation, preservation and restore rather than unsafe live-shell execution.

The VT engine bounds resident cell content per primary/alternate screen: two UTF-16 units per cell remain available for base glyphs, with at most 1,048,576 additional units shared by grapheme extensions. The existing 4,096-unit individual-cell limit and width-preserving replacement/overflow-tail behavior remain. Plain primitive row counters move with row arrays; character growth and replacement use constant-time accounting, character shifts recount their affected row, and resize counts the retained rows. Ordinary base writes use a fast path without extension calculations when the pool is empty. No per-cell or per-line Compose state is added.

`VtTerminalCellTextBudgetTest` and its mutation matrix cover erasure, replacement, wide continuations, insert/delete, scroll margins, resize, alternate-screen independence, one-byte UTF-8 boundaries and immutable-row reuse. The shared owned fixture also runs on Android. A separate 64 MiB JVM contract compares the same workload against frozen old and current engine classes. Host ASCII probes compare output hashes and row counts; they do not establish phone FPS or real terminal acceptance. This pool bounds resident cell content only: history, queued output and hyperlink metadata retain separate ownership and still require their own byte-budget review.






`MoshConnectionTest` owns every connection and connect worker created by its fixture helpers.
A JUnit rule releases blocked fake resizes, closes connections and waits up to two seconds
per worker, including when an assertion fails or a prepared connection never starts.
Cleanup failures remain test failures; workers are not made daemon threads to hide leaks.
Two lifecycle regressions check pipe/thread cleanup and prepared-password wiping while
preserving the original assertion failure.

Saved-host chips, manual reconnect and duplicate dialogs preserve Mosh UDP settings, executable,
locale and configured SSH fallback policy. Explicit `en_US.UTF-8` and `mosh-server` defaults compare
equal to their unset forms when checking whether a saved password still matches. Other transport
option changes require fresh authentication. Editing the visible UDP/executable fields retains
the locale and policy; selecting a different saved host replaces those inherited options.
An SSH submission or completed fallback to SSH carries no Mosh-only options. These checks preserve
the existing fallback policy rather than introducing a new automatic-fallback rule. Room already
stores these fields; the legacy settings codec and database schema are unchanged.
`C.UTF-8` is a supported explicit locale override and is preserved rather than treated as unset.

### Local interaction diagnostics (debug)

Debug builds automatically keep bounded numeric interaction diagnostics in the app-private
`files/interaction-diagnostics/` directory. They record touch routing, input acceptance,
SSH/Mosh writer progress, incoming chunks, parsed/published/drawn frames, and a main-thread
heartbeat. They never include terminal/input text, endpoints, titles, passwords or keys.
High-frequency output and draw events are sampled once per second. Two 256 KiB files rotate
on a background worker; at most 2,048 events wait in memory. The newest two seconds may be
lost if the process is killed abruptly. Release builds do not start the recorder.

When an incident occurs, long-press the app session tab and choose **Save freeze diagnostics**
(**保存卡顿诊断**), or use **Settings → Developer**. Save before disconnecting/reconnecting.
The action freezes `last-incident.txt` locally before opening Android's document picker;
that frozen copy remains even if the picker is cancelled. Exporting only writes to the
document chosen by the user and never uploads a report. An attached, explicitly targeted
ADB connection can also retrieve the private files with `run-as com.yanjiyu.terminalspike`.
Mosh writer completion only confirms delivery into the native client's input pipe, not
acknowledgement from the remote host. An idle terminal without output is not itself a fault.

The debug session menu also offers **Send diagnostics and analyze** (**发送诊断并分析**).
It freezes and queues up to four private reports on the phone. The explicitly installed
computer receiver uses the existing authorized ADB connection, independent of a blocked
SSH/Mosh terminal. It accepts only bounded, numeric diagnostic reports from authorized
supported phones, saves them under ignored `build/diagnostics/`, then acknowledges receipt.
Disconnected phones retain their outbox. No GitHub issue, Git commit, or release is created.

Install the local receiver with `python3 scripts/install-diagnostic-receiver.py --codex
<existing-codex-binary>`. This creates the user service `terminal-spike-diagnostics.service`
and a backed-up, idempotent Bash startup hook. New shells and user services default to
`~/.cache/terminal-spike/tmp` unless a different `TMPDIR` was explicitly chosen. Existing
Herdr servers and their panes are not restarted. The Android verification gate also probes
an actual 4 KiB write and passes this disk directory to Gradle, Kotlin and SQLite; `df`
free space alone cannot detect a per-user tmpfs quota failure.

Each user-requested submission starts one bounded Codex analysis with a read-only sandbox,
no approvals, no user MCP configuration, and no model override. Authentication uses the
existing Codex installation. It may inspect source but cannot fix, commit, push, publish,
install, or run device tests. Reports include a current host write probe and separate facts
from suspected causes. The newest result is `build/diagnostics/latest-analysis.md` with
status in `latest-status.json`; 64 completed submissions are retained. Interrupted analysis
is reported as failed and is not automatically billed/replayed. Inspect the service with
`systemctl --user status terminal-spike-diagnostics.service`; disable it with
`systemctl --user disable --now terminal-spike-diagnostics.service`.

The receiver prepares each complete prompt in a private temporary file before starting
the child, then immediately tracks the process and its log. A failed startup receipt
does not abandon a running child: cleanup signals its owned process group with TERM,
waits up to two seconds, then escalates to KILL with another two-second wait. If it
still cannot reap the child, it retains that slot and log for the next receiver tick;
it cannot start another analysis or prune that active receipt. These startup failures
remain failed even when a partial report exists. No analysis is automatically replayed.

`--once` collects once and waits for the one analysis selected by that pass, including
its existing ten-minute deadline and TERM/KILL escalation. It does not start the next
queued analysis while draining; `--no-codex --once` only receives reports. Ctrl+C,
SIGTERM, and unexpected loop exits mark an owned analysis failed and run the same
bounded process-group cleanup before restoring the previous SIGTERM handler. Failure
to reap is an explicit shutdown error, not a successful exit; its receipt is preserved
and the child is never silently replayed. These changes affect the repository script;
an already installed pinned receiver is updated only by an explicit installation.

The receiver and its analysis children use nice 19, a 50% CPU quota and a 2 GiB memory
ceiling. The installed shell hook defaults this project's verification to one Gradle worker
and one JVM processor; explicit `TERMINAL_SPIKE_GRADLE_WORKERS` and
`TERMINAL_SPIKE_JVM_PROCESSORS` override those defaults. Verification uses low CPU/I/O
priority where the host provides those controls. Keep unrelated concurrent changes out of
a diagnostic APK by building a reviewed isolated checkout when necessary.

For an explicitly selected S10e and an owned two-tab Herdr session, the opt-in host runner
`scripts/run-herdr-quota-device-check.py` performs an isolated EDQUOT versus writable-path
comparison and dispatches real Android taps. Its host-only preload fixture never fills
real `/tmp` or modifies user panes. Screenshots, OCR and per-tap server acknowledgement times
are retained. This validates the controlled write failure and UI interactions; it does not
by itself reproduce or prove the cause of an intermittent saved-session freeze.

### Stable local debug signing

The main app and its instrumentation APK explicitly use `~/.android/debug.keystore`.
This path stays the same when Android Studio, a shell, or an agent supplies a different
`XDG_CONFIG_HOME` or `ANDROID_USER_HOME`. Keep this key when moving development machines;
restore it outside the checkout before building updates for an existing debug install.
Do not replace it with another automatically generated debug key. Release signing is
configured separately and is unaffected.

If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, compare the installed APK's
certificate with `:app:signingReport` and restore the matching debug key before rebuilding.
Do not uninstall the app or clear its data to work around a signing mismatch. Debug
keys and all other signing material must stay outside Git.

Known debug certificates (public SHA-256 fingerprints, not secrets). Matching keystores live
outside Git in `~/.config/terminal-spike/signing/` and the external signing repository's
`debug-key-backups/`:

| Fingerprint prefix | Keystore | Installed on |
| --- | --- | --- |
| `f71bc506…` | `~/.android/debug.keystore`, `s10e-legacy-debug.keystore` | S10e and the default build |
| `946b2b10…` | `fold-legacy-debug.keystore` | Foldable `SM_F976B` |

Read an installed certificate with `adb -s <serial> pull <base.apk path>` and
`apksigner verify --print-certs`. When the target uses a different key, re-sign a private copy
of the verified APK with that keystore (`apksigner sign --ks <keystore> ...`) in a scratch
directory, then `adb -s <serial> install -r` it. This keeps app data and leaves the build output
and the default key untouched. Never uninstall or clear data.

The following release-manifest block and historical signing/emulator evidence describe the
pre-bundling release. See ADR-005 for the single-APK verification record.

<!-- release-evidence-current:start -->
Current release evidence: app JVM `971` tests (`971` passed, `0` skipped, `0` failures/errors); Mosh API JVM `11` tests (`11` passed, `0` skipped, `0` failures/errors); Mosh extension JVM `8` tests (`8` passed, `0` skipped, `0` failures/errors); old-phone Android app `332` tests (`307` passed, `25` skipped, `0` failures/errors). The source and artifact hashes and any pending external gates are recorded in `build/release-evidence/candidate-manifest.json`.
<!-- release-evidence-current:end -->

The complete local gate covers debug and release lint/build, release APK/AAB packaging,
Android-test APKs, dual-ABI built-in Mosh, and benchmark assembly. The exact hashes become
authoritative only in the generated candidate manifest after the final source freeze.

Clean process-isolated emulator suites also passed under a checked-in exact test contract. API 26
ran all 316 named tests with 17 approved skips, and API 35 ran all 316 with 13 approved skips.
Focused 33-test boundary suites passed on APIs 28, 29, and 32 with two approved skips each, and API
33 with one. The runner rejects missing, added, duplicate, or unexpectedly skipped tests rather
than accepting aggregate counts alone.

On the exact model-checked, test-authorized old `SM-S911B`, the complete app runner passed its
contract. A separately enabled real-Mosh matrix passed five password-bootstrap methods plus
one private-key method against the disposable OpenSSH/Mosh fixture. It proves interactive bytes,
four simultaneous sessions with independent resize/close, isolated-worker death and slot reuse,
broker death/rebind, and production app-selected-tmux local scrolling plus reader anchoring across
Activity recreation. The same physical method proves Auto stays local with zero remote wheels in
pre-existing tmux copy mode, while explicit Remote mouse drives it. Device tests were not run on
the `SM_F976B` fold. The old phone also passed real Samsung-IME entry, physical landscape,
maximum system text (`font_scale=2.0`), and Samsung split-screen checks; its Compose accessibility
tree remained populated with usable controls in each applicable configuration. Physical TalkBack
focus-order traversal and OEM battery-mode observation remain manual gates. Real network roaming,
the user's external server, hosted CI, production signing, and public-extension legal approval
also remain open.

Release builds remain unsigned unless all four external signing inputs are supplied. Signing material stays outside the repository:

```bash
TERMINAL_SPIKE_RELEASE_STORE_FILE=/absolute/path/to/release.keystore \
TERMINAL_SPIKE_RELEASE_STORE_PASSWORD='...' \
TERMINAL_SPIKE_RELEASE_KEY_ALIAS='...' \
TERMINAL_SPIKE_RELEASE_KEY_PASSWORD='...' \
./gradlew assembleRelease bundleRelease
```

Equivalent Gradle properties are `terminalSpike.releaseStoreFile`, `terminalSpike.releaseStorePassword`, `terminalSpike.releaseKeyAlias`, and `terminalSpike.releaseKeyPassword`. A partial signing configuration fails instead of silently producing an unsigned artifact. Release packaging also fails if R8 removes or renames any JSch implementation covered by the reflection keep policy, or if packaged third-party notices differ from the canonical notice file.

A separate local release-like smoke gate used the existing Android debug keystore, not production
signing. `:app:assembleRelease :app:bundleRelease :mosh-core:assembleRelease` completed with
`BUILD SUCCESSFUL` in 1 min 28 s with 159 tasks. SHA-256 values were app release APK
`29f64e9b6ac233a8546f5ae934962212804d8a62e6a7004657cc47d307990b1b`, extension release APK
`206c74610de6cc808dcccae8e14d32dedc8e99efea2e32a85f60b004174c4c49`, and app AAB
`27ae1393b34ed1a42edc031352229ef3106049adbe0ce033c893f7519355f61f`. Both APK signatures
verified, with matching certificate SHA-256 `946b2b…a3ee`; `jarsigner -verify` accepted the AAB with the
expected self-signed/no-timestamp warnings. Both APKs installed successfully on disposable API 35
AVD `terminal-spike-release-test`; the app cold-launched in 504 ms and `MainActivity` was
`topResumedActivity`. A later guarded smoke completed real SSH, performed same-certificate
`install -r`, retained the saved host, and completed SSH again while Mosh remained absent. This is
local acceptance evidence only and does not establish production
signing, public extension distribution, or store readiness.

The newer current `0.0.2` release-like gate used a fresh ephemeral acceptance key and the named
disposable API 35 AVD. The release APK/AAB and separate extension APK signatures verified with a
matching APK signer. The main app then passed clean install → real extension-absent SSH →
same-certificate `install -r` → process restart → saved-host retention → password re-prompt → real
SSH again. The re-prompt proves the new-host password remained session-only by default. This is
still local acceptance signing, not a production or Play-signing claim.

See [docs/PUBLISHING.md](../docs/PUBLISHING.md) for the remaining production-signing, Play Console,
store-listing, manual acceptance, and Mosh legal gates.

Install and launch on a connected device:

```bash
scripts/install-wireless.sh --list
scripts/install-wireless.sh --install-old-phone \
  '<exact-SM-S911B-adb-serial>' app/build/outputs/apk/debug/app-debug.apk
```

Run the device smoke test with:

```bash
scripts/install-wireless.sh --test-old-phone \
  '<exact-SM-S911B-adb-serial>' :app:connectedDebugAndroidTest
```

The helper rechecks the exact serial, authorization state, and `SM-S911B` model immediately before
the operation. It refuses physical-device test tasks on any other model.

The controlled real-Mosh device matrix has a separate fixed, self-cleaning runner. It requires an
explicit trusted-LAN acknowledgement, binds both SSH and the bounded UDP range to the supplied host
IPv4 address, verifies the old-phone identity before every install and test launch, runs the full
password/lifecycle class plus a private-key round trip, rejects skips, writes sanitized reports,
and tears the fixture down on success or failure:

```bash
scripts/run-real-mosh-device-tests.sh \
  --serial '<exact-SM-S911B-adb-serial>' \
  --host-address '<development-machine-trusted-LAN-ipv4>' \
  --trusted-lan
```

Set `JAVA_HOME` to the compatible JDK listed above. This command installs the single debug app and test utilities only on the authorized old phone.

## Built-in Mosh

`mosh-core` is an internal Android library containing the pinned native transport and private
broker/worker services. `mosh-api` is the Apache-2.0 AIDL contract between those processes.
The main app verifies the SSH host and authenticates, starts `mosh-server`, and hands the broker
only a bounded numeric UDP endpoint, dimensions, options and a one-shot session key. Terminal
bytes use bounded file-descriptor pipes. The broker and workers have no separate launcher,
application ID, signing configuration or Play version.

Build and install the single app using the commands above. The app includes `arm64-v8a` and
`x86_64` native libraries; release APK/AAB checks enforce their presence and native notices.
See [ADR-005](../docs/ADR-005-BUNDLED-MOSH.md), [native build details](../mosh-core/BUILDING.md) and
[the dependency inventory](../mosh-core/DEPENDENCIES.md). Existing legacy extensions may remain
installed, but this app binds only its private built-in transport.

The server still needs `mosh-server`, reachable over SSH plus its selected UDP port/range.
Mosh uses `xterm-256color`; SSH-only TERM overrides and startup commands are not forwarded.

After committing a reviewed source revision, create a complete deterministic source archive:

```bash
scripts/create-source-bundle.sh
```

This includes the entire app, native sources, patches, notices and build scripts. Use your own
Android debug key or external release key to build a modified app; no separate matching signer
is required. The historical standalone source releases remain in the archived old repository.

## Try a remote shell

1. From **Connections**, add or choose a saved host. Choose **SSH** or the built-in **Mosh** transport.
2. Enter a host, port, and username, then use a password or an imported private key. Password saving is off by default; opt in with **Save password on this device** while also saving the host. Key passphrases remain one-time only.
3. On first contact, compare the displayed SHA-256 fingerprint with a trusted fingerprint from the server administrator, then tap **Trust and connect**. Mosh uses this strict SSH step to authenticate and start `mosh-server`, then switches the terminal transport to the server's UDP port.
4. Tap the terminal to open the keyboard. The workspace resizes above the IME; hiding the keyboard restores the full terminal height. PTY resize is settled at the end of the keyboard animation to avoid repeated remote redraws. The default live deck is exactly 20 buttons in two rows of ten, with an image button immediately left of **Home**: tap for the system photo picker showing recent images/videos, or long-press to select files of any type. The app adds no count or size cap (the system photo picker can impose its own count limit), direct **^C** and **^W** chords, and a stacked-window key in the former Alt slot that opens the active tmux-session switcher; customize it from **Settings → Keyboard**. Selected files and clipboard URI content are transferred concurrently and their paths inserted in selection order, so compatible terminal tools can consume attachments in the current input. Local Arch imports files into its own home directory. The text draft and clipboard paste have no app-defined character cap. Long-press terminal output to select it; the keyboard hides so the local **Copy** and **Select all** toolbar and drag handles remain visible. Swipe the input strip to its left page for a normal editable field with correction and selection, then tap **Send** to paste the exact staged text without Enter. A successful paste clears the visible field as before, while a one-tap undo action can restore the last sent draft if a remote full-screen app had moved its own input focus. Swipe back to restore immediate terminal input with correction, completion, and suggestions disabled. The compact strip has no permanent Raw/Text selector labels; the same default mode can also be chosen in Settings.
5. When **Settings → Sessions &amp; Background → Show tmux session selector** is enabled, every authenticated connection checks the remote server before opening its terminal. Choose **Start new session** to protect new work, **Attach** to resume an existing session, or **Open shell** to skip tmux. If tmux is missing or the check fails, the chooser says so instead of disappearing. Add another app tab with the trailing **+**, tap a short tab name to switch, use the stacked-window button in the top bar to jump to or close any app tab, double-tap a connection tab to duplicate it, or long-press it for disconnect/close actions. A remote tab automatically prefers a safe OSC 0/2 terminal title over its connection name; for a tmux session name, enable `set -g set-titles on` and use `set -g set-titles-string '#S'` in the remote tmux configuration. Stock tmux titles are shortened to the session name. A double tap starts the duplicate immediately when authentication can be safely reloaded; only an intentionally non-retained one-shot password or passphrase asks for re-entry. Each remote tab keeps isolated scrollback and connection state.
6. Tap **Disconnect** when finished.

Accepted host keys are stored in the app-private database. If a known key changes, connection is blocked. Trusted keys and their full selectable fingerprints can be reviewed or forgotten from **Settings → Security**, and clearing app data removes them all.

During height-only terminal resizes, the native renderer retains a bounded one-display picture of live text at its original glyph size, anchored at the bottom. API 29+ hardware canvases reuse a RenderNode display list during the transition; API 26–28 and software canvases replay the Picture. History readers, selection, and find highlights keep live rendering. Parsing and viewport geometry continue normally. The production Compose bridge reads current/target IME insets after layout and submits the final PTY grid during the animation. Intermediate heights do not resize the remote terminal again when the target grid is unchanged. Completion or reversal reconciles the actual grid immediately; a missing animation-end event has a one-second fallback. Non-IME resizes retain the 120 ms settle policy.

The VT engine supports synchronized output (DEC mode 2026), split markers, and DECRQM queries. The controller retains the last published display and queues real scrollback until ESU, while parsing and input protocol modes continue. A one-second timeout or 4,000 pending history rows forces publication; repeated BSU cannot extend that deadline or repeatedly freeze the same unfinished generation. The native Mosh renderer wraps each nonempty framebuffer diff in BSU/ESU before PFD writes, preserving frame boundaries across pipe reads without creating idle traffic. Mosh history inference compares completed frames and carries explicit-scroll exclusion across chunks, retaining all ordered history rows.

An early completed frame stays hidden until IME motion ends, then can be presented without a quiet-time heuristic. Unmarked output waits for 48 ms of quiet with a 500 ms limit after size submission. Touch, direct terminal input, font/session changes, and detach release the retained picture. Real-keyboard and hardware-pixel instrumentation tests cover early target submission in both directions, interrupted/reversed motion, and delayed presentation of complete frames. Their frame timings are emulator evidence, not proof of physical-device smoothness.

The retained picture now follows an interpolated whole-row grid, rather than translating by raw window pixels and snapping to a different grid at release. Confirmed agent/mouse/alternate-screen UIs retain bottom anchoring; ordinary VT shells clamp their cursor only when needed; transcript workloads use their actual content bounds. These are presentation-only anchors: the live viewport, history ownership, and input routing remain unchanged. Reversal rebases from the last presented position. Cancellation eases the sub-row remainder for at most 96 ms before handoff. Empty/invalid source viewports skip capture. Keyboard accessory visibility uses a derived Boolean so animated IME height does not recompose the accessory deck on every frame. Local systrace sections `Terminal.captureResize`, `Terminal.retainedFrame`, and `Terminal.liveFrame` identify work without recording terminal contents.

A controlled fixed-grid alternative demonstrates its tradeoff: a 40-row remote UI with top chrome and bottom input cannot expose both in a 20-row client crop. No fixed-grid mode or scrolling-policy change is enabled by this investigation. Pixel-handoff tests compare retained/live images at non-integral row heights in both directions, including sparse prompts and filled shells. The unchanged whole-row hardware cases continue checking exact glyph pixels; cancellation and thousands of geometry combinations have independent host coverage.

## Connections and settings

The phone shell has exactly three primary destinations: **Connections**, **Terminal**, and **Settings**. Connections is the main page for saved hosts, SSH keys, and snippets. **Terminal** owns active sessions, and **Settings** contains appearance, terminal, keyboard, background, backup, security, Mosh, and About/licence controls.

Remote tabs have no fixed application count limit. Reconnecting still requires an existing failed or disconnected remote session; foreground-service, transport and authentication failures remain explicit.

Modifier tap transitions live with pending input encoding. Each retry of a state update uses the same incoming tap timestamp, and the UI owner commits the resulting timestamp after the state CAS succeeds. Background updates retain their own fields. Bounded SSH-key reading and its wiping accumulator live with identity import; their size, exception and cleanup contracts are unchanged.

ViewModel teardown attempts every independent cleanup in its existing order, then rethrows the first failure with later failures suppressed. Identity-ID reservation runs inside the import job before reading the key, so the existing completion callback also releases its document grant if preparation fails or dispatch is cancelled.

`TerminalSpikeScreen` mounts `SettingsToolsScreen` for Settings. It supplies the snippet editor
and forwards the current navigation and persistence callbacks to `SettingsDestination`;
`MoshExtensionSection.kt` owns the extension status and installation-help UI. The former
`TerminalToolsSheet.kt` combined page has been removed. Connection and shortcut-preview tests
mount the same `ConnectionsScreen` and `TerminalShortcutPreview` components used by the app.

- Host profiles save a display name, host, port, and username. Connections lists every saved host and resolves the current authoritative saved authentication. A password can be saved separately only with explicit opt-in. **Forget** removes retained authentication.
- Mosh profiles also save an optional UDP port/range and one executable name or path for `mosh-server`; command fragments and arguments are rejected. If the verified extension is unavailable, the profile remains saved but Mosh connect is disabled and SSH can be selected explicitly. Per-profile fallback can be Never, Ask, or Automatic; Automatic is an explicit opt-in that starts a clearly reported fresh SSH shell only for eligible Mosh transport failures, never a claim that the Mosh process resumed.
- SSH private keys are imported through Android's document picker, validated by the SSH library, encrypted under an app-specific Android Keystore key, and stored only in app-private storage. Encrypted-key passphrases are requested for each connection and never saved. Trusted host keys can be reviewed and removed from **Settings → Security**.
- Terminal appearance defaults to bundled JetBrains Mono and includes the built-in theme catalogue, persistent custom themes, System monospace, Source Code Pro, IBM Plex Mono, Cascadia Mono, and a Symbols Nerd Font Mono fallback for Powerline/Nerd glyphs. TTF/OTF imports use the system document picker, stay private to the app, and are rejected when representative glyph advances show that the font is proportional and would break the terminal cell grid.
- The extra-key editor can show, hide, reset, and reorder the complete accessory action set. Replacing a key uses a compact searchable multi-column picker, the visible order is used directly by the bottom key bar, and every action has a standalone at-least-48 dp touch target while the default deck remains exactly 20 keys arranged as 10 × 2.
- Snippets support names, multiline commands, and optional Enter. A snippet is sent only after tapping **Send**, and only to the active connected remote tab. The paste and optional Enter are accepted as one bounded writer batch; a full or stopped transport reports failure instead of claiming the snippet was sent. Do not put passwords or access tokens in snippets.
- **Settings → Backup & Restore** writes one complete portable file through Android's document picker, with no mode or passphrase prompt. It includes hosts, portable credential material, SSH keys, snippets, settings, profiles, themes, and referenced imported fonts. Restore validates the archive, previews replacement changes, and applies them transactionally. Because the file can contain credentials and is not protected by a user secret, it must be stored privately. Google Drive is available when its document provider is installed; Terminal Spike does not log into Google or call a Drive API.
- **Settings → About** shows the installed version, local-first privacy summary, and the complete selectable third-party notices and licence text packaged in that APK.
- **Settings → Mosh Extension** reports Checking, Not installed, Disabled, Untrusted, Incompatible, Available, or Error from live package/signature/API/capability checks. Available state includes the installed version, negotiated API, capabilities, and session limit; **Refresh** or **Retry** repeats the check. It does not download or install an extension.

Non-secret profiles, identity metadata, snippets, themes, and keyboard layouts live in app-private Room/DataStore storage with bounded validation and explicit migrations. Each imported private key and each opt-in password is separately bounded and protected with authenticated encryption under an app-specific Android Keystore key. Password ciphertext is bound to the profile ID, host, port, and username so it cannot silently follow an edited endpoint. Android cloud backup and device-transfer backup are disabled for all app data. Corrupt or undecryptable legacy data is preserved behind fail-closed startup and Settings recovery gates; every such gate offers retry and the same separately confirmed full app-data reset instead of trapping the user behind unreadable local state. Credential reads never create replacement keys for ciphertext whose Android Keystore key is unavailable. There is no account or network sync.

Active transports are owned by the user-started foreground session service rather than by an Activity. Its low-noise notification exposes safe open/disconnect actions and privacy-aware content. Navigating away or recreating the Activity does not implicitly disconnect a session, but Android may still terminate the process; the app does not claim to resurrect a lost live socket after process death. A wiped API 35 external acceptance passed real SSH through background, deep-idle, restricted-standby, and Data Saver cases with exact policy restoration, then killed one ownership-verified app PID. The old service/notification cleared, a distinct process showed no live session, one saved host and recent row survived with zero stored session-only secrets, the password was requested again, and real SSH succeeded afterward.

Connected terminal tabs also accept bounded task-attention events. OSC 9 retains its message on direct SSH, while a text-mode BEL produces a generic **Terminal task complete** notification and survives both tmux and Mosh. BEL used only as an OSC terminator is not an alert. Tapping the notification opens its originating app tab; closing or disconnecting that tab ends its notification path. Because BEL is a general terminal signal, any program that emits one can use this channel. For Codex turn completion across SSH, tmux, and Mosh, configure Codex before starting it:

```toml
[tui]
notifications = ["agent-turn-complete"]
notification_method = "bel"
notification_condition = "always"
```

Codex reads this configuration at process startup. Android notification permission and the **Terminal task completion** notification channel must remain enabled on the phone.

App-selected Herdr sessions monitor bounded agent metadata over the existing authenticated SSH
connection (including Mosh's retained side channel). Each task produces its own notification;
simultaneous replies are not coalesced and later turns do not replace earlier turns. Tapping opens
the app tab, then focuses the originating Herdr pane after the startup/app-lock gates. The live
terminal and Codex session identity are checked again; a closed/replaced target displays a message
and leaves the user in the original app tab rather than starting a new agent.

For reply excerpts, install the optional local helper on the SSH host:

```sh
install -Dm755 tools/terminal-task-notify.py "$HOME/.local/bin/terminal-task-notify"
```

Add this **top-level** entry to the host's `~/.codex/config.toml` before any `[section]`:

```toml
notify = ["/absolute/home/path/.local/bin/terminal-task-notify", "--notify"]
```

Preserve any existing notify integration by explicitly composing it rather than overwriting it.
New Codex clients load this hook at startup. The helper also supports existing Codex clients by
opening the local `state_*.sqlite` index read-only for the exact Herdr-reported thread ID and
reading at most the final 256 KiB of that thread's rollout. It accepts only actual `task_complete`
events, stops at a newer `task_started`, and never forwards tool output, reasoning, or user prompts.
This compatibility path depends on Codex's local schema; unknown schemas fall back to the hook
cache or lifecycle-only notifications. It does not scan other transcripts or trust inherited
`HERDR_PANE_ID`, which can be stale with shared Codex daemons.

The supported notify hook stores only the latest turn ID and up to 400 characters of the final
assistant reply per thread under `$XDG_STATE_HOME/terminal-spike/replies` (default
`~/.local/state/terminal-spike/replies`), with private files and a 256-thread retention bound.
The summary removes Markdown/control formatting and truncates the author's wording. It makes no
additional model request and does not infer that a finished answer means the task succeeded.
The notification title says **Reply ready**; failures, questions and next steps remain in its body.
A confirmed `working` → `blocked` transition displays **Needs input** with an attention prompt;
the exact approval question is not available through this metadata path. Questions returned as
final answers appear in the reply excerpt.

**Notification privacy** defaults to off, so new settings show task names and excerpts. Existing
saved choices are preserved; the switch can still hide these details. With privacy enabled, notification title/body remain generic and secret on the lock screen. Android permission,
channel settings and Do Not Disturb still apply. No helper or no usable Codex identity means
lifecycle-only notifications. Other SSH hosts need their own helper installation; ordinary direct
SSH/tmux sessions retain the existing OSC 9/BEL behavior.

A sample is taken off the UI thread every two seconds after the preceding read, with a five-second
command deadline, 256 KiB response limit and 256-agent limit. Active and background panes are
included. Repeated final replies are deduplicated by turn; an observed working transition can wait
for a final reply persisted just after the idle sample. Generic BEL alerts are suppressed while the
rich Herdr route is available. First samples, failed reads, disappeared panes, unknown states,
identity changes and disconnects do not replay old replies. Whole turns between polls are detected
when a known thread's reply ID advances; intermediate turns may be missed if several complete
between polls. A failed auxiliary SSH channel disables this path even if Mosh remains live.
Manually launched Herdr sessions that the app did not select do not acquire this monitor.

This avoids relying on Herdr 0.8.2's outer-terminal detection or on OSC notifications crossing Mosh.
Herdr's own `[ui.toast] delivery = "terminal"` remains useful for supported desktop terminals but
is not required by the Android metadata path. Newly created **Terminal task completion** channels
enable vibration; existing channel preferences are preserved by Android and must be changed in
system notification settings. Notification permission, channel settings and Do Not Disturb still
apply. After installing an update, reconnect the saved Herdr session before testing a new task.

Manual acceptance on 2026-09-26: after enabling the existing channel's vibration setting, the user
confirmed real task-completion vibration on the foldable. A second ten-second task produced one
vibration and the user accepted the behavior. Automated notification tests ran only on USB SM-S911B.

### Compact Codex status in buffered input

Enable **Settings → Appearance → Codex usage in input box** to show
`Context 72% · Week 70%` at the bottom right of the buffered input box in app-selected
Herdr sessions over SSH or Mosh. Both percentages mean **remaining**. The option is off by
default on fresh and existing installs; disabling it hides the row immediately and skips the
extra usage-reader command. This device-local choice survives app restarts and is omitted from
portable backups. It does not change Codex's footer or the terminal width. The row occupies its
own space so drafts remain editable above it; no model or five-hour allowance is shown.

Herdr's focused pane and terminal identity select the exact Codex `agent_session` thread ID.
The existing SSH side channel runs a Python 3 standard-library reader against that thread's
read-only local SQLite index and at most the final 256 KiB of its rollout. It returns only two
nullable percentages, then checks the pane identity again before publication. No Codex process,
inference, authentication-file read, or external account-service request is started. Missing
Python/index/usage events or an unrecognized thread leave `—`; another thread is never substituted.

Context uses `last_token_usage.total_tokens`, not cumulative session token counts, and the
12,000-token baseline in Codex 0.166's
[`TokenUsage::percent_of_context_window_remaining`](https://github.com/openai/codex/blob/rust-v0.166.0/codex-rs/protocol/src/protocol.rs).
Week comes from the identified thread's most recent eligible `codex` rate-limit snapshot,
not a fresh account query. The weekly window is identified by its actual 10,080-minute duration
rather than primary/secondary position; expired snapshots display `—`. Values update when
Codex records usage and do not promise live account-wide consumption while another session runs.
Direct SSH/tmux or local terminal sessions without a verified thread mapping do not acquire one
by scanning unrelated logs.

## Remote text to the phone clipboard

Choose **Settings → Terminal → OSC 52 clipboard writes → Ask**. When a remote program
sends an OSC 52 write, the permission dialog offers **Automatically allow until this
connection reconnects**. Check it and tap **Allow** to copy this request and allow
subsequent writes from that live connection without further prompts. Leaving it
unchecked allows just one write. The grant is not saved or shared with other tabs;
a new transport or an app restart requires permission again. Automatic requests
received while the app UI is not resumed are discarded, not copied on return.

For a direct terminal smoke check, run this on the connected host:

```sh
printf '\033]52;c;%s\a' "$(printf '%s' 'Phone clipboard test' | base64 | tr -d '\n')"
```

Run it again with different text after granting automatic access. The phone clipboard
should change without a second dialog. The remote AI or program must actively emit
OSC 52; ordinary displayed commands are not copied automatically. A tmux or Mosh
route must also deliver OSC 52 to the outer app; a filtered request cannot trigger
this permission or copy path. This change does not configure the remote AI or tmux.

## Wireless development install

After pairing a phone through Android's Wireless debugging screen, list exact ADB targets. The
guarded helper supports tests and main-app installs on the authorized old `SM-S911B`. It also has a
separate final-install-only mode for `SM-F976B`, which requires an explicit feature-complete
acknowledgement and can neither update-install nor execute tests:

```bash
scripts/install-wireless.sh --list
scripts/install-wireless.sh --test-old-phone \
  '<exact-SM-S911B-adb-serial>' :app:connectedDebugAndroidTest
scripts/install-wireless.sh --install-old-phone \
  '<exact-SM-S911B-adb-serial>' app/build/outputs/apk/debug/app-debug.apk
# Only after the entire requested feature is complete:
scripts/install-wireless.sh --final-fold-install \
  '<exact-SM-F976B-adb-serial>' app/build/outputs/apk/debug/app-debug.apk --feature-complete
```

Every operation rechecks ADB authorization and `ro.product.model`; every ADB command is pinned with
`-s`. Both install modes launch the main app and require `MainActivity` to become top-resumed. Build
and run the host-side verification gates before using an install mode.

## Debug-only workloads

- **Stream** appends deterministic terminal-like lines at stopped, 10, 100, 1,000, or 5,000 lines per second. Samples include styled output, paths, compiler and container logs, emoji, Chinese text, combining characters, malformed Unicode, and clipped long lines.
- **Full screen** replaces a fixed model at 1, 10, 30, or 60 updates per second. It simulates a changing tmux/htop-style table, progress display, and moving cursor without parsing escape sequences.
- **Preload** adds 1,000, 10,000, 50,000, or 100,000 lines in frame-friendly chunks. Scrollback remains bounded at 100,000 lines.

These controls are compiled only into debug/profile test surfaces and are rejected by release-packaging checks. The workload is cancelled while the Activity is stopped and restarted with the retained selection when it becomes visible. Preload is also structured under the ViewModel and is cancelled by Stop, mode changes, replacement preload requests, or ViewModel clearing.

## Performance checks

The debug-only optional overlay reports diagnostic approximations for FPS, average/p95 recent frame interval, slow-frame counts, scrollback/visible/pending lines, heap usage, auto-follow, and workload. It publishes at most twice per second and is not a Macrobenchmark.

For a repeatable check:

1. Use a release-like physical device with a fixed refresh rate and thermal state.
2. Preload 100,000 lines, turn the overlay on, and drag/fling through the middle of scrollback.
3. Repeat with 5,000 lines/s at the bottom and while held above the bottom.
4. Repeat the full-screen workload at 60 updates/s.
5. Reset and collect platform frame data:

```bash
old_phone_serial='<exact-SM-S911B-adb-serial>'
test "$(adb -s "$old_phone_serial" get-state)" = device
test "$(adb -s "$old_phone_serial" shell getprop ro.product.model | tr -d '\r')" = SM-S911B
adb -s "$old_phone_serial" shell dumpsys gfxinfo com.yanjiyu.terminalspike reset
# Exercise one scenario for a fixed period.
test "$(adb -s "$old_phone_serial" get-state)" = device
test "$(adb -s "$old_phone_serial" shell getprop ro.product.model | tr -d '\r')" = SM-S911B
adb -s "$old_phone_serial" shell dumpsys gfxinfo com.yanjiyu.terminalspike
```

Use Android Studio CPU and Memory profilers to inspect `FastTerminalView.onDraw`, workload generation, GC frequency, and main-thread batches. See [docs/PERFORMANCE.md](../docs/PERFORMANCE.md).

The dedicated `benchmark` module also provides cold-start/Baseline Profile comparisons and
project-authored multi-megabyte terminal scenarios. Host-side parser/controller contracts require no
device; profile and frame measurements use the pinned API 35 AOSP Gradle-managed device:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.yanjiyu.terminalspike.terminal.benchmark.TerminalParserControllerBenchmarkTest'
./gradlew :app:generateBaselineProfile
./gradlew :benchmark:pixel6Api35BenchmarkAndroidTest
```

Benchmark fixtures and their target Activity are test/profile-variant only and are excluded from the
production release. See [docs/PERFORMANCE.md](../docs/PERFORMANCE.md) for scenario sizes and evidence
recording requirements.

## Local Herdr/Mosh frontend cleanup

The host helper `scripts/cleanup-herdr-mosh.py` defaults to a dry run. It combines
Mosh's own disconnected utmp record with three minutes of passive Tailscale traffic
observation. Only same-user Mosh processes at least one hour old, bound to this
host's Tailscale address, with exactly one child that is a leaf Herdr frontend,
are eligible. Missing records, live peers, other shells, tmux clients and the
persistent Herdr server are preserved. PID identities and disconnected state are
checked again before SIGTERM. Capture failures abort cleanup. No packet payloads
or peer addresses are saved.

Each successfully opened process descriptor is registered for cleanup immediately.
Both pinned descriptors are released after validation or signalling, including when
the child disappears during acquisition or another close fails. A vanished process
returns false; permission and other operating-system errors still propagate.

```bash
python3 scripts/cleanup-herdr-mosh.py --report build/diagnostics/mosh-cleanup-preview.json
# Explicitly authorized cleanup:
python3 scripts/cleanup-herdr-mosh.py --apply --report build/diagnostics/mosh-cleanup.json
```

This is an explicitly invoked recovery helper, not a scheduled cleanup policy.
Disconnected or quiet does not prove that the user closed a connection. Open
connections must persist through inactivity, sleep and temporary loss of network.
No idle-expiry wrapper or cleanup timer is installed. The one-off 2026-11-15
cleanup reclaimed 146 user-confirmed excess frontend pairs and preserved every
workspace, tab and pane ID. A briefly installed 24-hour expiry wrapper was removed
in the same session to honor the user's lifecycle requirement.

Native Mosh treats a closed local input/output pipe as the start of authenticated
shutdown. It removes closed pipes from polling, continues UDP shutdown/ACK retries,
and exits after acknowledgement or the bounded shutdown deadline. This also covers
Java closing the pipes before its asynchronous Binder stop arrives. A connection
that is still open has no inactivity deadline. The persistent Herdr server and
its panes have a separate lifetime from a disconnected frontend.

`scripts/run-mosh-close-device-check.py` runs one opt-in acceptance case on the
authorized S10e, using a disposable named Herdr server and one already-permitted,
unused LAN UDP port. It verifies both the remote Mosh process and its Herdr
frontend exit, while the fixture server and the user's main workspace/tab/pane
IDs survive. `--expect-leak` records the old-build red case. The four close cases
are both pipes, input only, output only and explicit stop before pipe close. Keys
are kept out of saved logs and evidence; device and APK identity are recorded.

Device identity, install results and close acceptance use explicit runtime checks,
including under optimized Python (`-O`/`-OO`). Setup owns the server log before
spawning the fixture, and installed-app queries run inside its cleanup boundary.
Cleanup independently attempts the owned pidfd signal/close, named fixture stop,
owned child reap, log close and named fixture deletion. A stubborn child uses
bounded TERM/KILL waits; cleanup errors fail the run and evidence, while an
existing execution error remains primary. These host-side fault tests do not
replace the four real-device close scenarios above.
An exited `/proc` read may report ENOENT or ESRCH; either means the process is
gone. Permission and other inspection errors remain failures.

For resource-constrained native builds, `TERMINAL_SPIKE_NATIVE_JOBS=1` limits
Make/CMake compilation alongside the existing single-worker JVM options.

## Known limitations

- SSH supports password, private-key, and bounded keyboard-interactive authentication, typed host-key trust/replacement, configurable TERM/startup commands, keepalives, and bounded fresh-shell reconnect. Saved secrets are device-bound and remain accessible to anyone who can unlock both the device and app. Agent forwarding is not implemented.
- Local profiles and snippets use app-private Room/DataStore storage and are not a credential vault. Saved passwords and private keys receive separate Keystore-backed encryption; that protection does not cover snippet text or protect credentials from a compromised or unlocked device running the app. Exported backups can include private keys and must be treated as recoverable by anyone who obtains the file; see [backup privacy](backup-format.md#stored-contents-and-privacy).
- No optional cryptographic provider is bundled. SSH algorithm availability therefore depends on Android's installed JCA providers; the client does not re-enable obsolete RSA/SHA-1 algorithms.
- Mosh is built in and requires a reachable `mosh-server` and its selected UDP port/range through the server and network firewall. SSH remains available independently.
- The upstream Mosh transport retains authenticated UDP roaming and port-hopping behavior. The main app now publishes generation-scoped Android network hints to the active Mosh transport; real Wi-Fi/cellular/VPN transition acceptance remains open. IPv6 link-local hosts containing a zone identifier such as `%wlan0` are not supported; use a globally routable IPv6 address/name or IPv4.
- The source and single-APK architecture are GPL-3.0-or-later under ADR-005. A new Play upload is a separate release operation.
- The bounded VT engine supports primary/alternate screens, cursor addressing, scroll regions, insert/delete/erase operations, xterm colours, application cursor keys, bracketed paste, focus reporting, wheel mouse reporting, resize, OSC 8 links, policy-gated OSC 52 clipboard requests, and common terminal queries. It is not yet a byte-for-byte xterm clone; every DEC private mode and full conformance fixtures remain.
- App-selected tmux sessions seed a separate bounded local model from tmux's physical pane history and keep it synchronized with live rows. In Auto, vertical drag and fling use the same pixel viewport as an ordinary terminal, including when tmux was already in copy mode; Auto never sends a fallback wheel or exits that remote mode. Choose explicit Remote mouse to drive tmux copy mode or a mouse-aware inner application. Its optional two-finger override keeps a local-history escape path. An alternate screen alone, including a Codex-style UI, never forces row-wheel scrolling.
- For manually launched tmux and explicit remote-mouse mode, enable its mouse option once with `tmux set -g mouse on` (and add `set -g mouse on` to `~/.tmux.conf` to persist it).
- The engine tracks combining and common wide/emoji code points as terminal cells, while the renderer still estimates the physical cell width from a monospace `M`. Bidi layout and every grapheme/emoji sequence are not exact.
- Long lines are clipped. Terminal selection/copy, safe OSC 8 link actions, private SAF-imported fonts, and the release-cleared Symbols Nerd Font Mono fallback are present; full row-by-row accessibility exploration remains open.
- Direct terminal input sends committed text immediately and deliberately suppresses correction and local rendering of in-progress composition. The separate bounded input page provides normal IME composition, correction, and selection before an explicit paste.
- Renderer statistics measure actual `FastTerminalView.onDraw` calls and CPU duration using fixed primitive rings. They do not isolate GPU/display work or replace Perfetto, `gfxinfo`, or Macrobenchmark.
- Smoothness has to be measured on actual target hardware; a successful build alone is not visual performance validation.

## In-app user guide

Settings → User guide opens the twelve-lesson index. The first-install subset has four
pages for typing, Snippets and Herdr workflows. **Setup & reference** opens the expandable
instructions for attachments, drafts, shortcut keys, terminal text, connection tabs and remote files.

Canonical recordings live in `store-assets/user-guide/source/`; only the current eleven
posters and seven short loops live in Android resources. See the [recording workflow](../store-assets/user-guide/README.md)
for offline regeneration. The original recording bytes remain available in the complete source archive.

Keep gesture-only and secondary actions discoverable here when adding or changing them.
`ui/guide/GuideQuickReference.kt` groups the instructions from `res/values/guide_strings.xml`;
include the exact control, tap/hold gesture and any required setting or session type.
Check the instructions against the actual action handlers. Keep the recorded tours consistent
with their own workflows; reference text can be updated independently of their recordings.

## Isolated packages for destructive old-phone runners

The task-indicator and real-Codex tmux runners require debug APKs built with
`-PterminalSpikeDeviceVerify=true`. This selects application ID
`com.yanjiyu.terminalspike.deviceverify` and test ID
`com.yanjiyu.terminalspike.deviceverify.test`; release builds keep the main application ID.
Debug document-provider authorities follow the selected application ID, so the isolated fixture
can coexist with the normal app without provider conflicts.

```sh
./gradlew -PterminalSpikeDeviceVerify=true :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/run-terminal-task-ui-tests.py --serial '<verified-old-phone-serial>' \
  --output-dir build/deviceverify-task-ui
```

Both runners inspect the actual APK package identities and instrumentation target before either
install, recheck both artifacts before each install, and recheck the selected device before each
install or test. They support the authorized `SM-S911B` and the long-term `SM-G9708` with hardware
serial `R28M227XA0E`; an available supported USB phone takes priority over wireless. They reject
other models, including the foldable. The four task-indicator component cases run directly through instrumentation and do not require
AndroidX test services or Orchestrator. The actual-Codex runner retains Orchestrator and may clear
its isolated fixture package only.
Merely disabling `clearPackageData` would not protect the normal app: fixtures can still change
preferences, write catalog records, or disconnect sessions. Do not point these runners at normal
main-app APKs. After a fixture build, rebuild without the property before distributing a normal
main-app debug APK; the ordinary output paths are shared.

The real-Codex runner holds a stable sidecar `flock` from temporary SSH authorization through
revocation. Revocation compares the current authorization file to its filtered snapshot and retries
observed external changes at most three times. Persistent conflicts fail explicitly while preserving
the observed writer's file. This serializes cooperating runners; it does not make the comparison and
replacement atomic against writers that ignore the sidecar lock. All actual Codex ordered-history,
first-gesture, local pixel scroll, remote-wheel and reader-anchor assertions remain required.

The tmux switcher uses one deletion confirmation owned by the current app session/dialog opening.
Asynchronous catalog results must match the latest request; closing or reopening a dialog invalidates
older results. This UI ownership does not revoke an already dispatched remote operation or establish
transport reconnect generation ownership.

## Default snippet replacement persistence

Room schema 6 stores the device-local hidden-default set in `snippet_toolbar_state`. Replacing a
built-in snippet writes its saved replacement and merges the original default ID in one Room
transaction under the existing catalog mutation authority. A cancelled UI callback cannot leave
only the replacement committed. Both Settings and the terminal toolbar consume the same catalog
projection; neither hides a default in a callback after saving. Directly hiding a default uses
that same repository owner, and deleting a saved replacement does not unhide its built-in source.

The 5→6 migration and fresh database callback import `codex-replaced`/`replaced-defaults` once.
They retain the old preference file and its `order`/`expanded` values. No saved snippet is removed
or deduplicated by command text. The state remains device-local and is outside portable backups;
see `docs/backup-format.md`. Do not use destructive migration fallback.

Herdr metadata publication and UI invalidation now share a generation boundary in the controller.
Projection and clock reads stay outside that boundary; final source, reader, pin and grid checks
reject stale history results. Rejected context samples do not seed effort smoothing. Repository
capture records source/generation together and advances captured-output reuse only after accepted
publication. These are metadata consistency guarantees, not evidence of completed real Codex/tmux
scroll acceptance.
