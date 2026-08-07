# Terminal Spike

Terminal Spike is Phase 0 of a possible commercial native Android SSH/Mosh terminal. It is deliberately a rendering and interaction benchmark: a Compose application shell surrounds a custom hardware-accelerated Android `View` that draws terminal rows directly with `Canvas` and `Paint`.

**SSH and Mosh are not implemented yet.** There is no networking, account system, host storage, credential handling, SFTP, sync, analytics, advertising, AI, or subscription code. The manifest intentionally has no `INTERNET` permission.

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

- This is not a terminal emulator: there is no VT/xterm parser, PTY, SSH, or Mosh transport.
- Cell width is estimated from a monospace `M`. Exact East Asian width, combining-character placement, bidi behavior, grapheme clusters, and line wrapping are deferred.
- Long lines are clipped. Selection, copy/paste UI, hyperlinks, accessibility exploration of individual rows, and configurable fonts are absent.
- The basic IME connection sends committed text and deliberately does not locally render in-progress composing text. Some IMEs may give limited composition feedback.
- Frame statistics sample application Choreographer intervals; they do not isolate GPU work or replace Perfetto, `gfxinfo`, or Macrobenchmark.
- Smoothness has to be measured on actual target hardware; a successful build alone is not visual performance validation.
