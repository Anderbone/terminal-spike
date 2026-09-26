# Native scrolling for Codex inside Herdr

Status: bounded native history implemented, checks passed, installed and launched on the foldable.

## Bounded implementation approved by the user

The user explicitly accepted Herdr's current 1,000-row read limit and requested implementation
and installation. App-selected Herdr sessions now fetch the focused Codex pane through the
existing authenticated SSH side channel, including the side channel retained by Mosh. The app
validates pane/terminal identity, layout, scroll metrics and content revision around each bounded
ANSI read. It does not introduce another connection, executable helper or library dependency.

A native Canvas overlay clips history to that pane's rectangle. One-finger vertical drag uses a
Float viewport, fling uses Android OverScroller, and touch catches fling. The reader pins its
snapshot while output continues; background checks validate identity/layout without refetching
the transcript during reading. Tapping the reader or typing returns to live output. The recent-history limit is documented here without interrupting gestures with a toast.
Explicit Remote mouse mode retains the existing input route.

This implementation supports Herdr sessions selected in the app and panes reported by Herdr as
Codex. A manually launched, unidentified Herdr client does not gain this adapter. Capture failures,
unsupported metadata and geometry mismatches do not display guessed history. History beyond the
bounded read remains unavailable in this native reader; this is not full tmux history parity.

Host unit coverage includes all 1,000 ordered styled rows, bounds, exact-session command quoting,
changed content/identity rejection, fractional movement, the oldest cached row, existing remote
offsets, reader stability during output, and pane/layout/disconnect invalidation. Physical gesture
acceptance remains pending on the authorized old phone, which was absent during this session.

## Requested behavior

The user confirmed that the target is Codex terminal output inside Herdr, not Herdr's chat or
workspace lists. The desired behavior is the existing native tmux experience: fractional drag,
Android fling and touch catch, ordered older history, and a stable reading position during output.

## Installed-version evidence

On 2026-09-11 the host has Herdr 0.8.2-1, socket API protocol 20. An isolated headless Herdr
server was started with a temporary configuration directory and socket. A disposable pane emitted
5,000 numbered shell lines. No existing Herdr workspace or phone was used by this probe. The
server was stopped and its temporary directory removed afterward.

Request: `pane.read`, `source=recent`, `lines=10000`, `format=text`.

| Observation | Result |
| --- | --- |
| Numbered lines emitted | 5,000 |
| Numbered lines returned | 998 |
| First returned marker | `HERDR_PROBE_04003` |
| Last returned marker | `HERDR_PROBE_05000` |
| Response `truncated` | `true` |
| Response `revision` | `0` |
| Pane `offset_from_bottom` | `0` |
| Pane `max_offset_from_bottom` | `4966` |
| Pane `viewport_rows` | `39` |

This is a host API-capacity experiment, **not** actual Codex or physical smooth-scroll acceptance.
The initial probe incorrectly expected an `ok` envelope field and failed before creating its
workspace. The corrected probe used the API's `result`/`error` envelope and produced the result
above. Both disposable servers were stopped.

The installed schema provides pane identity, layout rectangles and scroll metrics, but its read
request has no older-row range or continuation cursor. The observed response contains at most
1,000 physical rows, including shell/prompt rows. Increasing the requested count does not expose
the older rows that the server still retains.

Source corroboration:

- [Herdr snapshot reader](https://github.com/herdrdev/herdr/blob/v0.8.2/src/app/api_helpers.rs)
  clamps the requested line count to 1,000.
- [Herdr socket API documentation](https://github.com/herdrdev/herdr/blob/v0.8.2/docs/next/website/src/content/docs/socket-api.mdx)
  documents pane reads, layout and scroll metadata.
- [Herdr alternate-screen reader](https://github.com/herdrdev/herdr/blob/v0.8.2/src/server/alt_screen_read.rs)
  includes a wheel-driven history harvesting path. Do not treat that path as a passive capture
  equivalent to tmux `capture-pane`; its behavior was not exercised by the installed-version probe.

## Why an Android gesture-only patch is insufficient

The current Android Auto router sends remote wheel reports for a non-tmux session that requests
mouse tracking. Its remote branch moves in terminal rows and deliberately does not synthesize a
remote-wheel fling. The installed phone's exact first-gesture route has not been measured, so this
remains the source-level explanation rather than physical route evidence.

Tmux solves this by supplying real pane history off-thread, allowing the existing native renderer
and Float viewport to move without remote wheel reports. Herdr needs an equivalent history source.
Routing its gestures locally before that history exists would scroll the outer application screen
or expose an incomplete transcript. Wrapping Herdr in tmux would capture the outer pane, not
automatically grant access to Codex history held inside Herdr.

## Remaining work for full history parity

1. Herdr must expose passive, bounded history pages for an exact pane. A response needs pane and
   terminal identity, history epoch/revision, dimensions, first/last row coordinates, truncation
   information, and physical rows with styles and wrap metadata. Reading must not move the remote
   viewport or send keys/wheels to Codex.
2. Bind capture to the Herdr session attached to the exact authenticated transport. Use the pane
   under the initial touch and its matching layout; never select the latest arbitrary session or
   assume the server's focused pane belongs to this client. Invalidate on detach, pane switch,
   layout/size change and replacement of the pane's terminal.
3. Fetch and parse bounded pages through the existing authenticated side channel, outside the
   Android render and gesture loops. Preserve the established row-work and memory bounds.
4. Reuse native rendering, a Float pixel viewport and Android fling inside the selected pane's
   rectangle. Keep Herdr's surrounding controls and other panes in their live positions. Match
   the visible pane rows before the first transition so it does not jump.
5. Prefetch older pages before reaching the cache boundary and preserve the exact pixel anchor
   when prepending. Distinguish the true oldest row from a local safety limit. Never switch an
   in-progress native gesture to remote wheel input at that boundary.
6. Preserve the recent Herdr tap/typing support, explicit remote mouse mode, ordinary direct
   SSH/Mosh history, and all current tmux contracts. Include SSH and Mosh lifecycle handling.

## Acceptance and current limits

Run actual Codex inside Herdr on the authorized old `SM_S911B`. Ask Codex for all 200 numbered
markers, verify their full order, then exercise the first real gesture without waiting for an
internal ready flag. Require sub-row movement, zero outbound wheels, post-release motion, touch
catch, stable reader position under new output, seamless live-bottom return and history beyond
one initial page. Test pane switching and retain the existing direct/tmux regression matrix.

Unit tests, lint and an Android build must pass for the eventual implementation. Only then install
and launch the completed APK on the foldable without tests, and obtain the user's visual result.

At the initial investigation's device enumeration, only wireless `SM_F976B` was connected; the
authorized old phone was absent. The initial investigation performed no phone test, install or
gesture. The later bounded implementation and its deployment are recorded separately below.

## Bounded implementation verification

Both `test lint assembleDebug --max-workers=2 --no-configuration-cache` runs passed. The final
run completed 166 tasks in 54 seconds; all eight new Herdr unit tests passed, with zero failures,
errors or skips. The follow-up fixed a gesture-state reset during live redraws before the native
drag threshold was reached. Logs are retained in `build/herdr-native-scroll-evidence/`.

On 2026-09-11, ADB enumeration resolved wireless `192.168.0.33:34961`; `ro.product.model` was
verified as `SM-F976B` before installation. The completed debug APK SHA-256 is
`621abcf0d518eae3f912ec1fb25571bd14cdcb632133d37e584a83f85735f1c1`. Streamed `install -r`
succeeded. `am start -W` returned `Status: ok`, `LaunchState: COLD`, and MainActivity was
`topResumedActivity`; keyguard reported `showing=false` and `inputRestricted=false`.

No tests ran on the foldable. Physical old-phone drag/fling and actual Codex acceptance remain
unrun because SM_S911B was unavailable. User confirmation of the installed bounded reader is
pending; neither this result nor the host API probe closes full tmux-equivalent acceptance.

## 2026-09-11: viewport alignment and first-login sizing

User screenshots showed a fragment of old output above Herdr's numbered tabs and an undersized terminal on first login until the keyboard was expanded and hidden. The viewport now uses the same whole-row height as the reported PTY grid. A regression demonstrates that an 807-pixel available area with 20-pixel rows previously exposed history row 999 above live row 1000; the aligned 800-pixel viewport starts at row 1000 and still supports fractional history movement.

The native view reschedules terminal sizing on reattachment, skips unmeasured geometry instead of reporting a one-cell terminal, and resends the latest grid when the connection becomes ready. Renderer-profile changes discard history-reader pixel geometry. Idle Herdr snapshots reuse their capture when pane identity, geometry, revision and remote offset are unchanged, avoiding repeated 1,000-row downloads. Recent history remains supplied by Herdr and bounded to 1,000 rows.

Validation: `./gradlew test lint assembleDebug --max-workers=2 --no-configuration-cache` passed in 59 seconds (166 actionable tasks). HerdrPaneHistoryTest: 9 tests; TerminalGridGeometryTest: 3; SshSessionRepositoryTest: 39; all zero failures/errors/skips. Evidence: `build/herdr-native-scroll-evidence/layout-gates.log` and `layout-deployment.json`.

Deployment at 2026-09-11T00:10:59+00:00: verified model SM-F976B at exact serial `192.168.0.33:34961`, installed debug APK SHA-256 `b7d154f16c57bdb09f4b604fbfbd7916a705f403079db0a2fac92e8ce6b14759`, launched successfully, and confirmed MainActivity as topResumedActivity with keyguard not showing. No device tests ran on the foldable. The authorized SM_S911B was unavailable. Actual first-login geometry, gesture smoothness, route/reason, outbound wheel count and full ordered real-Codex history remain unmeasured for this build; user confirmation is pending. No tmux acceptance claim is made.

## 2026-09-12: history boundary and repeated toast

User red evidence: https://photos.app.goo.gl/zY5Vj9hY3AFDLu2cA reports repeated boundary jumps
and shows the 1,000-row toast over the Herdr terminal. Source inspection found that either drag
direction opened a cached snapshot and returning to bottom kept that snapshot until release.
The reader now opens only for an older-history drag, clears immediately at live bottom, and
consumes the remaining drag without reopening history or starting a fling on the cleared reader.
The repeated toast and its unused string are removed; the bounded 1,000-row capture is unchanged.

All 12 Herdr unit tests pass, including new repeated-live-bottom, immediate-bottom-return and
repeated-oldest-boundary regressions. Full `test lint assembleDebug --max-workers=2
--no-configuration-cache` passed in 44 seconds, 166 tasks. Evidence:
`build/herdr-boundary-evidence/gates.log`. Existing direct SSH/Mosh and tmux tests are preserved.
The authorized SM_S911B is absent, so real gesture, fling and actual-Codex acceptance have not
been rerun. No tests run on the foldable. Deployment evidence is recorded in
`build/herdr-boundary-evidence/deployment.json`; user verification remains pending.

Completed debug APK `4811639a18728d7e6c73a0367ea383c5ca28b57793ab2b59ac6071101d0c139b` installed successfully on model-verified `SM-F976B` at exact serial `adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`. Cold launch returned Status ok; MainActivity is topResumedActivity. No fold tests. User real-workflow confirmation remains pending.

## 2026-09-12: user acceptance and gesture regression coverage

The user confirmed that the boundary fix works on build `4811639a…c139b` and requested
regression protection. `HerdrNativeScrollTest` now dispatches Android MotionEvents through the
production gesture handler for repeated live-bottom swipes without opening history/flinging,
fractional reader movement and source-refresh stability, immediate bottom return with no
same-gesture reopening, and repeated oldest-boundary gestures retaining all 1,000 ordered rows.
These complement the 12 host Herdr capture/viewport tests. The native tests are included in the
explicit full-suite inventory; execution awaits the authorized old SM_S911B. They are synthetic
event regressions and do not substitute for actual-Codex physical acceptance.

2026-09-12T20:37:54.196444+00:00 — Herdr regression/uniform-buttons follow-up complete: unit tests app/API/core 1068/11/8, zero failures/errors/skips; lint/debug/Android-test builds GREEN (264 tasks, 1m41s); test inventory GREEN6/6. Three new native MotionEvent tests compiled but not run because authorized old SM_S911B absent. Completed APK 534e91c5d0f9201c5e1cde5f96c4b1fe415071a48ba9f822c11c79e59c006fd5 installed on model-verified SM-F976B at exact serial adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp, cold launch Status ok, MainActivity topResumedActivity. No fold tests. Herdr boundary already user-confirmed; broader tmux physical acceptance remains open. Evidence build/herdr-regression-evidence/.

## 2026-09-12: mobile spaces/tabs switcher owns its swipes

User red: on a phone-sized Herdr layout, opening **switch** and swiping the spaces/tabs
panel displayed cached terminal history instead of scrolling the panel. Herdr 0.8.2 retains
its focused-pane rectangle underneath that full-screen mobile panel, so pane geometry alone
cannot grant the native reader ownership.

For the known two-row mobile layout, native history now requires the live **switch** affordance
in the right-hand header. The panel's **close** / **×** header, missing header, or mismatched
width leaves the gesture with the existing remote-input policy. This bounded check reads the
live VT frame, without another capture, remote command, or per-cell Compose state. Opening the
panel clears a pinned reader/fling; a swipe begun on the panel cannot start native history
partway through if the panel closes. The next swipe over live terminal output can read history
again. Desktop Herdr, direct SSH/Mosh and tmux routing remain unchanged. Unknown future mobile
header formats conservatively retain remote scrolling.

Host regressions cover mobile/desktop geometry, wide-character labels, open/close redraws with
an unchanged history snapshot, and ordinary-terminal exclusion. The new native-view regression
checks outbound panel wheel reports, no cached reader, gesture ownership through a close redraw,
zero-wheel native scrolling on the next gesture, and reader invalidation when the panel opens.
It is synthetic regression coverage, not actual-Codex or physical smoothness acceptance.

2026-09-12T22:03:21.147407+00:00 — Herdr mobile switcher repair on base 93f6a28 plus preserved unrelated changes: user RED reports panel swipe opening terminal history. Live two-row header now gates native history; panel gestures stay on existing remote mouse policy (AUTO_REMOTE_MOUSE_TRACKING when negotiated), remain remote through a close redraw, and a new live gesture regains native history. Host app/API/core units GREEN1070/11/8, zero failures/errors/skips; full test/lint/debug/Android-test build GREEN264/1m21s, final test timing refinement lint/Android-test build GREEN155/9s, inventory GREEN6. Completed APK 1c54f296da4e506e9db4ebde923f7fcbdd521f01e7203102922179e2a39d26d0 installed and MainActivity top-resumed on model-verified SM-F976B at exact serial adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp; no fold tests. Authorized old SM_S911B absent; native-view event test compiled but UNRUN. Actual physical route/reason and outbound wheel count UNMEASURED; tmux pane metadata not applicable to this Herdr-only change. No new actual-Codex or tmux smooth-scroll acceptance. Evidence: terminal-spike/build/herdr-switcher-evidence/.

## 2026-09-26: returning from native history to a remotely scrolled pane

User recording: https://photos.app.goo.gl/A6KafMqjod4er5bt8. The user confirms the path is
Mosh → Herdr → Codex; other transports are not established comparisons. In the recording,
the input briefly appears near the bottom around 3 seconds, then older content returns;
the keyboard toggle around 9–11 seconds reveals the input again. The pane moves while
Herdr's sidebar remains largely fixed. The recording alone does not expose remote scroll metrics.

A deterministic reproduction identifies an ownership trap when Herdr reports a nonzero
`offset_from_bottom`. The native reader could still take ownership, reach the bottom of its
captured rows, then clear its overlay and reveal Herdr's older remote viewport. Further
newer-history drags were consumed without a native snapshot or any remote wheel reports,
so they could not reduce the actual remote offset. Cache reuse while reading also ignored
changes to that offset.

Capture now declines native ownership whenever the reported remote offset is nonzero, before
both idle and active-reader cache reuse. The existing remote-input route can then move the
already-scrolled Herdr pane back to its actual bottom. Native pixel scrolling resumes after
a successful zero-offset capture. This recovery may use remote row scrolling until bottom;
it does not synthesize a remote fling or change tmux ownership. No input commands are added
to the capture path, and the 1,000-row bound is unchanged.

The JVM regression was red before the change. The production capture/native-view regression
also failed on the model-verified USB SM-S911B: three newer-history drags emitted no wheel
reports and left the simulated remote pane above bottom. This device test uses a fake
transport and controlled metadata; it is not actual Mosh/Herdr/Codex acceptance. Its green
run must prove the remote offset reaches zero without an IME resize and a subsequent native
history gesture sends zero wheels. Evidence is retained in `build/scroll-bottom-evidence/`.
Actual user-workflow confirmation remains required; this finding is not proof that every
intermittent bottom jump in the supplied recording has the same cause.

Verification at 2026-09-26T10:15:38.948620+00:00: `scripts/verify-android.sh build` passed on the immutable
current-source checkout (450 tasks, 4m42s). App/API/core JVM totals: 1093/11/8, no failures,
errors or skips; preflight tooling 125 tests and exact Android test inventory passed.
The final five-method old-USB suite passed on model-verified SM-S911B `RZCW81JZ9CP`,
with installed APK hashes checked before and after. Final debug APK SHA-256:
`2c503a449e817679d640f4a6544f0ef848c1e3b293518c6a44b76bcff16ddb20`.
It was full-installed successfully on model-verified wireless SM-F976B
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`; cold launch returned Status ok.
The activity is resumed but the lock screen (`showing=true`, `inputRestricted=true`)
prevented top-resumed foreground proof. No tests ran on the fold. User unlock and
actual-workflow confirmation are pending. Concurrent changes were preserved; the
shared-checkout build was superseded by isolated verification to avoid colliding logs.

Unlock follow-up: 2026-09-26T15:19:25.200299+00:00 — Herdr bottom-return unlock follow-up: exact model-verified SM-F976B adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp is unlocked (showing=false/inputRestricted=false), and MainActivity is topResumedActivity. Read-only checks only, no tests/install. Installed APK SHA256 773b4b00fbc150e6bffc8910c8c260fa7f709e24a4abcd6a7a923e80d81ba91a differs from this session’s verified 2c503a449e817679d640f4a6544f0ef848c1e3b293518c6a44b76bcff16ddb20 and the current shared-checkout APK; later package replacement is observed, provenance unverified. Current source retains require(offset == 0). This establishes present foreground state, not exact-artifact deployment or real-workflow acceptance.

## 2026-09-26: reject the remote-wheel recovery regression

The user reports the preceding bottom-return change appeared to help reach the input, but
scrolling became visibly row-based again. The `offset_from_bottom == 0` ownership gate and
its positive-remote-wheel test therefore do **not** satisfy acceptance. The earlier red/green
record above is retained as evidence of the rejected tradeoff, not a solved result.

Native history now remains available with a nonzero remote offset. Both older- and newer-history
gestures use the same local Float viewport and OverScroller. At the local bottom, the reader
keeps the latest pane snapshot visible while Herdr's remote viewport is still older, instead
of clearing the overlay onto that older screen. It releases normally when metadata reports the
remote bottom. No automatic keys, wheels, resize, or server mutation is used for that handoff.

An older reader pins its rows and exact pixel anchor. A reader at latest instead accepts updated
bounded snapshots so the input and subsequent output do not freeze. Capture cache reuse now
also tracks received terminal output: Herdr 0.8.2's pane `revision` describes metadata and is
not a content revision. Background refreshes invalidate an idle live capture after transport
output. A latest overlay refreshes on the existing 500 ms worker even without new Mosh frames,
because output below a scrolled remote viewport may not redraw that viewport. Pinned older
content stays stable. The same worker owns all bounded reads;
there is no capture or network work in drag/fling frames and the 1,000-row limit is unchanged.

New host assertions first failed against the rejected candidate, then passed for nonzero-offset
capture, latest-overlay retention, safe zero-offset handoff, and latest-content refresh versus
pinned pixel anchoring. The old-phone fake-transport/native-view gate now requires **zero**
remote input throughout native recovery, visible latest input, and refresh without new terminal
frames even with an unchanged API revision. A separate Android MotionEvent regression proves
3.25 px movement, post-release motion and touch catch with remote offset 6. Together with the
existing history-boundary and mobile-switcher regressions, all six device methods pass on the
model-verified USB SM-S911B. These are controlled native regressions, not actual Mosh/Codex
workflow acceptance; user confirmation on the completed fold build remains pending.
Evidence: `build/herdr-smooth-recovery/` and `build/scroll-bottom-evidence/smooth-device/`.

Final verification/deployment: 2026-09-26T15:36:36.664012+00:00 — Final APK SHA256 7ae1dca1167ecb4dce319239e1b3bbd0aa72c0d6790831312f49b6286e15c9a1: verify-android.sh build passed (unit tests, lint, debug/release builds; 450 tasks). Final controlled native/fake-transport regressions passed 6/6 on model-verified USB SM-S911B RZCW81JZ9CP. With remote offset 6, a valid bounded Herdr snapshot selects local Float/OverScroller; outbound remote wheel/input count is zero. Tests prove 3.25 px movement, fling, touch catch, safe bottom handoff, and latest-input refresh with unchanged API revision 1 and NO new Mosh frame. This supersedes the earlier output-dependent refresh description; actual Mosh/Herdr/Codex and tmux acceptance remain unconfirmed. Full non-incremental fold install succeeded on verified SM-F976B adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp; installed SHA matches. Launch Status ok and MainActivity topResumedActivity were verified immediately after launch; a later read showed Reddit foreground. No fold tests. Evidence: build/herdr-smooth-recovery/ and build/scroll-bottom-evidence/smooth-final-device/.

Follow-up user regression (not solved): 2026-09-26T17:07:28.295522+00:00 — User RED on fold persists with exact installed APK 7ae1dca1167ecb4dce319239e1b3bbd0aa72c0d6790831312f49b6286e15c9a1: row-based scrolling begins near the input, not only at the oldest cache boundary. User adds new conversations/new speech or output can scroll smoothly. Read-only SM-F976B diagnostics (no tests, gestures or install) confirm profile auto; recovered heap state after the issue had subsided shows outer grid 88x33, viewport autoFollow=true, lineHeight=59px, Herdr snapshot x26/y1/62x32/offset0, reader inactive. This is a healthy-state sample, NOT failing-route proof. Actual failing route/reason/wheels remain unmeasured, tmux metadata N/A. Nine bounded real host Codex pane captures parse successfully (48–1000 rows); temporary local JVM diagnostic test passed and was removed. No new production patch; prior six controlled tests do not establish user acceptance. Sanitized evidence build/herdr-fold-row-regression/live-state.txt; raw heap removed after numeric-state extraction.

## 2026-09-26: letter-numbered Herdr panes lost native history

The user isolated a smooth window and a row-scrolling window in the same fold app.
Read-only state inspection showed a valid history snapshot in the smooth window and
`herdrHistory=null` in the failing window, despite Auto mode, matching terminal size,
and the outer viewport at bottom. The failing conversation is `w6:pC`; the earlier
working pane was `w6:p7`. Production capture rejected the real `pC` layout before
issuing a pane read because it required decimal-only pane numbers. Herdr public pane
IDs also contain uppercase letters. This supersedes speculation about old-window
state, content length or a need to recreate the conversation.

History and input-context validation now accept uppercase alphanumeric pane numbers.
Exact pane/tab identity, geometry, byte/row bounds and shell quoting remain checked.
Host regressions cover `pC`, multi-character numbers, numeric IDs and malformed IDs;
the two new history/context assertions failed before the fix and pass afterward.
The native device recovery regression now also exercises the real-world `w6:pC` ID.
This ID repair does not itself close the broader tmux smooth-scroll acceptance record.

2026-09-26T17:11:20.187732+00:00 — Root cause CONFIRMED for user per-window row-scroll regression. Fold read-only failing-window heap has herdrHistory=null while outer viewport autoFollow=true/grid88x33; focused real host pane is w6:pC. Earlier healthy snapshot was numeric pane w6:p7. Production capture against real focused pC returned null four times immediately after layout, before get/read. Both history and input-context parsers incorrectly required :p[0-9]+, but Herdr public pane numbers contain uppercase letters. Candidate accepts :p[0-9A-Z]+ without changing shell quoting, identity, geometry, capture bounds or gesture routing. Two new host assertions RED before fix (21 total/2 failed), GREEN after. New controlled old-phone pC integration method added alongside numeric-pane coverage; complete gates/device run pending. Later live host GREEN probe selected w9:p1 after user switched, so it is explicitly NOT actual pC end-to-end green proof. Source/live API reads are independent corroboration of the ID-format mismatch. User real fold confirmation still pending.

2026-09-26T17:14:01.818639+00:00 — Pane-ID candidate final GREEN: verify-android.sh build passed 450 tasks/2m11s; JVM app/API/core 1097/11/8, zero failures/errors/skips, lint and debug/release builds passed; exact Android inventory validated. Old USB SM-S911B RZCW81JZ9CP controlled native/fake-transport suite passed 7/7, including w6:pC offset6 older/newer native recovery, ZERO outbound remote input/wheels, visible latest input, refresh without new Mosh frames, safe offset0 handoff; separate sub-row/fling/catch and switcher regressions preserved. APK SHA256 3d41521901ff05e0b1f796fddcfe283dd9155caa04b36b5e81b5dbc1072febbe matches installed/tested provenance. Same APK full-installed on model-verified SM-F976B adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp; launch Status ok and MainActivity topResumedActivity verified, no fold tests. Logs build/herdr-fold-row-regression/ and build/scroll-bottom-evidence/pane-id-device/. No actual Mosh/Codex gesture acceptance claimed; user to retry original letter-numbered conversation, no recreation required. Overall status remains planned/done_at none.

### User acceptance and regression protection

2026-09-26T17:15:34.392631+00:00 — User ACCEPTED pane-ID fix on installed fold build 3d41521901ff05e0b1f796fddcfe283dd9155caa04b36b5e81b5dbc1072febbe: "ok smooth now". Follow-up regression audit confirms host coverage for pC/pA1/numeric IDs, malformed rejection, bounded ordered history and pinned pixel anchor; native tests cover 3.25px drag, post-release motion, immediate touch catch, zero outbound wheel/input for pC recovery, latest-input refresh without Mosh frames, bottom handoff and mobile switcher routing. Reused unchanged-source passing evidence: full unit/lint/debug/release gate (450 tasks, JVM1097/11/8) and old USB SM-S911B seven native methods passed. Methods are present in exact Android test inventory; CI configuration validates complete API26/API35 runtime membership. Hosted CI was not run or claimed green. This confirms the specific Herdr letter-pane smoothness repair, not every item in the broader tmux Definition of Solved. No new device actions or application changes this follow-up.
