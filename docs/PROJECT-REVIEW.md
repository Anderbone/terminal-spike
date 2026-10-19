# Remote project review

Connect an SSH or Mosh terminal, hold its session tab and choose **Open project**.
For an app-selected Herdr session, **Open project** reads an atomic Herdr API
snapshot and opens the focused pane's `foreground_cwd` (falling back to `cwd`). Git
resolves that directory to its actual worktree root. No directory is inferred from
the terminal title or the SSH exec channel's home directory.

Use **+** in the project tab strip to choose other pane/workspace directories or
recent projects on that host. Manual paths remain available when metadata is
unavailable. A single remembered project is the non-Herdr fallback. The last 20
opened roots are remembered per host/port/user. Feedback is additionally scoped
to the canonical worktree path.

Multiple project tabs can stay open on one host or different hosts. Each keeps its
own document, reading position, file-tree expansion, search and review drafts.
Opening a directory within an already-open worktree selects its existing tab;
matching basenames on different paths are disambiguated in tab labels. Switching
or closing tabs cancels pending reads without publishing into another project.
Open tabs survive activity recreation within the running process; recent paths
and review drafts also survive process restarts.

- **Changes** lists staged, unstaged and untracked files from Git status. Source
  annotations compare the complete current working file with the captured HEAD,
  not the index; a staged change subsequently reversed in the working file can
  therefore remain in the status list with no net source annotations.
- **Changes** and **Files** both use collapsible folder trees. Single-directory
  chains are compressed, and filenames do not repeat their parent path on a second
  line. Changed-file folders start expanded; the full file tree starts collapsed.
  Search expands matching paths across the repository. Git-ignored build output is
  omitted. File rows are at least 40 dp; source rows are approximately 18 dp at default font
  scaling, with the existing 13 sp code font retained.
- Open a file to see the complete text, lightweight lexical syntax colors and
  additions/deletions. Deleted base lines are inserted at their original change
  location with old line numbers; other numbers refer to the current file.
  Renamed files retain their old path in the list and show the new path's changes
  relative to HEAD without rename detection.
- The viewer initially jumps to the first change. **Previous / Next** navigate
  changes; **Full file / Diff** changes the display, and **Wrap** controls long
  lines. Extremely long lines stay wrapped to fit Android layout limits. At 700 dp and wider the file list stays beside the viewer. Returning to
  the list preserves its folder and scroll position.
- Tap/hold a line number to select a line; tap another number to extend the range.
  Current and deleted lines must be commented on separately. **Save draft** keeps
  the message, selected source, nearby context and reviewed revision on the phone.
  **Feedback** gathers comments across files, supports editing/removal and previews
  the combined instruction.
- **Copy** works with any agent workflow. **Paste to terminal** targets only the
  originating live terminal's current pane, requires detected agent foreground
  and bracketed paste, and does not send Enter. Check the target agent pane and
  submit from its input box. A rejected paste retains the draft. Comments also
  remain after a successful paste; remove them when that review is finished.

## Freshness and limits

There is no background polling. **Refresh** explicitly reloads repository state;
reopening a file reads it again. An agent cannot move the visible reading position
by continuing to write. Two content reads around Git diff reject a file changing
while it loads. Comments retain original quoted code; opening a changed version
marks the old comments as outdated rather than attaching them to unrelated lines.
Version-based reanchoring and delivery/acknowledgment to individual agent panes are
not implemented in this first iteration.

Herdr discovery only reads `api snapshot` through the selected session, with a
1 MiB response bound and at most 64 distinct reported directories. Workspace labels
are display text; canonical Git roots determine project identity. Discovery never
focuses a pane or sends agent input.

The reader supports UTF-8 text up to 4 MiB, 30,000 displayed lines (including deleted
lines), and 16,000 characters per current line. Repositories are limited to 25,000
listed files, with bounded exec output and 20-second read deadlines. Binary files,
submodule directories and symbolic links are not code previews. File-mode changes are shown separately.
An individual comment has a 4,000-character message and up to 16,000 selected
characters; a review has up to 100 comments and a 120,000-character storage budget.

## Transport and licensing boundary

Reads use separate exec channels on the existing authenticated SSH connection,
including a live Mosh connection's SSH companion. No command is entered into the
interactive terminal. A saved Mosh connection without a usable SSH companion must
be reconnected. Local Arch terminals do not expose this remote-project interface.

The host needs its own Git, POSIX shell, `cat`, `test` and `realpath -e --`. The app
neither installs, bundles nor links these host tools. Git queries disable optional
index locks, fsmonitor, external diff and text conversion; repository filenames
use literal pathspecs and shell quoting. Source reads verify the canonical file
path remains within the worktree. This feature adds no Android library dependency,
cloud service, account, source upload or third-party application source.

Code is read into memory, not downloaded to a public phone directory. Review drafts
are stored in app-private preferences, excluded by the app's existing Android
cloud-backup/device-transfer rules, and removed by Android Clear storage. Copy and
paste are explicit user actions.

## Verification

`ProjectReaderTest` exercises an actual disposable Git repository (unborn HEAD,
staged/unstaged changes, untracked names containing shell metacharacters, deleted
files, binary files, escaping symlinks and unchanged index bytes). Parser tests
cover rename status and deletion coordinates. `ProjectControllerTest` covers
worktree/endpoint draft isolation, original-source preservation, cancellation,
file hierarchy, multiple tabs, canonical-root deduplication and terminal-control
filtering. `HerdrProjectLocationsTest` covers foreground-directory precedence,
focused-pane ordering, explicit session quoting and malformed/bounded responses. The terminal workflow test verifies
review paste is bracketed, gated to an agent, and never submits.

`ProjectScreenTest` is a focused Compose UI fixture with no network or terminal
workload. It covers initial change positioning, complete adjacent context,
deleted code, feedback persistence/outdated state, rejected paste, and folder
navigation, compact row spacing, automatic Herdr discovery, and reading-position
restoration across project tabs. It is in the full instrumentation inventory. It does not establish
acceptance of a real Herdr agent workflow or authorize tests on the foldable.
