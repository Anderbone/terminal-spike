# Direct Mosh scrollback and the Codex table regression

## Fixed local history loss (2026-09-07)

The reported no-tmux Codex table failure is reproduced and fixed. Mosh delivered the
numbered rows, but `MoshDisplayHistory` rejected their movement and discarded older rows.
The original comparison assumed a whole-screen shift; Codex's table has blank top padding,
stationary input/status rows, and sometimes stationary annotations on its separators.
Cursor-addressed redraws can also leave different logical wrap flags on visually identical rows.

Recovery now compares visible text and styled runs, recognizes shifts above a fixed footer
and below blank padding, and permits unchanged decorations after matching output rows.
Partial-region recovery requires two distinct genuinely moving nonblank text rows;
repeated separators alone do not establish a scroll. Saved rows retain their original
styling and wrap metadata. History stays bounded by the existing terminal buffer.

An explicit vertical-movement flag prevents inference from reinterpreting actual VT scrolling
or line insertion/deletion. A real scroll region starting below row zero still contributes no
generic history. Existing top-anchored Codex scroll-region behavior is unchanged.

No transport switch, remote helper, dependency, or Mosh extension change is involved.

## Red / green evidence

Validation used an isolated checkout of `224aaac` plus this task's source/test changes,
because unrelated work was changing the shared workspace concurrently. The verified app APK
SHA-256 is `4f4eec975f0e92ca44416c364b0e45c3aeb48eccb06331b4e369be597e46e2a4`.
This validation does not cover those other concurrent changes.

- The final 15-case `MoshDisplayHistoryTest` suite against the baseline recovery implementation:
  **7 failures**. With the fix: **15 passed**. Coverage includes 50/200 ordered rows, table
  separators, blank padding, stationary annotations, differing wrap metadata, fixed footers,
  multiple displaced rows, true interior scroll exclusion, and ambiguous/unobserved frames.
- All 56 existing VT engine tests pass, including the unchanged 200-row Codex region and
  one-byte transport-chunk regressions.
- All **1,001 unit tests** across the three modules, lint, debug app build, and Android-test
  build pass.
- Actual Codex, direct Mosh, **200-row Markdown table**, no tmux, authorized USB `SM-S911B`
  (`RZCW81JZ9CP`), inventory/model checked before installation and testing:
  baseline **RED**, 182 markers missing beginning at 009, 125 controller rows;
  final **GREEN**, every marker 001–200 retained in order, 514 controller rows.
  Row 200 was visible and row 001 absent at the initial bottom. Real `MotionEvent` drags
  reduced `scrollY` from `28523` to `2760.2988` and exposed row 001.
- Baseline APK SHA-256:
  `93c07c2d9f5cadd671da281bac85f04e6aafc6802e4beb3f49128549e844288c`.

Sanitized device evidence lives in ignored
`build/direct-codex-mosh-baseline-2026-09-07-table` and
`build/direct-codex-mosh-2026-09-07-table-final-visual`.
Final artifacts and JVM red/green reports are retained in ignored `build/codex-no-tmux-final`.
See `terminal-codex-scrollback-regression.md` for the opt-in actual-Codex table arguments
and strict marker/order/bottom/gesture contract. A parser replay or synthetic workload is
not a replacement for this production-view device gate.

## Investigation record

Plain 200-line actual-Codex output passed on both baseline and initial patched Mosh builds,
so it did not reproduce the screenshot. The closer Markdown-table prompt reproduced it.
A footer-only fix still lost 182 markers; blank-padding recovery alone later lost 185.
These intermediate results are red, not evidence of a completed fix.

Temporary native-output capture established that all 200 markers had arrived. Replaying
original transport chunks exposed the additional stationary annotations and stale wrap
metadata. The final recovery retained all markers in replay and then passed the clean
actual-Codex table device gate. The earlier hypothesis that this captured table failure
required a remote recorder was withdrawn. Diagnostic capture code is not part of the patch.

An earlier direct-SSH table comparison also passed all 200 markers and real scrolling
(`lineCount=518`, `scrollY=28759 -> 5968`). Default plain-line tests remain separate
regression coverage, not the table's red/green proof.

## Remaining protocol boundary

Standard Mosh synchronizes screen states rather than delivering the full remote PTY byte
stream. Its native client renders `get_latest_remote_state().state.get_fb()` using
`Display::new_frame()`. Intermediate screens can be omitted before Android receives them;
local recovery cannot fabricate those unseen rows. The final-screen-only unit test keeps
this boundary explicit. Arbitrary repaints without sufficient matching movement are also
not treated as history.

This general limitation is separate from the fixed local loss above. Direct SSH retains
received PTY history within the configured buffer; tmux can own remote history when selected.
The app continues to honor the selected transport. Historical upstream context:
<https://github.com/mobile-shell/mosh/issues/122>.

The Wi-Fi foldable `SM_F976B` was unavailable in ADB during validation. No tests ran on it;
final installation, launch, and confirmation of the user's exact workflow remain outstanding.

Final plain-line regression: direct SSH passed all200/order/gesture assertions
(`lineCount=297`, `scrollY=15720 -> 5395`). The first final Mosh plain-line run completed
the same history/gesture assertions (`lineCount=324`, `scrollY=17313 -> 2527`) but failed
in ActivityScenario teardown: requested DESTROYED, last state PAUSED. It is recorded as an
overall failed run; its isolated retry is recorded separately.

The Mosh plain-line retry passed completely, including teardown: all 200 ordered markers,
`lineCount=317`, real drags from `scrollY=16900` to `2028`, and row 001 visible afterward.
Evidence: `build/direct-codex-mosh-2026-09-07-final-visual-retry`. Final ADB inventory still
contains only the old phone (USB and wireless transports); the foldable is unavailable.
