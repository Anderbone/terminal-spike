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
- Device tests may run on a currently connected, authorized local USB Android phone. Never run tests on the Wi-Fi-connected foldable phone.
- Do not install incremental builds on the Wi-Fi-connected foldable phone. Once the whole requested feature is finished, or when the user explicitly asks, install and launch the latest debug APK on that phone without running tests on it. Resolve the target from `adb devices -l`, use its exact serial, verify `MainActivity` is foregrounded, and report if it is offline, unauthorized, unavailable, or the install or launch fails.
- Do not broaden scope without an explicit task.
- Do not copy source from Termius, LobiShell, ConnectBot, Termux, or other terminal applications.
- Keep SDK paths, signing material, secrets, build outputs, APKs, and machine-specific IDE files out of Git.

## Terminal scrollback regression gate

- Preserve direct SSH and Mosh scrollback for inline terminal UIs such as Codex when tmux is absent.
- A scroll region whose top margin is terminal row zero owns real terminal history even when its bottom margin leaves an input or status area on screen. Rows removed from that region must enter bounded scrollback. A region that starts below row zero must not enter generic scrollback.
- Preserve `primaryTopAnchoredScrollRegionRetainsActualCodexStyleOutput` and its one-byte transport-chunk counterpart. Do not weaken their requirement that all 200 numbered rows survive in order and that rows below the scroll margin stay unchanged.
- Preserve the opt-in real USB `SshRealEndToEndTest` Codex path. It must open actual Codex first, wait for the input box, ask Codex itself for 200 numbered lines, verify every marker from `CODEX_SCROLL_001` through `CODEX_SCROLL_200` in order, and dispatch real `MotionEvent` drags until row 001 is visible.
- Never substitute output produced by the shell before Codex starts, a synthetic controller workload, zooming, parser-only checks, or the presence of only rows 001 and 200 for the real Codex acceptance path.
- The bottom viewport must initially contain row 200 and exclude row 001. A gesture toward older history must reduce `scrollY`; passing only because row 001 was already visible is invalid.
- Run real terminal device tests only on the authorized USB target. Never run them on the Wi-Fi foldable. See `docs/terminal-codex-scrollback-regression.md` for the evidence contract and exact test layers.
