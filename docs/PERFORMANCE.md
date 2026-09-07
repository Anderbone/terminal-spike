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
`ACTION_MOVE` or animation frame. For an app-selected tmux session, Auto always keeps a classified
vertical scroll local—even if tmux is already in copy mode or an inner pane application negotiated
mouse tracking—and it never exits or drives that remote mode. Explicit Remote mouse routes wheel
input to the negotiated application or tmux copy mode; its optional two-finger override remains
local. An alternate screen includes tmux's saved primary-screen rows in captured history. The
ordinary non-tmux Auto and explicit local/remote branches retain their existing behavior.

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

### 2026-09-03 primary-navigation trace diagnosis

A fresh focused `benchmarkRelease` run measured only
`PrimaryNavigationMacrobenchmark#workspaceSettingsRoundTripFrames` on the exact model-verified
`SM-S911B` Wi-Fi serial, with `ANDROID_SERIAL` pinned. All 10 iterations completed without a skip or
failure. The measured 46 frames had CPU-duration P50/P90/P95/P99 of
`6.78/25.31/29.35/36.23 ms`; the maximum sampled CPU duration was `41.33 ms`. The result reproduces
the earlier `29.02 ms` P95 instead of treating that retained number as a one-off.

Perfetto attribution shows that the slow direction is the first frame after tapping Settings, not
the return to Connections. Eight of ten iterations had a main-thread frame above 16 ms. Excluding
the shader outlier, their slow frames spent up to `16.57 ms` in
`AndroidOwner:measureAndLayout` and up to `7.81 ms` in `Recomposer:recompose`, which establishes
initial Settings category-list composition/layout as the repeatable cost. Iteration 4 additionally
spent `26.29 ms` compiling a cache-missed `FillRRectOp` shader on RenderThread; the main thread
blocked `18.76 ms` in `postAndWait`, producing a `38.99 ms` `Choreographer#doFrame`. The subsequent
Connections frame in that iteration was `14.15 ms`.

Two roughly 700 ms frame-overrun samples did not represent equivalent app CPU work: their relevant
main frames were only `22.37 ms` and `21.30 ms`. They are retained as scheduler/display-presentation
outliers and are not used to explain the app CPU P95. The trace showed 120 Hz display scheduling,
CPU frequency was unlocked, sustained-performance mode was unavailable, and thermal-throttle sleep
was zero. This is same-device diagnostic evidence, not a universal latency claim.

The result JSON SHA-256 is
`910b93686e26265bf394bb589343945f709b4369fedbc2efe691d167997c122e`; the shader-outlier Perfetto
trace SHA-256 is
`4dadf80a235cd4f375e9b6a2b67c0f58b08f88d8cc9bd3dc9e478abd42264d10`. The trace is diagnosed,
but the navigation performance gate is not closed: Settings first-frame composition/layout remains
an optimization target, and the separate real-session latency, memory, resize/concurrency, and
endurance measurements remain open.

A same-phone follow-up corrected the benchmark journey's UIAutomator interaction without changing
the product UI. Material exposes each described navigation semantics node inside a same-bounds
clickable parent; clicking the described child caused 20 `non-clickable object` warnings across the
10 measured iterations. The journey now requires and clicks the nearest clickable ancestor. The
focused benchmark again passed 10/10 iterations with zero such warnings. Its 42 sampled frames had
CPU-duration P50/P90/P95/P99 `7.93/22.13/22.66/24.32 ms`; result JSON SHA-256 is
`b4b0c79471748a52ac484130630c3653faa64d3dfeb1beac107fdcbbdb4fa05d`. Run-to-run variance and
the absence of the earlier shader outlier mean this is harness-correctness evidence, not proof that
Settings was optimized. The `22.66 ms` P95 remains above one 16.67 ms 60 Hz frame interval.

### 2026-09-03 bounded live tmux reconciliation

Tmux snapshot reconciliation now shares an explicit hard budget of 2,000 payload comparisons or
rebuilt rows per Choreographer callback. Preparation reads the published history into a separate
unpublished replacement over as many frames as required; the controller atomically swaps that
replacement only when complete. A newer authoritative snapshot, clear, detach, or session reset
cancels partial preparation. Terminal output that belongs to tmux history waits behind preparation
so it cannot invalidate the base being reconciled. This preserves stable retained-row IDs and
keeps the renderer on either the complete old transcript or the complete new transcript.

The deterministic maximum-shape test uses 20,480 rows (4,096 captured history plus a 16,384-row
saved primary screen). Before the change it failed because all rows were processed in one frame;
after the change every observed callback performed at most 2,000 units of row work, the old
transcript remained visible during preparation, and the new transcript appeared atomically.
Growing overlap, coordinate reset, a 4,096-row older-page prepend, capacity trim, stale work,
clear/detach cancellation, and 400 randomized growing/reset cases are also covered.

The release-like connected benchmark ran all seven terminal journeys on the exact model-verified
Wi-Fi ADB transport for the authorized old `SM-S911B` (Android 16/API 36). The display was actively
rendering at 60 Hz; thermal status was 0, Battery Saver was off, and CPU frequency was not locked.
The new measured journey starts with a 20,480-row history and injects a live 4,096-row coordinate
reset comprising a 1,024-row verified overlap plus 3,072 new rows. Route/reason and remote-wheel
count are not applicable to this synthetic benchmark; the real gesture gate below supplies those.

| Journey | Iterations | CPU P50 | CPU P90 | CPU P95 | CPU P99 |
|---|---:|---:|---:|---:|---:|
| Live tmux reconciliation | 3 | 2.2 ms | 3.6 ms | 4.8 ms | 5.8 ms |
| Plain text + 100,000-line scrollback | 3 | 3.2 ms | 5.9 ms | 8.6 ms | 12.8 ms |
| CJK/wide characters | 3 | 3.9 ms | 5.8 ms | 6.2 ms | 6.9 ms |
| tmux status/history | 3 | 3.2 ms | 3.9 ms | 4.0 ms | 4.0 ms |
| Rapid cursor | 3 | 4.2 ms | 5.4 ms | 5.5 ms | 5.8 ms |
| ANSI-heavy output | 3 | 8.8 ms | 11.9 ms | 12.6 ms | 13.5 ms |
| Full-screen redraw | 3 | 3.2 ms | 4.1 ms | 4.4 ms | 5.5 ms |

The live journey emitted three Perfetto traces and reported frame counts 4/3/3. Its frame-overrun
P50/P90/P95/P99 values were -3.3/-0.2/0.1/0.4 ms. The first harness attempt timed out because the
Macrobenchmark launcher injected `CLEAR_TASK` and recreated the fixture instead of delivering the
trigger to `onNewIntent`; the corrected test uses an explicit single-top `am start` trigger and
passed all three iterations. This is recorded as a harness failure, not a product-performance red.

On the same old phone, the guarded real-Codex gate passed 3/3. The first gesture in actual Codex
inside app-selected tmux selected `LOCAL_SCROLLBACK` for `AUTO_TMUX_LOCAL_READY`, produced two local
updates and zero remote wheels, then passed ordered 5,000-row paging, sub-row drag, fling/catch,
exact zero-pixel reader anchoring across new output, and the live-bottom transition. The real-Mosh
password/lifecycle and private-key matrices passed 5/5 and 1/1. No test or install targeted the
Wi-Fi foldable. The benchmark JSON SHA-256 is
`86f5401ed3239687131ea3471936f1a65d913720f8c628760e797febc6502d35`.

### 2026-09-02 current-source connected run

The first current-source Baseline Profile attempt failed before measurement because the benchmark
journey still searched for the removed `Open local workspace` navigation description. The release
UI now exposes the stable public destination as `Open connections`; the shared benchmark journey
was renamed and updated, so both profile generation and the independent navigation benchmark use
that current semantic contract.

After that repair, both commands below passed on the exact model-checked Wi-Fi ADB transport for
the authorized old `SM-S911B` (`adb-RZCW81JZ9CP-NpnzVa._adb-tls-connect._tcp`, Android 16/API 36):

```bash
./gradlew --no-daemon --max-workers=2 \
  -PterminalSpikeUseConnectedBenchmarkDevices=true :app:generateBaselineProfile
./gradlew --no-daemon --max-workers=2 \
  -PterminalSpikeUseConnectedBenchmarkDevices=true \
  :benchmark:connectedBenchmarkReleaseAndroidTest
```

Profile generation passed in 2m34s and produced 23,892 reported Baseline Profile rules and 21,122
reported Startup Profile rules. Their exact SHA-256 values are
`c690575bc4a58ad77059cb6f8c4eb60b02c2bd00949a0e8d3c2835913d8ede1f` and
`fb46fed545512fbed45ee8aadd324e35343e5ee00462bec6e1322a485f2bfa5f`. The separate connected
benchmark passed in 6m17s: all 12 declared tests completed with zero failures; the two profile
generator methods were the expected skips in the benchmark variant. The run emitted one benchmark
JSON file and 48 Perfetto traces. CPU frequency was not locked, sustained-performance mode was
unavailable, and thermal-throttle sleep was zero, so these are current same-device observations,
not universal performance guarantees.

| Journey | Iterations | Median/startup or CPU P50 | CPU P90 | CPU P95 | CPU P99 |
|---|---:|---:|---:|---:|---:|
| Cold startup, no compilation | 10 | 499.36 ms startup | — | — | — |
| Cold startup, Baseline Profile | 10 | 380.14 ms startup | — | — | — |
| Connections ↔ Settings navigation | 10 | 7.97 ms | 28.55 ms | 29.02 ms | 29.51 ms |
| Plain text + 100,000-line scrollback | 3 | 4.57 ms | 6.40 ms | 6.56 ms | 7.79 ms |
| CJK/wide characters | 3 | 5.87 ms | 6.90 ms | 7.12 ms | 8.86 ms |
| tmux status/history | 3 | 4.39 ms | 9.15 ms | 11.59 ms | 13.55 ms |
| Rapid cursor | 3 | 4.51 ms | 5.07 ms | 5.11 ms | 5.66 ms |
| ANSI-heavy output | 3 | 7.93 ms | 10.36 ms | 11.49 ms | 13.08 ms |
| Full-screen redraw | 3 | 3.79 ms | 4.61 ms | 4.89 ms | 6.15 ms |

Baseline Profile reduced the observed median cold start by 119.22 ms, about 23.9%. Navigation is
the only measured journey whose CPU P95 exceeded a 16.67 ms 60 Hz frame interval; its traces are
retained in the build output for diagnosis. Input-to-render, tap-to-Connecting, process memory,
rotation/resize, simultaneous live sessions, power/thermal endurance, and real-network latency
remain separate open measurements.

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
