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
- When an authorized USB phone is available and capable of the task, use it as the primary target for iterative installs, app runs, debugging, and permitted device tests. Resolve its current serial with `adb devices -l`, verify its model before each install or test, and explicitly target every device command with `adb -s` or `ANDROID_SERIAL`; do not rely on ADB's default target when multiple phones are connected.
- Device tests may run on a currently connected, authorized local USB Android phone. For tmux-scroll work, the user has also explicitly authorized wireless debugging and device tests on the old `SM_S911B`; resolve its exact current serial from `adb devices -l`, verify the model before every install or test, and target it with `adb -s`/`ANDROID_SERIAL`. Never run tests on the Wi-Fi-connected foldable `SM_F976B`.
- Do not install incremental builds on the Wi-Fi-connected foldable phone. Once the whole requested feature is finished or the current work has been verified to work on the primary USB phone, or when the user explicitly asks, install and launch the latest debug APK on the foldable without running tests on it. Resolve the target from `adb devices -l`, verify the `SM_F976B` model, use its exact serial, verify `MainActivity` is foregrounded, and report if it is offline, unauthorized, unavailable, or the install or launch fails.
- Do not broaden scope without an explicit task.
- Do not copy source from Termius, LobiShell, ConnectBot, Termux, or other terminal applications.
- Keep SDK paths, signing material, secrets, build outputs, APKs, and machine-specific IDE files out of Git.

## Routine commit, push, and publish defaults

- Ordinary commit/push/publish requests use required local verification and publication checks as the completion gate. Leave GitHub Actions running; do not poll, wait for, rerun, or repair full hosted CI unless the user explicitly asks for CI to be green. Report CI as unverified/pending, never as passed without evidence. Do not invoke `push-main-green` merely because a request includes pushing main.
- Use `scripts/verify-android.sh` for the shared local/CI gate. For main-app internal releases, use the verified prepare/publish commands in `docs/CI-AND-RELEASING.md`; keep hosted CI waiting opt-in.
- Inspect the intended diff once. Simplify only when requested and clearly useful; do not turn a release into an audit or feature-development session.
- Combine required unit tests, lint, and debug/release builds in one Gradle invocation where possible. Preserve incremental caches. Reuse passing evidence for unchanged inputs; rerun affected checks after a fix. Retain all existing signing, artifact, licensing, device-target, and Play version-code safeguards below.
- Full emulator suites and real-device acceptance are separate tasks unless explicitly requested or required for the feature being implemented. A routine release does not reopen previously unverified physical acceptance; report its limits. Preserve the required final foldable install/launch without tests.
- Save verbose output in ignored build logs; inspect concise summaries and relevant error excerpts. Keep updates short and useful. Avoid repeated full-file reads, CI polling, and speculative refactors.
- Complete release fixes before uploading, aiming for one verified upload. Commit and push the reviewed source, publish the main app to internal testing, re-read the Play track, perform the required final install/launch, and finish. Additional uploads require an actual artifact change or a publishing failure.
- If another session edits the workspace during release, preserve its changes but do not silently expand the reviewed release. Use an isolated checkout of the reviewed source when needed; report concurrent edits separately.
- These instruction-only Markdown edits need diff/skill validation, not Android builds. The unit-test/lint/build requirement applies to application, build, dependency, and test-code changes.

## Efficient CI repair

- Diagnose completed failed jobs from their artifacts before rerunning anything. Separate assertion failures, instrumentation crashes/timeouts, SDK download failures, and optional diagnostic collection failures.
- Reproduce the affected class/API or harness test first. Group confirmed fixes into one reviewed patch and one shared verification run; retain passing evidence for unchanged inputs.
- Retry a demonstrated transient infrastructure failure at most once using the failed job ID. Preserve the same run's passing jobs and verified APKs; never dispatch another full workflow merely to retry a job. Repeated failure requires diagnosis.
- Full API 26–37 coverage remains weekly/manual. Do not add it to ordinary push gates or launch it repeatedly during diagnosis. When full hosted verification is explicitly requested, run one final compatibility sweep after focused repairs pass.
- Report milestones, actionable failures, and final evidence concisely. Avoid repeated unchanged-status narration and repeated full log reads. Do not claim a speed or billing improvement without measurements.

## Google Play internal publishing

- External production upload-signing material, verified release artifacts, and the Google Play Developer API service-account credential live in `/home/jiyu/git/app_sign`. Read its `README.txt` before publishing. Never print credential or password contents, copy them into this repository, or commit them.
- When the user explicitly requests a main-app internal-test publish, target package `com.yanjiyu.terminalspike` and Play track `internal`. Inspect the track and consumed bundle version codes through the Android Publisher API before building or uploading.
- Never reuse a version code already consumed by Play. Build the signed AAB from the current source using the external upload key, run the required unit tests, lint, and release build, verify the signed artifact, upload it, commit the Play edit, and re-read the internal track to prove the release state.
- Version the main app and Mosh extension independently. Never bump or upload the Mosh extension merely to align its version code or version name with the main app. Leave the published Mosh version unchanged unless its own deliverable changes materially, such as native or Kotlin code, the AIDL/API contract, dependencies, manifest or SDK configuration, resources, notices, signing requirements, or compatibility behavior.
- If a changed or deliberately rebuilt Mosh bundle must be uploaded, use a new unconsumed Mosh version code because Play does not permit reusing one. Choose its version name according to the extension's own release history. Enforce main-app/extension compatibility through the explicit protocol and API contract, never by requiring equal Play version codes.
- Do not publish the separately packaged Mosh extension unless the user explicitly requests it and its licensing, trademark, signing, source-offer, and listing gates have been satisfied.

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
- Treat the top of cached local tmux history and the true beginning of tmux pane history as different states. Do not silently switch an `Auto` gesture to tmux wheel/copy mode at the cached boundary. If history must extend beyond one capture's safety bounds, retain Android-owned fractional scrolling and fetch older `capture-pane` line ranges asynchronously before the viewport reaches that boundary; preserve the reader's exact pixel anchor when prepending rows.
- Preserve red/green coverage for both boundary cases: a pane whose entire reported `history_size` is cached must stop cleanly at its true oldest row, while an app-truncated capture must expose and load older rows without remote wheel reports, a row-sized jump, or a full-history fetch on the animation path.
- The user's baseline red evidence is `https://photos.app.goo.gl/WY6cTXeX9wnvbSZ86`: tmux copy-mode rows advance discretely and motion stops on finger-up. Increasing wheel-event frequency or converting fling into tmux wheel reports cannot satisfy the requirement.
- Do not mark the living record done until all automated gates pass, the completed build is installed and launched on the Wi-Fi foldable under the project rules, and the user confirms that the real workflow meets the Definition of Solved. Never run tests on that foldable.
