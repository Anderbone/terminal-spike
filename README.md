# Terminal Spike

Terminal Spike is an early native Android SSH terminal and renderer benchmark. It opens on a local, device-only workspace for hosts, identities, snippets, trusted host keys, and terminal configuration. A dedicated Compose terminal workspace surrounds a custom hardware-accelerated Android `View` that draws terminal rows directly with `Canvas` and `Paint`.

Password- or private-key-authenticated SSH, opt-in device-bound saved passwords, encrypted local host profiles, identities and snippets, a customizable extra-key bar, bounded VT/xterm screen semantics, and up to four simultaneous SSH session tabs are available for real-device shell testing. There is no Mosh, SFTP, sync, analytics, advertising, AI, or subscription code.

## Prerequisites

- A stable Android Studio version compatible with Android Gradle Plugin 9.3.1, or command-line Gradle prerequisites
- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer stable build tools supported by AGP 9.3.1
- Optional: an API 26+ device or emulator for installation and the instrumentation smoke test

The Gradle wrapper downloads Gradle 9.5.0. SDK paths belong in an ignored `local.properties` file or the normal `ANDROID_HOME`/`ANDROID_SDK_ROOT` environment variables; no SDK path is committed.

## Open and build

Open this directory in Android Studio, select a JDK 17 Gradle runtime, allow Gradle sync to finish, and choose the `app` run configuration.

From a shell:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Install and launch on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.yanjiyu.terminalspike/.MainActivity
```

Run the device smoke test with:

```bash
./gradlew connectedDebugAndroidTest
```

## Try a real SSH shell

1. From the local workspace, tap **New connection**. You can also open **Terminal** and tap **+ SSH**.
2. Enter a host, port, and username, then use a password or an imported private key. Password saving is off by default; opt in with **Save password on this device** while also saving the host. Key passphrases remain one-time only.
3. On first contact, compare the displayed SHA-256 fingerprint with a trusted fingerprint from the server administrator, then tap **Trust and connect**.
4. Tap the terminal to open the keyboard. The workspace resizes above the IME; hiding the keyboard restores the full terminal height. PTY resize is settled at the end of the keyboard animation to avoid repeated remote redraws. The default full-width deck has nine buttons on each of its two rows, including **EDIT**; use it to choose and reorder navigation keys, Ctrl shortcuts, symbols, function keys, and the hide-keyboard action.
5. Add another tab with **+ SSH**, or switch and close sessions from the tab rail. Each SSH tab keeps an isolated 20,000-line scrollback and live connection.
6. Tap **Disconnect** when finished.

Accepted host keys are stored in app-private storage. If a known key changes, connection is blocked. Trusted keys can be reviewed or forgotten from **Tools → Security**, and clearing app data removes them all.

## Local tools

Use the resource rows or **Tools** destination on the local workspace to manage saved hosts, extra keys, and command snippets. The same tools remain available from the terminal header.

- Host profiles save a display name, host, port, and username. A password can be saved separately only with explicit opt-in. Selecting that profile later enables Connect without entering the password; **Forget** removes it.
- SSH private keys are imported through Android's document picker, validated by the SSH library, encrypted under an app-specific Android Keystore key, and stored only in app-private storage. Encrypted-key passphrases are requested for each connection and never saved. Trusted host keys can be reviewed and removed from **Tools → Security**.
- The extra-key editor can show, hide, reset, and reorder Escape, modifiers, Tab, arrows, and paging keys. The visible order is used directly by the bottom key bar.
- Snippets support names, multiline commands, and optional Enter. A snippet is sent only after tapping **Send**, and only to the active connected SSH tab. Do not put passwords or access tokens in snippets.

Profiles, identity metadata, snippets, and the key layout are encoded with strict size/count bounds, encrypted with an app-specific Android Keystore AES-GCM key, and written atomically to app-private storage. Each imported private key and each opt-in password is separately bounded and AES-GCM encrypted. Password ciphertext is bound to the profile ID, host, port, and username so it cannot silently follow an edited endpoint. Android cloud backup and device-transfer backup are disabled for all app data. A corrupted or undecryptable settings file is discarded with a visible warning. There is no account or network sync.

## Wireless development install

After pairing a phone through Android's Wireless debugging screen, list exact ADB targets and install only to the chosen wireless serial:

```bash
scripts/install-wireless.sh --list
scripts/install-wireless.sh '<wireless-adb-serial>'
```

The script runs unit tests, lint, and a debug build before `adb install -r`, then launches the app. It rejects USB-looking serials to reduce the chance of updating the wrong connected phone.

## Workloads

- **Stream** appends deterministic terminal-like lines at stopped, 10, 100, 1,000, or 5,000 lines per second. Samples include styled output, paths, compiler and container logs, emoji, Chinese text, combining characters, malformed Unicode, and clipped long lines.
- **Full screen** replaces a fixed model at 1, 10, 30, or 60 updates per second. It simulates a changing tmux/htop-style table, progress display, and moving cursor without parsing escape sequences.
- **Preload** adds 1,000, 10,000, 50,000, or 100,000 lines in frame-friendly chunks. Scrollback remains bounded at 100,000 lines.

The workload is cancelled while the Activity is stopped and restarted with the retained selection when it becomes visible. Preload is also structured under the ViewModel and is cancelled by Stop, mode changes, replacement preload requests, or ViewModel clearing.

## Performance checks

The optional overlay reports diagnostic approximations for FPS, average/p95 recent frame interval, slow-frame counts, scrollback/visible/pending lines, heap usage, auto-follow, and workload. It publishes at most twice per second and is not a Macrobenchmark.

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

## Known limitations

- SSH supports passwords and imported OpenSSH/PEM key formats accepted by the pinned JSch release. Saved passwords are device-bound and can be used by anyone with access to the unlocked app; biometric-per-use locking is not yet implemented. Agent forwarding, saved private-key passphrases, and automatic reconnect are not implemented.
- Local profiles and snippets are device-local conveniences, not a credential vault. The Keystore-backed encryption protects the file at rest but cannot protect data from a compromised or unlocked device running the app.
- No optional cryptographic provider is bundled. SSH algorithm availability therefore depends on Android's installed JCA providers; the client does not re-enable obsolete RSA/SHA-1 algorithms.
- The bounded VT engine supports primary/alternate screens, cursor addressing, scroll regions, insert/delete/erase operations, xterm colours, application cursor keys, bracketed paste, focus reporting, wheel mouse reporting, resize, and common terminal queries. It is not yet a byte-for-byte xterm clone; mouse buttons/drags, OSC clipboard operations, every DEC private mode, and full conformance fixtures remain.
- Alternate-screen programs such as tmux retain a separate bounded local scroll history. When a remote program enables xterm mouse tracking, touch scrolling sends standard legacy or SGR wheel reports instead.
- For tmux-managed history, enable its mouse option once with `tmux set -g mouse on` (and add `set -g mouse on` to `~/.tmux.conf` to persist it). This also makes history from before the current app connection available through tmux copy mode.
- The engine tracks combining and common wide/emoji code points as terminal cells, while the renderer still estimates the physical cell width from a monospace `M`. Bidi layout and every grapheme/emoji sequence are not exact.
- Long lines are clipped. Selection, copy/paste UI, hyperlinks, accessibility exploration of individual rows, and configurable fonts are absent.
- The basic IME connection sends committed text and deliberately does not locally render in-progress composing text. Some IMEs may give limited composition feedback.
- Renderer statistics measure actual `FastTerminalView.onDraw` calls and CPU duration using fixed primitive rings. They do not isolate GPU/display work or replace Perfetto, `gfxinfo`, or Macrobenchmark.
- Smoothness has to be measured on actual target hardware; a successful build alone is not visual performance validation.
