# Product completion status

Last updated: 2026-09-07

<!-- release-evidence-current:start -->
Current release evidence: app JVM `971` tests (`971` passed, `0` skipped, `0` failures/errors); Mosh API JVM `11` tests (`11` passed, `0` skipped, `0` failures/errors); Mosh extension JVM `8` tests (`8` passed, `0` skipped, `0` failures/errors); old-phone Android app `332` tests (`307` passed, `25` skipped, `0` failures/errors). The source and artifact hashes and any pending external gates are recorded in `build/release-evidence/candidate-manifest.json`.
<!-- release-evidence-current:end -->

This is the authoritative implementation checklist for the paid-release polish programme. A phase is checked only when its implementation, migrations, tests, documentation, and applicable device evidence are complete. A passing narrow test does not complete a broader item.

The current product direction keeps **Connections**, **Terminal**, and **Settings** as the three
primary phone destinations. Connections is the main catalogue for saved hosts, keys, and snippets;
Terminal owns active sessions.

The 2026-09-03 tmux performance slice now enforces a hard 2,000-row comparison/rebuild budget per
display callback and publishes prepared history only as an atomic coherent replacement. The
maximum 20,480-row snapshot, coordinate reset, 4,096-row prepend, randomized reference cases,
stale cancellation, stable IDs, reader anchoring, and lifecycle cancellation are covered. All
seven release-like terminal journeys passed on the model-verified old `SM-S911B`; live reset CPU
frames measured P50/P90/P95/P99 2.2/3.6/4.8/5.8 ms at 60 Hz and thermal status 0. The guarded
actual-Codex matrix passed 3/3 with local first gesture, zero wheels, ordered paging, sub-row
drag/fling/catch, reader anchoring, and live bottom; real Mosh passed 5+1. That local gate
passed 89 script tests and 475 Gradle tasks with JVM totals app `971/0/0/0`, Mosh API `11/0/0/0`,
and extension `8/0/0/0`. Its debug APK (`97e32305…1c90`) was restored and foregrounded on
the old phone after benchmarking. The fold was untouched; broader hosted/manual release gates and
the living record's final fold/user confirmation remain open.
The subsequent complete old-phone app suite passed its then-exact 329-method membership;
the Orchestrator console's lifecycle count was not used as the authoritative test total. The
expanded 332-method contract has since passed on the same model-verified phone.

The API 35 disposable backup gate exported encrypted Standard and Full archives, found no portable
secret plaintext in either artifact, uninstalled and reinstalled the app twice, proved prior private
state absent, restored Standard metadata with the expected non-exported-secret placeholder, and
restored the Full portable secret using a newly created Android Keystore. All three exact methods
passed with no failures or skips, and the same gate is required on pull requests and main pushes.
The post-change forced local source gate then executed all 451 tasks in 6m19s: app JVM
`971/0/0/0`, Mosh API `11/0/0/0`, extension `8/0/0/0`, debug/release lint and builds,
Android-test packaging, both Mosh native ABIs, staged test utilities, and benchmark assembly all
passed. All 95 host-script tests, release APK/AAB packaging checks, pinned Mosh source verification,
workflow YAML parsing, and the backup evidence plaintext scan are green.

The focused physical primary-navigation benchmark then reproduced the retained issue on the exact
model-verified old phone: 46 frames across 10 iterations measured CPU P50/P90/P95/P99
`6.78/25.31/29.35/36.23 ms`. Perfetto attributes the repeatable slow Settings-opening frame to up
to `16.57 ms` of initial category-list layout plus up to `7.81 ms` of recomposition. One iteration
also hit a `26.29 ms` rounded-rectangle shader cache miss, blocking the main thread for `18.76 ms`.
This closes diagnosis. The one-time shell transition remains a documented optimization candidate;
the terminal hot-path journeys, rather than speculative broad measurements, remain the release gate.

The follow-up primary-navigation harness now resolves each described Material navigation node to
its nearest clickable ancestor before clicking. The exact 10-iteration old-phone rerun passed with
zero non-clickable warnings (previously 20) and CPU P50/P90/P95/P99
`7.93/22.13/22.66/24.32 ms`. The lower P95 is not claimed as a product optimization because the UI
was unchanged and the prior one-time shader outlier did not recur; `22.66 ms` remains above a
16.67 ms frame.

The exact old phone subsequently passed the focused adaptive walkthrough: real Samsung IME input,
physical landscape (`ROTATION_90`), maximum system text (`font_scale=2.0` observed by
`MainActivity`), and Samsung split screen with the app in a `1080 x 1185` top pane. Each hierarchy
retained positive-area controls. TalkBack bound successfully and the app exposed its labelled
semantic tree, but ADB-injected motion bypassed Samsung TalkBack's gesture recognizer; physical
focus-order traversal therefore remains pending rather than being inferred.

The 2026-09-03 safety slice fixed exact SFTP keyboard-interactive prompt
ownership, retryable same-token clipboard expiry across Activity recreation,
and defense-in-depth sanitization of remote notification text. The complete
local `test lint assembleDebug assembleRelease assembleDebugAndroidTest` plus
benchmark assembly gate is green with app JVM `960/0/0/0`. Stable API 37.0
passed the exact 42-method boundary suite with no failures or skips. That run
uses the real permission UI for denial and grant, resumes exactly one pending
saved-host action across Activity recreation, keeps a public-host action
available without the broad grant, and proves the system-mediated Nearby SSH
picker does not request it. Its host phase separately grants, launches, revokes,
requires termination of the original app process, relaunches, and proves the
permission remains denied. Hostnames are classified off the UI thread; any
resolved local address requests permission, while public answers and resolver
failure do not trigger a broad prompt. A separate real API 37 gate proves the
permission blocks raw TCP and production SSH to the emulator's RFC1918 host
gateway before protocol traffic, then completes SSH terminal traffic and SFTP
upload/download/delete against the identical live server after grant. The exact
API 35 full run accounted for all 329 methods with 307 passes and 22 reviewed
skips, including the seven API-37-only permission/network methods by exact skip
identity. Completed SSH through an actual public-Internet route while the broad
grant is denied remains external evidence; it is not inferred from session-start
UI or ADB-reversed loopback.

The dated 2026-09-02 release-hardening slice is green but does not close every public-
release gate. The full `test lint assembleDebug assembleRelease assembleDebugAndroidTest` plus
benchmark assembly gate passed with 475 tasks. Fresh JVM totals are app `945/0/0/0`, Mosh API
`11/0/0/0`, and extension `8/0/0/0`. On the exact model-checked authorized old-phone Wi-Fi serial
`adb-RZCW81JZ9CP-NpnzVa._adb-tls-connect._tcp` (`SM-S911B`), the full app suite ran 318 tests:
303 passed and 15 opt-in external-server tests skipped. The new Android Ed25519, settings
Activity/ViewModel recreation, opaque backup export/restore, and real Room database-reopen checks
also passed in focused runs; Mosh API passed 5/5 and the extension passed 4/4.

The clean emulator runtime matrix is contract-enforced by class/method identity and exact
API-specific skips, not aggregate totals alone. Full suites passed on API 26 (`316/0/0/17`) and
API 35 (`316/0/0/13`); 33-test boundary suites passed on APIs 28, 29, and 32 with two skips each,
and API 33 with one. That emulator result predates one newly pinned opt-in real-mouse-app method; the
that 318-method manifest is one-for-one complete and passed with exact membership/skip identity
on the old phone.

The dated real-Mosh acceptance class passed 5/5 with no skips on that same old phone against a
fresh disposable OpenSSH 10.3/Mosh 1.4.0 fixture. It proves password bootstrap and 200 ordered
terminal rows, four simultaneous process-isolated sessions with independent live resize and
single-session close, isolated-worker death with slot reuse, and broker death with automatic
client rebind and fresh post-recovery traffic. Its fifth method proves production app-selected
tmux local scrolling, exact history/reader anchoring across Activity recreation, and the
pre-existing-copy-mode Auto/Remote ownership policy with real physical gestures. A first
worker-death run found a genuine EOF/event
race that reported a killed worker as a clean disconnect; terminal lifecycle callbacks now receive
a bounded one-second precedence window after pipe EOF, with a deterministic JVM regression. A
separate private-key real-Mosh run also passed. Real Wi-Fi/cellular/VPN roaming, the user's
external server, and manual OEM battery behavior remain unproved and are not inferred.
That exact 5+1 matrix was rerun green through the fixed
`scripts/run-real-mosh-device-tests.sh` entrypoint. The runner binds the disposable fixture to one
acknowledged LAN IPv4 address, resets it before use, re-verifies `SM-S911B` before every install and
test launch, rejects skips/missing named tests/wrong counts, sanitizes durable output, and tears the
fixture down on every exit; host tests cover its wrong-model, fold, failure, cleanup, and secret-
redaction paths.
After the credential-storage regression and final host gate, the full default runner was repeated
on the freshly enumerated old phone and passed 318 tests with 303 executed passes, 15 expected
opt-in skips, and no failures/errors. The guarded real-Mosh 5+1 matrix then passed again. The
that main debug APK (`0aef8e22…2ec`) was installed successfully on the fold without tests;
`MainActivity` is resumed, but the dozing locked device's `NotificationShade` prevented
top-resumed foreground proof.

A new external API 35 lifecycle gate then passed against real loopback OpenSSH. It preserved one
exact app PID and live service/notification through ordinary backgrounding, deep idle, restricted
standby, and Data Saver, restoring every changed AVD policy to its recorded baseline. The runner
proved PID ownership before `kill -9`, observed that PID, service, and notification disappear
without automatic restart, privately verified one saved host and one recent row with zero stored
session-only secrets, explicitly relaunched to a distinct PID and honest empty-session UI, required
the password again, and passed a fresh real SSH marker. No crash or ANR was reported. The fixed
runner is now a required CI job; its explicit physical mode accepts only freshly verified
`SM-S911B` and never mutates battery policy, but this acceptance was run only on its disposable AVD.

The minified release/update SSH smoke is also now wrapped by a hermetic CI runner. A local
CI-equivalent run generated a temporary PKCS12 identity, built and signer-matched the APK and AAB,
passed extension-absent real SSH before and after same-certificate reinstall, preserved non-secret
data, and required the unsaved password again. Cleanup left only a 510-byte sanitized status file;
the key, signed artifacts, private logs, AVD, and fixture were removed. This is ephemeral acceptance
signing and same-version reinstall evidence, not production signing or older-version migration;
the first hosted workflow run awaits an authorized commit/push.

Current-UI release inspection then found that a new host editor defaulted **Save password** on,
contradicting the documented opt-in policy. New hosts now start with password saving off while an
existing available encrypted password remains visibly selected; focused Compose instrumentation is
green. The previously checked-in release update smoke was also stale against the Connections-first
UI and ignored the tmux chooser. It now validates package/version/signature continuity, re-verifies
the named disposable AVD before destructive operations, follows the current host editor, explicitly
chooses the direct shell, and cleans the fixture on every exit. A fresh ephemeral-key `0.0.2` run
passed clean install, real extension-absent SSH, same-certificate update, process restart, non-secret
host retention, password re-prompt, and a second real SSH marker. The app APK/AAB and separate
extension APK signatures verified with matching APK signers; temporary signing material was removed.

The disposable OpenSSH 10.3/tmux 3.7c/Mosh 1.4.0 fixture then ran seven opt-in physical-device tests
with no skips or failures: password SSH, imported Ed25519 through a saved host, production terminal
scroll plus tmux, app-selected 5,000-row tmux paging, two SFTP workflows, and
SSH-bootstrap-to-native-UDP Mosh. The first run exposed a
real test-harness defect: the raw SSH command omitted Enter and waited forever; the test now sends
the terminating newline and passes. Release inspection also proved R8 had removed JSch's
reflection-loaded Bouncy Castle Ed25519 adapters from the minified artifact. Narrow keep rules and
APK/AAB packaging assertions now preserve their original names; release packaging passes. A
locally debug-signed minified release APK installed and cold-launched in 538 ms with `MainActivity`
as `topResumedActivity`. A debug Android-test APK cannot validly instrument that shrunk release
because its runner expects unshrunk target Kotlin/AndroidX support classes; that setup-only crash is
not counted as release runtime evidence.

The subsequent tmux-history slice remains green on the same source gate and raises the fresh app
JVM total to `913/0/0/0`. Initial app-selected tmux capture is now bounded to the newest 4,096 rows;
near-top drag/fling asynchronously requests exact older `capture-pane` ranges on the authenticated
side channel. Pane/history coordinates and a one-row overlap reject stale pages, stable row IDs plus
exact pixel compensation preserve the reading anchor, and diagnostics distinguish the true oldest
tmux row from a locally truncated cache and the 200,000-row app cap. A real tmux 3.7c run also found
and fixed its rewriting of tab format separators, using a tested pipe-delimited control protocol.
The full app device suite now ran 315 tests with zero failures and nine expected opt-in skips. A
seven-test real network matrix passed without skips, including a production-UI app-selected tmux
test that paged 5,000 ordered rows to row 1 after 265 real `MotionEvent` drags with route
`LOCAL_SCROLLBACK`, reason `AUTO_TMUX_LOCAL_READY`, and zero remote wheel reports. Device tests ran
only on the exact model-checked old `SM-S911B`; the fold was untouched.

Current-source connected performance was also refreshed on that exact old phone. The initial
Baseline Profile run exposed a stale benchmark selector for the removed `Open local workspace`
control; the shared journey now uses the release UI's `Open connections` semantic. Profile
generation then passed in 2m34s with 23,892 Baseline and 21,122 Startup Profile rules. The separate
connected Macrobenchmark passed in 6m17s with zero failures and two expected generator skips:
startup 2/2, navigation 1/1, all six terminal fixture journeys, and the asset contract completed.
Median cold start was 499.36 ms without compilation and 380.14 ms with the Baseline Profile. All
terminal fixture CPU-frame P95 values were 4.89–11.59 ms; navigation CPU-frame P95 was 29.02 ms and
remains a trace-backed optimization opportunity, not a hidden pass.

After the benchmark-source and generated-profile updates, the complete local gate was rerun and
passed in 54s with 474 tasks (15 executed, one from cache, 458 up-to-date), including unit tests,
lint, debug/release builds and packaging verification, Android-test APK assembly, and both
benchmark variants.

After connected performance collection, the benchmark-signed package was replaced on the same
re-verified old phone with the final debug APK from that green gate. The full install succeeded,
`MainActivity` cold-launched in 2,102 ms and was verified as `topResumedActivity`; debug APK
SHA-256 is `e3551d15f013bf9b1a209c775a3ea315af4ecdd4f30818a43695620276504c1e`. No install or test
targeted the Wi-Fi fold.

The dated accessibility/adaptive slice adds real-device coverage at 320 × 360 dp and 200% text
for all three compact primary destinations, the Connections host actions, and the Settings
hierarchy. Its first run found a 40 dp Add-host target; after fixing that, the full compact layout
also exposed a zero-height host list. Connections now scales its full-header height requirement
with the configured font scale, while Add and the owner-selected catalogue remain reachable in
the 200%-text 320 × 360 dp case and search remains visible at standard text in 320 × 480 dp. Every
Connections back/clear/details/overflow control plus Add exposes at least a 48 dp target. The
affected 52-test UI set and the complete app device suite were green on the exact model-checked old
phone at that checkpoint; the latter finished 315 tests with 306 passes, nine expected opt-in
external-server skips, and zero failures. The exact post-fix 474-task local gate passed in 1m45s.
The resulting debug APK
(`b5b60b5684c08885cf55a6ef83c2a957bcb0f40bbae4ff632006484f7a0b2051`) installed successfully on
that old phone, cold-launched in 1,168 ms, and was verified as `topResumedActivity`. No install or
test targeted the Wi-Fi fold.

The follow-up visual/resilience slice adds Settings light/dark surface-polarity and expanded
master/detail contracts, plus terminal-empty-state light/dark and 200%-text narrow-layout coverage
with a reachable at-least-48-dp saved-connection action. The old phone already had
`POST_NOTIFICATIONS` genuinely denied; a new instrumentation test started the real foreground
service from a visible-action seam, observed `LIMITED_BY_PERMISSION`, and remained alive through
service promotion/idle teardown. The focused 18-test visual/settings run and one-test denial run
passed. The complete default app instrumentation runner then passed 311/311 tests in 211.294 s on
the freshly model-checked `SM-S911B`. Official stable default x86_64 system images were provisioned
for APIs 33 and 34 and used with clean, wiped disposable AVDs; the existing wiped API 35 AVD was
also exercised. API 33 passed 311/311 in 217.746 s and API 34 passed 311/311 in 221.868 s. The first
API 35 run found two real portability defects: a synthetic compact Settings test inherited emulator
system insets, and the recreated keyboard-interactive prompt was queried in the Activity root even
though the focused root was a dialog window. Settings now accepts a defaulted safe-inset seam for
deterministic layout contracts, and the recreation test closes the keyboard and explicitly targets
the fresh dialog root. The corrected API 35 suite passed 311/311 in 194.471 s.

After those fixes, the final default app runner passed 311/311 in 207.128 s on the freshly
model-checked Android 16 `SM-S911B`. The exact 474-task unit/lint/debug/release/Android-test/
benchmark gate passed in 1m39s (27 executed, 447 up-to-date), followed by `git diff --check`. The
final debug APK has SHA-256
`b763eb00ae526fcac6fb3d6317b740920942ab0f8bb9ee4bb5f3509b8c079923`; its exact-serial install
succeeded on that old phone, it cold-launched in 1,292 ms, and `MainActivity` was
`topResumedActivity`. No install or test targeted the Wi-Fi `SM_F976B` fold.

The current actual-Codex matrix has now been rerun against the workstation SSH service on the exact
model-checked old phone. Direct SSH retained and scrolled through all 200 Codex-generated rows. The
fresh app-selected tmux path crossed its initial 4,096-row page, retained 5,000 ordered fixture rows,
selected `LOCAL_SCROLLBACK` on the first gesture, emitted zero remote wheels, and passed sub-row,
fling, and catch assertions. Explicit Remote mode also forwarded real drags to GNU less, Vim, and
htop: each advanced its visible numbered fixture, emitted three remote wheel reports, and applied
zero local viewport updates. The generated SSH identity was revoked and shredded, and retained
evidence is field-whitelisted to exclude terminal text and host/account details. Still open before
an unqualified public-ready claim: the latest wrapped-link user check, pre-existing tmux-copy-mode
policy and Auto-mode intent separation, resumed-Mosh coverage, reader anchoring/live-bottom, and
documented manual performance/release walkthroughs.

The 2026-08-19 launcher-branding refresh has a newer current-source gate than the historical phase
evidence below. The full 482-task unit/lint/debug/release/Android-test/package-integrity/native/
benchmark gate passed; fresh unit totals are app `815/0/0/0`, Mosh API `11/0/0/0`, and extension
`8/0/0/0`. Focused connected contracts passed app 2/2 and extension 4/4 on exact Wi-Fi serial
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp` (`SM-F976B`). Final exact-serial installs of both
debug APKs succeeded and `MainActivity` cold-launched in 716 ms as `topResumedActivity`. Current
debug SHA-256 values are main `e6c6098c7421ce4015b30151b92553b281a7990dbc7a2c661609947db25530ec`
and extension `2e4b6f2ec1ff92d171885bfefdb2c18ebe88458cce0a17410ee5a00de0f0ad53`.

After every substantial code slice, run the relevant unit tests, `./gradlew test`, `./gradlew lint`, debug and release builds, and Android-test assembly before continuing. Then resolve the fresh output of `adb devices -l`, install/launch by exact serial on every currently connected authorized USB and Wi-Fi phone, verify `MainActivity` is foreground, and report offline, unauthorized, and failed targets. A slice is not green merely because one device or one build variant passed.

Historical closure tables below describe earlier slices only. Evidence in this table is superseded
by the release-evidence block above; the Wi-Fi app suite was environment-blocked,
and open manual, performance, and release checks remain explicitly pending:

| Historical 2026-08-10 gate | Result |
|---|---|
| Frozen full source gate | App unit, lint, debug APK, Android-test APK, release APK/AAB packaging verification, Mosh API/extension lint/build/native, and benchmark assembly gates passed |
| Fresh JVM reports | Tests/failures/errors/skipped: app `785/0/0/0`, `mosh-api` `11/0/0/0`, extension `8/0/0/0` |
| Local release-like gate | Existing Android debug keystore only, not production signing: `BUILD SUCCESSFUL` in 1 min 28 s with 159 tasks; app release APK/AAB and extension release APK signatures verified; both APKs installed on disposable API 35 AVD `terminal-spike-release-test`; app cold launch 504 ms and `MainActivity` `topResumedActivity` |
| Connected Android tests on every freshly enumerated authorized target | USB app runner reported 252 tests with no failures. Opt-in real JSch/OpenSSH-with-Mosh-absent, Standard/Full opaque-provider backup, and physical-KeyEvent proofs passed. `mosh-api` 5/5 and extension 3/3 passed on both USB and Wi-Fi. The attempted Wi-Fi full app UI suite remains environment-blocked by secure keyguard/dozing |
| Exact-serial dated main/Mosh debug APK install and `MainActivity` foreground proof | Version `0.0.1` app SHA-256 `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408` and extension SHA-256 `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3` installed successfully on USB and Wi-Fi. USB `MainActivity` was `topResumedActivity` and the then-focused window; Wi-Fi reported it as the resumed/focused app behind the secure Bouncer, so unobscured foreground proof remains external |
| Performance measurements | Connected profile/Macrobenchmark collection passed on USB API 36; manual latency/memory/resize/multi-session evidence remains pending |
| Final manual/release requirement audit | Pending; the user's own saved Mosh server still needs an unlocked manual retest |

## Phase 1 — Baseline and audit

- [x] Phase 1 complete.
- [x] Read `AGENTS.md`, README, architecture decisions, performance guidance, Gradle configuration, lint defaults, tests, dependency inventory, and third-party notices.
- [x] Inspect the clean starting worktree (`ff8435b`, `main`).
- [x] Record SDK/toolchain and direct SSH/terminal dependencies.
- [x] Run the initial unit-test, lint, debug-build, release-build, and Android-test assembly baseline.
- [x] Record the initial Android-test compile failure separately from task changes.
- [x] Repair the obsolete Compose test import and regenerate the Android-test APK.
- [x] Complete current storage, credential, known-host, session-lifecycle, renderer, and performance audit.
- [x] Create and reconcile every required architecture/security/backup/Mosh document.
- [x] Rerun all Phase 1 gates after documentation and baseline repair.
- [x] Install and launch the verified debug APK on every connected authorized phone after the Phase 1 code repair passes all required gates.

Initial baseline evidence:

| Gate | Result |
|---|---|
| `testDebugUnitTest` | Passed in 8.863 s |
| `lintDebug` | Passed in 1.703 s |
| `assembleDebug` | Passed in 1.087 s |
| `assembleRelease` | Passed in 43.844 s; unsigned release artifact produced |
| Initial `assembleDebugAndroidTest` | Failed: obsolete `assertDoesNotExist` import in `MainScreenSmokeTest.kt` |
| Repaired `assembleDebugAndroidTest` | Passed in 1.568 s; Android-test APK regenerated |

Phase 1 closure evidence:

| Gate | Result |
|---|---|
| `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest` | Passed in 21 s against unchanged worktree hash `d997f8ca…`; 136 tasks, full lint report written |
| `connectedDebugAndroidTest` | 15/15 passed in 27 s on exact serial `RZCW81JZ9CP` (`SM-S911B`, Android 16) |
| Exact-serial debug install | `adb -s RZCW81JZ9CP install -r …` succeeded |
| Cold launch / foreground | `MainActivity` launch status `ok`, 1,033 ms total; verified `topResumedActivity` |
| Fresh ADB target audit | USB target authorized; previously seen Wi-Fi `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` absent from list and therefore not updated |

## Phase 2 — Data and security foundation

- [x] Phase 2 complete.
- [x] Add stable UUID-backed host profiles with display name, hostname/IP, port, username, SSH/Mosh protocol, authentication reference, optional terminal/keyboard profile, favourite, group/tag, startup command, global-override keepalive/reconnect, Mosh port/range/server command, and created/updated timestamps.
- [x] Add credential models for password, private-key identity, and keyboard-interactive authentication; hosts reference credential IDs and never duplicate secret material.
- [x] Add SSH key identities with name, algorithm, public fingerprint/key, encrypted private payload reference, imported/generated and passphrase flags, creation date, and optional comment.
- [x] Add known hosts keyed by host+port with algorithm, fingerprint, public host key, first-seen, and last-seen.
- [x] Add snippets with name, optional group, command, insert/run mode, append-Enter, multiline-confirmation, favourite, and updated timestamp.
- [x] Add terminal profiles with theme/font/size/line height/letter spacing/cursor/scrollback/bell/scroll/link/TERM/alternate-history fields.
- [x] Add keyboard profiles with ordered actions, one/two rows, modifier behavior, haptics, repeat, input mode, tmux prefix, and per-host override support.
- [x] Add a relational application database with exported schemas, explicit migrations, constraints, transactions, and migration tests.
- [x] Migrate the existing encrypted settings v1–v3 data without losing profiles, identities, snippets, key order, saved-password references, or known hosts.
- [x] Add Proto DataStore 1.2.1 with an explicit schema and migrations for typed global settings; store no secret payloads in it.
- [x] Add a `CredentialStore` abstraction for passwords, private keys, and explicitly saved passphrases.
- [x] Preserve non-exportable Android Keystore keys and authenticated encryption at rest.
- [x] Handle unavailable, invalidated, corrupt, and legacy credentials without silently deleting them; expose one separately confirmed full app-data reset from every fail-closed startup and Settings recovery gate.
- [x] Add central log redaction and release logging policy.
- [x] Keep decrypted secrets out of UI state, saved state, navigation arguments, and logs.
- [x] Add host validation, migration, credential round-trip/error, and redaction tests.

Phase 2 closure evidence:

| Gate | Result |
|---|---|
| `./gradlew testDebugUnitTest` | Full JVM suite passed after the final cutover/security changes |
| `./gradlew lint` | Passed with debug, unit-test, and Android-test analysis; report generated |
| `assembleDebug assembleRelease` | Passed with R8, release lint-vital, packaging verification, and unsigned release APK |
| `assembleDebugAndroidTest` | Passed; fresh 1,376,875-byte Android-test APK produced |
| Focused `connectedDebugAndroidTest` | 49/49 Room, migration, credential, container, recovery, and smoke tests passed on exact serial `RZCW81JZ9CP` (`SM-S911B`, Android 16) |
| Exact-serial debug install | Latest 14,865,606-byte debug APK installed successfully on `RZCW81JZ9CP` |
| Cold launch / foreground | `MainActivity` launch status `ok`, 1,140 ms total; verified `topResumedActivity` |
| Fresh ADB target audit | One authorized USB target; no offline/unauthorized/failed targets and no Wi-Fi target present |

## Phase 3 — Navigation and design system

- [x] Phase 3 complete.
- [x] Keep exactly Connections, Terminal, and Settings as phone destinations per the latest product direction.
- [x] Remove Tools and the redundant Workspace-to-Connections route as top-level destinations.
- [x] Add compact `NavigationBar` and expanded-width `NavigationRail`/list-detail behavior.
- [x] Respect status, navigation, cut-out, gesture, and IME insets edge to edge.
- [x] Add reusable spacing, shape, typography, icon, status-color, feedback, and motion primitives.
- [x] Make app theme system/light/dark aware without changing terminal palettes unexpectedly.
- [x] Keep all debug surfaces behind `BuildConfig.DEBUG` or debug-only sources.
- [x] Move Known Hosts to Settings > Security.
- [x] Build Workspace in the required order: compact app bar, Active sessions, every saved host favourites-first with one-tap authoritative authentication, Recent connections, and primary New connection action.
- [x] Show active-session friendly name, SSH/Mosh badge, state, sanitized optional OSC title, last activity, reopen tap, and reconnect/disconnect/duplicate overflow without exposing passwords/private endpoint detail.
- [x] Add polished multi-session horizontal/list presentation and a no-session state with explanatory text, New connection, and optional Restore backup action.
- [x] Verify bottom navigation, predictive/back behavior, large text, light/dark, and compact/expanded layouts.

Phase 3 closure evidence:

| Gate | Result |
|---|---|
| `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest` | Passed in 1 min 50 s; 152 tasks, full lint analysis, release R8, and release packaging verification all succeeded |
| Focused Phase 3 device contracts | 14/14 live predictive-back, terminal-inset, light/dark, large-text, adaptive Workspace, paste, and mouse-acceptance tests passed on `RZCW81JZ9CP` |
| Full `connectedDebugAndroidTest` | 126/126 passed in 1 min 25 s on exact serial `RZCW81JZ9CP` (`SM-S911B`, Android 16) |
| Exact-serial debug install | Latest 15,452,656-byte debug APK installed successfully on `RZCW81JZ9CP` |
| Cold launch / foreground | `MainActivity` launch status `ok`, 1,234 ms total; verified `topResumedActivity` |
| Fresh ADB target audit | One authorized USB target; no Wi-Fi, offline, unauthorized, or failed target present |
| Independent completion audit | No remaining Phase 3 blocker after privacy, OSC, inset, input-acceptance, predictive-back, reconnect-lifecycle, and visual-contract re-audit |

## Phase 4 — Connections management

- [ ] Phase 4 complete — the final device walkthrough is pending.
- [x] Add polished Hosts, Keys, and Snippets tabs with clear selected state.
- [x] Search hosts across display name, username, hostname, and tags; search keys/snippets; add favourite/recent/group sorting and filtering.
- [x] Replace inline Edit/Use/X actions with row actions and confirmed overflow deletion.
- [x] Host rows show protocol/computer icon, friendly name, `user@hostname:port`, SSH/Mosh badge, favourite, active-session state, and overflow; row tap connects.
- [x] Host editor covers every model field, authentication method, credential/key selector, save-password explanation, test connection, field-local validation, unsaved-change protection, and conditional Mosh fields; Quick Connect preserves its selected saved private-key association.
- [x] Support editable/savable Mosh profiles while honestly blocking Mosh launch when the extension is absent or incompatible.
- [x] Add key import through SAF, Ed25519/RSA generation, copy-public-key, full selectable fingerprint detail, rename, search, and confirmed deletion while preserving passphrase protection and every OpenSSH format supported by JSch.
- [x] Key rows show friendly name, algorithm, short fingerprint, imported/generated provenance, and lock/passphrase status without private material.
- [x] Add snippet groups/favourites, copy and terminal access; default multiline snippets to insert, show Enter state, and require explicit confirmation before immediate multiline execution.
- [x] Add empty, no-results, loading, error, disabled, and success states.
- [x] Add host/key/snippet unit and UI coverage.

## Phase 5 — Settings

- [ ] Phase 5 complete — the persistence walkthrough is pending.
- [x] Add searchable hierarchical categories: Appearance, Terminal, Keyboard, Sessions & Background, Notifications, Backup & Restore, Security, Mosh Extension, About, and debug-only Developer.
- [x] Add system/light/dark mode, dynamic color toggle, restrained accents, and live persistence.
- [x] Add Current, Ayu Dark, One Dark, Dracula, Nord, Solarized Dark/Light, Gruvbox Dark, Tokyo Night, Catppuccin Mocha, High Contrast, and Custom terminal themes with licensed notices, live preview, ANSI 16, cursor/selection/bold-bright, edit/reset, and persistence.
- [x] Add System monospace, Source Code Pro, JetBrains Mono, IBM Plex Mono, Cascadia Mono, and Symbols Nerd Font Mono fallback; add bounded SAF custom-font import with proportional-font rejection and a live preview covering ASCII, box drawing, Powerline/Nerd, CJK, combining, emoji fallback, bold, and italic.
- [x] Add font size, line height, letter spacing, bold, ligatures-off-default/cell-alignment validation, pinch zoom, and reset.
- [x] Add scrollback presets/custom validation; block/underline/beam cursor/blink; visual/vibration/audible bell; URL/OSC 8; OSC 52 Disabled/Ask default; copy-on-selection; multiline paste; bracketed paste; alternate-history; touch/two-finger modes; input-follow/viewport policies; and reset.
- [x] Keep `xterm-256color` as the normal TERM default with per-host override and do not recommend global `screen-256color` for tmux.
- [x] Add General terminal, tmux/Codex, Vim, Minimal, and Custom keyboard presets plus layout/actions/modifier/haptic/repeat/tmux-prefix controls and per-host overrides.
- [x] Support Esc, Ctrl, Alt, Tab, Shift, arrows, Home/End, Page Up/Down, Insert/Delete/Enter, slash/backslash/pipe/tilde/backtick/hyphen/underscore/at, F1–F12, Ctrl+C/D/L/R/W/U/A/E, tmux prefix, Paste, Snippets, Hide keyboard, and Keyboard settings actions.
- [x] Add sessions, notifications, background health, security, Mosh status, local-first privacy, notices, version, and debug-only developer settings.
- [x] Add the live Mosh Extension status slice with Checking/Absent/Disabled/Untrusted/Incompatible/Available/Error, version/API/capabilities/session-limit details, and a functional refresh/retry action.
- [ ] Verify settings persist across restart and previews reflect saved values.

## Phase 6 — Terminal UI and rendering

- [ ] Phase 6 complete — measured renderer evidence is pending.
- [x] Use compact session chrome with friendly title, protocol/state, add-session, and overflow actions.
- [x] Support simultaneous session tabs, selection, protected close, safe immediate double-tap duplicate when authentication is reloadable, re-entry only for intentionally non-retained one-shot secrets, reorder where practical, and sanitized OSC titles.
- [x] Add reconnect, disconnect, find, clear scrollback, transcript export, full screen, settings, snippets, and connection details.
- [x] Add bounded local selection, handles, copy/select-all, OSC 8 link actions, and jump-to-latest behavior.
- [x] Preserve viewport position under new output and resize correctly under IME/orientation/multi-window/fold changes.
- [x] Preserve the custom Canvas renderer, bounded buffers, frame batching, cached metrics, and no per-cell/per-line Compose state.
- [x] Keep parser, screen, and renderer state separated; add dirty-row/region publication, reuse hot-path buffers, preserve exact monospace cell geometry, and keep parser/I/O off main.
- [x] Preserve/add ANSI/VT, 16/256/true color, SGR, alternate screen, cursor save/restore/shape, scroll regions, origin and insert/replace modes, bracketed paste, application cursor/keypad, DEC/SGR mouse, OSC title/8/52 policy, resize, combining/wide/emoji/box drawing, wrapped selection, and correct Backspace/Delete behavior.
- [x] Add recorded project-authored/standards-derived ANSI fixtures for each required mode, resize, Unicode, terminal application trace, randomly chunked input, and malformed/bounded recovery.
- [ ] Measure before/after input, output, scroll, resize, multi-session, memory, and renderer behavior.

## Phase 7 — Keyboard and IME

- [ ] Phase 7 complete — the physical IME walkthrough is pending.
- [x] Replace the heavy square grid with one/two refined accessible rows that respect insets; give every action a standalone at-least-48 dp target while preserving the exact default 20-key, 10 × 2 deck.
- [x] Add the complete accessory action set, presets, compact searchable multi-column replacement picker, drag reorder, and restore.
- [x] Add one-shot and double-tap locked modifiers with distinct visuals and correct clearing.
- [x] Add long-press repeat, optional haptics, physical-keyboard independence, and configurable tmux prefix.
- [x] Keep Raw mode as default and add composed Text mode through the swipeable input page and persisted Settings choice without permanent Raw/Text selector labels.
- [x] Send only committed IME text; never send intermediate CJK/dead-key composition.
- [x] Support Unicode/emoji, deletion, clipboard, line breaks, bracketed paste, multiline confirmation, and Back-to-hide behavior.
- [x] Add IME composition, physical-keyboard, modifier, repeat, and paste tests.

## Phase 8 — Scrolling and tmux behavior

- [ ] Phase 8 complete — GNU less, Vim, and htop are proven; copy-mode, resumed-Mosh, Auto-intent,
  and reader/live-bottom walkthroughs remain pending.
- [x] Track normal/alternate screen, remote mouse mode/encoding, viewport, history, and dimensions.
- [x] Add Auto, Local scrollback, and Remote mouse user modes.
- [x] Route ordinary shell gestures to local scrollback and remote-app gestures to bounded wheel reports; never shell history keys.
- [x] Add two-finger local override and long-press local selection.
- [x] Add optional bounded alternate-screen history without corrupting full-screen redraw semantics.
- [x] Add jump-to-latest and native-feeling fling behavior that bounds remote events.
- [x] Page retained app-selected tmux history asynchronously in bounded 4,096-row ranges, preserve
  exact pixel anchoring during prepend/fling, and distinguish true-oldest from local truncation.
- [x] Add copyable tmux guidance without changing remote configuration.
- [x] Cover deterministic plain-shell, remote-mouse, two-finger override, and output-while-scrolled
  routing contracts; real tmux/Codex plus GNU less, Vim, and htop are device-proven.

## Phase 9 — SSH reliability and background service

- [ ] Phase 9 complete — the notification/battery device matrix is pending.
- [x] Preserve JSch transport and strict first-contact/changed-key behavior, expose complete selectable trust fingerprints, and add keyboard-interactive support with clear redacted errors.
- [x] Add staged connection testing: DNS, TCP, negotiation, host key, authentication, and channel.
- [x] Add global/per-host protocol keepalive and bounded reconnect with connectivity awareness and cancel.
- [x] Never imply that a new SSH shell resumed the lost process; explain tmux continuity accurately.
- [x] Move live transport/session ownership from ViewModel/Activity into a process-level foreground session service.
- [x] Declare and start the defensible `specialUse` FGS only from a visible user action; document manifest subtype and Play declaration.
- [x] Add a private low-noise notification channel, privacy-aware content, Open/safe-disconnect actions, and denial behavior.
- [x] Request `POST_NOTIFICATIONS` contextually when background sessions are first enabled/started, explain denial, and add optional unexpected-disconnect/reconnect notifications without repeated nagging.
- [x] Show notification-enabled, background-restricted, battery-optimization, and Data Saver/background-network status where detectable.
- [x] Provide public intents for app notification settings, app details, battery-optimization settings, and app battery/background settings; use manufacturer-specific text only as fallback and no undocumented OEM intents.
- [x] Add optional CPU/screen-awake policies with immediate release and battery explanations.
- [ ] Verify behavior with notifications denied and battery mode Optimised and Restricted; never promise Android cannot kill the process or auto-request unrestricted battery access.
- [x] Verify the Android 16 denied-notification path on the real old phone: foreground service startup succeeds, reports limited visibility, and survives promotion/idle teardown. Optimised/Restricted battery modes remain open.
- [x] Add service, notification, recreation, keepalive, reconnect, and connectivity tests.

## Phase 10 — Backup and restore

- [x] Phase 10 complete — same-install provider and separate clean-install Standard/Full round trips pass.
- [x] Export one versioned product-specific backup through `ACTION_CREATE_DOCUMENT`.
- [x] Import streams through `ACTION_OPEN_DOCUMENT` without assuming filesystem paths or broad storage permission.
- [x] Add Standard and Full encrypted modes with explicit content summaries.
- [x] Derive keys with per-backup salted, benchmarked PBKDF2-HMAC-SHA256 and encrypt with AES-256-GCM.
- [x] Keep export/import passphrases in wipeable mutable buffers, clear them at operation boundaries, and never retain them in public or saved UI state.
- [x] Add magic/version/app metadata/KDF metadata and a forward-compatible structured payload.
- [x] Add authenticated preview, Merge/Replace/Keep both resolution, transactions, rollback, and a temporary recovery snapshot for Replace.
- [x] Exclude sockets, ephemeral Mosh keys, temporary prompts, transcripts, Keystore masters, and logs. Keep imported fonts excluded by default; expose a separate explicit opt-in, validate/install included content-addressed fonts before profile activation, and safely fall back to System monospace when a font was not included.
- [x] Add round-trip, wrong-passphrase, tamper, truncation, schema migration, duplicate conflict, rollback, and stream tests.
- [ ] Verify a backup between two clean installations and a cloud document provider when available.

## Phase 11 — Mosh extension

- [ ] Phase 11 complete — controlled extension absence, private-key bootstrap, multi-session,
  resize, and process-death recovery are proved; observed network transition/roaming and the
  user's external server remain open.
- [x] Record the accepted local/debug architecture/licensing decision and pinned upstream/native dependency inventory; keep public distribution and production signing legally blocked.
- [x] Add a permissively licensed `mosh-api` Android library with versioned AIDL, parcelables, capabilities, errors, and no GPL implementation.
- [x] Add explicit package/component binding, signature-level permission, package signature verification, visibility query, and API negotiation.
- [x] Use `ParcelFileDescriptor` streams for terminal bytes and the one-shot key; reserve callbacks for bounded control/state events.
- [x] Bootstrap Mosh through the existing strict SSH path and pass only numeric endpoint/port/ephemeral key/session display data.
- [x] Clear the ephemeral key promptly and never send saved SSH passwords, private keys, passphrases, or credential IDs to the extension.
- [x] Produce a distinct `mosh-extension` application/APK with a distinct application ID and no native/GPL Mosh implementation in the main APK graph.
- [x] Build pinned official Mosh/native dependencies reproducibly for arm64-v8a and x86_64, with checksums, 16 KiB alignment, stripping, local symbols, GPL notices/source, and build instructions.
- [x] Add honest package/trust/API/server/locale/firewall/native-failure presentation, functional status retry, editable Mosh profiles, and explicit SSH fallback without silent downgrade.
- [x] Add Never/Ask/Automatic SSH-fallback profile settings while keeping every fallback transition explicit and truthful as a fresh SSH shell in session state.
- [x] Add fake-extension discovery, signature rejection, version mismatch, pipe lifecycle, extension death, and rebinding tests.
- [x] Wire Android connectivity changes to the implemented Mosh network-hint control call; core protocol roaming remains present without it.
- [x] Reverify password bootstrap and real UDP terminal input/output against the disposable local Mosh 1.4.0 server on the latest native build, sending every command byte separately and validating the reconstructed VT screen: Wi-Fi 1 plus repeated 5/5, USB 1/1, all green.
- [ ] Manually retest the user's own saved Mosh server while the device is unlocked; controlled local-server evidence does not establish external-server behavior.
- [x] Verify private-key bootstrap, resize, four simultaneous worker sessions, independent close,
  worker cleanup/slot reuse, and broker death/rebind against a controlled `mosh-server` on the
  only test-authorized physical target, the exact model-checked old `SM-S911B`.
- [ ] Observe Wi-Fi/cellular/VPN transition and protocol roaming on the old phone. Link-local IPv6
  zone IDs are unsupported.
- [x] Verify the main APK launches and real SSH works with the extension uninstalled on the guarded disposable AVD.

Historical Phase 11 implementation and device evidence:

| Gate | Result |
|---|---|
| `mosh-api` JVM/API contract | 11 focused version/validation tests passed; debug and release AARs produced without a native Mosh library |
| `mosh-api` PFD/Parcelable device contract | Fresh 5/5 focused tests passed on each of USB `RZCW81JZ9CP` and Wi-Fi `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp`; this does not by itself prove a live Mosh session |
| Main-app Mosh host tests | Strict bootstrap, transport adapter, discovery/negotiation, and status-presentation suites passed in that unit-test output |
| Separate extension host/build | Eight JVM tests passed with no failures/errors/skips; debug and unsigned release APKs, Android-test APK, native-symbol archive, and byte-reproducible Corresponding Source archive `19da994e…4b4d` were produced |
| Mosh extension device contract | Fresh 3/3 connected tests passed on each of the USB and Wi-Fi targets |
| Native artifact inspection | `libmosh_extension.so` is present for `arm64-v8a` and `x86_64` in the extension APK with packaged licences/notices; it is absent from the main APK |
| Real Mosh E2E | The dated old-phone class `4/0/0/0`: 200-row VT reconstruction, four simultaneous sessions with independent resize/close, worker death/slot reuse, and broker death/rebind with fresh traffic. Separate password and private-key bootstrap runs passed. The user's saved server and observed roaming remain manual; no external-server claim is made |
| Frozen debug artifact identity | Main SHA-256 `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408`; extension SHA-256 `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3` |
| Final connected Android instrumentation | USB full app runner reported 252 tests with no failures. The credentialed real-server fixture passed separately with runtime arguments and Mosh absent. The Wi-Fi full app UI attempt remains environment-blocked because secure keyguard/dozing prevents Compose activities from owning a hierarchy |
| Foreground verification | USB `MainActivity` was `topResumedActivity` and focused. Wi-Fi reported `MainActivity` as `ResumedActivity` and the app focused behind `NotificationShade`/keyguard; that is recorded honestly rather than treated as unobscured foreground proof |

## Phase 12 — Final polish and release validation

- [ ] Phase 12 complete.
- [ ] Complete TalkBack semantics, focus order, touch targets, contrast, large font scale, adaptive widths, split screen, foldables, and both navigation modes.
- [x] Add automated 200%-text, 320 × 360 dp compact split-screen coverage for all primary destinations, Connections host actions, and the Settings hierarchy; keep core actions scroll-reachable and at least 48 dp.
- [x] Verify real Samsung IME input, physical landscape, maximum `font_scale=2.0`, and Samsung split screen on the exact old phone; retain physical TalkBack focus order and fold acceptance as explicit remaining observations.
- [x] Keep StrictMode enabled only in debug for thread disk/network and Activity/Closeable/registration leak detection, with no release penalty/logging.
- [x] Add plain-text, ANSI-heavy, rapid-cursor, full-redraw, CJK/wide, tmux-status, 10,000+-line, and several-megabyte benchmark fixtures.
- [ ] Collect cold-start, first-Workspace, tap-to-Connecting, input latency, 5,000-line/s output, 100,000-line history scroll, 60-Hz full-screen redraw, resize, multi-session, memory, thermal, and device frame evidence.
- [x] Add compatible Macrobenchmark and Baseline Profile modules with managed-device default and explicit connected-device opt-in.
- [x] Add a documented local OpenSSH Docker Compose test server with test-only password/key fixtures and host-key mismatch flow; never package credentials in release.
- [ ] Add visual tests for primary screens in light/dark, compact/expanded, large font, and empty/populated/loading/error states.
- [x] Add automated light/dark surface-polarity contracts for Workspace, Connections, Settings, and the empty Terminal; cover compact/expanded primary layouts, 200% text, and Connections loading/error/empty/no-results states. Broader screenshot-golden/manual visual review remains open.
- [x] Review dependency graph, notices, R8 rules, manifest exports, backup exclusions, screenshot/clipboard behavior, and store disclosures.
- [x] Verify release has no test credentials, debug pages, plaintext secrets, transcript/protocol logging, analytics, ads, AI, accounts/teams/subscriptions, cloud sync, hidden requests, fake/inert controls, SFTP/file browser, port-forwarding dashboard, or unrelated feature expansion.
- [x] Update README, architecture, security/backup/Mosh docs, dependency inventory, third-party notices, and CHANGELOG to match reality.
- [x] Run the frozen full source gate: `BUILD SUCCESSFUL` in 3 min 25 s across 385 tasks (57 executed); app JVM `783/0/0/0`, `mosh-api` `11/0/0/0`, and extension `8/0/0/0` tests/failures/errors/skipped; debug/release lint and builds, release APK/AAB packaging verification, both Android-test APKs, `arm64-v8a`/`x86_64` native outputs, and benchmark assembly all green.
- [x] Resolve the fresh `adb devices -l` targets and install both version `0.0.1` frozen debug APKs successfully by exact serial on authorized USB and Wi-Fi phones.
- [ ] Complete unobscured `MainActivity` foreground proof on every target: USB is `topResumedActivity`/focused; Wi-Fi is `ResumedActivity`/app-focused behind `NotificationShade`/keyguard and remains environment-blocked.
- [x] Run the local release-like build/signature/install smoke with the existing Android debug keystore on disposable API 35 AVD `terminal-spike-release-test`; keep it explicitly separate from production-signing evidence.
- [x] Verify the release-like main app launches, preserves a saved host across same-certificate update, and real SSH works before/after with the extension absent.
- [ ] Verify supported behavior on Android 13, 14, 15, and 16 where the available SDK/toolchain/devices or emulators permit, including notification denial, settings restart, and OEM Optimised/Restricted battery modes. Exact process death is now proven separately on a disposable API 35 AVD.
- [x] Run the complete dated-source default instrumentation suite on clean Android 13/API 33, Android 14/API 34, and Android 15/API 35 AVDs and on the authorized Android 16/API 36 old phone: all four targets passed 311/311. Preserve the initial API 35 inset/dialog-root failures and their fixes as portability evidence. Notification denial is additionally proven on the real Android 16 phone; deterministic API 35 process death/background pressure is green, while OEM Optimised/Restricted battery modes remain open.
- [ ] Complete a final manual walkthrough and requirement-by-requirement audit.

Historical final-source performance evidence:

| Gate | Result |
|---|---|
| Connected Baseline/Startup Profile generation | The dated source passed in 2m34s on exact Wi-Fi serial `adb-RZCW81JZ9CP-NpnzVa._adb-tls-connect._tcp` (`SM-S911B`, Android 16/API 36); the preceding stale-navigation-selector run is preserved as red evidence |
| Generated Baseline Profile | `baseline-prof.txt`, 23,892 reported rules, SHA-256 `c690575bc4a58ad77059cb6f8c4eb60b02c2bd00949a0e8d3c2835913d8ede1f` |
| Generated Startup Profile | `startup-prof.txt`, 21,122 reported rules, SHA-256 `fb46fed545512fbed45ee8aadd324e35343e5ee00462bec6e1322a485f2bfa5f` |
| Connected release benchmark suite | The dated source passed in 6m17s; 12 declared tests completed, 0 failures, 2 expected `BaselineProfileGenerator` skips; JSON plus 48 Perfetto traces collected |
| Benchmark journeys | Startup 2/2, primary navigation 1/1, terminal fixtures 6/6, asset contract 1/1; traces and `benchmarkData` JSON collected |
| Remaining evidence | Input-to-render, tap-to-Connecting, refresh/power/thermal, memory, and broader endurance measurements remain open; controlled live Mosh resize and simultaneous sessions are now green |

Frozen artifact final device evidence:

| Target/gate | Result |
|---|---|
| Fresh ADB audit | Authorized USB `RZCW81JZ9CP` (`SM-S911B`) and authorized Wi-Fi `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` (`SM-S936U`) |
| APK identity | Version `0.0.1`; main debug SHA-256 `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408`; Mosh extension debug SHA-256 `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3` |
| Exact-serial installs | App and extension installs returned `Success` on both USB and Wi-Fi targets |
| USB app connected gate | Runner reported 252 tests with no failures; physical-KeyEvent and opaque-provider backup coverage are included, and the runtime-credentialed JSch/OpenSSH fixture passed separately with Mosh absent |
| Wi-Fi app connected gate | Attempted, then stopped because secure keyguard/dozing prevented Compose activities from owning a hierarchy; environment-blocked, not green |
| `mosh-api` connected gate | 5/5 passed on USB and 5/5 passed on Wi-Fi |
| Mosh extension connected gate | 3/3 passed on USB and 3/3 passed on Wi-Fi |
| USB foreground | `com.yanjiyu.terminalspike/.MainActivity` verified as `topResumedActivity` and focused |
| Wi-Fi foreground | `MainActivity` reported as `ResumedActivity` and the app focused behind `NotificationShade`/keyguard; unobscured foreground proof remains environment-blocked |

Local release-like evidence, distinct from production signing and public-release approval:

| Gate | Result |
|---|---|
| Signing scope | Existing Android debug keystore only; not a production signing identity |
| Release-like build | `:app:assembleRelease :app:bundleRelease :mosh-extension:assembleRelease` passed with `BUILD SUCCESSFUL` in 1 min 28 s; 159 tasks |
| App release APK | SHA-256 `29f64e9b6ac233a8546f5ae934962212804d8a62e6a7004657cc47d307990b1b` |
| Mosh extension release APK | SHA-256 `206c74610de6cc808dcccae8e14d32dedc8e99efea2e32a85f60b004174c4c49` |
| App release AAB | SHA-256 `27ae1393b34ed1a42edc031352229ef3106049adbe0ce033c893f7519355f61f` |
| Signature verification | Both APK signatures verified and their certificate SHA-256 matched at `946b2b…a3ee`; AAB `jarsigner -verify` passed with expected self-signed/no-timestamp warnings |
| Disposable AVD smoke | On API 35 AVD `terminal-spike-release-test`, the frozen debug APK completed SSH, same-certificate `install -r`, retained its saved host, and completed SSH again with the Mosh package absent; `MainActivity` was foreground afterward |
| Evidence boundary | Does not prove production signing, store readiness, or public Mosh-extension clearance |

## Definition of done audit

- [ ] Bottom navigation reads Connections, Terminal, and Settings; Tools is not a top-level destination.
- [ ] Tools has been removed as a top-level destination everywhere.
- [ ] Connections is useful but uncluttered.
- [ ] Renderer Lab is unavailable in release.
- [ ] Connections has polished Hosts, Keys, and Snippets tabs.
- [ ] Host rows contain no inline Edit/Use/X debug-style actions.
- [ ] Hosts can be added, edited, tested, connected, searched, and deleted.
- [ ] SSH works at least as reliably as before.
- [ ] Host-key verification is secure.
- [ ] Saved credentials are encrypted.
- [ ] Appearance themes and font selections apply live and persist.
- [ ] Nerd/Powerline symbols render through an appropriate fallback.
- [ ] Custom font import works through SAF.
- [ ] Terminal settings apply and persist.
- [ ] Keyboard accessory rows are editable and polished.
- [ ] Raw and composed Text input modes work.
- [ ] Chinese/composing input sends no partial composition.
- [ ] Multiple terminal sessions work.
- [ ] Terminal rendering remains responsive under heavy output.
- [ ] Ordinary shell scrolling uses local scrollback.
- [ ] Tmux mouse scrolling sends correct remote wheel events.
- [ ] Two-finger local scrolling works while tmux owns the mouse.
- [ ] Touch scrolling never cycles command history.
- [ ] New output does not pull the user away from reviewed history.
- [ ] SSH keepalive and reconnect settings work.
- [ ] A foreground session service owns active sessions.
- [ ] The ongoing notification behaves correctly.
- [ ] Battery/background diagnostics and settings links work.
- [ ] Backup exports one portable file.
- [ ] Google Drive is selectable through the system picker when installed.
- [ ] Standard and Full encrypted backups restore correctly.
- [ ] Bad passphrases and tampered backups fail safely.
- [ ] The main APK works without Mosh installed.
- [x] Mosh is an optional protocol with honest extension status and explicit SSH fallback in the implemented UI/build.
- [x] The Mosh API is a secure, versioned cross-app boundary at source, AAR, and focused-test level.
- [x] The Mosh extension produces a genuinely separate APK with dual-ABI native Mosh code absent from the main APK.
- [x] The versioned IPC model and focused tests ensure SSH credentials are not passed into the Mosh extension.
- [x] Main/extension licence boundaries, pinned sources, notices, and reproducible build/source-bundle instructions are documented.
- [x] Final main-app and extension unit/lint/build/package gates pass together; native extension outputs are synchronized.
- [ ] Final JVM/module/connected Android tests pass on every applicable target.
- [x] Final `lintDebug` and `lintRelease` pass without broad suppression or unjustified exceptions.
- [x] Final release APK/AAB inspection rejects test credentials, private-key payloads,
      debug/benchmark surfaces, forbidden Mosh implementation/native payloads, disallowed runtime
      dependencies, renamed reflection-loaded JSch classes, and notice drift; both packaging
      verifiers pass.
- [ ] Documentation and reported evidence accurately describe the final implementation.
