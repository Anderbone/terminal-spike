# Performance notes

## Expected hot path

The main-thread hot path is `TerminalController` frame drain → ring-buffer append or latest full-screen swap → `FastTerminalView.onDraw`. Drawing visits only visible and overscan rows. It does not create Compose nodes, row Views, cells, or a scrollback bitmap.

The renderer caches font metrics, line height, estimated cell width, terminal padding, typefaces, Paint instances, and viewport geometry. Styled text is drawn per contiguous run. Long runs are clipped and their draw range is bounded to the viewport.

## Frame scheduling and scrolling

Generated output is already batched off the main thread. A bounded queue prevents unbounded producer memory, and a single scheduled Choreographer callback drains up to 2,000 lines per display frame. Full-screen updates coalesce to the newest pending screen.

Drag and fling update a floating-point pixel offset in `TerminalViewport`; row calculation happens only for drawing. Touch-down immediately stops a fling. Bounds are clamped, so overscroll cannot expose invalid content. If the viewport is at bottom, appends follow. Any upward pixel movement disables follow until the exact bottom is reached or Jump to bottom is used.

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

## Future benchmarks

Add a dedicated Macrobenchmark module and Baseline Profile only after the renderer/parser choice. Scenarios should cover cold startup, 100,000-line fling, simultaneous 5,000-line/s append and held scroll, 60 Hz full-screen replacement, keyboard latency, rotation anchoring, and parser-heavy tmux/Codex traces. Use trace files that can be redistributed legally.

## Measurement limitations

The overlay derives approximate intervals from Choreographer callbacks. It includes unrelated application frames, cannot separate CPU/GPU/display latency, and may itself have a small cost. Percentiles use a bounded recent sample. Heap is the Java runtime estimate, not total process PSS. Use Perfetto, Macrobenchmark, `gfxinfo`, and device-level power/thermal observations for decisions.
