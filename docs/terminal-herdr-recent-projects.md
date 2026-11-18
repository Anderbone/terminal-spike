# Herdr recent project picker

Tap **+ new workspace** or its compact **+** form in the grouped desktop sidebar or mobile Switch
list, or **new** in the legacy desktop Spaces footer, to open the Android project
picker. The grouped sidebar requires its collapse control and tab strip; only
the action's text cells inside the sidebar open the picker, including when the
action is on the first row. Recognition follows the standalone action within
verified navigation chrome, without fixed row or indentation coordinates. The
tab strip's separate New Tab **+** is excluded. Holding that workspace action sends Herdr's original New click once; it does not open the picker.
The left scrollbar's `│` track and `▌` thumb are excluded from the action label;
both compact and full labels work while either scrollbar segment is visible.
Existing Space and tab menus, the footer menu button, and terminal text selection
keep their actions.

The picker reads `zoxide query --list` on the connected host over the existing
authenticated SSH side channel (also for Mosh). It shows up to 256 distinct
absolute directories, ordered by last use, newest first, rather than zoxide's
frequency/recency score. Case-insensitive search matches the full path and
preserves this order. Both the initial and foreground directories of every pane
in the selected Herdr session are excluded. Failed snapshot reads fail closed;
they do not turn all projects into apparently unopened candidates.

A read-only adapter obtains actual `last_accessed` timestamps from zoxide's
[version 3 database format](https://github.com/ajeetdsouza/zoxide/tree/v0.10.0/src/db),
using `_ZO_DATA_DIR`, the standard macOS data directory, or Linux's
`XDG_DATA_HOME` / `~/.local/share`. The CLI remains authoritative for candidate
paths. Metadata is bounded to 128 KiB; missing, oversized, corrupt, unsupported,
or incomplete metadata produces **Time unknown**, never an invented date.
Known times sort ahead of unknown times; equal and unknown times use path order
for deterministic results. Sorting happens before the 256-result limit.

The compact native list separates project names, muted paths, and right-aligned
last-use times. Recent dates use localized relative time; records older than a
week use a local calendar date. Accessibility labels include the exact date and
time. The list scrolls independently of the search field and Cancel action,
including with enlarged system text. No-match results are distinct from an
empty history, and search can be cleared in one tap.

Selecting a directory refreshes the open-directory snapshot, checks that the
remote directory still exists, and invokes the selected session's
`workspace create --cwd <directory> --focus`. Only a confirmed successful
creation runs `zoxide add -- <directory>` to record the visit in the existing
host history. A recording failure does not retry or undo workspace creation.
Paths and session arguments are shell quoted. The app sends no shell keystrokes into the active pane. Another
client opening the same directory after the final snapshot remains a possible
race; Herdr's create API has no atomic create-if-directory-is-unopened option.

The host needs zoxide and a working SSH side channel. Failure and empty-history
states are shown separately. Once the selected Herdr connection is ready, the
app prefetches the list off the UI thread and refreshes it at most once every
30 seconds through the existing metadata poll. Opening the picker immediately
shows the last successful list and requests a background refresh; concurrent
requests share one read. Only a first read without cached rows shows Loading.
Refresh failures retain cached rows, including a successfully empty list. A
confirmed workspace creation removes its directory immediately and refreshes
the list; selection still revalidates against the live remote snapshot.
The cache is scoped to the originating connection and is cleared on terminal
detach or replacement; late reads cannot repopulate it. Nothing is saved across
app restarts. It adds no dependencies or persistent project database.

Mosh can keep running after its original SSH connection drops. Before loading
projects or opening a selection, the app probes that SSH connection with a
read-only no-op. If it has expired and saved credentials can be reloaded, the
operation uses a fresh authenticated SSH connection, which closes afterwards.
This does not restart Mosh, replay startup commands, or recreate port forwards.
New or changed host keys and interactive authentication challenges still require
an explicit terminal reconnect. Workspace creation is never retried after a lost response.

The shared `MoshSshSideChannel` owns these temporary connections and attachment connections.
Cleanup removes the pending authentication lease from tracking even if close fails, attempts both
distinct handles, and retains the original operation/authentication error with cleanup errors
suppressed. It never retries the project operation to recover from a cleanup failure.

Host regression coverage: `HerdrRecentProjectsTest` covers all-pane
filtering, failed reads, bounded results, selected-session commands, shell
quoting, stale selections and create failures. `HerdrTabTargetTest` covers New
hit testing and opening/dismissing the picker without terminal input.
`MoshProjectSessionTest` covers expired SSH recovery for loading and selection,
connection cleanup, non-reloadable credentials, cancellation, failed snapshots,
and avoiding duplicate workspace creation after losing a response.
`HerdrContextMenuRecoveryTest.recentProjectsTapAndHoldSwapMobileNewWorkspaceInBothInputModes`
dispatches real tap and hold events against the mobile layout with usage rows,
top tabs, the `▌` thumb and concealed bottom-button dividers. In direct and buffered
modes, tapping must open the picker without remote input; holding must send one
primary click/release and leave the picker closed.

`MoshSshSideChannelTest` injects close failures into the production helper and verifies primary
error preservation, both-handle cleanup, aliasing, cancellation during authentication, and no
fresh authentication after a directly interrupted upload. These are controlled JVM transport
fixtures, separate from real SSH/Mosh acceptance.

`ZoxideRecentProjectsTest` covers access-time ordering independent of score,
unknown and equal times, sorting before truncation, corrupt/unsupported database
formats, read-only extraction from a custom data directory, and recording visits
only after successful creation. `HerdrProjectTimeTest` covers localized relative
and calendar dates. `HerdrRecentProjectsPickerTest` covers aligned dates,
selection state during background refresh, search/clear/no matches, and Chinese
at 200% font size. `HerdrRecentProjectsCacheTest` covers prefetch, shared in-flight
reads, throttling and retry, retained rows on failure, successful empty results,
immediate removal after creation, late reads, detach, and connection isolation.
