# Herdr sidebar on narrow screens

## Restore meaningful task status dots (2026-11-07)

The user clarified that green/yellow task status dots must remain. This
supersedes their removal in the compact-prefix change below. Task rows now
start with the existing colored status indicator, then `1 Task name`, with
one space between each part and no decorative separator. The dot occupies
the leftmost content cell; compact headings and left alignment remain. Names
retain six extra cells compared with the original padded grouped list.

The width regression now checks green idle and yellow working indicators,
their exact foreground colors, and the full 20-character task name across
desktop/mobile layouts. All 3,658 nextest tests passed (seven configured
skips), as did formatting, clippy and the optimized build. The isolated real
PTY width/focus/context-menu/Git checks passed with a meaningful leading
status marker and no middle-dot separator after the number. A plain shell
has unknown status (`·`), rather than an invented idle green dot.

Installed Herdr SHA-256:
`df1a62030121ee1b0198b167c5757ece37c95b6e370bbe779958af408536f11e`.
The previous executable is backed up; the server and tasks were retained.
Existing clients need `menu → detach` and reattachment. Evidence and exact
deployment metadata: ignored `build/verification/grouped-status-restore/`.

## Compact task prefixes and left alignment (2026-11-07)

The user's unfolded-screen screenshot showed task names truncated behind wide
indents, status dots and middle-dot separators. Grouped desktop and mobile lists
now use a single task indent and `1 Task name`, without either dot. This gives
the name seven additional terminal cells at the same sidebar width. Workspace
headings, actions and menu labels also move left; Git heading gaps use one space.
Selected-row backgrounds, task numbers, grouping, Git metadata and input targets
remain intact.

Validation: all 25 mobile/grouped tests and all 3,658 Herdr nextest tests passed
(seven configured skips), along with formatting, clippy, six architecture checks
and the optimized build. A width regression proves a 20-character task name fits
the existing 26-column sidebar and that task rows have only the scrollbar gutter,
one indent and their numbered label. An isolated real PTY passed 88 → 120 → 160
→ 44 → 88 columns, compact-prefix assertions, exact task focus, context menus and
cached Git markers. Evidence: ignored `build/verification/grouped-compact-prefix/`.

Installed local Herdr SHA-256:
`4e9279c43aee2fad779c37c071482957c4e89278b80495af85a17cc2421a75b2`.
The previous executable is backed up. The server and tasks were retained;
existing clients need `menu → detach` and reattachment to load the new rendering.
The latest debug APK was non-incrementally installed on model-verified SM-F976B
and MainActivity was confirmed top-resumed; no tests ran on that phone. The
artifact hash and exact target are recorded in `grouped-compact-prefix/install.json`.

## Grouped tasks in the unfolded sidebar (2026-11-07)

The local Herdr fork now supports `[ui].grouped_sidebar = true`. Its expanded
sidebar reuses the mobile Switch list's workspace headings, numbered tasks,
status dots, cached Git markers and context-menu targets. The default Spaces /
Agents split remains available when this setting is false. The local narrow-screen
threshold remains 64: wider grids retain the sidebar and terminal side by side,
without a Switch/Close bar. Collapsed navigation and the width-resize divider
retain their existing routes.

The list shares its projection and visible-row renderer with mobile navigation.
Desktop sidebar scroll offsets remain separate from mobile Switch offsets;
clicks and wheel events are consumed only inside the sidebar's list viewport.
Terminal input, history, transport and protocol are unchanged.

Validation: three new focused tests and all 3,657 Herdr nextest tests passed
(seven configured skips); formatting, clippy, the optimized build, configuration
reference validation and 20 configuration/architecture contract tests passed.
An isolated real PTY passed 88 → 120 → 160 → 44 → 88 column transitions,
including exact task focus, task context menus and cached Git markers. Evidence:
ignored `build/verification/grouped-desktop/`. Existing Herdr clients need
`menu → detach` and reattachment to load the new client code; the running
server, Spaces and tasks can remain running.

All seven manual render profiles passed. At fixed 88×30 geometry, one → 15
populated panes measured 133.621 → 169.919 µs (1.27×) in one Space and
129.594 → 173.656 µs (1.34×) across background Spaces. These measure
cardinality scaling, not a before/after speed improvement.

Installed local Herdr SHA-256:
`1c4a5e49e616ad3d909bd74dc6b12ab6f2947bbc856d95fd33bebdcf5067386c`.
The previous executable and config were backed up; the running server was
retained. The local grouped-sidebar option is enabled. The existing shared
Android gate completed successfully, and the latest debug APK was installed
non-incrementally on model-verified SM-F976B. MainActivity was confirmed
top-resumed after the launch transition; no device tests ran on the foldable.
Deployment metadata: `build/verification/grouped-desktop/install.json`.

## Restore the sidebar on unfolded screens (2026-11-07)

At the user's request, the local `[ui].mobile_width_threshold` was restored
from 160 to the default 64 in `~/.config/herdr/config.toml`. Narrow phone
grids retain Switch; wider unfolded grids show the sidebar and terminal
together. This supersedes the threshold change recorded below and preserves
the grouped mobile navigation and Git headings.

`herdr config check` passed, and `herdr server reload-config` returned
`applied` with no diagnostics. An isolated real PTY using the installed
client passed 44 → 64 → 88 → 120 → 160 → 44 column transitions: Switch
at 44/64, sidebar and terminal at 88/120/160, then Switch again at 44.
Evidence: ignored `build/verification/fold-layout-restore/`. No application
source or Herdr executable changed; no device tests ran on the foldable.

## Unfolded-phone grouping and Git in project headings (2026-11-07)

The local host's existing `[ui].mobile_width_threshold` is now 160 instead of
the default 64. This keeps the grouped Switch layout on folded and unfolded
phone-sized terminal grids; widths above 160 retain desktop navigation. Only
that key was changed in `~/.config/herdr/config.toml`, and server config reload
returned `applied` without diagnostics. This is a local setting, not a new
protocol field or a change to the fork's default for other users.

Grouped project headings now include cached `git_ahead_behind` arrows and the
existing `git_clean`, `git_dirty`, and `git_conflict` token values. Project names
retain their bold hierarchy; Git spans use green/red/yellow and reserve bounded
space on the heading line. Unknown state and zero ahead/behind never manufacture
a clean checkmark. Dirty/conflict values suppress a simultaneously present clean
marker. This adds no Git subprocess, filesystem read, network request or new
status producer to rendering; it reuses the data previously shown by the sidebar.

Validation: 21 focused tests and all 3,654 nextest tests passed (seven configured
skips); fmt, clippy, optimized build and six architecture checks passed. The
populated Git-cache profile at 44×30 measured one→15 panes at
179.096→200.126 µs (1.12×) in one Space and one→15 background Spaces at
162.194→304.241 µs (1.88×). These are cardinality measurements with the new Git
fixture, not a before/after performance claim.

A real isolated PTY client retained the same groups and explicit cached dirty /
clean markers across 44→88→120→160→44 columns. Numbered tabs, context menus and
selecting a task in another Space passed. Unit fixtures also verify ahead/behind,
unknown versus clean, conflict precedence, width bounds and each project/task
appearing once across those widths. Evidence and previews are in ignored
`build/verification/grouped-switch-unfold/`.

Installed Herdr client SHA-256:
`2379288e3cc3782ad20c919f7ae4360d35364489a7c75ef08418a64387abd618`.
The previous executable is retained as
`herdr-0.9.1-before-grouped-git-20261107T005430`. The running server and tasks
were retained. Loading the Git heading code in an already running client needs
Herdr `menu → detach` and reattachment. The unchanged latest debug APK was
non-incrementally installed on model-verified SM-F976B and MainActivity was
confirmed foregrounded; no gestures or tests ran on the foldable.

The terminal UI runs in Herdr on the connected computer, not in the Android APK.
Users of that upgraded Herdr installation share the feature. Independently
hosted installations need the matching Herdr build and configuration/status
provider; users do not need to hand-edit the source. APK-only distribution does
not include these Herdr presentation changes.

## Numbered tabs and a Space/task Switch list (2026-11-07)

The tab strip now shows workspace-relative numbers only, preserving full stored
names for navigation and rename menus. Phone tabs use a six-cell minimum so all
five tabs fit at 44 columns. Overflow, focused-tab reveal, zoom markers, pointer
selection and context menus retain their existing behavior.

The default mobile Switch list now has one hierarchy: each Space is a heading
with its named tasks beneath it. The duplicate aggregate Agents section and the
separate focused-Space Tabs section are removed from this default view. Task
rows keep a status dot and full name (ellipsized to available width), without
repeating machine/project names, `idle`/`working` or agent kind. The one-machine
case omits `Local`; multiple machines retain endpoint-level identification.
Explicitly selected agent views retain their filtered result section.

Single-agent tasks retain exact pane focus and pane context menus. Empty tabs
retain tab actions, and multi-agent tabs retain their individual pane targets.
Workspace headings retain keyboard preview, endpoint identity and context menus.
New Workspace, the focused Space's New Tab, menu actions, linked worktree groups,
stale endpoint treatment, scroll clipping and all four Close corners remain.
No Android, terminal-history, mouse transport, protocol or dependency changes
were made for this layout update. The existing two-row Switch/Close faces are
preserved, including their Android ownership markers.

Validation: 3,653 nextest tests passed (seven configured skips), formatting,
clippy, optimized build and six architecture checks passed. Seven manual render
profiles passed. The fixed 44×30 mobile profile with one versus 15 populated
panes measured 170.571→201.097 µs (+30.526 µs, 1.18×) for one active Space, and
174.407→203.742 µs (+29.335 µs, 1.17×) for background Spaces. These measure
cardinality scaling, not a before/after performance improvement. An unrelated
plugin fixture once read a partially written capture file; its focused recheck
and the final full suite passed. The failed capture is retained in the logs.

A real isolated 44×28 PTY client verified five numbered tabs, grouped task names
appearing once, absence of `Local`, tab selection, right-click tab menus and
selection of a task in another Space. Focus verification uses the actual
client's highlighted tab and unique pane output: public CLI focus is a separate
session-wide projection. Initial fixture experiments hit its first-run welcome
modal; the final isolated fixture explicitly disables onboarding. No interactive
tests or gestures ran on the foldable. Host-rendered preview, real PTY transcript,
logs, measured profiles and deployment metadata are under ignored
`build/verification/grouped-switch/`.

Installed client SHA-256:
`083c84efcd3cc7a49fd643259845a94ac878d51dd3d319fbe58a3fae1dda489a`.
Previous executable: `herdr-0.9.1-before-grouped-switch-20261107T003920`.
The running server and workspaces were retained. Existing clients must use
Herdr's `menu → detach` and reattach to load the new executable; reconnecting a
Mosh transport alone preserves the old client. The unchanged latest Android
debug APK was installed non-incrementally on model-verified SM-F976B and
MainActivity was confirmed foregrounded, without device tests.

## Two-row button face with Switch on the first row (2026-11-07)

The user preferred the larger two-row button over the single-row experiment.
Switch and Close again fill the entire two-row rectangle with the accent color.
Switch's visible label stays centered horizontally on the first row; its lower
ownership marker is concealed against the same accent background. Close shows
its label on the first row and its cross on the second. Four-corner positioning,
two-row hit targets, right-hand information, invisible boundaries, and the
full-width mobile tab strip remain intact. No Android source change is needed.

The existing 25 mobile/graphics tests, fmt, clippy and release build passed.
A real isolated PTY verified the two-row color, first-row Switch label, Close
cross, both right-hand information rows, and opening/closing by clicking either
row. Tabs and hidden Jump status also passed. Evidence: ignored verification
files named `mobile-two-row-top-label-*`.

Installed Herdr SHA-256:
`76b03bd8ebf5ddc5ddb0295f26d790647a60068bd4ecff180eab39efb48fc6e8`.
Previous binary: `herdr-0.9.1-before-two-row-top-label-20261107T002348Z`.
The server and running workspaces were retained. The unchanged verified debug
APK `16b5cc4255229aaf90683c2c955beb4fd8ecfbe7dbc12cb02b8f1e603c77220d`
was installed non-incrementally on model-verified SM-F976B without tests.
The initial immediate foreground check required a relaunch; MainActivity was
then confirmed top-resumed. Existing Herdr clients still need to detach and
reattach to load the new executable; Mosh resumption alone retains the old one.

## Single-row button face beside two-row information (2026-11-06)

Switch and Close now color only their first row and align their visible label
with the workspace information. The second row beneath each button uses the
normal panel background and looks blank. The original two-row hit rectangle,
four-corner preference, and two rows of information on the right remain intact.

The second-row VT ownership label and boundary are retained with SGR conceal,
matching foreground/background and no bold. This keeps existing Android
clients' history and context-menu recognition compatible without showing
duplicate labels or the old Close cross, including themes with default colors.
No Android source or APK change is needed for this rendering update.

The final 25 mobile/graphics tests, fmt, clippy and release build passed.
An isolated real PTY verified the first-row visible label, one-row color,
blank lower row, both right-hand information rows, and opening/closing through
both the visible and lower hit rows. Tabs and hidden Jump status also passed.
Evidence: ignored `build/verification/mobile-single-row-*` logs and visible
frame captures. Installed Herdr SHA-256:
`14396af8a8045fa8960008dbeac213698074ddb18e0e600c48d28e7d6ea080a7`.
The previous binary is saved as `herdr-0.9.1-before-single-row-20261106T235247Z`.
The server was not restarted. Model-verified SM-F976B was foregrounded without
tests; its app and concurrent Android work were retained. Existing clients
need to exit and reattach to see the updated rendering.

## Cleaner mobile navigation and full-width tabs (2026-11-06)

Mobile tab bars no longer reserve or render the right-side status segments
(including the Jump shortcut hint and hostname). Their whole row is available
for tabs, overflow controls and New Tab. Desktop status and the actual jump
shortcut remain available. A regression requires four short tabs and New Tab
to fit at 44 columns with the status hidden, while desktop status still renders.

Switch and Close labels are centered within their full button width. Their
structural VT delimiter remains for Android's chrome/history recognition, but
uses identical foreground/background and explicitly removes bold. This avoids
ANSI indexed foreground brightening making the separator visible again.

Herdr nextest passed 3,650 tests with six configured skips. After the final
indexed-color correction, 35 focused tests, clippy, formatting and release build
passed. An isolated real 44-column PTY verified four tabs plus New Tab, hidden
Jump/hostname status, invisible non-bold Switch/Close boundaries, and opening
and closing at the same position. Evidence: ignored verification logs and
frames named `mobile-clean-chrome-*`.

Installed Herdr SHA-256:
`0e74c27340dbbb12ab496e86151a5c927203a5b21ca8e0674925653951890bcc`.
Previous binary: `herdr-0.9.1-before-clean-chrome-20261106T232853Z` beside the
installed binary. The server was not restarted. Android source was unchanged
by this task. A separate Android build was still being verified, so its new
APK was not installed as part of this change; the existing app on the verified
SM-F976B was brought to the foreground without running tests. Existing Herdr
clients need to exit and reattach to use the updated client rendering.

## Distinct mobile navigation button colors (2026-11-06)

Switch and Close share a solid theme-accent background with bold contrasting
text; the waiting-for-attention badge retains its red foreground on a separate
panel-colored cell. Geometry and corner preferences are unchanged. The shared
Herdr source contains the styling; an isolated source snapshot at
`/home/jiyu/git/herdr-mobile-colors` excluded concurrent tab and integration work
for verification. Its 23 mobile/graphics regression tests, clippy, formatting
and release build passed (`build/verification/mobile-colors-*`).

The concurrent installed Herdr was preserved, SHA-256
`5b65593e2f33be3495d297f76ccede258992e7b7d539a4a5d4555150e30be832`.
An actual isolated PTY confirmed its bottom Close button uses accent RGB
137/180/250, contrasting text RGB 24/24/37 and bold styling under the default
theme; the temporary server was stopped. See `mobile-colors-installed.json`
and `mobile-colors-installed.ansi` in ignored verification output. Android source
was not changed for this styling task. The latest verified APK's concurrent
foldable install was reused and MainActivity was foregrounded again; no phone
tests ran. Existing Herdr clients need to exit and reattach for new rendering.

## Workspace tabs at the top on phones (2026-11-06)

The local Herdr 0.9.1 client fork at
`/home/jiyu/git/herdr-mobile-menu-0.9.1` now retains the focused workspace's
existing tab bar on terminal row zero in mobile layouts. It reuses desktop
labels, selected styling, click/context-menu actions, new-tab and overflow
controls, and focused-tab reveal. The desktop tab-position preference remains
independent; mobile tabs stay at the top. Terminals taller than three rows
reserve one extra row, leaving the two-row Switch bar in its chosen corner.
The expanded Switch list excludes the tab row, and Close stays in Switch's
opening rectangle in all four corner settings.

Android recognizes both the legacy mobile chrome and the extra top tab row.
History occupies only the pane, and long presses reach the existing remote
tab menu. Switch-list targets exclude the tab row, Close and the divider.
No new dependency, session protocol, network service or Android tab overlay
is introduced.

Validation: the isolated reviewed source passed 3,648 Herdr nextest tests
with six configured skips, formatting, clippy and the release build. The
shared Android gate passed 141 tooling tests, 1,230 JVM tests, debug/release
lint and all required builds. A real isolated 44-column × 28-row PTY checked
top-tab rendering, exact tab focus, right-click menus and the bottom Switch
bar against both the reviewed candidate and the currently installed client.

Concurrent work updated the installed Herdr during verification. That install
was preserved and its top-tab behavior verified from an exact binary copy;
this session did not restart the server or overwrite the other deployment.
Installed SHA-256 is
`5b65593e2f33be3495d297f76ccede258992e7b7d539a4a5d4555150e30be832`;
the independent reviewed candidate is
`737db875e51f781ac876a5493c5c5f9c07df51dd5f7930e683d0f233c97e0cd5`.
Evidence and the isolated source are in ignored `build/verification/mobile-tabs-*`.
Existing Herdr clients must disconnect and reattach to load the new chrome.

Model-verified SM-F976B received debug APK
`8fc1bc592854e887c2b86575125d89505431437fc649d4525e47091e99b8c30f`
through a non-incremental update, and MainActivity was confirmed foregrounded.
No device tests ran on the foldable. No authorized ordinary phone was connected,
so physical phone interaction acceptance remains unverified. Deployment metadata:
`build/verification/mobile-tabs-{herdr,fold}-install.json`.


## Close follows the Switch corner (2026-11-06)

The expanded mobile switcher now puts Close in the same rectangle as Switch
in all four corner settings. Its title bar, divider, list viewport and notices
follow that position. Android recognizes the switcher body at either edge,
including a New Workspace action on row zero, while excluding Close and the
divider from context-menu and project-picker hit testing. It also accepts the
older top-right Close/× bar.

Regression evidence: all four rendered Close targets equal their opening
Switch targets and clicking each returns to the terminal; the list does not
overlap Close. Android tests cover all four switcher bars, project actions,
control exclusions and suppression of native history over the switcher.
Herdr nextest passed 3,647 tests with six configured skips; fmt, clippy and
release build passed. The shared Android gate passed 1,227 JVM tests, lint,
and debug/release builds. Logs: ignored `build/verification/close-corners-*`.

Installed local Herdr SHA-256: `92f5f5c5afb28c887076b9f4702fde07ded6fa23f02c90bf493b9f8a471a7a86`. Previous binary:
`/home/jiyu/.local/lib/herdr-mobile-menu/herdr-0.9.1-before-close-corners-20261106T225837Z`. The server and running Spaces were retained; existing
clients need to exit and reattach. Model-verified SM-F976B received APK
`82c2bafb4960986ebebe9fa751d66c3d64bbdb60064d66078b9863e563505075` via non-incremental update, and MainActivity was confirmed
foregrounded. No tests ran on the foldable; no authorized old phone was
connected for real gesture acceptance. Deployment metadata:
`build/verification/herdr-close-corners-install.json` and
`build/verification/close-corners-fold-install.json`.

## Mobile Switch corners and recent projects (2026-11-06)

The local Herdr 0.9.1 client fork in
`/home/jiyu/git/herdr-mobile-menu-0.9.1` defaults its narrow-screen navigation
bar to the bottom with Switch on the left. Herdr **Settings → switch** offers
bottom left, bottom right, top left, and top right. The choice uses the existing
client chrome preferences file for the local endpoint, survives reattachment,
and does not change the server protocol or desktop navigation layout. Bottom
choices reserve the final two rows; top choices reserve the first two rows.
The expanded switcher uses that same corner and button rectangle for Close,
so opening and closing do not require reaching across the screen. Its title
bar and divider move together; list rows and notices exclude those controls.
Notification and clipboard feedback stay inside the pane area.

Android recognizes both top and bottom navigation bars when projecting native
Herdr history. Long-pressing `+ new workspace` in Herdr's mobile Switch list
opens the same recent-project picker as the desktop New footer. Recognition
requires the visible Switch/close bar and divider at either edge, excludes the left
scrollbar, and consumes the hold without sending a remote click. Existing
Space/Tab menus and pane text selection retain their own routes.

Regression coverage includes the four rendered corners and hit targets,
persisted settings, narrow-screen access to all four choices, history placement,
and the mobile picker route with and without the switcher's scrollbar. Device
interaction acceptance remains separate from host tests; the foldable is an
install/launch target only.

Verification: 141 tooling tests and the exact instrumentation inventory passed;
1,226 Android JVM tests passed without failures or skips; the shared Android
unit/lint/debug/release build gate passed. Herdr nextest passed 3,647 tests with
six configured skips, followed by 62 focused mobile/graphics/lifecycle tests
for the final desktop-offset correction; formatting, clippy and the release
build passed. Evidence is in ignored `build/verification/*corners*` and
`build/verification/switch-corner-*` logs.

The local executable was replaced atomically without restarting the server;
SHA-256 is `b381bd2718d74c23c2752c84e683328d4514cd0b5708c3d77c6635ccf3c2ad81`.
Its previous binary is preserved beside it as
`herdr-0.9.1-before-mobile-corners-20261106T182851Z`. Existing clients need to
exit and reattach to use the new client rendering; Spaces and tasks can stay
running. Model-verified SM-F976B received the debug APK via a non-incremental
update and MainActivity was confirmed foregrounded. APK SHA-256 is
`4cc38cf19d885adf0ca952160b5de84b0921f69924cb6bfbce45be4babe65c3f`.
No authorized old phone was connected, so real gesture acceptance remains
unverified; no tests ran on the foldable. Deployment metadata is in ignored
`build/verification/{herdr-mobile-corners-install,mobile-corners-fold-install}.json`.

## Switch list retained behind mobile context menus (2026-10-08)

The user's phone screenshots showed that a long press opened the correct menu,
but the underlying Switch list disappeared and the live terminal returned.
The local Herdr fork changed from Navigate to ContextMenu while rendering the
mobile panel only in Navigate. Its mobile ContextMenu branch now draws the
existing panel before the menu, preserving the current scroll offset and list.
Android source and gesture routing are unchanged by this fix.

`mobile_context_menu_keeps_switcher_list_visible` failed before the rendering
fix and passed afterward. Pinned Rust 1.96.1 nextest passed 3,485 tests with
one existing manual profile skip; clippy and the release build passed. A real
isolated 44-column PTY verified list retention behind the menu, closing the
exact unfocused tab, surviving tabs, and shell PID preservation during handoff.
The first live assertion used a label covered by the menu; the retained evidence
shows the list rendered correctly, and the corrected assertion checks exposed
list rows. Logs: ignored `build/herdr-switcher-{red,green,lint,build,live}.log`.

The installed local Herdr SHA-256 is
`960b6714a4e865c23069b8dc4849a37a3f172ee1f46d5748e6158760b3e79eab`.
Default-session live handoff retained all recorded pane IDs and shell PIDs;
see `build/herdr-switcher-deployment.json`. Existing clients need reattachment.
The previous executable is retained at
`/home/jiyu/.local/lib/herdr-mobile-menu/herdr-0.8.2-before-switcher-backdrop`.
No Android APK changed or phone tests ran in this session; phone workflow
confirmation remains pending. No upstream publication was performed.

## Remote mobile context menus (2026-10-07)

The user rejected the native tab-panel prototype because it looked poor, loaded
slowly and showed no useful content in the actual connection. That prototype,
its catalog/mutation side-channel calls and its Switch interception were removed.
The earlier prototype checks did not establish real saved-connection UX acceptance.

Switch now opens Herdr's own original list. The app sends a secondary mouse click
on a hold only when a verified mobile layout and the live header's `×`/`close`
affordance identify that list. The live `switch` header, unknown overlays and
pane text retain local selection. Direct and buffered input share this behavior;
failed layout reads retain the verified geometry. No tab-list query or Android
dialog runs on this gesture.

The local Herdr 0.8.2 fork at `/home/jiyu/git/herdr-mobile-menu` handles the formerly
swallowed right press in its mobile Navigate mode. It uses its existing scrolled,
grouped switcher hit testing to open existing Space, Tab and agent-pane context
menus. Existing close/rename handlers and worktree confirmation safeguards remain
in use. Render loops and session persistence have no new state or work. Its two
new regression methods fail against the old right-click swallowing behavior;
they cover scrolled exact targets and closing an unfocused tab without closing
the currently active or remaining tabs. App unit/native tests guard overlay
routing, both input modes, stale metadata, desktop menus and pane selection.

Android shared gate passed:132 tooling tests, exact instrumentation inventory,
1,155 JVM tests (app/API/core1136/11/8) with no failures/errors/skips, debug/release
lint and builds, instrumentation compilation (450 tasks139s). Model-verified USB
SM-S911B `RZCW81JZ9CP` passed all24 menu/mobile/selection/buffered/paste methods
in84.308s. Installed USB APK matches SHA-256 `f49de77925e0db8b168a2741abb53195a2bd03c2c48df9f8d7f593ad921a075c`.
Herdr pinned Rust1.96.1 fmt/clippy/release checks passed; nextest passed3484 tests
in91.438s with one pre-existing manual render-profile skip. The plain cargo-test
shared-process workspace-ID counter failure and newer Rust1.99 clippy warnings
are retained in ignored logs; using the repository's pinned toolchain and its
intended process-isolated runner passed without weakening source or assertions.

The actual isolated44-column TUI test verified stock→candidate live handoff,
same shell PID, reattachment, Switch rendering, menu opening and exact unfocused
Tab close with both survivors. Local default-session deployment retained all15
pane IDs and15 shell PIDs. The installed local executable is
`/home/jiyu/.local/lib/herdr-mobile-menu/herdr-0.8.2`, selected by a local-bin
symlink; `/usr/bin/herdr` and Herdr config remain unchanged. Candidate SHA-256
`2a861db67acfd8a0420e6f6f90c4eb519b1facc8bf3e51c268e855b086120e66`.
Live handoff disconnects clients once; reconnect/reattach the existing session.
Source, regression tests and rollback instructions are retained in the local
fork's `LOCAL-MOBILE-MENU.md`; preserve this patch during Herdr self-updates.

Fold rollout completed 2026-10-07T16:57:30.231593+00:00: model-verified SM-F976B `adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`
received the latest debug APK through full non-incremental install. Installed SHA-256
`54231272159618f7bcfd5a433f5d17ba5e2884f9b743b19885dfd70f9a7469f3` matches the latest artifact exactly. Cold launch returned Status ok;
a later launch brought MainActivity to front and independent top-resumed verification
passed. The initial delayed foreground check found ChatGPT in front; this was corrected
by relaunching, without gestures or tests. Previous native-panel APK `c5692e62…e158`
is replaced. Passing shared verification for this artifact was reused (450 tasks110s).
No tests ran on the fold. Evidence: ignored `build/fold-latest-20261007/`.
User real-workflow confirmation remains pending.
Current evidence: ignored `build/herdr-mobile-close-investigation/remote-*`,
`herdr-nextest.log`, `herdr-pinned-clippy.log`, `herdr-red-menu-test.log`,
`live-mobile-menu-smoke.log` and `local-herdr-deployment.json`. Real phone SSH/Mosh
workflow acceptance remains distinct from these controlled/native/host-TUI gates.

## Mobile round trips and cross-client height (2026-10-07)

The user reported that both Space and Tab menus stopped working in every input
mode on the foldable. Read-only inspection found a live 88-column, 32-row grid
with a cached 41-column mobile layout and no native history overlay. The remote
`pane layout` API also returned desktop geometry at different heights as clients
redrew. The existing acceptance tests never published a successful mobile layout
between desktop resizes, and required the API height to match the phone exactly.

The controller now retains the most recent verified desktop chrome separately
from the current mobile layout. Returning to that verified width or wider restores
the sidebar and top/bottom tab anchors before another SSH read; narrower unknown
widths stay guarded, and detach clears both caches. Same-width top-tab metadata
uses this phone's height because row zero is its fixed anchor. Unknown widths and
ambiguous bottom/hidden-tab height mismatches are still rejected. Buffered input
keeps keyboard ownership while allowing the same navigation right-click as direct
input. Pane-output long presses remain local selection.

Regression coverage includes three desktop/mobile/desktop round trips with
failed refreshes, top/bottom/hidden tab layouts, detach isolation, another
client's top-tab height, and direct/buffered input. The new native test checks
exactly one secondary press/release per hold and no local selection, including
the first hold after unfolding. It is registered in the exact CI inventory.

Validation: both new regression layers failed before repair (unit sidebar
restoration assertion; native metadata-read timeout). Shared verification passed:
132 tooling tests, exact Android inventory, 1,154 JVM tests without failures or
skips, debug/release lint, debug/release builds and instrumentation compilation
(450 Gradle tasks). On model-verified USB SM-S911B `RZCW81JZ9CP`, all five focused
menu/selection/mobile-switcher tests passed in 69.884 seconds; all 18 buffered
input/paste tests passed in 11.009 seconds. The new native method checked twelve
holds through three mobile round trips and direct/buffered/direct input changes.
APK SHA-256 `9d75153cb351a948ba41822e18b5df9f2dfbef7f4b90781b10e48976f50acae1` matches the installed old-phone and foldable packages.
Full non-incremental install on model-verified SM-F976B
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp` succeeded, launch returned
`Status: ok`, and MainActivity was top-resumed. No foldable tests or injected
gestures ran. Logs and scalar diagnostics are under ignored
`build/herdr-menu-20261007/`. Hosted CI was not run. Controlled native transport
checks prove mouse dispatch; the real remote Herdr menu remains for user
confirmation in the original session.

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

## Double-tap Space close in grouped navigation

Double-tap a workspace heading in the grouped desktop sidebar or mobile Switch
list to request its existing Herdr Close menu action. Recognition uses the
verified navigation area and the renderer's bold workspace heading style,
excluding the scrollbar gutter. Underlined section titles, task rows, usage
rows, New actions and pane output are excluded. Worktree headings use the same
route; Herdr retains ownership of close/group-close semantics and confirmation.
The legacy Spaces sidebar and separate tab-strip close behavior are preserved.

Only workspace-heading taps wait for the system's double-tap decision. A
confirmed single tap opens the Space once; a double tap sends only its Close
menu action, without first opening the Space and removing the list. Other
terminal/task taps keep their immediate dispatch. Pending workspace taps are
discarded when the controller changes or the view detaches.

`HerdrTabTargetTest` covers both grouped surfaces, scrollbars, chrome placement,
worktree labels and the exact workspace-menu input.
`HerdrContextMenuRecoveryTest.groupedAndMobileSpaceDoubleTapClosesOnlyTheClickedWorkspaceInBothInputModes`
dispatches actual double-tap events in direct and buffered modes. Its simulated
server removes the list immediately on an opening click, reproducing the real
transition. It requires no opening click before Close, one opening click after
a confirmed single tap, immediate task taps, and no pending click after detach.
