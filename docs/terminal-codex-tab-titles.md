# Codex tab names

The local Herdr fork at `/home/jiyu/git/herdr-mobile-menu-0.9.1` names unnamed
Space tabs from the first meaningful Codex session title received over each
pane's own PTY. Android rendering and input/history behavior are unchanged.
Herdr adds no AI request, service or dependency.

The top tab strip now shows only workspace-relative numbers. Full stored task
names appear in the mobile Switch list, grouped beneath each Space. A task is
listed once; the normal list no longer repeats an Agents section and a separate
Tabs section. Single-machine navigation omits the repeated `Local` label.

Codex currently emits `<task> | <project>` as its OSC terminal title. Herdr
removes the spinner and final project suffix, collapses whitespace and limits
the result to 32 Unicode code points including an ellipsis. It requires detected
Codex process identity. Shells, other agents, generic titles and greetings
cannot claim names. Existing custom names, including manual renames, take
priority; later title changes do not replace the first name. The existing tab
rename event and persistence path carry the name to all clients.

Title events can precede process detection. A Codex process-acquisition event
revisits just that pane's title, including an idle imported pane whose OSC title
has not changed. No periodic transcript scan or per-cell work is added.

## Previous implementation and user red evidence

The original submit-hook implementation passed isolated socket tests and 3,649
nextest tests, but did not establish real Codex acceptance. The user's screenshot
still showed numeric tab names after opening another Codex conversation.
Investigation found that the shared Codex app-server daemon inherited
`HERDR_PANE_ID=w1G:p1`, which no longer resolved, while the actual visible client
panes were in `w1Y`. The daemon ran hooks with that old environment. A fake hook
payload with a fresh pane ID could not reproduce this failure. Existing real
Codex panes already exposed task-specific OSC titles such as `为 Tab 自动命名`
and `查看效果`, while their tab labels remained `2` and `5`.

The unsafe prompt-hook route has been removed. Integration v9 installation
removes only the obsolete managed prompt command, preserving session and
unrelated hooks. The terminal-title route identifies the owner from the PTY,
independently of shared-daemon hook environment or whether hooks were reloaded.

Earlier optimized build SHA-256 was
`5b65593e2f33be3495d297f76ccede258992e7b7d539a4a5d4555150e30be832`.
It was installed and handed off successfully, but is not accepted as fixing the
user-visible behavior. Historical logs are in ignored
`build/verification/codex-tab-titles/`. Follow-up evidence is recorded below.

## Corrected verification and deployment — 2026-11-07

The opt-in `scripts/test-codex-tab-title.py` in the Herdr fork starts a real
installed Codex 0.166.0 client in an isolated Herdr server, waits for its actual
input box, and submits a harmless task through `agent.prompt`. It uses the
normal shared daemon (no `--no-daemon`) and does not inject an OSC title or fake
hook payload. Both debug and exact optimized release binaries passed: label
`1` became `验证标签自动命名`, matching the real Codex title
`验证标签自动命名 | terminal-spike`; another focused tab stayed `2`.
Manual rename was also checked, with subsequent-title protection covered by
regression tests. The initial fixture attempt missed the startup input boundary;
it is retained as red setup evidence, not treated as a product pass.

Nextest: 3,652 passed, six configured skips. Formatting, clippy, optimized build,
six architecture checks and six render-scale profiles passed. At fixed geometry,
the server pane-surface median for one versus 15 active panes was 830→1,085 µs
(+255 µs, 1.31×); one versus 15 background workspaces was 703→671 µs (0.95×).
These are cardinality measurements, not a before/after speed improvement claim.
No Android application/build/dependency/test source changed for this correction.

Installed binary SHA-256:
`2a9b1d1733d27ba53a7cc37dfc558356b88e8cd70dc1c682300147c5f7b4f6c5`.
The previous binary is retained at
`/home/jiyu/.local/lib/herdr-mobile-menu/herdr-0.9.1-before-codex-pty-titles-20261107T000229`.
Integration reinstall and live handoff succeeded. In the actual default session,
`w1Y:t2` now reports `为 Tab 自动命名` and `w1Y:t5` reports `查看效果`; all five
existing terminal-spike tabs acquired their existing Codex task titles.

The latest debug APK was non-incrementally installed on model-verified
SM-F976B at `adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`. Launch returned
`Status: ok`, and `MainActivity` was confirmed foregrounded. A read-only device
screenshot visibly confirms `为 Tab 自动命名` on the selected top tab and
`添加 Snippets 折叠按钮` on the neighboring tab. No gestures, instrumentation or
device tests ran on the foldable. Evidence: ignored
`build/verification/codex-tab-titles/{fix-real-debug-2,fix-real-release,fix-deployment}/`.
