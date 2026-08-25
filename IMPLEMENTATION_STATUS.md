# Product completion status

Last updated: 2026-08-19T10:55:02Z

This is the authoritative implementation checklist for the paid-release polish programme. A phase is checked only when its implementation, migrations, tests, documentation, and applicable device evidence are complete. A passing narrow test does not complete a broader item.

The current product direction keeps **Connections**, **Terminal**, and **Settings** as the three
primary phone destinations. Connections is the main catalogue for saved hosts, keys, and snippets;
Terminal owns active sessions.

The 2026-08-19 launcher-branding refresh has a newer current-source gate than the historical phase
evidence below. The full 482-task unit/lint/debug/release/Android-test/package-integrity/native/
benchmark gate passed; fresh unit totals are app `815/0/0/0`, Mosh API `11/0/0/0`, and extension
`8/0/0/0`. Focused connected contracts passed app 2/2 and extension 4/4 on exact Wi-Fi serial
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp` (`SM-F976B`). Final exact-serial installs of both
debug APKs succeeded and `MainActivity` cold-launched in 716 ms as `topResumedActivity`. Current
debug SHA-256 values are main `e6c6098c7421ce4015b30151b92553b281a7990dbc7a2c661609947db25530ec`
and extension `2e4b6f2ec1ff92d171885bfefdb2c18ebe88458cce0a17410ee5a00de0f0ad53`.

After every substantial code slice, run the relevant unit tests, `./gradlew test`, `./gradlew lint`, debug and release builds, and Android-test assembly before continuing. Then resolve the fresh output of `adb devices -l`, install/launch by exact serial on every currently connected authorized USB and Wi-Fi phone, verify `MainActivity` is foreground, and report offline, unauthorized, and failed targets. A slice is not green merely because one device or one build variant passed.

Historical closure tables below describe earlier slices only. Current-source evidence is limited to
the final-gate table and the Phase 12 evidence sections; the Wi-Fi app suite is environment-blocked,
and open manual, performance, and release checks remain explicitly pending:

| Current final gate | Result |
|---|---|
| Frozen full source gate | Current app unit, lint, debug APK, Android-test APK, release APK/AAB packaging verification, Mosh API/extension lint/build/native, and benchmark assembly gates passed |
| Fresh JVM reports | Tests/failures/errors/skipped: app `785/0/0/0`, `mosh-api` `11/0/0/0`, extension `8/0/0/0` |
| Local release-like gate | Existing Android debug keystore only, not production signing: `BUILD SUCCESSFUL` in 1 min 28 s with 159 tasks; app release APK/AAB and extension release APK signatures verified; both APKs installed on disposable API 35 AVD `terminal-spike-release-test`; app cold launch 504 ms and `MainActivity` `topResumedActivity` |
| Connected Android tests on every freshly enumerated authorized target | USB app runner reported 252 tests with no failures. Opt-in real JSch/OpenSSH-with-Mosh-absent, Standard/Full opaque-provider backup, and physical-KeyEvent proofs passed. `mosh-api` 5/5 and extension 3/3 passed on both USB and Wi-Fi. The attempted Wi-Fi full app UI suite remains environment-blocked by secure keyguard/dozing |
| Exact-serial latest main/Mosh debug APK install and `MainActivity` foreground proof | Version `0.0.1` app SHA-256 `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408` and extension SHA-256 `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3` installed successfully on USB and Wi-Fi. USB `MainActivity` was `topResumedActivity` and the current focused window; Wi-Fi reported it as the resumed/focused app behind the secure Bouncer, so unobscured foreground proof remains external |
| Performance measurements | Connected profile/Macrobenchmark collection passed on USB API 36; manual latency/memory/resize/multi-session evidence remains pending |
| Final manual/release requirement audit | Pending; the user's own saved Mosh server still needs an unlocked manual retest |

## Phase 1 — Baseline and audit

- [x] Phase 1 complete.
- [x] Read `AGENTS.md`, README, architecture decisions, performance guidance, Gradle configuration, lint defaults, tests, dependency inventory, and third-party notices.
- [x] Inspect the clean starting worktree (`ff8435b`, `main`).
- [x] Record SDK/toolchain and direct SSH/terminal dependencies.
- [x] Run the initial unit-test, lint, debug-build, release-build, and Android-test assembly baseline.
- [x] Record the initial Android-test compile failure separately from task changes.
- [x] Repair the obsolete Compose test import and regenerate the Android-test APK.
- [x] Complete current storage, credential, known-host, session-lifecycle, renderer, and performance audit.
- [x] Create and reconcile every required architecture/security/backup/Mosh document.
- [x] Rerun all Phase 1 gates after documentation and baseline repair.
- [x] Install and launch the verified debug APK on every connected authorized phone after the Phase 1 code repair passes all required gates.

Initial baseline evidence:

| Gate | Result |
|---|---|
| `testDebugUnitTest` | Passed in 8.863 s |
| `lintDebug` | Passed in 1.703 s |
| `assembleDebug` | Passed in 1.087 s |
| `assembleRelease` | Passed in 43.844 s; unsigned release artifact produced |
| Initial `assembleDebugAndroidTest` | Failed: obsolete `assertDoesNotExist` import in `MainScreenSmokeTest.kt` |
| Repaired `assembleDebugAndroidTest` | Passed in 1.568 s; Android-test APK regenerated |

Phase 1 closure evidence:

| Gate | Result |
|---|---|
| `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest` | Passed in 21 s against unchanged worktree hash `d997f8ca…`; 136 tasks, full lint report written |
| `connectedDebugAndroidTest` | 15/15 passed in 27 s on exact serial `RZCW81JZ9CP` (`SM-S911B`, Android 16) |
| Exact-serial debug install | `adb -s RZCW81JZ9CP install -r …` succeeded |
| Cold launch / foreground | `MainActivity` launch status `ok`, 1,033 ms total; verified `topResumedActivity` |
| Fresh ADB target audit | USB target authorized; previously seen Wi-Fi `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` absent from list and therefore not updated |

## Phase 2 — Data and security foundation

- [x] Phase 2 complete.
- [x] Add stable UUID-backed host profiles with display name, hostname/IP, port, username, SSH/Mosh protocol, authentication reference, optional terminal/keyboard profile, favourite, group/tag, startup command, global-override keepalive/reconnect, Mosh port/range/server command, and created/updated timestamps.
- [x] Add credential models for password, private-key identity, and keyboard-interactive authentication; hosts reference credential IDs and never duplicate secret material.
- [x] Add SSH key identities with name, algorithm, public fingerprint/key, encrypted private payload reference, imported/generated and passphrase flags, creation date, and optional comment.
- [x] Add known hosts keyed by host+port with algorithm, fingerprint, public host key, first-seen, and last-seen.
- [x] Add snippets with name, optional group, command, insert/run mode, append-Enter, multiline-confirmation, favourite, and updated timestamp.
- [x] Add terminal profiles with theme/font/size/line height/letter spacing/cursor/scrollback/bell/scroll/link/TERM/alternate-history fields.
- [x] Add keyboard profiles with ordered actions, one/two rows, modifier behavior, haptics, repeat, input mode, tmux prefix, and per-host override support.
- [x] Add a relational application database with exported schemas, explicit migrations, constraints, transactions, and migration tests.
- [x] Migrate the existing encrypted settings v1–v3 data without losing profiles, identities, snippets, key order, saved-password references, or known hosts.
- [x] Add Proto DataStore 1.2.1 with an explicit schema and migrations for typed global settings; store no secret payloads in it.
- [x] Add a `CredentialStore` abstraction for passwords, private keys, and explicitly saved passphrases.
- [x] Preserve non-exportable Android Keystore keys and authenticated encryption at rest.
- [x] Handle unavailable, invalidated, corrupt, and legacy credentials without silently deleting them; expose one separately confirmed full app-data reset from every fail-closed startup and Settings recovery gate.
- [x] Add central log redaction and release logging policy.
- [x] Keep decrypted secrets out of UI state, saved state, navigation arguments, and logs.
- [x] Add host validation, migration, credential round-trip/error, and redaction tests.

Phase 2 closure evidence:

| Gate | Result |
|---|---|
| `./gradlew testDebugUnitTest` | Full JVM suite passed after the final cutover/security changes |
| `./gradlew lint` | Passed with debug, unit-test, and Android-test analysis; report generated |
| `assembleDebug assembleRelease` | Passed with R8, release lint-vital, packaging verification, and unsigned release APK |
| `assembleDebugAndroidTest` | Passed; fresh 1,376,875-byte Android-test APK produced |
| Focused `connectedDebugAndroidTest` | 49/49 Room, migration, credential, container, recovery, and smoke tests passed on exact serial `RZCW81JZ9CP` (`SM-S911B`, Android 16) |
| Exact-serial debug install | Latest 14,865,606-byte debug APK installed successfully on `RZCW81JZ9CP` |
| Cold launch / foreground | `MainActivity` launch status `ok`, 1,140 ms total; verified `topResumedActivity` |
| Fresh ADB target audit | One authorized USB target; no offline/unauthorized/failed targets and no Wi-Fi target present |

## Phase 3 — Navigation and design system

- [x] Phase 3 complete.
- [x] Keep exactly Connections, Terminal, and Settings as phone destinations per the latest product direction.
- [x] Remove Tools and the redundant Workspace-to-Connections route as top-level destinations.
- [x] Add compact `NavigationBar` and expanded-width `NavigationRail`/list-detail behavior.
- [x] Respect status, navigation, cut-out, gesture, and IME insets edge to edge.
- [x] Add reusable spacing, shape, typography, icon, status-color, feedback, and motion primitives.
- [x] Make app theme system/light/dark aware without changing terminal palettes unexpectedly.
- [x] Keep all debug surfaces behind `BuildConfig.DEBUG` or debug-only sources.
- [x] Move Known Hosts to Settings > Security.
- [x] Build Workspace in the required order: compact app bar, Active sessions, every saved host favourites-first with one-tap authoritative authentication, Recent connections, and primary New connection action.
- [x] Show active-session friendly name, SSH/Mosh badge, state, sanitized optional OSC title, last activity, reopen tap, and reconnect/disconnect/duplicate overflow without exposing passwords/private endpoint detail.
- [x] Add polished multi-session horizontal/list presentation and a no-session state with explanatory text, New connection, and optional Restore backup action.
- [x] Verify bottom navigation, predictive/back behavior, large text, light/dark, and compact/expanded layouts.

Phase 3 closure evidence:

| Gate | Result |
|---|---|
| `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest` | Passed in 1 min 50 s; 152 tasks, full lint analysis, release R8, and release packaging verification all succeeded |
| Focused Phase 3 device contracts | 14/14 live predictive-back, terminal-inset, light/dark, large-text, adaptive Workspace, paste, and mouse-acceptance tests passed on `RZCW81JZ9CP` |
| Full `connectedDebugAndroidTest` | 126/126 passed in 1 min 25 s on exact serial `RZCW81JZ9CP` (`SM-S911B`, Android 16) |
| Exact-serial debug install | Latest 15,452,656-byte debug APK installed successfully on `RZCW81JZ9CP` |
| Cold launch / foreground | `MainActivity` launch status `ok`, 1,234 ms total; verified `topResumedActivity` |
| Fresh ADB target audit | One authorized USB target; no Wi-Fi, offline, unauthorized, or failed target present |
| Independent completion audit | No remaining Phase 3 blocker after privacy, OSC, inset, input-acceptance, predictive-back, reconnect-lifecycle, and visual-contract re-audit |

## Phase 4 — Connections management

- [ ] Phase 4 complete — the final device walkthrough is pending.
- [x] Add polished Hosts, Keys, and Snippets tabs with clear selected state.
- [x] Search hosts across display name, username, hostname, and tags; search keys/snippets; add favourite/recent/group sorting and filtering.
- [x] Replace inline Edit/Use/X actions with row actions and confirmed overflow deletion.
- [x] Host rows show protocol/computer icon, friendly name, `user@hostname:port`, SSH/Mosh badge, favourite, active-session state, and overflow; row tap connects.
- [x] Host editor covers every model field, authentication method, credential/key selector, save-password explanation, test connection, field-local validation, unsaved-change protection, and conditional Mosh fields; Quick Connect preserves its selected saved private-key association.
- [x] Support editable/savable Mosh profiles while honestly blocking Mosh launch when the extension is absent or incompatible.
- [x] Add key import through SAF, Ed25519/RSA generation, copy-public-key, full selectable fingerprint detail, rename, search, and confirmed deletion while preserving passphrase protection and every OpenSSH format supported by JSch.
- [x] Key rows show friendly name, algorithm, short fingerprint, imported/generated provenance, and lock/passphrase status without private material.
- [x] Add snippet groups/favourites, copy and terminal access; default multiline snippets to insert, show Enter state, and require explicit confirmation before immediate multiline execution.
- [x] Add empty, no-results, loading, error, disabled, and success states.
- [x] Add host/key/snippet unit and UI coverage.

## Phase 5 — Settings

- [ ] Phase 5 complete — the persistence walkthrough is pending.
- [x] Add searchable hierarchical categories: Appearance, Terminal, Keyboard, Sessions & Background, Notifications, Backup & Restore, Security, Mosh Extension, About, and debug-only Developer.
- [x] Add system/light/dark mode, dynamic color toggle, restrained accents, and live persistence.
- [x] Add Current, Ayu Dark, One Dark, Dracula, Nord, Solarized Dark/Light, Gruvbox Dark, Tokyo Night, Catppuccin Mocha, High Contrast, and Custom terminal themes with licensed notices, live preview, ANSI 16, cursor/selection/bold-bright, edit/reset, and persistence.
- [x] Add System monospace, Source Code Pro, JetBrains Mono, IBM Plex Mono, Cascadia Mono, and Symbols Nerd Font Mono fallback; add bounded SAF custom-font import with proportional-font rejection and a live preview covering ASCII, box drawing, Powerline/Nerd, CJK, combining, emoji fallback, bold, and italic.
- [x] Add font size, line height, letter spacing, bold, ligatures-off-default/cell-alignment validation, pinch zoom, and reset.
- [x] Add scrollback presets/custom validation; block/underline/beam cursor/blink; visual/vibration/audible bell; URL/OSC 8; OSC 52 Disabled/Ask default; copy-on-selection; multiline paste; bracketed paste; alternate-history; touch/two-finger modes; input-follow/viewport policies; and reset.
- [x] Keep `xterm-256color` as the normal TERM default with per-host override and do not recommend global `screen-256color` for tmux.
- [x] Add General terminal, tmux/Codex, Vim, Minimal, and Custom keyboard presets plus layout/actions/modifier/haptic/repeat/tmux-prefix controls and per-host overrides.
- [x] Support Esc, Ctrl, Alt, Tab, Shift, arrows, Home/End, Page Up/Down, Insert/Delete/Enter, slash/backslash/pipe/tilde/backtick/hyphen/underscore/at, F1–F12, Ctrl+C/D/L/R/W/U/A/E, tmux prefix, Paste, Snippets, Hide keyboard, and Keyboard settings actions.
- [x] Add sessions, notifications, background health, security, Mosh status, local-first privacy, notices, version, and debug-only developer settings.
- [x] Add the live Mosh Extension status slice with Checking/Absent/Disabled/Untrusted/Incompatible/Available/Error, version/API/capabilities/session-limit details, and a functional refresh/retry action.
- [ ] Verify settings persist across restart and previews reflect saved values.

## Phase 6 — Terminal UI and rendering

- [ ] Phase 6 complete — measured renderer evidence is pending.
- [x] Use compact session chrome with friendly title, protocol/state, add-session, and overflow actions.
- [x] Support simultaneous session tabs, selection, protected close, safe immediate double-tap duplicate when authentication is reloadable, re-entry only for intentionally non-retained one-shot secrets, reorder where practical, and sanitized OSC titles.
- [x] Add reconnect, disconnect, find, clear scrollback, transcript export, full screen, settings, snippets, and connection details.
- [x] Add bounded local selection, handles, copy/select-all, OSC 8 link actions, and jump-to-latest behavior.
- [x] Preserve viewport position under new output and resize correctly under IME/orientation/multi-window/fold changes.
- [x] Preserve the custom Canvas renderer, bounded buffers, frame batching, cached metrics, and no per-cell/per-line Compose state.
- [x] Keep parser, screen, and renderer state separated; add dirty-row/region publication, reuse hot-path buffers, preserve exact monospace cell geometry, and keep parser/I/O off main.
- [x] Preserve/add ANSI/VT, 16/256/true color, SGR, alternate screen, cursor save/restore/shape, scroll regions, origin and insert/replace modes, bracketed paste, application cursor/keypad, DEC/SGR mouse, OSC title/8/52 policy, resize, combining/wide/emoji/box drawing, wrapped selection, and correct Backspace/Delete behavior.
- [x] Add recorded project-authored/standards-derived ANSI fixtures for each required mode, resize, Unicode, terminal application trace, randomly chunked input, and malformed/bounded recovery.
- [ ] Measure before/after input, output, scroll, resize, multi-session, memory, and renderer behavior.

## Phase 7 — Keyboard and IME

- [ ] Phase 7 complete — the physical IME walkthrough is pending.
- [x] Replace the heavy square grid with one/two refined accessible rows that respect insets; give every action a standalone at-least-48 dp target while preserving the exact default 20-key, 10 × 2 deck.
- [x] Add the complete accessory action set, presets, compact searchable multi-column replacement picker, drag reorder, and restore.
- [x] Add one-shot and double-tap locked modifiers with distinct visuals and correct clearing.
- [x] Add long-press repeat, optional haptics, physical-keyboard independence, and configurable tmux prefix.
- [x] Keep Raw mode as default and add composed Text mode through the swipeable input page and persisted Settings choice without permanent Raw/Text selector labels.
- [x] Send only committed IME text; never send intermediate CJK/dead-key composition.
- [x] Support Unicode/emoji, deletion, clipboard, line breaks, bracketed paste, multiline confirmation, and Back-to-hide behavior.
- [x] Add IME composition, physical-keyboard, modifier, repeat, and paste tests.

## Phase 8 — Scrolling and tmux behavior

- [ ] Phase 8 complete — the real tmux/Vim/less walkthrough is pending.
- [x] Track normal/alternate screen, remote mouse mode/encoding, viewport, history, and dimensions.
- [x] Add Auto, Local scrollback, and Remote mouse user modes.
- [x] Route ordinary shell gestures to local scrollback and remote-app gestures to bounded wheel reports; never shell history keys.
- [x] Add two-finger local override and long-press local selection.
- [x] Add optional bounded alternate-screen history without corrupting full-screen redraw semantics.
- [x] Add jump-to-latest and native-feeling fling behavior that bounds remote events.
- [x] Add copyable tmux guidance without changing remote configuration.
- [x] Cover deterministic plain-shell, remote-mouse, two-finger override, and output-while-scrolled routing contracts; real tmux/Codex/less/Vim device walkthrough remains part of phase completion.

## Phase 9 — SSH reliability and background service

- [ ] Phase 9 complete — the notification/battery device matrix is pending.
- [x] Preserve JSch transport and strict first-contact/changed-key behavior, expose complete selectable trust fingerprints, and add keyboard-interactive support with clear redacted errors.
- [x] Add staged connection testing: DNS, TCP, negotiation, host key, authentication, and channel.
- [x] Add global/per-host protocol keepalive and bounded reconnect with connectivity awareness and cancel.
- [x] Never imply that a new SSH shell resumed the lost process; explain tmux continuity accurately.
- [x] Move live transport/session ownership from ViewModel/Activity into a process-level foreground session service.
- [x] Declare and start the defensible `specialUse` FGS only from a visible user action; document manifest subtype and Play declaration.
- [x] Add a private low-noise notification channel, privacy-aware content, Open/safe-disconnect actions, and denial behavior.
- [x] Request `POST_NOTIFICATIONS` contextually when background sessions are first enabled/started, explain denial, and add optional unexpected-disconnect/reconnect notifications without repeated nagging.
- [x] Show notification-enabled, background-restricted, battery-optimization, and Data Saver/background-network status where detectable.
- [x] Provide public intents for app notification settings, app details, battery-optimization settings, and app battery/background settings; use manufacturer-specific text only as fallback and no undocumented OEM intents.
- [x] Add optional CPU/screen-awake policies with immediate release and battery explanations.
- [ ] Verify behavior with notifications denied and battery mode Optimised and Restricted; never promise Android cannot kill the process or auto-request unrestricted battery access.
- [x] Add service, notification, recreation, keepalive, reconnect, and connectivity tests.

## Phase 10 — Backup and restore

- [ ] Phase 10 complete — the clean-install/provider round trip is pending.
- [x] Export one versioned product-specific backup through `ACTION_CREATE_DOCUMENT`.
- [x] Import streams through `ACTION_OPEN_DOCUMENT` without assuming filesystem paths or broad storage permission.
- [x] Add Standard and Full encrypted modes with explicit content summaries.
- [x] Derive keys with per-backup salted, benchmarked PBKDF2-HMAC-SHA256 and encrypt with AES-256-GCM.
- [x] Keep export/import passphrases in wipeable mutable buffers, clear them at operation boundaries, and never retain them in public or saved UI state.
- [x] Add magic/version/app metadata/KDF metadata and a forward-compatible structured payload.
- [x] Add authenticated preview, Merge/Replace/Keep both resolution, transactions, rollback, and a temporary recovery snapshot for Replace.
- [x] Exclude sockets, ephemeral Mosh keys, temporary prompts, transcripts, Keystore masters, and logs. Keep imported fonts excluded by default; expose a separate explicit opt-in, validate/install included content-addressed fonts before profile activation, and safely fall back to System monospace when a font was not included.
- [x] Add round-trip, wrong-passphrase, tamper, truncation, schema migration, duplicate conflict, rollback, and stream tests.
- [ ] Verify a backup between two clean installations and a cloud document provider when available.

## Phase 11 — Mosh extension

- [ ] Phase 11 complete — extension absence, private-key bootstrap, multi-session, resize, network-transition, and roaming evidence are still open.
- [x] Record the accepted local/debug architecture/licensing decision and pinned upstream/native dependency inventory; keep public distribution and production signing legally blocked.
- [x] Add a permissively licensed `mosh-api` Android library with versioned AIDL, parcelables, capabilities, errors, and no GPL implementation.
- [x] Add explicit package/component binding, signature-level permission, package signature verification, visibility query, and API negotiation.
- [x] Use `ParcelFileDescriptor` streams for terminal bytes and the one-shot key; reserve callbacks for bounded control/state events.
- [x] Bootstrap Mosh through the existing strict SSH path and pass only numeric endpoint/port/ephemeral key/session display data.
- [x] Clear the ephemeral key promptly and never send saved SSH passwords, private keys, passphrases, or credential IDs to the extension.
- [x] Produce a distinct `mosh-extension` application/APK with a distinct application ID and no native/GPL Mosh implementation in the main APK graph.
- [x] Build pinned official Mosh/native dependencies reproducibly for arm64-v8a and x86_64, with checksums, 16 KiB alignment, stripping, local symbols, GPL notices/source, and build instructions.
- [x] Add honest package/trust/API/server/locale/firewall/native-failure presentation, functional status retry, editable Mosh profiles, and explicit SSH fallback without silent downgrade.
- [x] Add Never/Ask/Automatic SSH-fallback profile settings while keeping every fallback transition explicit and truthful as a fresh SSH shell in session state.
- [x] Add fake-extension discovery, signature rejection, version mismatch, pipe lifecycle, extension death, and rebinding tests.
- [x] Wire Android connectivity changes to the implemented Mosh network-hint control call; core protocol roaming remains present without it.
- [x] Reverify password bootstrap and real UDP terminal input/output against the disposable local Mosh 1.4.0 server on the latest native build, sending every command byte separately and validating the reconstructed VT screen: Wi-Fi 1 plus repeated 5/5, USB 1/1, all green.
- [ ] Manually retest the user's own saved Mosh server while the device is unlocked; controlled local-server evidence does not establish external-server behavior.
- [ ] Verify private-key bootstrap, resize, simultaneous worker sessions, cleanup, and roaming against a controlled `mosh-server` on every target phone. Link-local IPv6 zone IDs are unsupported.
- [x] Verify the main APK launches and real SSH works with the extension uninstalled on the guarded disposable AVD.

Current Phase 11 implementation and device evidence:

| Gate | Result |
|---|---|
| `mosh-api` JVM/API contract | 11 focused version/validation tests passed; debug and release AARs produced without a native Mosh library |
| `mosh-api` PFD/Parcelable device contract | Fresh 5/5 focused tests passed on each of USB `RZCW81JZ9CP` and Wi-Fi `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp`; this does not by itself prove a live Mosh session |
| Main-app Mosh host tests | Strict bootstrap, transport adapter, discovery/negotiation, and status-presentation suites passed in the current unit-test output |
| Separate extension host/build | Eight JVM tests passed with no failures/errors/skips; debug and unsigned release APKs, Android-test APK, native-symbol archive, and byte-reproducible Corresponding Source archive `19da994e…4b4d` were produced |
| Mosh extension device contract | Fresh 3/3 connected tests passed on each of the USB and Wi-Fi targets |
| Native artifact inspection | `libmosh_extension.so` is present for `arm64-v8a` and `x86_64` in the extension APK with packaged licences/notices; it is absent from the main APK |
| Real Mosh E2E | Latest-build controlled local-server harness sends every command byte separately and reconstructs the VT screen: Wi-Fi 1 plus repeated 5/5, USB 1/1, all green. The user's own saved server remains a separate unlocked manual retest; no external-server claim is made |
| Frozen debug artifact identity | Main SHA-256 `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408`; extension SHA-256 `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3` |
| Final connected Android instrumentation | USB full app runner reported 252 tests with no failures. The credentialed real-server fixture passed separately with runtime arguments and Mosh absent. The Wi-Fi full app UI attempt remains environment-blocked because secure keyguard/dozing prevents Compose activities from owning a hierarchy |
| Foreground verification | USB `MainActivity` was `topResumedActivity` and focused. Wi-Fi reported `MainActivity` as `ResumedActivity` and the app focused behind `NotificationShade`/keyguard; that is recorded honestly rather than treated as unobscured foreground proof |

## Phase 12 — Final polish and release validation

- [ ] Phase 12 complete.
- [ ] Complete TalkBack semantics, focus order, touch targets, contrast, large font scale, adaptive widths, split screen, foldables, and both navigation modes.
- [x] Keep StrictMode enabled only in debug for thread disk/network and Activity/Closeable/registration leak detection, with no release penalty/logging.
- [x] Add plain-text, ANSI-heavy, rapid-cursor, full-redraw, CJK/wide, tmux-status, 10,000+-line, and several-megabyte benchmark fixtures.
- [ ] Collect cold-start, first-Workspace, tap-to-Connecting, input latency, 5,000-line/s output, 100,000-line history scroll, 60-Hz full-screen redraw, resize, multi-session, memory, thermal, and device frame evidence.
- [x] Add compatible Macrobenchmark and Baseline Profile modules with managed-device default and explicit connected-device opt-in.
- [x] Add a documented local OpenSSH Docker Compose test server with test-only password/key fixtures and host-key mismatch flow; never package credentials in release.
- [ ] Add visual tests for primary screens in light/dark, compact/expanded, large font, and empty/populated/loading/error states.
- [x] Review dependency graph, notices, R8 rules, manifest exports, backup exclusions, screenshot/clipboard behavior, and store disclosures.
- [x] Verify release has no test credentials, debug pages, plaintext secrets, transcript/protocol logging, analytics, ads, AI, accounts/teams/subscriptions, cloud sync, hidden requests, fake/inert controls, SFTP/file browser, port-forwarding dashboard, or unrelated feature expansion.
- [x] Update README, architecture, security/backup/Mosh docs, dependency inventory, third-party notices, and CHANGELOG to match reality.
- [x] Run the frozen full source gate: `BUILD SUCCESSFUL` in 3 min 25 s across 385 tasks (57 executed); app JVM `783/0/0/0`, `mosh-api` `11/0/0/0`, and extension `8/0/0/0` tests/failures/errors/skipped; debug/release lint and builds, release APK/AAB packaging verification, both Android-test APKs, `arm64-v8a`/`x86_64` native outputs, and benchmark assembly all green.
- [x] Resolve the fresh `adb devices -l` targets and install both version `0.0.1` frozen debug APKs successfully by exact serial on authorized USB and Wi-Fi phones.
- [ ] Complete unobscured `MainActivity` foreground proof on every target: USB is `topResumedActivity`/focused; Wi-Fi is `ResumedActivity`/app-focused behind `NotificationShade`/keyguard and remains environment-blocked.
- [x] Run the local release-like build/signature/install smoke with the existing Android debug keystore on disposable API 35 AVD `terminal-spike-release-test`; keep it explicitly separate from production-signing evidence.
- [x] Verify the release-like main app launches, preserves a saved host across same-certificate update, and real SSH works before/after with the extension absent.
- [ ] Verify supported behavior on Android 13, 14, 15, and 16 where the available SDK/toolchain/devices or emulators permit, including notification denial, settings restart, Optimised/Restricted battery, and honest process-death limitations.
- [ ] Complete a final manual walkthrough and requirement-by-requirement audit.

Current final-source performance evidence:

| Gate | Result |
|---|---|
| Connected Baseline/Startup Profile generation | Passed in 4 min 46 s on exact serial `RZCW81JZ9CP` (`SM-S911B`, Android 16/API 36) |
| Generated Baseline Profile | `baseline-prof.txt`, 23,280 lines, SHA-256 `1fab54f82077759e9367a36e2479567846ef99f600e1e90545e7c326e73e2cb0` |
| Generated Startup Profile | `startup-prof.txt`, 18,654 lines, SHA-256 `48fde97176bd368f189f3810294426ff9cea74808136b54bfc3fe0da51b14598` |
| Connected release benchmark suite | Passed in 5 min 31 s; 12 tests, 0 failures, 0 errors, 2 expected `BaselineProfileGenerator` skips |
| Benchmark journeys | Startup 2/2, primary navigation 1/1, terminal fixtures 6/6, asset contract 1/1; traces and `benchmarkData` JSON collected |
| Remaining evidence | Input-to-render, tap-to-Connecting, refresh/power/thermal, memory, resize, and simultaneous live sessions remain open |

Frozen artifact final device evidence:

| Target/gate | Result |
|---|---|
| Fresh ADB audit | Authorized USB `RZCW81JZ9CP` (`SM-S911B`) and authorized Wi-Fi `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` (`SM-S936U`) |
| APK identity | Version `0.0.1`; main debug SHA-256 `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408`; Mosh extension debug SHA-256 `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3` |
| Exact-serial installs | App and extension installs returned `Success` on both USB and Wi-Fi targets |
| USB app connected gate | Runner reported 252 tests with no failures; physical-KeyEvent and opaque-provider backup coverage are included, and the runtime-credentialed JSch/OpenSSH fixture passed separately with Mosh absent |
| Wi-Fi app connected gate | Attempted, then stopped because secure keyguard/dozing prevented Compose activities from owning a hierarchy; environment-blocked, not green |
| `mosh-api` connected gate | 5/5 passed on USB and 5/5 passed on Wi-Fi |
| Mosh extension connected gate | 3/3 passed on USB and 3/3 passed on Wi-Fi |
| USB foreground | `com.yanjiyu.terminalspike/.MainActivity` verified as `topResumedActivity` and focused |
| Wi-Fi foreground | `MainActivity` reported as `ResumedActivity` and the app focused behind `NotificationShade`/keyguard; unobscured foreground proof remains environment-blocked |

Local release-like evidence, distinct from production signing and public-release approval:

| Gate | Result |
|---|---|
| Signing scope | Existing Android debug keystore only; not a production signing identity |
| Release-like build | `:app:assembleRelease :app:bundleRelease :mosh-extension:assembleRelease` passed with `BUILD SUCCESSFUL` in 1 min 28 s; 159 tasks |
| App release APK | SHA-256 `29f64e9b6ac233a8546f5ae934962212804d8a62e6a7004657cc47d307990b1b` |
| Mosh extension release APK | SHA-256 `206c74610de6cc808dcccae8e14d32dedc8e99efea2e32a85f60b004174c4c49` |
| App release AAB | SHA-256 `27ae1393b34ed1a42edc031352229ef3106049adbe0ce033c893f7519355f61f` |
| Signature verification | Both APK signatures verified and their certificate SHA-256 matched at `946b2b…a3ee`; AAB `jarsigner -verify` passed with expected self-signed/no-timestamp warnings |
| Disposable AVD smoke | On API 35 AVD `terminal-spike-release-test`, the frozen debug APK completed SSH, same-certificate `install -r`, retained its saved host, and completed SSH again with the Mosh package absent; `MainActivity` was foreground afterward |
| Evidence boundary | Does not prove production signing, store readiness, or public Mosh-extension clearance |

## Definition of done audit

- [ ] Bottom navigation reads Connections, Terminal, and Settings; Tools is not a top-level destination.
- [ ] Tools has been removed as a top-level destination everywhere.
- [ ] Connections is useful but uncluttered.
- [ ] Renderer Lab is unavailable in release.
- [ ] Connections has polished Hosts, Keys, and Snippets tabs.
- [ ] Host rows contain no inline Edit/Use/X debug-style actions.
- [ ] Hosts can be added, edited, tested, connected, searched, and deleted.
- [ ] SSH works at least as reliably as before.
- [ ] Host-key verification is secure.
- [ ] Saved credentials are encrypted.
- [ ] Appearance themes and font selections apply live and persist.
- [ ] Nerd/Powerline symbols render through an appropriate fallback.
- [ ] Custom font import works through SAF.
- [ ] Terminal settings apply and persist.
- [ ] Keyboard accessory rows are editable and polished.
- [ ] Raw and composed Text input modes work.
- [ ] Chinese/composing input sends no partial composition.
- [ ] Multiple terminal sessions work.
- [ ] Terminal rendering remains responsive under heavy output.
- [ ] Ordinary shell scrolling uses local scrollback.
- [ ] Tmux mouse scrolling sends correct remote wheel events.
- [ ] Two-finger local scrolling works while tmux owns the mouse.
- [ ] Touch scrolling never cycles command history.
- [ ] New output does not pull the user away from reviewed history.
- [ ] SSH keepalive and reconnect settings work.
- [ ] A foreground session service owns active sessions.
- [ ] The ongoing notification behaves correctly.
- [ ] Battery/background diagnostics and settings links work.
- [ ] Backup exports one portable file.
- [ ] Google Drive is selectable through the system picker when installed.
- [ ] Standard and Full encrypted backups restore correctly.
- [ ] Bad passphrases and tampered backups fail safely.
- [ ] The main APK works without Mosh installed.
- [x] Mosh is an optional protocol with honest extension status and explicit SSH fallback in the implemented UI/build.
- [x] The Mosh API is a secure, versioned cross-app boundary at source, AAR, and focused-test level.
- [x] The Mosh extension produces a genuinely separate APK with dual-ABI native Mosh code absent from the main APK.
- [x] The versioned IPC model and focused tests ensure SSH credentials are not passed into the Mosh extension.
- [x] Main/extension licence boundaries, pinned sources, notices, and reproducible build/source-bundle instructions are documented.
- [x] Final current-source main-app and extension unit/lint/build/package gates pass together; native extension outputs are current.
- [ ] Final current-source JVM/module/connected Android tests pass on every applicable target.
- [x] Final current-source `lintDebug` and `lintRelease` pass without broad suppression or unjustified exceptions.
- [ ] Final release inspection contains no test credentials, debug pages, plaintext secrets, analytics, or fake controls.
- [ ] Documentation and reported evidence accurately describe the final implementation.
