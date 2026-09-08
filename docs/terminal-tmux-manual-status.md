# Shell-started tmux and terminal task indicators

The terminal uses the same local history, pixel viewport, and Android fling for a tmux client
started from an ordinary SSH/Mosh shell as for one selected in the application popup.

## Identity and history ownership

An off-thread probe matches tmux client processes to the exact authenticated transport's
`SSH_CONNECTION`. It compares the environment on the remote host; no environment values are
returned to Android, displayed, or logged. Only a unique matching client is accepted. Identity
includes its TTY, session, pane, and tmux server PID. The existing authenticated SSH side channel
also provides this association for a Mosh-launched client. Capture targets the verified pane,
not the newest session or another client's current window. Client detach/pane changes invalidate
staged history. Ambiguous or unavailable probes never select an arbitrary client.

Automatic discovery currently requires a Linux remote host with readable `/proc` client
environments and the default tmux socket. Hosts that hide process environments, nested ambiguous
clients, and custom tmux sockets do not gain automatic discovery from this change. The existing
popup-selected path remains available. No shell startup file or tmux configuration is modified.

Mosh delivers framebuffer repaints, which are not an ordered tmux history stream. A confirmed
Mosh/tmux connection therefore does not advance remote history coordinates from local VT repaint
scroll operations. It refreshes bounded `capture-pane` pages off-thread. Ordinary direct Mosh
history inference and direct SSH history semantics remain unchanged. History bytes and their coordinates are captured in one synchronous tmux command queue, so
output between SSH requests cannot shift the captured range. Older pages reject changed
coordinates. Paging and reconciliation retain their existing limits, pixel anchors, and per-frame
work budget.

## Indicators

The top tabs, app-tab grid, tmux-session grid, and startup tmux picker share one small status view:

- Running: an observed working title (Codex's braille spinner or explicit Working title).
- Finished / ready: that working title explicitly returned to its ready/base title. This means
  work has stopped; it does not assert command success or distinguish cancellation from completion.
- Needs attention: a bell, generic terminal notification, or notification/waiting title. A bell
  alone is never labeled successful completion.

Codex's default `tui.terminal_title` contains a spinner and project name; terminal title updates
can be disabled by the user. Supported configuration is documented in the
[official configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).
The adapter was observed against installed Codex 0.153.4. Customized/disabled title formats may
provide no reliable running/ready state; output volume or silence is not used as a substitute.

Inactive tmux panes are observed through bounded title/bell/process metadata, including Codex
running under a shell whose reported pane command is still `fish`. The foreground process group
must contain Codex before its title is interpreted as Codex work. Remote task state is keyed by
server PID, session, and pane; session badges aggregate running and unread attention/completion
without hiding one behind the other. Viewing one pane does not clear another pane's alert.
Repeated bell flags and unchanged title samples do not recreate acknowledged badges.

State is runtime metadata, independent of sound, vibration, Android notification permission, and
Compose terminal cells. Closing/disconnecting stops running indicators. A new transport/process
must observe fresh state; no persistent stale spinner is restored. Queries use the already
authenticated connection, outside gestures and rendering, and do not capture transcripts for
status inference. No app library dependency is added.

## Verification

The guarded real runner supports `--manual-tmux` (SSH) and `--manual-mosh` (Mosh). Each executes
its one exact actual-Codex method, preserving all 200 Codex-generated rows, the 5,000-row history
paging gate, first-gesture route/wheel evidence, sub-row drag, fling, catch, reader anchoring, and
live bottom. The default runner still executes direct SSH, popup-selected tmux, and real mouse
applications. The task UI runner executes four process-isolated UI contracts. Both runners
re-enumerate and model-check the exact old `SM_S911B` before every install/test and reject the fold.

Exact current red/green evidence and unresolved runs are maintained in the Obsidian tmux living
record. No synthetic UI/controller test substitutes for physical actual-Codex acceptance.

## Verified implementation — 2026-09-07T21:57:59+00:00

Implemented in the shared checkout on base `42d80af` with concurrent work preserved.
Latest debug APK SHA-256: `2a3339528161482ca5807c557a7d7111535569c513f3eb0df4736749589af6ea`. The authorized device was USB `RZCW81JZ9CP`, model `SM-S911B`; no fold tests ran.

| Gate | Result | Evidence under `build/` |
| --- | --- | --- |
| Shell-started SSH, actual Codex | 1 passed, no skips; running/ready and all scrolling assertions | `tmux-status-final-manual-ssh/TEST-real-codex-tmux.xml` |
| Shell-started bundled Mosh, actual Codex on outer primary screen | 1 passed, no skips; running/ready and all scrolling assertions | `tmux-status-final-bundled-mosh/TEST-real-codex-tmux.xml` |
| Direct SSH + popup-selected tmux + less/Vim/htop mouse input | 3 passed, no skips | `tmux-status-final-standard-matrix/TEST-real-codex-tmux.xml` |
| Top tabs and all three picker surfaces | 4 passed, no skips | `tmux-status-final-ui-retry/TEST-task-ui.xml` |
| Mosh lifecycle, Activity recreation, password/private key | 5 + 1 passed, no skips | `tmux-status-final-mosh-lifecycle/TEST-real-mosh-*.xml` |
| Unit tests, lint, debug app and test builds | Passed; app 1,022, API 11, core 8 unit tests | `tmux-status-completed-source-gates.log` |
| Host tooling | 98 passed; bundled runner follow-up 5 passed | `tmux-status-final-script-tests.log`, `tmux-bundled-runner-green.log` |

Both actual-Codex manual paths preserved every numbered Codex row, paged all 5,000 fixture rows,
selected `LOCAL_SCROLLBACK / AUTO_TMUX_LOCAL_READY` on the first gesture with zero remote wheel
reports, and passed sub-row drag, release fling, touch catch, exact reader anchor (0.0 px delta),
and live bottom. These checks do not wait for the internal local-history-ready flag before touch.

Red evidence is retained in the Obsidian living record and the isolated task checkout: missing
manual tmux identity/history, all four original UI surfaces lacking indicators, Mosh repaint
coordinate inflation, and the capture race. Re-reading metadata in a later SSH request was
rejected as an approach because it starved the history cache during output; the final capture
uses one tmux command queue. One integrated UI run failed before UI assertions in coroutine
ServiceLoader setup; the exact four-test rerun passed. The failure log is retained at
`tmux-status-final-ui/instrumentation.txt`.

The Wi-Fi fold `SM_F976B` was unavailable in adb. Completed-build installation/foreground proof
and the user's visual acceptance remain pending; the living smooth-scroll record is not marked solved.
