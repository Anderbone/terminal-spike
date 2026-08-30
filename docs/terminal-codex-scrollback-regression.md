# Direct Codex scrollback regression gate

Date established: 2026-08-30

## User-visible contract

For a direct SSH session without tmux:

1. Open actual Codex in the terminal.
2. Ask Codex itself to output exactly 200 lines named `CODEX_SCROLL_001` through
   `CODEX_SCROLL_200`.
3. At completion, the bottom viewport contains the newest Codex rows and row 001 is off-screen.
4. Drag a finger downward over the terminal to move toward older history.
5. The viewport must pass through earlier Codex rows and make row 001 visible before reaching any
   shell content that predates Codex.

Zooming out is not scrolling. Finding only the first and last marker is not enough. Generating the
200 lines before Codex starts is not this test.

## Terminal invariant

Codex is an inline TUI. It can set a primary-screen scroll region whose top margin is row zero and
whose bottom margin leaves input/status rows untouched. Every row removed from the top of that region
is real terminal history and must be delivered through `TerminalFrameUpdate.completedScrollback`.

A scroll region beginning below row zero is an in-place screen operation and must not be promoted to
generic history. Rows below a partial scroll region must stay unchanged.

This rule belongs in `VtTerminalEngine`, not in gesture code, renderer caches, SSH-specific code, or
a Codex-name special case. SSH and Mosh must consume the same parser semantics.

## Automated layers

### Parser regression

`VtTerminalEngineTest.primaryTopAnchoredScrollRegionRetainsActualCodexStyleOutput` reproduces the
captured Codex control shape in one parser batch. It requires:

- all 200 markers retained exactly once and in order;
- row 001 in completed scrollback;
- row 200 in the current screen;
- input and status rows below the margin unchanged.

`primaryTopAnchoredCodexHistoryIsIndependentOfOneByteTransportChunks` repeats the same contract with
one-byte network chunks. It protects parser state across arbitrary SSH/Mosh read boundaries.

### Production USB E2E

`SshRealEndToEndTest.realServerScrollsToFirstRowThroughProductionSessionAndComposeView` has an opt-in
actual-Codex branch. The test is valid only when all of these occur:

- the production `MainActivity`, repository, SSH connection, controller, viewport, and
  `FastTerminalView` are used;
- actual Codex is launched after SSH connects;
- the test waits for `Ask Codex to do anything` before entering the prompt;
- an Android Enter key is dispatched through the mounted terminal view;
- every marker 001 through 200 exists and its controller index is strictly increasing;
- row 200 is visible and row 001 is not visible at the initial bottom;
- a real `MotionEvent` drag reduces `scrollY` and repeated drags make row 001 visible.

The test must run only on an authorized USB phone. It must never run on the Wi-Fi foldable.
Machine-specific host addresses, credentials, SDK paths, Codex paths, and generated private keys are
instrumentation inputs or ignored build artifacts; they must not enter source control.

## Required evidence after scroll changes

Run these gates before declaring any change to terminal parsing, buffering, viewport behavior,
gesture routing, PTY resize, SSH, Mosh, tmux, or renderer caching complete:

```bash
./gradlew test lint assembleDebug
```

Then run the focused actual-Codex E2E on the authorized USB phone with the opt-in instrumentation
arguments. Record the controller diagnostic at the bottom and after gestures. A credible result
shows at least 200 Codex rows retained, row 200 at the bottom, row 001 absent from that bottom
viewport, decreasing `scrollY` during the older-history gesture, and row 001 visible afterward.

Run the existing real SSH plus tmux and real Mosh 200-row regressions when their disposable local
fixture is available. These are regression checks for adjacent transport/mouse/history paths; they
do not replace the actual-Codex SSH gate.

After the complete feature is green, install the full latest debug APK on the exact Wi-Fi serial and
launch `com.yanjiyu.terminalspike/.MainActivity` without running tests there. Report honestly if
secure keyguard, sleeping display state, authorization, or connectivity prevents foreground proof.

## Invalid evidence

The following must never be used alone to close this regression:

- a synthetic `TerminalBuffer` or controller containing 200 prebuilt lines;
- ordinary shell output produced before Codex starts;
- parser assertions that check only rows 001 and 200;
- screenshots or zoom changes without a viewport-position assertion;
- a swipe helper that starts with row 001 already visible;
- a Mosh framebuffer test presented as proof of actual SSH PTY byte history;
- tmux copy-mode history presented as proof of the no-tmux path;
- tests on the Wi-Fi foldable.
