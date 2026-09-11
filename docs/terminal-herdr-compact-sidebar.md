# Herdr sidebar on narrow screens

The terminal window's available width selects the presentation. Below 600 dp, app-selected
Herdr sessions hide the Spaces/Agents sidebar and show a left chevron in a small header to
reveal or hide it. At 600 dp and above there is no additional header or clipping. Opening a
foldable therefore restores the existing full layout; closing it starts compact again.
This also handles ordinary phones, landscape windows and split-screen without model lists.

The sidebar is remote terminal output, not Android application chrome. The existing
authenticated SSH side channel reads `herdr --session <selected-name> pane layout` for both
SSH and Mosh. The outer `area.x` identifies the sidebar boundary independently of which
split pane or agent is focused. It does not use the focused pane's x-coordinate. Metadata
is bounded, checked against the current terminal column count, and cleared on disconnect.
The default full view remains visible until a valid layout arrives. Herdr's own mobile
layout has no left sidebar, so its existing menu remains in use without an extra header.

A native ViewGroup widens the terminal by the sidebar's measured pixel width, positions
the child to the left, and clips the sidebar. This gives the pane the reclaimed columns;
it does not just crop part of a narrow terminal. Android maps input to the original child
coordinates. The same FastTerminalView stays attached across expand/hide and width changes.
The existing renderer, selection and history coordinates remain in the outer terminal grid.
Font metric changes remeasure the container. Expanding reveals the complete remote display.

No remote config changes, synthetic keyboard/mouse toggles, new dependencies, or changes
to non-Herdr layout policy are introduced. Herdr's existing shared-terminal-size behavior
still applies if multiple clients attach to one remote session. Remote overlays are part
of the same display; expanding the sidebar reveals their full extent as well.

## Validation (2026-09-11)

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: passed;
  1,082 unit tests, zero failures/errors/skips. Existing history regression tests are retained.
- New API tests cover shell quoting, split-pane geometry, mobile layout, malformed/oversized
  responses and unavailable transports.
- Eight API 35 emulator tests passed with zero failures/errors/skips. They cover compact/expanded
  layout, the 599/600 dp boundary, reconnect/session isolation, production bridge/native view
  retention, child measurement and MotionEvent coordinate mapping, plus existing terminal
  navigation, buffered input/focus, mouse tap/typing and 200-row scrollback checks. Evidence:
  `build/herdr-sidebar-emulator-final/TEST-api35-full.xml` (not tracked).
- An isolated local Herdr 0.8.2 PTY check measured `area={x:26,width:74}` at 100 columns,
  `area={x:26,width:100}` at 126 columns, and the exact original area after restoring 100
  columns. At 60 columns Herdr used its native mobile layout, `area={x:0,width:60}`.
  Evidence: `build/herdr-sidebar-evidence/real-herdr-layout.json` (not tracked).
- Physical old-phone testing was unavailable: ADB listed only the foldable. No device tests
  run on the foldable. Emulator checks do not constitute real-phone SSH/Mosh acceptance.
- Final APK installation succeeded on `192.168.0.33:34961`, verified model `SM-F976B`.
  Android accepted the MainActivity launch (`Status: ok`), but foreground verification
  failed because the phone remained locked (`mKeyguardShowing=true`), including after
  waking it and requesting normal keyguard dismissal. Unlocking the phone is still needed
  to confirm the visible launch; no tests were run on it.
