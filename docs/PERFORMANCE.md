# Performance notes

## Expected hot path

The main-thread hot path is `TerminalController` frame drain → ring-buffer append or latest full-screen swap → `FastTerminalView.onDraw`. Drawing visits only visible and overscan rows. It does not create Compose nodes, row Views, cells, or a scrollback bitmap.

The renderer caches font metrics, line height, estimated cell width, terminal padding, typefaces, Paint instances, and viewport geometry. Styled text is drawn per contiguous run. Long runs are clipped and their draw range is bounded to the viewport.

## Frame scheduling and scrolling

Generated output is already batched off the main thread. A bounded queue prevents unbounded producer memory, and a single scheduled Choreographer callback drains up to 2,000 lines per display frame. Full-screen updates coalesce to the newest pending screen.

Drag and fling update a floating-point pixel offset in `TerminalViewport`; row calculation happens only for drawing. Touch-down immediately stops a fling. Bounds are clamped, so overscroll cannot expose invalid content. If the viewport is at bottom, appends follow. Any upward pixel movement disables follow until the exact bottom is reached or Jump to bottom is used.

Remote mouse wheel reports remain thresholded by terminal line height and capped at one report per
touch event. Remote fling stays disabled because flooding a mouse-aware terminal application can
discard intermediate redraws. A confirmed app-selected tmux pane instead captures its physical
history rows through the existing authenticated side channel, parses them off the Android main
thread, and renders them through the same floating-point `TerminalViewport` and `OverScroller` as
ordinary local history. The touch and animation paths only change the pixel offset and draw visible
rows plus overscan; they do not send wheel events, run tmux commands, or rebuild history.

Full history transfer is limited to bootstrap or a detected pane/count mismatch. Output and
vertical-gesture boundaries otherwise use a lightweight, coalesced metadata probe, with a bounded
maximum interval for continuously updating full-screen apps. Neither probe runs for each
`ACTION_MOVE` or animation frame. tmux copy mode and pane applications with tmux's
`mouse_any_flag` keep remote mouse routing. An alternate screen without application mouse tracking
keeps local pixel scrolling and includes tmux's saved primary-screen rows in the captured history.
The ordinary non-tmux local and remote-mouse branches retain their existing behavior.

## Platform graphics data

Reset counters before each fixed scenario:

```bash
adb shell dumpsys gfxinfo com.yanjiyu.terminalspike reset
```

After exercising the scenario, collect them:

```bash
adb shell dumpsys gfxinfo com.yanjiyu.terminalspike
```

Record device model, Android version, refresh rate, build type, workload/rate, scrollback size, test duration, power mode, and thermal state. Avoid comparing a warm throttled run with a cold run.

In Android Studio, inspect:

- CPU flame charts around `FastTerminalView.onDraw`, `TerminalController.frameCallback`, text measurement, and generator formatting;
- main-thread scheduling and missed vsync deadlines;
- allocations and GC while flinging, at 5,000 lines/s, and at 60 full-screen updates/s;
- heap retention after clear and after buffer wraparound;
- GPU rendering bars and overdraw for the renderer plus diagnostic overlay.

## Repeatable benchmark infrastructure

The `benchmark` test module targets the Baseline Profile plugin's non-minified release variant. It
contains cold-start comparisons for no compilation versus a required Baseline Profile, a
Workspace/Settings frame journey, Baseline/Startup Profile generators, and renderer frame journeys
for every terminal fixture below. By default it pins a Pixel 6 API 35 AOSP Gradle-managed device;
connected-device collection is disabled by default and can be enabled explicitly with the project
property documented below.

The terminal target activity, fixture generator, and fixture assets exist only in
`nonMinifiedRelease`, `benchmarkFixtures`, `testDebug`, and the benchmark APK. They are absent from
the production `release` source set. Release packaging verification rejects their class/package if
that boundary regresses.

The project-authored scenario catalogue is
`app/src/test/resources/terminal-benchmark-fixtures/scenarios.tsv`. It describes deterministic
synthetic output rather than copying a terminal transcript:

| Scenario | Records | Declared minimum payload | Stress shape |
|---|---:|---:|---|
| `plain-100k` | 100,000 lines | 9,600,000 bytes | large scrollback and fling |
| `ansi-heavy` | 20,000 lines | 3,200,000 bytes | indexed colours and dense SGR transitions |
| `cursor-redraw` | 20,000 updates | 2,560,000 bytes | cursor positioning and line erasure |
| `full-screen-redraw` | 12,000 updates | 2,304,000 bytes | clear/home and multi-row replacement |
| `cjk-wide` | 20,000 lines | 2,880,000 bytes | CJK, emoji, combining, ZWJ, and regional indicators |
| `tmux-status` | 20,000 updates | 2,880,000 bytes | mouse-aware alternate-screen pane scrolling, retained history, bottom-row status repaint, and cursor restoration |

### 2026-08-28 physical retained-history result

The corrected `tmux-status` fixture is loaded outside the measured block. Each iteration then times
two swipe journeys through retained alternate-screen history. On USB-connected Android 16
`SM-S911B`, three iterations of the release-like benchmark measured:

| Immutable-row cache | CPU P50 | CPU P90 | CPU P95 | CPU P99 |
|---|---:|---:|---:|---:|
| 256 rows | 5.64 ms | 9.30 ms | 13.21 ms | 14.92 ms |
| 512 rows | 4.87 ms | 6.85 ms | 8.07 ms | 9.48 ms |

The 512-row bound reduced these percentiles by approximately 13.5%, 26.4%, 38.9%, and 36.4%
respectively. CPU frequency was not locked and sustained-performance mode was unavailable, so this
is same-device directional evidence rather than a universal device claim. All six physical
benchmark scenarios passed. Tests and benchmark installs were pinned to USB serial `RZCW81JZ9CP`;
none ran on the Wi-Fi foldable.

The cache is not part of the current baseline. It was removed after foldable-device evidence showed
inaccessible or stale-looking live history. These figures remain a historical experiment only and
must not be used as the current renderer result.

Fixture generation streams bounded chunks and deliberately permits UTF-8 and escape sequences to
cross chunk boundaries. `TerminalParserControllerBenchmarkHarness` uses an injected manual frame
scheduler: it performs the real parser and controller drains without clocks, sleeps, network, or a
device, then records byte, line, frame, cursor, and transcript digests. Its unit tests verify the
same end state at aligned and prime-sized chunk boundaries.

Build and run the host-side contract without a device:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.yanjiyu.terminalspike.terminal.benchmark.TerminalParserControllerBenchmarkTest'
./gradlew :benchmark:assemble
```

Generate the profile and run device measurements on the pinned managed emulator:

```bash
./gradlew :app:generateBaselineProfile
./gradlew :benchmark:pixel6Api35BenchmarkAndroidTest
```

When the pinned API 35 system image is unavailable, an already-authorized physical test phone can
collect the same local profile and Macrobenchmark evidence without changing source configuration:

```bash
./gradlew -PterminalSpikeUseConnectedBenchmarkDevices=true :app:generateBaselineProfile
./gradlew -PterminalSpikeUseConnectedBenchmarkDevices=true \
  :benchmark:connectedBenchmarkReleaseAndroidTest
```

The Android/Baseline Profile plugin task listings for the checked-in versions expose
`:app:generateBaselineProfile` and `:benchmark:connectedBenchmarkReleaseAndroidTest`. Task discovery
does not imply that profile generation or a benchmark measurement has run.

Record the exact serial, API level, refresh rate, power mode, and thermal status beside those
results. Connected-device mode is opt-in so ordinary builds and CI never assume a phone exists.

Profile generation is intentionally not automatic during ordinary release assembly. The generated
profile is saved to the release-specific generated Baseline Profile source directory by the plugin;
review and commit it after a representative managed-device run. Keep benchmark JSON and trace
captures as CI artifacts unless a privacy-safe summary is intentionally checked in.

Still collect device/API/refresh rate, build revision, battery mode, and thermal state with every
result. The infrastructure does not yet substitute for measured input-to-render latency,
tap-to-Connecting latency, rotation/resize, or simultaneous live-session evidence.

## Current release-candidate evidence

The frozen full source artifact gate completed with `BUILD SUCCESSFUL` in 3 min 25 s across 385 tasks
(57 executed). Its fresh JVM reports were app `783/0/0/0`, `mosh-api` `11/0/0/0`, and extension `8/0/0/0` in
tests/failures/errors/skipped order. Debug and release lint/build gates, release APK/AAB packaging
verification, both Android-test APKs, extension native outputs for both supported ABIs, and
`:benchmark:assemble` were green. This source/assembly result does not add new device measurements.

Final functional-device evidence used the frozen version `0.0.1` app and extension on USB
`RZCW81JZ9CP` (`SM-S911B`) and Wi-Fi
`adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` (`SM-S936U`). USB app instrumentation reported
252 tests with no failures; the credentialed real-server fixture passed separately with runtime
arguments and Mosh absent. `mosh-api` passed 5/5 and the extension passed 3/3 on each phone. The
Wi-Fi full app UI attempt was environment-blocked by secure keyguard/dozing and is not green. These
functional results do not provide latency, frame, memory, power, or thermal measurements.

A separate local release-like smoke used the existing Android debug keystore, not production
signing. Its 159-task release APK/AAB gate passed in 1 min 28 s; the app release APK, extension
release APK, and app AAB SHA-256 values were respectively
`29f64e9b6ac233a8546f5ae934962212804d8a62e6a7004657cc47d307990b1b`,
`206c74610de6cc808dcccae8e14d32dedc8e99efea2e32a85f60b004174c4c49`, and
`27ae1393b34ed1a42edc031352229ef3106049adbe0ce033c893f7519355f61f`. Both APKs installed on
disposable API 35 AVD `terminal-spike-release-test`; one cold launch completed in 504 ms with
`MainActivity` as `topResumedActivity`. That single smoke observation is not a startup distribution,
physical-device result, or production-signing claim and is not compared with the Macrobenchmark
results below.

The partial connected performance collection below predates the frozen gate and remains separate
performance evidence. It completed on exact ADB serial `RZCW81JZ9CP` (`SM-S911B`, Android 16/API
36). The Baseline Profile command completed successfully in 4 min 46 s, and the full connected
benchmark command completed successfully in 5 min 31 s:

```bash
./gradlew -PterminalSpikeUseConnectedBenchmarkDevices=true :app:generateBaselineProfile
./gradlew -PterminalSpikeUseConnectedBenchmarkDevices=true \
  :benchmark:connectedBenchmarkReleaseAndroidTest
```

| Evidence | Current result |
|---|---|
| Host-side parser/controller fixture contract | Passed within the frozen full source gate, `BUILD SUCCESSFUL` in 3 min 25 s across 385 tasks (57 executed) |
| Benchmark APK assembly | Passed in the frozen full source gate |
| Managed/connected benchmark task discovery | Verified task names |
| Baseline Profile generation | Passed; `baseline-prof.txt`, 23,280 lines, SHA-256 `1fab54f82077759e9367a36e2479567846ef99f600e1e90545e7c326e73e2cb0` |
| Startup Profile generation | Passed; `startup-prof.txt`, 18,654 lines, SHA-256 `48fde97176bd368f189f3810294426ff9cea74808136b54bfc3fe0da51b14598` |
| Connected benchmark instrumentation | 12 tests, 0 failures, 0 errors, 2 expected `BaselineProfileGenerator` skips |
| Cold-start journeys | Startup 2/2 completed; trace and `benchmarkData` JSON artifacts collected |
| Primary-navigation journey | Navigation 1/1 completed; trace and `benchmarkData` JSON artifacts collected |
| Terminal fixture journeys | 6/6 completed; trace and `benchmarkData` JSON artifacts collected |
| Fixture asset contract | 1/1 completed |
| Input-to-render, tap-to-Connecting, resize, and simultaneous sessions | Pending exact-device/manual measurement |

The generated profiles are at
`app/src/release/generated/baselineProfiles/baseline-prof.txt` and
`app/src/release/generated/baselineProfiles/startup-prof.txt`. Benchmark traces and
`benchmarkData` JSON were collected by the connected run. Refresh rate, power/thermal state, numeric
input-to-render latency, tap-to-Connecting latency, rotation/resize, and simultaneous live-session
measurements are not inferred from the green instrumentation result and remain open where listed.

## Measurement limitations

The overlay records actual `FastTerminalView.onDraw` start/end timestamps in fixed primitive rings and reports recent draw rate plus CPU duration. An idle terminal explicitly reports idle instead of display refresh rate. It still cannot separate GPU/display latency and may itself have a small cost. Percentiles use a bounded recent sample. Heap is the Java runtime estimate, not total process PSS. Use Perfetto, Macrobenchmark, `gfxinfo`, and device-level power/thermal observations for decisions.
