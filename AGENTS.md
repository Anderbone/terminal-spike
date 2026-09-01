# Project rules

- Use native Kotlin Android only.
- Use Jetpack Compose for surrounding application UI and a custom/native renderer for the terminal hot path.
- Never introduce per-cell or per-line Compose state in the terminal hot path.
- Do not add unreviewed network, analytics, AI, cloud, advertising, sync, or account features.
- Do not add a GPL or AGPL dependency without an explicit architecture and licensing decision.
- Dependency versions must be stable: no alpha, beta, RC, preview, snapshot, or nightly versions.
- Record every new direct dependency, its purpose, source, licence, and notice obligations in `docs/DEPENDENCIES.md` and `THIRD_PARTY_NOTICES.md` as applicable.
- Preserve the terminal buffer, viewport, batching, workload, smoke, and future performance tests.
- Run unit tests, lint, and a build before declaring work complete.
- Device tests may run on a currently connected, authorized local USB Android phone. For tmux-scroll work, the user has also explicitly authorized wireless debugging and device tests on the old `SM_S911B`; resolve its exact current serial from `adb devices -l`, verify the model before every install or test, and target it with `adb -s`/`ANDROID_SERIAL`. Never run tests on the Wi-Fi-connected foldable `SM_F976B`.
- Do not install incremental builds on the Wi-Fi-connected foldable phone. Once the whole requested feature is finished, or when the user explicitly asks, install and launch the latest debug APK on that phone without running tests on it. Resolve the target from `adb devices -l`, use its exact serial, verify `MainActivity` is foregrounded, and report if it is offline, unauthorized, unavailable, or the install or launch fails.
- Do not broaden scope without an explicit task.
- Do not copy source from Termius, LobiShell, ConnectBot, Termux, or other terminal applications.
- Keep SDK paths, signing material, secrets, build outputs, APKs, and machine-specific IDE files out of Git.

## Terminal scrollback regression gate

- Preserve direct SSH and Mosh scrollback for inline terminal UIs such as Codex when tmux is absent.
- A scroll region whose top margin is terminal row zero owns real terminal history even when its bottom margin leaves an input or status area on screen. Rows removed from that region must enter bounded scrollback. A region that starts below row zero must not enter generic scrollback.
- Preserve `primaryTopAnchoredScrollRegionRetainsActualCodexStyleOutput` and its one-byte transport-chunk counterpart. Do not weaken their requirement that all 200 numbered rows survive in order and that rows below the scroll margin stay unchanged.
- Preserve the opt-in real-device `SshRealEndToEndTest` Codex path. It must run only on the authorized old phone, open actual Codex first, wait for the input box, ask Codex itself for 200 numbered lines, verify every marker from `CODEX_SCROLL_001` through `CODEX_SCROLL_200` in order, and dispatch real `MotionEvent` drags until row 001 is visible.
- Never substitute output produced by the shell before Codex starts, a synthetic controller workload, zooming, parser-only checks, or the presence of only rows 001 and 200 for the real Codex acceptance path.
- The bottom viewport must initially contain row 200 and exclude row 001. A gesture toward older history must reduce `scrollY`; passing only because row 001 was already visible is invalid.
- Run real terminal device tests only on the authorized old-phone target, using USB by default or its explicitly authorized wireless transport for tmux-scroll work. Resolve and verify `SM_S911B` before each command. Never run them on the Wi-Fi foldable `SM_F976B`. See `docs/terminal-codex-scrollback-regression.md` for the evidence contract and exact test layers.

## Tmux smooth-scroll living record

- The canonical investigation and implementation plan is `/home/jiyu/Documents/Jiyu-obsidian/plannow/2026-08-31-tmux-native-smooth-scroll-investigation.md`.
- Before changing tmux history capture, history ownership, touch routing, remote mouse handling, viewport movement, or fling behavior, read that entire note and treat its current Definition of Solved as the acceptance contract.
- During every tmux-scroll work session, update the note's `updated_at`, Current State / Evidence, Experiment Log, Open Questions, and Next Step before declaring the session complete. Preserve exact red and green evidence, including the build or commit, scenario, selected route, route reason, relevant tmux metadata, remote wheel count, and device used.
- Never claim tmux smooth scrolling is solved from a synthetic alternate screen, a controller-only test, an emulator benchmark, or a test that waits for `isTmuxLocalScrollAvailable()` before the user's first gesture. The real old-phone gate must exercise the first gesture in actual Codex inside app-selected tmux, require zero outbound remote wheel reports, prove sub-row drag and post-release motion, and preserve full ordered history.
- Preserve `confirmedTmuxPrimaryScreenHistoryUsesTheFloatViewport`. A managed tmux client resumed through Mosh may use the outer VT primary screen; tmux capture scheduling, history mapping, fractional scrolling, and fling must not depend on `terminalScreenIsAlternate`. A fresh SSH tmux fixture that enters the alternate screen is not a substitute for this saved-Mosh path.
- The user's baseline red evidence is `https://photos.app.goo.gl/WY6cTXeX9wnvbSZ86`: tmux copy-mode rows advance discretely and motion stops on finger-up. Increasing wheel-event frequency or converting fling into tmux wheel reports cannot satisfy the requirement.
- Do not mark the living record done until all automated gates pass, the completed build is installed and launched on the Wi-Fi foldable under the project rules, and the user confirms that the real workflow meets the Definition of Solved. Never run tests on that foldable.
