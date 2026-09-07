# Changelog

All notable product changes are recorded here. The project is still in development and has not
assigned a public semantic version to this release candidate.

## 0.0.3 (6) — 2026-09-07

### Added

- Added a project-owned terminal-window launcher family for the main app and Mosh extension,
  including adaptive, round, Android 13 monochrome, reproducible SVG, and 512 px Play store assets.
- Polished Workspace, Terminal, and hierarchical Settings primary experiences, plus a first-class
  Connections catalogue for hosts, keys, and snippets, with adaptive phone and expanded layouts;
  Workspace now presents every saved host favourites-first with one-tap authoritative authentication.
- UUID-backed hosts, encrypted credentials and keys, snippets, known hosts, terminal profiles,
  keyboard profiles, recent sessions, and schema-preserving Room/DataStore migrations.
- Secure first-contact, trust-once, saved-trust, changed-key replacement, password, private-key,
  passphrase, and bounded keyboard-interactive SSH flows.
- Application-scoped SSH/Mosh session ownership, foreground-service notification policy,
  keepalive, network-aware bounded reconnect, battery/background diagnostics, and optional wake
  policies.
- Native Canvas terminal selection, links, find, transcript export, fixed cell-grid rendering,
  dirty-region updates, terminal bell policy, OSC 8/52 policy, scroll modes, two-finger local
  override, and compact multi-session chrome.
- Built-in terminal theme catalogue, persistent custom theme editor, live appearance previews,
  System monospace, Source Code Pro, JetBrains Mono, IBM Plex Mono, Cascadia Mono, private TTF/OTF
  import, and Symbols Nerd Font Mono fallback.
- Editable 20-key terminal accessory deck, keyboard presets/actions, one-shot and locked
  modifiers, Raw/Text input modes, committed-composition handling, repeat, haptics, and tmux help.
- Large terminal-tab and live tmux-session switchers with direct jump and confirmed close actions;
  the default accessory deck now opens the tmux switcher in the former Alt slot.
- Phone clipboard image paste for live SSH and Mosh terminals: PNG, JPEG, WebP, and GIF content is streamed
  through the authenticated session's SFTP channel and inserted as a Codex-compatible remote path.
- Multi-image photo selection and clipboard paste for terminal input, with ordered uploads and an
  image selector replacing Paste on the shipped default accessory deck.
- Versioned streaming Standard and Full encrypted backup/restore through Android’s document picker,
  with authenticated preview, conflict strategies, transactional apply, and crash recovery.
- Optional, separately installed Mosh extension APK with a versioned signature-verified Binder API,
  SSH bootstrap, explicit Never/Ask/Automatic fresh-SSH fallback, two native ABIs, source bundle,
  notices, and lifecycle tests.
- App lock, screenshot privacy, notification privacy, clipboard expiry, clear-credentials flow,
  local-data recovery reset, local-first privacy statement, and open-source notices.
- User-triggered foreground LAN SSH discovery using Android NSD without subnet scanning or saved
  credential inference.
- Review-before-send terminal voice input using Android speech recognition, with a clearly visible
  Listening/Stop state and a persisted device/English/Chinese/Japanese/Korean/French/German/Spanish
  language choice in Keyboard settings.
- Reproducible terminal fixtures, Macrobenchmark/Baseline Profile module, local OpenSSH Docker test
  environment, byte-fragmented real-Mosh command delivery with reconstructed VT-screen assertions,
  and release-packaging integrity checks.
- Added a fixed real-Mosh old-phone acceptance runner with per-operation serial/model verification,
  exact-address LAN exposure, deterministic fixture reset/teardown, password/private-key coverage,
  strict named-result and skip checks, sanitized reports, and tested failure cleanup.

### Changed

- Reconciled exact old-phone release evidence for Samsung IME input, physical landscape, maximum
  system text, and real Samsung split screen while retaining physical TalkBack focus traversal as
  an explicit manual gate.
- Bounded tmux history comparison and replacement construction to 2,000 rows per display callback,
  with cancellation of stale preparation and atomic publication of complete history.
- Android 17 local-network access is now requested only from explicit SSH, Mosh, or SFTP actions
  whose literal or off-main resolved address is local. One pending action survives Activity
  recreation without persisting credentials, denial starts no session, public and unresolved-host
  actions bypass the broad grant, and the system-mediated Nearby SSH picker remains
  permission-preserving. The API 37 runner also proves process death and denied-state relaunch
  after permission revocation.
- Added a stable API 37 real-network gate against the emulator's RFC1918 host gateway: denial must
  block raw TCP and production SSH before protocol traffic, while a real grant must complete SSH
  terminal traffic plus SFTP upload, download, and deletion against the identical OpenSSH fixture.
- New host editors now require an explicit opt-in before storing a password, matching Quick
  Connect and the documented device-encrypted credential policy. Existing hosts with an available
  saved password continue to show that state without exposing the value.
- Updated and hardened the release-like install/update smoke for the current Connections-first UI,
  strict main-package/version/certificate preflight, repeated named-AVD verification, explicit tmux
  routing, bounded temporary cleanup, and fixture teardown on every exit.
- Added native inertial continuation for tmux/remote mouse-mode touch scrolling while keeping wheel
  reports line-height-thresholded and bounded per display frame.

- Long-pressing a detected terminal URL now prioritises a focused Open link / Copy link action menu;
  Open link continues through Android's default browser handling.
- Kept compact primary navigation visually fixed when switching between Connections and Settings,
  and let immersive terminals use the status-bar area while retaining bottom/side safe insets.
- Compacted tmux session chooser rows while keeping the entire named session row tappable.
- Made remote terminal tabs follow safe OSC window-title updates, including concise tmux session
  names, while retaining the connection name as the fallback.
- Made bundled JetBrains Mono the default terminal font while retaining System monospace as an
  explicit option.
- Replaced debug-style host/key/snippet controls and the long accessory-key replacement list with
  deliberate menus, confirmations, a compact searchable multi-column key grid, full selectable
  known-host fingerprints, and 48 dp accessory hit targets.
- Removed persistent Raw/Text selector labels while retaining swipe and Settings access to both input
  modes.
- Made connection-tab double tap start a duplicate immediately when authentication is reloadable;
  only deliberately non-retained one-shot secrets require re-entry.
- Reject proportional custom terminal fonts, and restore profiles that referenced excluded custom
  font files with System monospace while retaining profile, host, and default-profile references.
- Add an off-by-default custom-font backup option with size/licensing disclosure, content-hash and
  monospace validation, transactional file preparation, and process-death journal reconciliation.
- Moved terminal transport lifetime out of Activity/ViewModel ownership and kept navigation or
  recreation from implicitly disconnecting sessions.
- Kept the main APK fully independent from native Mosh/GPL implementation code.

### Fixed

- Prevented an isolated Mosh worker or broker crash from being reported as a clean disconnect when
  its terminal PFD reaches EOF just before the authoritative Binder lifecycle callback.
- Made Codex-style parenthesized links such as `Google (https://www.google.com)` tappable and
  selectable without treating the opening parenthesis as part of the URL.
- Restored whole-link Open/Copy actions for URLs that span terminal soft wraps, including tmux
  history rows, while keeping hard-ended rows separate.
- Migrated the released 20-key default deck that combined the tmux switcher with Paste so its
  intended image-selection key is restored without changing custom keyboard profiles.
- Restored immediate clearing after a successful Text-mode paste while retaining a one-tap undo
  copy for recovery when a remote full-screen app had moved its own input focus.
- Made raw terminal input reliably disable terminal-unsafe correction and completion, kept local
  Copy and Paste available, hid the IME during local output selection, and made the tmux chooser
  explain a missing/failed server check as well as offering a new protected session. The tmux probe now runs
  portably through `/bin/sh`, including for Fish login shells, and falls
  back to the remote account shell when non-interactive SSH and terminal `PATH` values differ.
- Made terminal selection, link, and approved remote clipboard writes ordinary plain-text clips
  without app-private description metadata, while retaining safe delayed-clear ownership through
  Android's system clipboard timestamp.
- Corrected the native Mosh `Overlay::PredictionEngine` type used by the final extension build.
- Preserved the selected saved private-key association when a host is started through Quick Connect.
- Kept backup import/export passphrases in wipeable mutable buffers and cleared them at operation
  boundaries instead of retaining immutable or saved UI copies.
- Exposed the same separately confirmed full app-data reset from every fail-closed startup and
  Settings recovery gate.
- Gave every accessory action a standalone at-least-48 dp touch target without changing the exact
  default deck of 20 keys arranged as 10 × 2.
- Updated the stale Settings section-count instrumentation assertion and targeted the embedded
  native `EditText` directly so the full app connected suite exercises the current UI hierarchy.

### Validation

- The 2026-09-02 full source gate passed 475 Gradle tasks with app JVM `939/0/0/0`, Mosh API
  `11/0/0/0`, and extension `8/0/0/0`. On the exact model-checked old `SM-S911B`, the full app
  runner passed `315/0/0/13`; a separately enabled real-Mosh class passed `4/0/0/0`, covering
  ordered terminal output, four simultaneous resize-isolated sessions, worker death/slot reuse,
  and broker death/rebind with fresh traffic. A separate private-key bootstrap run passed. The
  fold was not used for tests.
- The guarded self-cleaning real-Mosh runner subsequently repeated the required password class
  `4/0/0/0` and private-key method `1/0/0/0` on the exact model-checked old `SM-S911B`; its
  disposable fixture was confirmed stopped afterward and its durable reports contained no fixture
  credential, private key, or LAN address. The unchanged current main APK was then reinstalled and
  foreground-verified on that old phone; the connected fold was untouched.
- A fresh ephemeral-key release-like `0.0.2` gate passed on named disposable API 35 AVD
  `terminal-spike-release-test`: release APK and AAB plus the separately built extension APK were
  signed and verified, both APK signers matched, a clean main-app install connected through real
  SSH with the extension absent, same-certificate `install -r` preserved the non-secret host, an
  actual app restart prompted for the deliberately unsaved password, and the second real SSH
  marker passed. The disposable fixture was stopped and temporary signing material was removed.
- After adding the new-host password opt-in regression, the complete current-source old-phone app
  runner passed `316/0/0/13` (303 executed passes), and the guarded real-Mosh 4+1 matrix passed
  again with no skips or failures. The final current debug APK was installed and `MainActivity`
  was verified top-resumed on the same model-checked old phone; the connected fold was untouched.
- The 2026-08-19 branding gate passed the complete unit, lint, debug/release build,
  Android-test-assembly, release APK/AAB packaging, Mosh native, and benchmark suite (482 Gradle
  tasks). Focused launcher/native device contracts passed 2/2 for the app and 4/4 for the extension
  on `SM-F976B`; exact-serial installs succeeded and `MainActivity` cold-launched as
  `topResumedActivity` in 716 ms.
- The frozen current-source gates completed successfully. Tests/failures/errors/skipped were app
  JVM `785/0/0/0`, `mosh-api` `11/0/0/0`, and
  extension `8/0/0/0`; debug/release lint and builds, release APK/AAB packaging verification, both
  Android-test APKs, native extension outputs for `arm64-v8a` and `x86_64`, and benchmark assembly
  were green.
- Frozen debug SHA-256: main app
  `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408`; Mosh extension
  `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3`.
- Exact-serial installs of both version `0.0.1` artifacts succeeded on USB `RZCW81JZ9CP`
  (`SM-S911B`) and Wi-Fi `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` (`SM-S936U`). USB
  `MainActivity` was `topResumedActivity`/focused; Wi-Fi reported it as `ResumedActivity` with the
  app focused behind `NotificationShade`/keyguard, so unobscured foreground proof is not claimed
  there.
- The final USB full app connected runner reported 252 tests with no failures. A separate
  runtime-credentialed test passed against real OpenSSH with Mosh absent; opaque-provider
  Standard/Full backup and physical-KeyEvent coverage also passed.
  `mosh-api` passed 5/5 and the Mosh extension passed 3/3 on both USB and Wi-Fi.
- The attempted Wi-Fi full app UI suite was stopped because secure keyguard/dozing prevented
  Compose activities from owning a hierarchy. It remains environment-blocked, not green. Manual
  behavior, exact-device latency/memory/resize/multi-session observations, the user's saved-server
  Mosh retest, and external distribution/legal gates also remain open.
- A separate local release-like gate used the existing Android debug keystore, not production
  signing. The app release APK, app AAB, and extension release APK built in 1 min 28 s/159 tasks;
  APK signatures and the AAB signature verified, both APKs installed on disposable API 35 AVD
  `terminal-spike-release-test`, and the app cold-launched in 504 ms with `MainActivity` as
  `topResumedActivity`. Artifact SHA-256 values are app APK
  `29f64e9b6ac233a8546f5ae934962212804d8a62e6a7004657cc47d307990b1b`, app AAB
  `27ae1393b34ed1a42edc031352229ef3106049adbe0ce033c893f7519355f61f`, and extension APK
  `206c74610de6cc808dcccae8e14d32dedc8e99efea2e32a85f60b004174c4c49`. This is not public-release
  or production-signing evidence.

### Security

- Secrets remain mutable and wipeable across connection, test-connection, backup, and prompt
  boundaries; public state, saved state, logs, notifications, and Mosh IPC contain no credentials.
- Backup and credential storage use authenticated encryption; Android Keystore master material is
  never exported.
- Release checks reject debug terminal surfaces, test credentials, prohibited logging, missing
  legal notices, and native Mosh code in the main APK.
