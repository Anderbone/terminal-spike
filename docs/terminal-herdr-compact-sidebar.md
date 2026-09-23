# Herdr sidebar on narrow screens

## Repeated menu regression coverage (2026-09-23)

The user confirmed the width-change fix works on the foldable. Follow-up coverage
adds `topAndBottomTabMenusSurviveRepeatedResizesAndViewReplacement` to
`HerdrContextMenuRecoveryTest` and the exact full-suite test inventory. It tests
top and bottom tabs with both visible and hidden sidebars, three successive
widen/shrink cycles per layout, null/throwing/stale-successful layout reads,
and native-view replacement while refreshes fail. Holds start after native
measurement settles without waiting for new metadata. Every hold must emit
exactly the secondary-button press/release, with no extra click or local text
selection. The existing font/keyboard resizing, pane-selection, and mobile
switcher regressions remain in place.

The checked-in CI workflow runs the full inventory in its API 26/35 runtime
jobs for pushes and pull requests; missing methods and unexpected skips fail
the inventory check. `scripts/verify-android.sh` runs unit tests and compiles
the instrumentation APK but does not execute these native touch tests locally.
Use `HerdrContextMenuRecoveryTest` with `TerminalSpikeTestRunner` on the
model-verified old USB phone for the focused device gate. Never run it on the
foldable. Evidence for this follow-up is under ignored
`build/longpress-regression/`.

These tests exercise Android's production refresh loop and native input with a
controlled connection. They do not prove that a particular remote Herdr version
renders its Close menu, or that a fresh session can obtain metadata when its SSH
side channel is unavailable. Keep the user-confirmed real-workflow check as
separate evidence, and retain each newly observed failure as a regression.

Validation: shared verification passed (125 tooling tests; 450 Gradle tasks
including unit tests, lint, and builds). All four focused device methods passed
on model-verified USB `SM-S911B` (`RZCW81JZ9CP`) in 59.596 seconds. The new method
checked 40 long presses across 24 native resizes and four view replacements.
The generated CI shard filter includes the class, and inventory validation
passed. Hosted CI was not run. This follow-up changes tests/documentation only;
the debug app SHA-256 remains
`dc556e2c05699c6ba9635d6a4a3cceff9541a444387c53d76ebfbf7dce03d6e6`,
also verified directly against the installed foldable APK. The preceding
successful install and the user's real-menu confirmation apply to this exact
unchanged app binary. Final launch returned `Status: ok` with MainActivity
top-resumed on the foldable. No foldable tests ran.

## Cached menus across desktop width changes (2026-09-23)

Read-only inspection of the affected foldable (`SM-F976B`, wireless serial
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`, installed version 0.0.6 / 9)
found a live 96-column, 20-row terminal with cached 88-column geometry,
top=1 and height=32. Direct input was enabled and no history reader was active.
The earlier height-only correction left that cache unchanged after a width
change, so navigation hit testing rejected every cell. No gestures or tests
were sent to the foldable.

The cache now carries the width at which desktop geometry was verified. It
preserves sidebar width and top/bottom tab anchors when widening and when
returning to that verified width, including simultaneous height changes.
Unknown narrower widths can switch to mobile navigation and still require a
fresh layout read; mobile, incomplete, and already mismatched geometry are
never extrapolated. This does not repair an unavailable SSH side channel or
infer navigation when no valid layout has ever been received.

`herdrMenusFollowDesktopWidthAndHeightChangesWithoutAnotherSshRead` first failed
with `Sidebar must survive desktop width changes` on the exact observed resize.
It covers top/bottom/hidden tabs, repeated width/height changes, pane exclusion,
and the narrower-width guard. `HerdrContextMenuRecoveryTest` now includes native
font-size changes while layout reads fail, then delayed old-size replies, and
requires exactly the secondary-button press/release for sidebar and tab holds.
Evidence is retained under ignored `build/longpress-investigation/`.

Validation: the shared verification gate passed (450 Gradle tasks), including
1,105 unit tests, debug/release lint and builds, and the test APK. The preflight
passed all 125 tooling tests and exact Android-test inventory validation. All
three focused tests passed on model-verified USB `SM-S911B` (`RZCW81JZ9CP`):
menu recovery including font resizing, navigation long-press versus output
selection, and mobile-switcher routing. An initial invocation used the wrong
runner component and started no tests; the corrected invocation used
`TerminalSpikeTestRunner`. Completed debug APK SHA-256:
`dc556e2c05699c6ba9635d6a4a3cceff9541a444387c53d76ebfbf7dce03d6e6`.
That APK installed successfully on the model-verified foldable at the serial
above. MainActivity cold launch returned `Status: ok` and the activity resumed,
but the secure lock screen (`showing=true`, `inputRestricted=true`, focused
`Bouncer`) prevented visible-foreground verification. Unlock/open and try the
real Herdr tab/space menu; that user-workflow confirmation remains pending.
No foldable tests were run.

## Cached menus across keyboard height changes (2026-09-17)

The follow-up report reproduced on the exact previously verified APK
`6434043ac03386949f31db42522ccfc56ed3770a7e8a02cac6db16c14b2c10e7`.
Read-only inspection of the attached foldable view found an 88-column, 33-row
terminal with enabled mouse reporting and no history reader, but its retained
layout had top=1 and height=17 (the earlier 18-row terminal). The first fix
preserved the cache through SSH failures but did not adapt it when closing the
keyboard increased the terminal height. Menu hit testing correctly rejected
that stale geometry.

Height-only resizes now preserve verified desktop top/bottom chrome offsets in
the cached layout. Width changes and unverified/mobile geometry are not
extrapolated. Background refresh also rejects replies for an obsolete height,
so a delayed response cannot undo the correction. History snapshots and scroll
routing are unchanged.

`herdrMenusFollowKeyboardHeightChangesWithoutAnotherSshRead` reproduced the
failure before the fix and covers repeated shrink/grow cycles with top, bottom,
and hidden tabs, sidebar targets, pane exclusion, width changes, and detach.
The native `HerdrContextMenuRecoveryTest` also exercises view height changes
while SSH reads fail, followed by delayed replies for the original height.
Logs are under ignored `build/herdr-height-evidence/`.

Validation: RED unit assertion for the sidebar after growing from 18 to 33 rows;
GREEN 1076 app unit tests, full `test lint assembleDebug assembleDebugAndroidTest`
(264 tasks), and final Android-test/lint rebuild. All three focused old-USB-phone
tests passed on model-verified `SM-S911B` (`RZCW81JZ9CP`), including native height
changes and stale replies. Inventory validation and diff checks passed.
APK `f6a6ed2dae5f6b46e55d481dc669030a149efec58337eb45ae9c81d3914c69f7`
installed successfully on model-verified `SM-F976B` at
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`. Launch returned Status ok, but
foreground verification was blocked by the secure lock screen (`showing=true`,
`inputRestricted=true`) even after wake and normal keyguard-dismiss requests.
Unlock and open the app for the real-workflow visual check. No foldable tests ran.

## Long-press recovery after an auxiliary SSH failure (2026-09-16)

The reported regression occurred in the desktop sidebar/tab layout on the
foldable, not in Herdr's mobile switcher. Read-only inspection of the running
Mosh session found mouse reporting enabled, no active history reader, and no
cached navigation layout. The auxiliary SSH connection repeatedly failed to
open exec channels (`channel is not opened`), although the Mosh terminal stayed
connected. Each failed layout refresh previously cleared the cached geometry,
so a tab or space long press fell through to local text selection.

Failed layout reads now retain the last successful layout for that connection.
A successful refresh still replaces it, including when tabs or the sidebar are
hidden. Existing grid-dimension checks reject stale coordinates, and detach
clears the cache. This does not change Herdr's layout or remote configuration.

`HerdrContextMenuRecoveryTest` uses the production session refresh loop and native
`MotionEvent` long presses. It checks tab and space right-click press/release
reports before and after both null and throwing side-channel failures, then
checks successful layout replacement and detach cleanup. Evidence is retained
under ignored `build/herdr-longpress-evidence/`.

Validation: the new test first failed on USB `RZCW81JZ9CP` (`SM-S911B`): after a
failed refresh, the expected secondary press/release at column 7, row 1 was
absent. With the fix, that test, the original navigation long-press test, and
`HerdrMobileSwitcherScrollTest` all passed (3/3). `test lint assembleDebug
assembleDebugAndroidTest` passed (264 tasks); app unit tests passed 1075/1075,
Mosh API tests 11/11, and test-inventory validation passed. The verified debug
APK SHA-256 is `6434043ac03386949f31db42522ccfc56ed3770a7e8a02cac6db16c14b2c10e7`.
It was installed and launched on model-verified `SM-F976B` at
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`, with MainActivity top-resumed.
No tests ran on the foldable. This verifies Android menu dispatch; the user's
real Herdr workflow remains the final visual check.

## Phone navigation correction (2026-09-14)

The Android sidebar toggle and artificial terminal widening have been removed.
Herdr now receives the actual window width from the first frame and owns its
responsive navigation throughout. On a narrow grid, its mobile header opens the
switcher directly; its right-hand action changes from switch to close. The left
“switch” text in the open panel is a title, not a relocated Android button.
Opening navigation no longer resizes the terminal or introduces another header.
The production bridge regression checks actual-width measurement and native-view
retention through desktop/mobile metadata updates, resizing, font-size changes,
and session changes. It also checks stable rows/columns and focus when metadata
arrives or disappears.

Validation on 2026-09-14: `test lint assembleDebug assembleDebugAndroidTest` passed
(264 tasks); app unit tests 1070/1070 and Mosh API tests 11/11 passed. Three focused
instrumentation tests passed on USB `RZCW81JZ9CP`, model verified as `SM-S911B`:
`HerdrAdaptiveTerminalTest`, `HerdrTerminalContainerTest`, and
`HerdrMobileSwitcherScrollTest`. Test inventory validation passed 6/6. Debug APK
SHA-256: `536c820473afb18c0db12ddecccdf8182da799f5f0489745abd246c0d756f03e`.
The installed build launched with MainActivity top-resumed. Logs are under
`build/phone-switcher-evidence/` (ignored).

These device regressions use fixtures. The USB phone had no saved hosts or open
sessions, so actual Herdr chooser appearance, rotation, and saved SSH/Mosh
reconnection remain visually unverified. The remote panel still uses its own
left “switch” title; this patch does not redesign that remote UI. No foldable was
connected in ADB for the required final install/launch. User visual acceptance
remains pending.

The earlier implementation and evidence below are historical.

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

## Touch context menus (2026-09-12)

In app-selected Herdr sessions, long-press a visible space in the sidebar or a tab
in the top/bottom tab bar to send a negotiated right-button press/release at that
cell. Successful dispatch gives native long-press haptics. Release does not send a
left click or open the keyboard. Tap an item in Herdr's existing menu to act;
long-press itself does not close a tab, space, or delete files. Herdr retains its
own close/delete choices and confirmations. Closing shared remote work remains
visible to other attached clients.

The existing bounded `pane layout` read now retains the outer area's vertical
geometry as well as its horizontal boundary. Hit testing excludes pane output,
requires matching grid dimensions and a desktop layout, and rejects scrollback,
disabled input/mouse reporting, padding, and an active local history reader.
Pane text keeps local selection/link actions. Herdr's separate two-row mobile
header/switcher continues to use its own existing menu; it is not guessed from
terminal text. No remote configuration, laptop input mapping, dependency, or
scroll/fling policy changes are introduced.

Geometry and right-button semantics were checked against upstream Herdr v0.8.2
(`src/ui.rs` and `src/app/input/mouse.rs`); no upstream source was copied.
Unit regressions cover sidebar, top/bottom/hidden tabs, pane exclusion, mobile and
resized geometry, SGR/legacy/X10 reports, and controller history/grid guards.
`herdrNavigationLongPressSendsOnlyRightClickAndOutputStillSelects` exercises native
MotionEvent long-press dispatch and selection fallback; it requires an authorized
old-phone run. No tests may run on the foldable.

Final validation: `./gradlew test lint assembleDebug assembleDebugAndroidTest
--max-workers=2 --no-configuration-cache` passed (264 tasks, 1m10s); six host
Android-test-contract checks passed. Focused layout/encoding/controller suites:
4/4/31 tests, zero failures/errors/skips. Native instrumentation compiled but was
not run: SM_S911B was absent. Completed debug APK SHA-256
`534e91c5d0f9201c5e1cde5f96c4b1fe415071a48ba9f822c11c79e59c006fd5` installed successfully
on model-verified SM-F976B at `adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`;
MainActivity was verified top-resumed. No foldable tests or injected gestures.
Evidence: `build/herdr-context-evidence/` (ignored). Real-workflow user feedback
remains pending.
