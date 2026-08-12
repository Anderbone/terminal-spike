# Changelog

All notable product changes are recorded here. The project is still in development and has not
assigned a public semantic version to this release candidate.

## Unreleased

### Added

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
- Editable 18-key terminal accessory deck, keyboard presets/actions, one-shot and locked
  modifiers, Raw/Text input modes, committed-composition handling, repeat, haptics, and tmux help.
- Versioned streaming Standard and Full encrypted backup/restore through Android’s document picker,
  with authenticated preview, conflict strategies, transactional apply, and crash recovery.
- Optional, separately installed Mosh extension APK with a versioned signature-verified Binder API,
  SSH bootstrap, explicit Never/Ask/Automatic fresh-SSH fallback, two native ABIs, source bundle,
  notices, and lifecycle tests.
- App lock, screenshot privacy, notification privacy, clipboard expiry, clear-credentials flow,
  local-data recovery reset, local-first privacy statement, and open-source notices.
- User-triggered foreground LAN SSH discovery using Android NSD without subnet scanning or saved
  credential inference.
- Reproducible terminal fixtures, Macrobenchmark/Baseline Profile module, local OpenSSH Docker test
  environment, byte-fragmented real-Mosh command delivery with reconstructed VT-screen assertions,
  and release-packaging integrity checks.

### Changed

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

- Corrected the native Mosh `Overlay::PredictionEngine` type used by the final extension build.
- Preserved the selected saved private-key association when a host is started through Quick Connect.
- Kept backup import/export passphrases in wipeable mutable buffers and cleared them at operation
  boundaries instead of retaining immutable or saved UI copies.
- Exposed the same separately confirmed full app-data reset from every fail-closed startup and
  Settings recovery gate.
- Gave every accessory action a standalone at-least-48 dp touch target without changing the exact
  default deck of 18 keys arranged as 9 × 2.
- Updated the stale Settings section-count instrumentation assertion and targeted the embedded
  native `EditText` directly so the full app connected suite exercises the current UI hierarchy.

### Validation

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
