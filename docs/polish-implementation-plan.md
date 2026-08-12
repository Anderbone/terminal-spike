status: active
created_at: 2026-08-08T10:42:15.487Z
updated_at: 2026-08-08T11:50:29.310Z
done_at: none
independent: yes
dependencies: none

# Terminal Spike paid-release completion plan

## Open Questions

- None. The product brief and repository rules resolve the implementation choices needed to begin. External signing/store publication and a live disposable SSH/Mosh server are validation inputs, not reasons to defer local implementation.

## Goal

Turn the current early SSH terminal into a polished, reliable, local-first Android application suitable for paid public release while preserving the existing application ID, name, user data, strict SSH trust path, native Canvas terminal renderer, bounded hot path, and test fixtures.

Completion is defined by the full checklist in `IMPLEMENTATION_STATUS.md`, not by a UI mock-up, a subset build, or documentation alone.

## Product and visual direction

- Visual thesis: a calm, dark-first terminal workspace with ink-like surfaces, crisp type, a single cool mint action accent, and dense information arranged as deliberate lists rather than dashboard tiles.
- Content plan: Workspace leads with live sessions and useful reconnect targets; Terminal is the direct second destination; the secondary Connections catalogue owns host/key/snippet operations; Settings exposes progressively disclosed configuration and security context.
- Interaction thesis: use short shared-axis destination transitions, compact selection/connection-state motion, and direct press/reorder feedback; avoid ornamental animation and never animate terminal content through Compose.

## Current State

- One native Android application module uses Kotlin, Compose, and a custom Canvas `View` renderer.
- The project-owned bounded VT engine, viewport, scrollback, frame batching, workload fixtures, strict known-host verification, JSch password/private-key SSH, encrypted local settings, and up to four ViewModel-owned SSH tabs are useful foundations.
- The top-level shell is still Workspace / Terminal / Tools. Workspace exposes Known Hosts and Renderer Lab. Tools is a large debug-like management surface.
- The encrypted aggregate settings format is version 3 and lacks the target relational model, global preference hierarchy, full credential abstraction, portable backup, and migrations to stable UUIDs.
- Live sockets are owned by `TerminalSpikeViewModel` and are destroyed in `onCleared`; no foreground service or ongoing notification exists.
- No Mosh API/application, backup format, biometric/app lock, adaptive navigation, selection/link UI, raw/text mode control, or release-ready settings hierarchy exists.

Detailed evidence is maintained in `docs/current-state-audit.md`.

## Scope

- SSH and optional separately installed Mosh sessions.
- Multiple active terminal sessions and reliable visible/background ownership.
- Hosts, SSH credentials/keys, known hosts, snippets, terminal profiles, and keyboard profiles.
- Native-rendered terminal correctness, selection, links, input, touch, scroll, tmux, and performance.
- Local security, privacy controls, and manual encrypted SAF backup/restore.
- Compact/expanded Compose application UI, accessibility, documentation, notices, and release verification.

## Non-Goals

- SFTP or a file browser.
- Port-forwarding dashboard.
- Accounts, teams, subscriptions, social/community UI, analytics, advertising, telemetry, crash upload, AI, custom cloud, or direct Drive API.
- Bundling Mosh native/GPL code in the main APK.
- Replacing the SSH engine or native terminal renderer without measured correctness/security evidence and an accepted licensing decision.

## Architecture decisions

1. Keep the main project package-oriented while it is small. Extract only `mosh-api` and `mosh-extension`, whose packaging/licensing/security boundaries require real Gradle modules.
2. Add Room 2.8.4 with KSP 2.3.10 for relational product data. It is stable, supports the existing minSdk, and has a mature migration/test path. Export every schema and prohibit destructive fallback.
3. Use stable Proto DataStore 1.2.1 with protobuf Gradle plugin 0.9.5 and lite runtime/protoc 4.32.1, a checked-in schema, and explicit migrations for typed global preferences while keeping secret payloads outside it. Migrate the legacy encrypted aggregate once, transactionally and idempotently.
4. Keep Android Keystore plus AES-GCM for device-bound credentials behind `CredentialStore`; bind ciphertext to record identity/type/version as associated data.
5. Preserve JSch 2.28.3 and strict `KnownHostStore`; add targeted authentication, testing, error, keepalive, and reconnect layers rather than replacing transport.
6. Move session runtimes into an application process `SessionForegroundService`. Start it only from a visible user connection action and declare `specialUse` with the explicit subtype “active user-started remote terminal sessions.”
7. Use Android's Storage Access Framework for backup, custom fonts, private-key import, and transcript save. Never request broad storage or Google account access.
8. Use PBKDF2-HMAC-SHA256 with a per-backup salt and device-benchmarked floor, followed by AES-256-GCM. This avoids adding an unaudited/native KDF dependency while meeting the brief's explicit fallback.
9. Keep terminal parser/model/I/O off the Compose hot path; frame-limit immutable renderer updates and continue bounding all queues, histories, control strings, and streams.
10. Use an explicit, signature-verified AIDL service and `ParcelFileDescriptor` pipes for the optional Mosh extension. The main app performs SSH bootstrap and passes no saved SSH credential material.

## Implementation Tasks

### Slice 1 — Evidence and foundations

- Repair the existing Android-test compile baseline.
- Create the status, audit, architecture, security, backup, and Mosh protocol documents.
- Add stable relational/global models, Room/DataStore infrastructure, schema exports, and legacy v1–v3 migration.
- Use AGP 9.3 built-in Kotlin with KSP 2.3.10; do not add `kotlin-android`, kapt, Room3, `room-ktx`, SQLCipher, or destructive migration fallback.
- Add `CredentialStore`, redaction, and failure-safe tests.
- Compile/test/lint/build and install/launch on every authorized phone.

### Slice 2 — Product shell and Connections

- Use Workspace / Terminal / Settings as the three user-requested primary destinations, with Connections as an owner-aware secondary catalogue that returns to its entry surface.
- Add compact/expanded navigation and design-system primitives.
- Implement Workspace session/pinned/recent content and Connections Hosts/Keys/Snippets search/edit flows.
- Remove release access to renderer workloads and move debug controls to Developer settings.
- Add UI/accessibility tests and device walkthroughs.

### Slice 3 — Settings and personalization

- Implement appearance, licensed themes/fonts, terminal and keyboard profiles, security, backup, Mosh status, About, and debug-only Developer categories.
- Wire live/persistent values into the app shell and native renderer without introducing terminal Compose state.
- Update dependency/licence notices for every bundled asset/library.

### Slice 4 — Terminal interaction

- Compact session chrome and overflow actions; OSC titles; selection/copy/link/find/transcript.
- Editable accessory profiles with modifier state/repeat/haptics.
- Explicit Raw/Text `InputConnection` behavior with composing-only-on-commit semantics.
- Auto/local/remote mouse scroll modes, two-finger override, bounded alternate history, and tmux scenarios.
- Add parser/input/gesture/trace tests and before/after device measurements.

### Slice 5 — Reliability, background, and backup

- Service-owned sessions, notification actions/privacy, connectivity-aware bounded reconnect, keepalive, and background-health diagnostics.
- Versioned authenticated SAF backup with preview/conflict transactions/recovery and comprehensive corruption/migration tests.
- Validate Android 13–16 behavior available on connected devices/emulators.

### Slice 6 — Optional Mosh application

- Accept a detailed licensing/build ADR based on official upstream sources.
- Add permissive API module, secure plugin manager, SSH bootstrap parser, fake extension, and death/rebind tests.
- Add separately installed extension application with pinned reproducible native build, required GPL source/notices, real pipes, and distinct APK artifact.
- Prove the main runtime/dependency graph contains no native/GPL Mosh implementation and works when the extension is absent.

### Slice 7 — Release closure

- Complete accessibility/adaptive UI, performance fixtures/measurements, R8/content/security review, documentation, CHANGELOG, and notices.
- Run all host/device/integration gates and perform a requirement-by-requirement completion audit.

## Guardrails

- Preserve application ID `com.yanjiyu.terminalspike`, app name `Terminal Spike`, user data, signing assumptions, and SDK paths/secrets outside Git.
- Use only stable dependency versions and record every direct dependency/licence obligation before completion of the slice that adds it.
- No GPL/AGPL component enters the main application packaged graph.
- No unknown SSH key is accepted silently; changed keys always block.
- No credential, terminal transcript, snippet, Mosh key, or complete connection string enters ordinary logs.
- No fake controls, placeholders, inert menu items, or misleading “coming soon” states.
- No source copied from other terminal applications.
- No per-cell/per-line Compose state, unbounded terminal storage, per-byte UI publishing, or main-thread network/disk I/O.
- Review each slice diff for unrelated work and keep release builds free of developer surfaces.

## Verification

Run after every substantial slice. The wrapper can be launched by JDK 17 or newer, while the checked-in daemon criteria requires an installed JDK 25 so lint and the rest of the Android toolchain execute consistently; application compilation still targets Java 17:

```bash
./gradlew test
./gradlew lint
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew assembleDebugAndroidTest
```

Run applicable connected suites and exact-target manual checks after every green code slice. Resolve targets from `adb devices -l`, install by exact serial, launch `com.yanjiyu.terminalspike/.MainActivity`, verify it is foreground on every authorized USB and Wi-Fi phone, and report offline, unauthorized, or failed targets. Phase 11 additionally assembles and independently installs the extension APK; release closure also installs a locally signed release artifact without the extension.

Final evidence must include database/settings migrations, backup cross-install round trip, main-without-extension behavior, dependency/runtime graph review, current-device performance measurements, and every definition-of-done item in `IMPLEMENTATION_STATUS.md`.
