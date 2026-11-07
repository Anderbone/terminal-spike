# Herdr recent project picker

Long-press **+ new workspace** in the grouped desktop sidebar or mobile Switch
list, or **new** in the legacy desktop Spaces footer, to open the Android project
picker. The grouped sidebar requires its collapse control and tab strip; only
the action's text cells inside the sidebar open the picker, including when the
action is on the first row. A normal tap keeps Herdr's existing New action.
Existing Space and tab menus, the footer menu button, and terminal text selection
keep their actions.

The picker reads `zoxide query --list` on the connected host over the existing
authenticated SSH side channel (also for Mosh). It keeps zoxide's ranking, shows
up to 256 distinct absolute directories, and supports case-insensitive path
search. This is directory history, not a chronological Git repository list.
Both the initial and foreground directories of every pane in the selected Herdr
session are excluded. Failed snapshot reads fail closed; they do not turn all
projects into apparently unopened candidates.

Selecting a directory refreshes the open-directory snapshot, checks that the
remote directory still exists, and invokes the selected session's
`workspace create --cwd <directory> --focus`. Paths and session arguments are
shell quoted. The app sends no shell keystrokes into the active pane. Another
client opening the same directory after the final snapshot remains a possible
race; Herdr's create API has no atomic create-if-directory-is-unopened option.

The host needs zoxide and a working SSH side channel. Failure and empty-history
states are shown separately. Dismiss and long-press again to refresh. The picker
is scoped to the originating connection and is cleared on terminal detach or
replacement. It adds no dependencies or persistent project database.

Host regression coverage: `HerdrRecentProjectsTest` covers ranking, all-pane
filtering, failed reads, bounded results, selected-session commands, shell
quoting, stale selections and create failures. `HerdrTabTargetTest` covers New
hit testing and opening/dismissing the picker without terminal input.
