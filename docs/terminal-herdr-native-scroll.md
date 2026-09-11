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
the transcript during reading. Tapping the reader or typing returns to live output. A toast
explains the recent-history limit. Explicit Remote mouse mode retains the existing input route.

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
