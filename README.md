# Terminal Spike

Terminal Spike is an open-source, local-first native Android SSH terminal release candidate. It opens on a device-only workspace for active sessions, saved connections, identities, snippets, trusted host keys, and terminal configuration. A dedicated Compose application shell surrounds a custom hardware-accelerated Android `View` that draws terminal rows directly with `Canvas` and `Paint`.

Password- or private-key-authenticated SSH, opt-in device-bound saved passwords, protected private-key material, SFTP file management, a customizable extra-key bar, bounded VT/xterm screen semantics, and simultaneous remote-session tabs are implemented. Live SSH and Mosh terminals can paste a phone clipboard image into tools such as Codex: the app streams the image over the session's authenticated SSH side channel to a private cache directory on the connected host and bracket-pastes its remote path. Genuine Mosh 1.4.0 transport is built into the same APK. Ten private worker processes isolate concurrent native transports. There is no sync, analytics, advertising, bundled AI, or subscription code.

## Source and licence

This is the canonical repository for the main Android app, Mosh API, native Mosh implementation,
tests and build tooling. Development and issues for the former standalone Mosh repository move
here. One APK is built from this checkout: `com.yanjiyu.terminalspike`, including SSH and Mosh.
No companion installation is required. Existing standalone extensions are no longer selected.

Project-owned code is **GPL-3.0-or-later**, with no warranty. `mosh-api` retains Apache-2.0;
third-party code, fonts and artwork retain their component licences. See [LICENSE](LICENSE),
[LICENSING.md](LICENSING.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the
[Mosh notices](mosh-core/THIRD_PARTY_NOTICES.md). Mosh is a registered trademark;
Terminal Spike is not affiliated with or endorsed by the Mosh project.

See [CONTRIBUTING.md](CONTRIBUTING.md) for changes and [SECURITY.md](SECURITY.md) for vulnerability
reports. The [consolidation record](docs/OPEN_SOURCE_MIGRATION.md) preserves source provenance;
older release and architecture records below describe their original verification context.

## Prerequisites

- A stable Android Studio version compatible with Android Gradle Plugin 9.3.1, or command-line Gradle prerequisites
- JDK 25 for the Gradle daemon (the application source and bytecode toolchain remains JDK 17)
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer stable build tools supported by AGP 9.3.1
- Optional: an API 26+ device or emulator for installation and the instrumentation smoke test
- Native Mosh build: Android NDK r29 revision `29.0.14206865` plus the host tools listed in [mosh-core/BUILDING.md](mosh-core/BUILDING.md)

The Gradle wrapper downloads Gradle 9.5.0. SDK paths belong in an ignored `local.properties` file or the normal `ANDROID_HOME`/`ANDROID_SDK_ROOT` environment variables; no SDK path is committed.

## Open and build

Open this directory in Android Studio, use its bundled JBR 25 (or another JDK 25) as the Gradle runtime, allow Gradle sync to finish, and choose the `app` run configuration. The checked-in daemon criteria keeps command-line and IDE builds on the same runtime while Android source compatibility remains Java 17. This also avoids the current lint parser's use of Java collection APIs that are unavailable when lint itself is hosted on JDK 17.

From a shell:

```bash
./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest
```

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

See [docs/PUBLISHING.md](docs/PUBLISHING.md) for the remaining production-signing, Play Console,
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
See [ADR-005](docs/ADR-005-BUNDLED-MOSH.md), [native build details](mosh-core/BUILDING.md) and
[the dependency inventory](mosh-core/DEPENDENCIES.md). Existing legacy extensions may remain
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
4. Tap the terminal to open the keyboard. The workspace resizes above the IME; hiding the keyboard restores the full terminal height. PTY resize is settled at the end of the keyboard animation to avoid repeated remote redraws. The default live deck is exactly 20 buttons in two rows of ten, with an image button immediately left of **Home** for selecting up to 20 photos, direct **^C** and **^W** chords, and a stacked-window key in the former Alt slot that opens the active tmux-session switcher; customize it from **Settings → Keyboard**. Selected images and multi-image clipboard content are uploaded and inserted in order so compatible terminal tools can show several attachments in the current input. Long-press terminal output to select it; the keyboard hides so the local **Copy** and **Select all** toolbar and drag handles remain visible. Swipe the input strip to its left page for a normal editable field with correction and selection, then tap **Send** to paste the exact staged text without Enter. A successful paste clears the visible field as before, while a one-tap undo action can restore the last sent draft if a remote full-screen app had moved its own input focus. Swipe back to restore immediate terminal input with correction, completion, and suggestions disabled. The compact strip has no permanent Raw/Text selector labels; the same default mode can also be chosen in Settings.
5. When **Settings → Sessions &amp; Background → Show tmux session selector** is enabled, every authenticated connection checks the remote server before opening its terminal. Choose **Start new session** to protect new work, **Attach** to resume an existing session, or **Open shell** to skip tmux. If tmux is missing or the check fails, the chooser says so instead of disappearing. Add another app tab with the trailing **+**, tap a short tab name to switch, use the stacked-window button in the top bar to jump to or close any app tab, double-tap a connection tab to duplicate it, or long-press it for disconnect/close actions. A remote tab automatically prefers a safe OSC 0/2 terminal title over its connection name; for a tmux session name, enable `set -g set-titles on` and use `set -g set-titles-string '#S'` in the remote tmux configuration. Stock tmux titles are shortened to the session name. A double tap starts the duplicate immediately when authentication can be safely reloaded; only an intentionally non-retained one-shot password or passphrase asks for re-entry. Each remote tab keeps isolated scrollback and connection state.
6. Tap **Disconnect** when finished.

Accepted host keys are stored in the app-private database. If a known key changes, connection is blocked. Trusted keys and their full selectable fingerprints can be reviewed or forgotten from **Settings → Security**, and clearing app data removes them all.

## Connections and settings

The phone shell has exactly three primary destinations: **Connections**, **Terminal**, and **Settings**. Connections is the main page for saved hosts, SSH keys, and snippets. **Terminal** owns active sessions, and **Settings** contains appearance, terminal, keyboard, background, backup, security, Mosh, and About/licence controls.

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

Use Android Studio CPU and Memory profilers to inspect `FastTerminalView.onDraw`, workload generation, GC frequency, and main-thread batches. See [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

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
production release. See [docs/PERFORMANCE.md](docs/PERFORMANCE.md) for scenario sizes and evidence
recording requirements.

## Known limitations

- SSH supports password, private-key, and bounded keyboard-interactive authentication, typed host-key trust/replacement, configurable TERM/startup commands, keepalives, and bounded fresh-shell reconnect. Saved secrets are device-bound and remain accessible to anyone who can unlock both the device and app. Agent forwarding is not implemented.
- Local profiles and snippets are device-local conveniences, not a credential vault. The Keystore-backed encryption protects the file at rest but cannot protect data from a compromised or unlocked device running the app.
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
