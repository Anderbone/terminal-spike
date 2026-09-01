# Terminal Spike

Terminal Spike is a local-first native Android SSH terminal release candidate. It opens on a device-only workspace for active sessions, saved connections, identities, snippets, trusted host keys, and terminal configuration. A dedicated Compose application shell surrounds a custom hardware-accelerated Android `View` that draws terminal rows directly with `Canvas` and `Paint`.

Password- or private-key-authenticated SSH, opt-in device-bound saved passwords, protected private-key material, SFTP file management, a customizable extra-key bar, bounded VT/xterm screen semantics, and simultaneous remote-session tabs are implemented. Live SSH and Mosh terminals can paste a phone clipboard image into tools such as Codex: the app streams the image over the session's authenticated SSH side channel to a private cache directory on the connected host and bracket-pastes its remote path. Genuine Mosh 1.4.0 transport is available through an optional, separately installed GPL extension; the main APK contains only the permissive IPC API and continues to provide SSH when the extension is absent. The Mosh extension advertises and enforces its capacity of ten process-isolated concurrent transports independently. There is no sync, analytics, advertising, bundled AI, or subscription code.

## Prerequisites

- A stable Android Studio version compatible with Android Gradle Plugin 9.3.1, or command-line Gradle prerequisites
- JDK 25 for the Gradle daemon (the application source and bytecode toolchain remains JDK 17)
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer stable build tools supported by AGP 9.3.1
- Optional: an API 26+ device or emulator for installation and the instrumentation smoke test
- Optional Mosh extension build: Android NDK r29 revision `29.0.14206865` plus the host tools listed in [mosh-extension/BUILDING.md](mosh-extension/BUILDING.md)

The Gradle wrapper downloads Gradle 9.5.0. SDK paths belong in an ignored `local.properties` file or the normal `ANDROID_HOME`/`ANDROID_SDK_ROOT` environment variables; no SDK path is committed.

## Open and build

Open this directory in Android Studio, use its bundled JBR 25 (or another JDK 25) as the Gradle runtime, allow Gradle sync to finish, and choose the `app` run configuration. The checked-in daemon criteria keeps command-line and IDE builds on the same runtime while Android source compatibility remains Java 17. This also avoids the current lint parser's use of Java collection APIs that are unavailable when lint itself is hosted on JDK 17.

From a shell:

```bash
./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest
```

The 2026-08-19 current-source artifact gates completed successfully after the launcher-branding
refresh. Fresh JUnit totals in tests/failures/errors/skipped order were app JVM `815/0/0/0`,
`mosh-api` `11/0/0/0`, and Mosh extension `8/0/0/0`. Debug and release lint/build gates,
release APK/AAB packaging verification, both Android-test APKs, extension native outputs for
`arm64-v8a` and `x86_64`, and benchmark assembly were green. The resulting debug APK SHA-256 values
are main app `e6c6098c7421ce4015b30151b92553b281a7990dbc7a2c661609947db25530ec` and Mosh
extension `2e4b6f2ec1ff92d171885bfefdb2c18ebe88458cce0a17410ee5a00de0f0ad53`.

Focused current connected tests passed 2/2 for main launcher/lifecycle behavior and 4/4 for the
Mosh launcher/native smoke contract on authorized Wi-Fi target
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp` (`SM-F976B`). Both final debug APKs then installed
successfully by exact serial, and the app cold-launched in 716 ms with `MainActivity` reported as
`topResumedActivity`.

Final exact-serial installs of those version `0.0.1` artifacts succeeded on authorized USB
`RZCW81JZ9CP` (`SM-S911B`) and Wi-Fi
`adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` (`SM-S936U`). On USB, the full app runner
reported 252 tests with no failures. A separate opt-in test then carried a real command through
JSch to the disposable OpenSSH server with the Mosh extension absent, and Standard/Full encrypted
archives round-tripped through an opaque test DocumentsProvider. `mosh-api` connected tests
passed 5/5 and Mosh extension connected tests passed 3/3 on each phone. The full Wi-Fi app UI suite
was attempted, but secure keyguard/dozing prevented Compose activities from owning a hierarchy; it
is environment-blocked, not green. `MainActivity` was verified as `topResumedActivity` and focused
on USB. On Wi-Fi it was reported as `ResumedActivity`, with the app focused behind
`NotificationShade`/keyguard, so unobscured
foreground proof remains incomplete there.

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
signing. `:app:assembleRelease :app:bundleRelease :mosh-extension:assembleRelease` completed with
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

See [docs/PUBLISHING.md](docs/PUBLISHING.md) for the remaining production-signing, Play Console,
store-listing, manual acceptance, and Mosh legal gates.

Install and launch on a connected device:

```bash
adb -s '<exact-adb-serial>' install -r app/build/outputs/apk/debug/app-debug.apk
adb -s '<exact-adb-serial>' shell am start -n com.yanjiyu.terminalspike/.MainActivity
```

Run the device smoke test with:

```bash
./gradlew connectedDebugAndroidTest
```

## Optional Mosh-compatible extension

Mosh is not bundled into the main application. `mosh-api` is a project-owned Apache-2.0 AIDL contract, while `mosh-extension` is a distinct `com.yanjiyu.terminalspike.mosh` APK containing the pinned upstream Mosh client and its GPL/native materials. The main app performs the same strict SSH host-key verification and password/private-key authentication used for SSH, starts `mosh-server`, then transfers only bounded session metadata, the numeric UDP endpoint, and a one-shot ephemeral Mosh key to the verified extension. Terminal bytes travel through bounded file-descriptor pipes rather than Binder calls.

The version 1 extension contract does not negotiate a per-profile `TERM` value or arbitrary startup input. Mosh uses its supported fixed `xterm-256color` type; a different SSH `TERM` selection and any saved SSH startup command are not sent through the bootstrap command or extension IPC, and the main app surfaces that limitation when it applies.

Build the local/debug extension independently:

```bash
mosh-extension/scripts/verify-sources.sh
./gradlew :mosh-api:test :mosh-api:lint \
  :mosh-extension:test :mosh-extension:lint \
  :mosh-extension:assembleDebug :mosh-extension:assembleRelease
adb install -r mosh-extension/build/outputs/apk/debug/mosh-extension-debug.apk
```

The native build uses pinned official sources and produces `arm64-v8a` and `x86_64` clients. See [the extension build guide](mosh-extension/BUILDING.md), [the protocol boundary](docs/mosh-extension-protocol.md), and [ADR-003](docs/ADR-003-MOSH-EXTENSION-BOUNDARY.md). The latest build passed strengthened password-authenticated Mosh tests against the disposable local server on both authorized Android 16 phones: the harness writes every command byte separately and reconstructs the resulting VT screen, with one Wi-Fi run plus a repeated 5/5 Wi-Fi run and a 1/1 USB run all green. This validates only that controlled local environment; the user's own saved Mosh server still needs an unlocked manual retest, and no behavior on an arbitrary external server is inferred. Local/debug evaluation is approved, but public distribution and production signing remain blocked pending the ADR's specialist GPL, signing, installation-information, and trademark review.

## Try a remote shell

1. From **Connections**, add or choose a saved host. Choose **SSH**, or choose **Mosh** when Settings reports that the separately installed extension is verified and available.
2. Enter a host, port, and username, then use a password or an imported private key. Password saving is off by default; opt in with **Save password on this device** while also saving the host. Key passphrases remain one-time only.
3. On first contact, compare the displayed SHA-256 fingerprint with a trusted fingerprint from the server administrator, then tap **Trust and connect**. Mosh uses this strict SSH step to authenticate and start `mosh-server`, then switches the terminal transport to the server's UDP port.
4. Tap the terminal to open the keyboard. The workspace resizes above the IME; hiding the keyboard restores the full terminal height. PTY resize is settled at the end of the keyboard animation to avoid repeated remote redraws. The default live deck is exactly 20 buttons in two rows of ten, with an image button immediately left of **Home** for selecting up to 20 photos, direct **^C** and **^W** chords, and a stacked-window key in the former Alt slot that opens the active tmux-session switcher; customize it from **Settings → Keyboard**. Selected images and multi-image clipboard content are uploaded and inserted in order so compatible terminal tools can show several attachments in the current input. Long-press terminal output to select it; the keyboard hides so the local **Copy** and **Select all** toolbar and drag handles remain visible. Swipe the input strip to its left page for a normal editable field with correction and selection, then tap **Send** to paste the exact staged text without Enter. Swipe back to restore immediate terminal input with correction, completion, and suggestions disabled. The compact strip has no permanent Raw/Text selector labels; the same default mode can also be chosen in Settings.
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

Active transports are owned by the user-started foreground session service rather than by an Activity. Its low-noise notification exposes safe open/disconnect actions and privacy-aware content. Navigating away or recreating the Activity does not implicitly disconnect a session, but Android may still terminate the process; the app does not claim to resurrect a lost live socket after process death.

Connected terminal tabs also accept bounded task-attention events. OSC 9 retains its message on direct SSH, while a text-mode BEL produces a generic **Terminal task complete** notification and survives both tmux and Mosh. BEL used only as an OSC terminator is not an alert. Tapping the notification opens its originating app tab; closing or disconnecting that tab ends its notification path. Because BEL is a general terminal signal, any program that emits one can use this channel. For Codex turn completion across SSH, tmux, and Mosh, configure Codex before starting it:

```toml
[tui]
notifications = ["agent-turn-complete"]
notification_method = "bel"
notification_condition = "always"
```

Codex reads this configuration at process startup. Android notification permission and the **Terminal task completion** notification channel must remain enabled on the phone.

## Wireless development install

After pairing a phone through Android's Wireless debugging screen, list exact ADB targets and install only to the chosen wireless serial:

```bash
scripts/install-wireless.sh --list
scripts/install-wireless.sh '<wireless-adb-serial>'
```

The script runs unit tests, lint, and a debug build before `adb install -r`, then launches the app. It rejects USB-looking serials to reduce the chance of updating the wrong connected phone.

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
adb shell dumpsys gfxinfo com.yanjiyu.terminalspike reset
# Exercise one scenario for a fixed period.
adb shell dumpsys gfxinfo com.yanjiyu.terminalspike
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
- Mosh requires the separate extension, a reachable `mosh-server`, and its selected UDP port/range through the server and network firewall. SSH remains independently usable without the extension.
- The upstream Mosh transport retains authenticated UDP roaming and port-hopping behavior. The main app now publishes generation-scoped Android network hints to the active Mosh transport; real Wi-Fi/cellular/VPN transition acceptance remains open. IPv6 link-local hosts containing a zone identifier such as `%wlan0` are not supported; use a globally routable IPv6 address/name or IPv4.
- Public distribution of the Mosh-compatible extension is blocked by ADR-003 until licensing, Corresponding Source/installation-information, signing, and name-use review is complete.
- The bounded VT engine supports primary/alternate screens, cursor addressing, scroll regions, insert/delete/erase operations, xterm colours, application cursor keys, bracketed paste, focus reporting, wheel mouse reporting, resize, OSC 8 links, policy-gated OSC 52 clipboard requests, and common terminal queries. It is not yet a byte-for-byte xterm clone; every DEC private mode and full conformance fixtures remain.
- App-selected tmux sessions seed a separate bounded local model from tmux's physical pane history and keep it synchronized with live rows. Drag and fling use the same pixel viewport as an ordinary terminal. Tmux copy mode and inner applications that actually enable terminal mouse tracking continue receiving remote mouse input; an alternate screen alone, including a Codex-style UI, no longer forces row-wheel scrolling.
- For manually launched tmux and explicit remote-mouse mode, enable its mouse option once with `tmux set -g mouse on` (and add `set -g mouse on` to `~/.tmux.conf` to persist it).
- The engine tracks combining and common wide/emoji code points as terminal cells, while the renderer still estimates the physical cell width from a monospace `M`. Bidi layout and every grapheme/emoji sequence are not exact.
- Long lines are clipped. Terminal selection/copy, safe OSC 8 link actions, private SAF-imported fonts, and the release-cleared Symbols Nerd Font Mono fallback are present; full row-by-row accessibility exploration remains open.
- Direct terminal input sends committed text immediately and deliberately suppresses correction and local rendering of in-progress composition. The separate bounded input page provides normal IME composition, correction, and selection before an explicit paste.
- Renderer statistics measure actual `FastTerminalView.onDraw` calls and CPU duration using fixed primitive rings. They do not isolate GPU/display work or replace Perfetto, `gfxinfo`, or Macrobenchmark.
- Smoothness has to be measured on actual target hardware; a successful build alone is not visual performance validation.
