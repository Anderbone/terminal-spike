status: done
created_at: 2026-08-08T10:42:15.487Z
updated_at: 2026-08-08T11:50:29.310Z
done_at: 2026-08-08T11:50:29.310Z
independent: yes
dependencies: none

# Current-state audit

## Open Questions

- None. The repository and completion brief provide enough evidence to begin. Device/network/server-dependent claims remain unproven until their explicit validation runs.

## Audit basis

- Starting revision: `ff8435b` on `main`, matching `origin/main`.
- Starting worktree: clean.
- Project guidance: `AGENTS.md`, README, both ADRs, `docs/ARCHITECTURE.md`, `docs/PERFORMANCE.md`, `docs/NEXT_STEPS.md`, dependency inventory, notices, Gradle configuration, and all current test source.
- Product brief: 1,555-line pasted specification read in full.
- Source inspected: the complete main/test/androidTest file inventory, manifests/resources, direct dependencies, UI/session/data/security/SSH/terminal classes, and release/debug configuration.
- During the audit, a separate concurrent in-scope change set appeared in the shared worktree for a paged accessory deck and buffered Compose text input. It was not part of the clean baseline. It is preserved and described as in progress, not treated as verified baseline functionality.

## Toolchain and dependency baseline

| Item | Current value |
|---|---|
| Application ID / namespace | `com.yanjiyu.terminalspike` |
| App name | Terminal Spike |
| Modules | `:app` only |
| min / target / compile SDK | 26 / 37 / 37 |
| Java toolchain | 17 |
| Android Gradle Plugin | 9.3.1 stable |
| Gradle wrapper | 9.5.0 stable |
| Kotlin / Compose compiler plugin | 2.4.10 stable |
| Compose BOM | 2026.04.01 stable |
| UI | Jetpack Compose Material 3 around custom Android `View` |
| SSH engine | mwiede JSch 2.28.3, Revised BSD/ISC components |
| Runtime coroutines | kotlinx-coroutines-android 1.10.2 |
| Database / DataStore | None |
| Terminal engine | Project-owned `VtTerminalEngine`; no terminal runtime dependency |
| GPL/AGPL packaged dependency | None |

All declared versions are stable. Existing direct dependencies and JSch notices are recorded. The concurrent input patch adds Compose Foundation through the BOM and updates the dependency/notices documents, but that change requires the current full gate before it is accepted.

The shell initially had no `java`/`JAVA_HOME`. The repository-compatible JDK is `/home/jiyu/.cache/terminal-spike-tools/jdk`; all successful baseline Gradle runs used it without writing an SDK/JDK path into Git.

## Initial build and test baseline

| Command | Result | Evidence |
|---|---|---|
| `./gradlew testDebugUnitTest` | Pass | 8.863 s; 26 tasks up to date |
| `./gradlew lintDebug` | Pass | 1.703 s; one executed, 28 up to date |
| `./gradlew assembleDebug` | Pass | 1.087 s; debug APK 12,828,043 bytes |
| `./gradlew assembleRelease` | Pass | 43.844 s; unsigned shrunk APK 1,455,564 bytes |
| Initial `./gradlew assembleDebugAndroidTest` | Fail | stale `assertDoesNotExist` top-level import in `MainScreenSmokeTest.kt` |
| Repaired `./gradlew assembleDebugAndroidTest` | Pass | obsolete import removed; 1.568 s; fresh test APK 1,072,369 bytes |

The initial Android-test failure pre-dated product work and is recorded separately. The member assertion call remains valid with the BOM-managed Compose test API. Concurrent edits landed after that repair, so the full current worktree still needs a fresh gate.

Initial `adb devices -l` found two authorized targets:

- USB: `RZCW81JZ9CP`, Samsung `SM_S911B`.
- Wi-Fi: `adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp`, Samsung `SM_S936U`.

No baseline APK was installed because the initial Android-test assembly was not green. After each code slice is green, the exact current target list must be resolved again and every authorized target updated/launched.

### Phase 1 closure

After the documentation/checklist repair and concurrent bounded-input/About changes stabilized, the binary worktree diff hash was captured as `d997f8ca0c8cf8a72ec2ef2721b6ff0c42f4af89587fbccb3b4f1e53cde26c85` before and after validation.

- `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest`: passed in 21 s.
- Current debug APK: 12,643,074 bytes.
- Current unsigned shrunk release APK: 1,591,959 bytes.
- Current Android-test APK: 1,089,448 bytes.
- `connectedDebugAndroidTest`: 15/15 passed in 27 s on exact serial `RZCW81JZ9CP`, model `SM-S911B`, Android 16.
- Exact-serial `adb install -r`: success.
- Cold `MainActivity` launch: status `ok`, 1,033 ms total; `dumpsys activity activities` verified it as `topResumedActivity`.
- The Wi-Fi target present at initial baseline (`adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp`, `SM_S936U`) was absent from the fresh target list. It was not installed or reported as updated.

This proves the Phase 1 repository/device baseline only. It does not prove the incomplete product phases or real SSH/Mosh/server scenarios listed below.

## Architecture baseline

### Application/UI

- `MainActivity` owns an `AndroidViewModel` and hosts one `TerminalSpikeScreen` under a dark-only Compose theme.
- `TerminalSpikeScreen` uses local enum navigation and directly switches Workspace / Terminal / Tools.
- `TerminalSpikeViewModel` constructs concrete stores, JSch connections, engines, and controllers. No constructor-injected repository/application container exists.
- UI state is immutable `StateFlow`, but it mixes benchmark, live session, connection, persistence, local tools, keyboard modifiers, and notices in one 900-line ViewModel.
- Edge-to-edge, `adjustResize`, basic status/navigation/IME padding, and lifecycle-aware Flow collection exist.
- No adaptive navigation rail/list-detail, window-size model, predictive-back model, or light/system theme exists.

### Live sessions

- SSH sessions have isolated `JschSshConnection`, `VtTerminalEngine`, `TerminalController`, bounded buffer, and coroutine job, without an app-imposed tab-count limit.
- Every live socket/runtime is stored in `TerminalSpikeViewModel.sshSessions` and closed in `onCleared`.
- Sessions survive navigation and Activity recreation only while the same ViewModel/process survives.
- There is no `Service`, foreground-service manifest entry/type/permission, notification channel/action, connectivity observer, reconnect coordinator, or background health screen.
- The selected connected session forces `keepScreenOn` outside terminal context with no user preference.

This contradicts the required service-owned lifecycle and is a Phase 9 architecture migration, not a cosmetic refactor.

### Terminal hot path

The core terminal direction is sound and must be preserved:

- project-owned Android-independent bounded VT/xterm engine;
- 100,000-line benchmark ring and 20,000-line per-SSH histories;
- separate bounded alternate history;
- immutable screen/scrollback publication into `TerminalController`;
- bounded incoming line queue and latest full-screen coalescing;
- one `Choreographer` drain per frame;
- custom hardware-accelerated Canvas view drawing visible rows plus two overscan rows;
- cached Paint/font metrics and pixel viewport/fling state;
- no per-cell/per-line Compose state.

Existing coverage includes incremental UTF-8, primary/alternate screens, cursor movement/save/restore, scroll regions, insert/delete/erase, origin/autowrap/cursor visibility, 16/256/true colour SGR, combining/coarse wide characters, application cursor keys, bracketed paste, focus reporting, resize, common queries, mouse tracking and legacy/SGR wheel encoding, plus malformed-input bounds.

Material gaps:

- OSC title/8/52 behavior;
- selection, copy/select-all, links, find, transcript;
- dirty-row publication and invisible-session frame suppression;
- exact grapheme/width/bidi/resize-reflow behavior;
- complete mouse-mode state, configurable routing, two-finger local override;
- recorded ANSI traces, Macrobenchmark/Baseline Profile, and current-device measurements.

No current evidence proves heavy-output responsiveness on target hardware.

## Data and security baseline

### Implemented

- `UserSettingsCodec` v3 is bounded and reads v1/v2, storing basic profiles, snippets, identity metadata, and one ordered accessory-key list.
- The aggregate settings payload is AES-256-GCM encrypted under an Android Keystore AES key and written with `AtomicFile`.
- Saved passwords are opt-in, separately AES-GCM encrypted, and bound to profile ID/host/port/username as associated data.
- Imported private keys are SAF-read, size-bounded, inspected by JSch, separately encrypted, and stored app-private; private key bytes/passphrase/password arrays are cleared on important paths.
- Android automatic/cloud/device-transfer backup is deliberately disabled.
- Known hosts are stored atomically in OpenSSH-style app-private storage.
- No production logging calls, telemetry, analytics, ads, cloud/account, or hidden sync integration were found.

### High-priority defects

1. Identity IDs are omitted when restoring `nextSettingsId`. Importing a key after restart can reuse an existing identity ID and overwrite its encrypted file.
2. Any settings read failure deletes the settings file; a `GeneralSecurityException` also deletes the Keystore alias. The test currently codifies destructive reset, contradicting explicit recoverability.
3. A different presented host-key algorithm for a known host is treated as a new first-trust key; only same-algorithm mismatches block.
4. JSch writer failure/queue overflow calls `close`, marks closure intentional, and can suppress a Failed state so UI remains falsely Connected.
5. Case-insensitive host editing can preserve a password whose exact-host AAD no longer authenticates.
6. Identity writes use `AtomicFile`, but reads bypass `openRead` recovery.
7. Metadata, credential files, and conflated settings writes lack an end-to-end transaction/flush boundary and can leave orphans or dangling references.

### Missing target data/security

- stable UUID/domain models and timestamps;
- relational database, exported schemas, foreign keys, migration tests;
- typed global settings store;
- credential entity/reference and `CredentialStore` abstraction;
- terminal/keyboard profiles, protocol, favourites, tags, startup/override/Mosh fields, recent sessions;
- known-host port/public record/first-seen/last-seen;
- key generation/rename/public-key copy and richer metadata;
- app lock, screenshot/notification privacy, clipboard clear, security summary, credential-clear flow;
- central redaction and typed technical error boundary;
- manual encrypted backup/restore.

## SSH baseline

Implemented:

- password, stored-password, imported private-key, and passphrase-protected-key paths supported by JSch;
- strict checking enabled;
- first-contact SHA-256 fingerprint prompt and same-algorithm mismatch block;
- PTY dimensions/resize, connect/channel timeouts, modern default algorithms, fixed 30-second protocol keepalive with three failures;
- bounded 256-item outgoing writer queue and clean explicit close path.

Missing or incomplete:

- keyboard-interactive flow;
- trust once and explicit different-algorithm replacement;
- mismatch old/new fingerprints;
- staged DNS/TCP/negotiation/trust/auth/channel test;
- typed/redacted technical detail instead of raw JSch message exposure;
- global/per-host keepalive and reconnect settings;
- connectivity-aware bounded backoff/cancel and honest new-shell semantics;
- startup command and per-host TERM/profile overrides;
- service/background integration and tests.

The existing SSH engine is retained; the audit found targeted correctness/lifecycle gaps, not evidence supporting a wholesale replacement.

## Product UI baseline

### Navigation and Workspace

- Top-level destinations and bottom labels are Workspace / Terminal / Tools, not the required three.
- Workspace is a grouped resource dashboard rather than a session home. It exposes Known hosts and Renderer lab.
- Renderer/benchmark session and performance controls are constructed for release; release strings include developer labels.
- Known hosts is not under Settings > Security.
- Theme/tokens are duplicated between workspace constants and the Material theme.
- Most user strings are hardcoded; `strings.xml` only contains the app name.

### Connections/tools

`LocalToolsScreen` provides basic profile CRUD, identity import/delete, known-host removal, snippet CRUD/send, and accessory-key editing, but:

- tabs are Hosts / Security / Keys / Snippets, where Keys means accessory keys;
- host rows contain inline Edit / Use / × and deletion is immediate;
- no search, favourite/recent/group/tag, protocol/status badges, row-connect, overflow, confirmation, or no-results state;
- host editor lacks authentication/profile selection, field-local errors, connection test, startup/terminal/keyboard/reconnect/keepalive/advanced/Mosh options, and unsaved protection;
- key generation/rename/copy/search/confirmed deletion is absent;
- snippet group/favourite/insert/copy/multiline confirmation and in-terminal access are absent.

### Settings

No Settings destination/hierarchy exists. App appearance, theme/font profiles, terminal options, keyboard profiles/presets, sessions/background, notifications, backup, security status/app lock, Mosh status, About/privacy/notices, and debug-only Developer categories are absent.

### Terminal/session UI

- Compact-ish horizontal tabs, state dot, direct Disconnect, Tools, and +SSH exist.
- Close is a 28 dp immediate destructive action.
- No protocol badge/menu/reconnect/duplicate/reorder/OSC title/find/clear/transcript/fullscreen/snippets/details.
- The painted NEW OUTPUT badge is not actionable for SSH.
- No output selection, handles, copy/select-all, or links.

### Keyboard/IME and scrolling

At the clean baseline:

- broad persisted accessory-key list and one-shot Ctrl/Alt exist;
- keys are two fixed compressed rows with 44 dp targets and stepwise reorder;
- direct input sends committed UTF-8 and does not send intermediate composing text;
- touch uses local scrollback without mouse tracking and bounded remote wheel sequences with tracking;
- ordinary gesture code does not send Up/Down shell-history keys.

The concurrent in-progress patch adds a two-page accessory area with a bounded Compose `TextFieldValue` and explicit bracketed-paste-aware Send. It is a useful partial composition path, but it is not yet the required visible Raw/Text toggle and has no multiline confirmation, CJK/dead-key device proof, clipboard action, locked modifiers, repeat/haptics, presets, drag reorder, collapse, two-finger override, or selectable Auto/Local/Remote scroll mode.

## Accessibility/adaptive/release baseline

- Some controls have content descriptions and the native terminal exposes one coarse semantic node.
- Several targets are below 48 dp; selected navigation semantics and critical-detail expansion are incomplete.
- No TalkBack, large-font, light mode, landscape, split-screen, foldable, expanded-width, gesture/three-button navigation, or visual-state suite exists.
- Release debug isolation is false: benchmark/renderer lab/performance UI is reachable and retained.

## Existing test evidence

Strong host-side coverage exists for:

- bounded queue, buffer, viewport/follow/trim anchoring;
- VT engine colors/modes/Unicode/malformed input;
- gesture tap/drag/fling routing and wheel sequence encoding;
- workload determinism and renderer stats;
- settings codec v1/v2 migration/bounds;
- Android encrypted settings/password/key round trips;
- known-host persistence and same-algorithm mismatch;
- session selection/count;
- basic Compose smoke and native touch smoke.

Missing coverage tracks the product gaps: UUID/database migrations, security failure recovery, different-algorithm trust, transport failure/reconnect, service/notifications, backup, host/key/snippet workflows, Settings/appearance/fonts/keyboard profiles, release-surface isolation, selection/links, explicit scroll modes/two-finger/tmux scenarios, IME composition/hardware keyboard, adaptive/accessibility/visual states, and device performance.

## Phase assessment

| Phase | Current assessment |
|---|---|
| 1 Baseline/audit | In progress; initial gates/audits complete, required docs and current-worktree/device revalidation underway |
| 2 Data/security | Useful encrypted baseline; target models/repository and several high-risk defects outstanding |
| 3 Navigation/design | Not implemented; old destinations and release debug surfaces remain |
| 4 Connections | Basic debug-style CRUD only; target behavior largely absent |
| 5 Settings | Not implemented |
| 6 Terminal UI/rendering | Strong hot-path foundation; product interaction/conformance/performance proof incomplete |
| 7 Keyboard/IME | Partial baseline plus unverified concurrent composer; required profile/mode/accessory behavior incomplete |
| 8 Scrolling/tmux | Auto-like low-level routing exists; selectable modes/two-finger/scenarios incomplete |
| 9 SSH/service | Working foreground SSH baseline; resilience/service/notification architecture absent |
| 10 Backup | Not implemented |
| 11 Mosh | Not implemented; official upstream/licensing/Android research completed and recorded |
| 12 Release | Not implemented; release currently exposes debug UI |

## Immediate implementation order

1. Stabilize and verify the concurrent buffered-input change; fix any instrumentation compile/runtime issues without broadening it.
2. Fix the identity-ID overwrite, destructive settings failure, different-algorithm host mismatch, transport false-connected state, hostname/AAD edit, and atomic identity recovery with regression tests.
3. Add stable product models and transactional Room/DataStore/legacy migration plus `CredentialStore`/redaction.
4. Implement the exact three-destination shell and complete one host-to-terminal vertical slice; move debug/security surfaces to their correct scopes.
5. Continue phases in `docs/polish-implementation-plan.md` and evidence-gate every checkbox in `IMPLEMENTATION_STATUS.md`.
