# Agent task tab names

The local Herdr fork at `/home/jiyu/git/herdr-mobile-menu-0.9.1` names tabs
from the first meaningful task in each agent session. Supported agents are
Codex, pi, OpenCode and Claude Code. Android rendering and input/history
behavior are unchanged. Herdr adds no model request, service or dependency.

The top tab strip shows workspace-relative numbers. Full stored task names
appear in the mobile Switch list, grouped beneath each Space.

Codex publishes `<task> | <project>`, OpenCode publishes `OC | <task>`, and
Claude Code publishes an activity-prefixed task title. Herdr uses titles from
the originating PTY after process identification. Pi's default terminal title
contains only the directory, so integration v10 publishes its first submitted
prompt as a bounded, sanitized `pi - <task> - <project>` title. Existing named
pi titles using `π` are recognized too. Task labels collapse whitespace and
are limited to 32 Unicode code points including an ellipsis.

Automatic names now retain their provenance separately from manual names.
Ending an agent process or replacing an established session identity reopens
that pane's naming opportunity. The old name remains visible until a fresh
meaningful task title arrives. Later titles within that session leave the name
stable; startup titles, greetings and stale previous-session titles cannot
claim the next name. A manual rename removes automatic ownership, including
when the user manually chooses the same text. Other panes cannot replace the
owning pane's automatic name. Names and ownership survive session save/restore
and server handoff, with pane IDs remapped on restore. These are optional JSON
session fields; frozen client codecs and socket method shapes are unchanged.

Legacy saved labels have no ownership marker. They remain protected as manual
names unless explicitly migrated; matching text alone cannot prove origin.

Title events can precede process detection. An agent process-acquisition event
revisits that pane's title, including an idle imported pane whose OSC title has
not changed. No periodic transcript scan or per-cell work is added.

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

## Session restart and additional agents — 2026-11-07

The corrected implementation tracks automatic-name ownership per tab and session.
An actual Codex process was closed and restarted in the same isolated tab; both
sessions were named from their own first prompt. The same two-session sequence
passed with installed pi 1.4.0 and OpenCode 2.1.2. No synthetic OSC title or hook
payload was used for these acceptances. Another focused tab stayed numbered 2.
The first titles in one Codex run were `解释 SSH 与 Mosh 的区别` and then
`解释 Kotlin 与 Java 区别`. Pi uses bounded first-prompt text; Codex and
OpenCode retain their own generated task summaries.

All three exact-candidate result files record SHA-256
`8a608db598fd2b0a4ffafe541ec55c8c805b96bf965c4b7950081c3964f2411c`.
Claude Code 2.1.316 reached its actual input box, but the first prompt returned
`Not logged in · Please run /login` and retained generic title `✳ Claude Code`.
Its real task-title acceptance is **not established**. Its format, restart and
manual-name behavior are covered by the same state/PTY regression tests.

Verification: 3,662 nextest tests passed with seven configured skips; the final
11 affected title, persistence and restore checks passed after the last Rust
change. Forty integration tests, six architecture checks, formatting, clippy
with warnings denied and the optimized build passed. Seven render-scale profiles
passed; these are cardinality checks, not a before/after performance claim.
The pi v10 CLI expectation initially failed after its integration version bump;
that contract was updated, and the full suite then passed. Early fixture attempts
with an incorrect Codex path, premature readiness, pasted control characters,
missing/unsupported model configuration and Claude onboarding are retained as
red setup/authentication evidence, not product acceptance.

The verified candidate replaced the local Herdr executable, and pi integration
v10 was installed. Live handoff retained all 13 pane IDs and all seven recorded
server-child PIDs. The previous local binary was preserved at
`/home/jiyu/.local/lib/herdr-mobile-menu/herdr-0.9.1-before-session-task-titles-20261107T103035Z`.
Concurrent client/layout work present in the shared checkout was retained.
Legacy names have not been reclassified without a migration choice.

Android source, build configuration, dependencies and tests were unchanged by
this task. The latest existing debug APK (SHA-256
`bc79b4775fa4abac690a60f88325f744416f0b0633fbd2d33eb37ed4c85247d5`)
was installed non-incrementally on model-verified SM-F976B at
`adb-RFGL80WYDZW-QnawRi._adb-tls-connect._tcp`. Launch returned `Status: ok`,
and a final read of Samsung's `ResumedActivity:` entry confirmed MainActivity
foregrounded. An earlier check recognized only `topResumedActivity` and
`mResumedActivity`; that narrower verifier missed this device's output format.
No foldable tests ran.

Herdr evidence: ignored `build/agent-tab-titles/`, including the immutable
candidate, per-agent result hashes, setup failures and deployment comparison.
Phone evidence: Terminal Spike's ignored `build/verification/agent-tab-titles/`.
