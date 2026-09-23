# Automatic terminal input selection

The accessory bar opens the Type composer for foreground Codex in a confirmed tmux
pane, or a foreground agent in an app-selected Herdr pane. A shell, another
program, or unavailable metadata defaults to the terminal shortcuts. This works
through the existing SSH connection and Mosh SSH side channel.

Tmux reuses its foreground-process observation, independently of task spinners.
Herdr reads the focused pane id from `pane layout`, then queries that explicit id
with `pane get` and `pane process-info --pane`. It checks focus again before
publishing. An old agent label is insufficient: the foreground process must be
Codex or match Herdr's agent name. Commands are read-only, bounded metadata reads;
they do not capture history or inject input. Herdr's process-info contract is
documented in its [socket API](https://github.com/herdrdev/herdr/blob/v0.8.2/docs/next/website/src/content/docs/socket-api.mdx).

Selection refreshes on the existing background metadata loop. It is not an exact
cursor-widget detector: Codex menus and approval dialogs still count as Codex.
Plain SSH/Mosh and local shells without this metadata retain the manual Type
toggle; no title or screen-text guessing is used.

Manual toggles survive keyboard closing/reopening and unchanged metadata. A new
pane, session, or foreground classification restores automatic selection. An
unfinished visible draft stays visible until cleared, sent, or manually hidden.
Changing input mode never sends or deletes a draft.

Regression coverage:

- `HerdrInputContextTest`: focused agent, shell with stale agent label, changing
  focus, malformed/failed/oversized metadata, and explicitly targeted reads.
- `TmuxTaskMonitorTest`: foreground state independent of spinner and failure reset.
- `BufferedInputPagerTest`: automatic defaults, context changes, manual override,
  and unsent draft retention.
- Existing shortcut, accessory, and renderer-lab smoke tests preserve focus and
  input behavior with the new default.

## Verification — 2026-09-16

An isolated copy was used after concurrent Gradle builds in the shared checkout
collided on a test-results file. `test lint assembleDebug
:app:assembleDebugAndroidTest` passed: 1,075 app unit tests, 11 Mosh API tests,
and 8 Mosh core tests, with no failures or skips. All 12 selected input/accessory
and renderer-lab UI tests passed on USB `SM-S911B` (`RZCW81JZ9CP`). Live host
Herdr metadata also identified the focused Codex foreground process. This is
metadata and UI coverage, not a full real remote-agent acceptance test.

The completed debug APK was installed on Wi-Fi `SM-F976B`; `MainActivity` was
verified as the top resumed activity. No tests ran on that phone.

Ignored logs, source hashes, and deployment evidence are under
`build/input-context-evidence/`.
