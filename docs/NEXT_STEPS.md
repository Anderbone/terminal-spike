---
status: active
updated_at: 2026-08-07
baseline_commit: 765380e
---

# Remaining work before Mosh

The app has moved beyond the original renderer spike. The pre-Mosh product foundation now includes:

- the native Canvas renderer, bounded scrollback, viewport anchoring, frame batching, fake workloads, and interaction tests;
- a bounded project-owned VT/xterm screen engine selected by ADR-002;
- password and imported-private-key SSH authentication;
- strict first-contact host-key verification, changed-key blocking, and trusted-key removal;
- up to four isolated SSH tabs with PTY resize, keepalives, bounded input queues, and lifecycle-owned jobs;
- Keystore/AES-GCM encrypted local profiles, private keys, snippets, and extra-key layout;
- actual renderer draw timing rather than display-callback FPS;
- a guarded exact-target wireless ADB update script.

No Mosh, GPL/AGPL component, SFTP, cloud sync, account, analytics, advertising, or remote AI feature is included.

## Current verification baseline

Host-side completion requires:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
```

Device completion additionally requires `connectedDebugAndroidTest`, a clean launch, the deterministic renderer workloads, and a disposable real SSH host. Device results must name the exact ADB serial/model and may not be inferred from a successful build.

## Priority 1 — Device validation and fixes

Run the full instrumentation suite on the explicitly selected wireless phone. Verify:

1. fresh install and upgrade install both launch;
2. keyboard tap versus drag/fling behavior and IME resizing;
3. settings encryption round trip and private-key encryption test;
4. profile/snippet/key-layout CRUD and process restart;
5. host-key first contact, matching reconnect, changed-key block, and intentional removal;
6. password SSH and unencrypted/encrypted private-key SSH against a disposable host;
7. multiple SSH tabs, close/disconnect, rotation, background/foreground, and PTY resize;
8. tmux, vim, top/htop, Codex-style streaming output, alternate screen, colours, Unicode, and bracketed paste;
9. no crash, ANR, secret-bearing log line, or permissive host verifier.

Any device-only regression found here outranks new feature work.

## Priority 2 — Terminal conformance gaps

Extend only from project-authored or standards-derived fixtures. Preserve bounded parsing and JVM determinism.

- Add versioned golden traces for representative shell, tmux, vim, progress, resize, and malformed-stream cases.
- Extend the implemented legacy/SGR wheel reporting only after defining selection, button, and drag interaction semantics.
- Decide OSC 8 hyperlinks and OSC 52 clipboard behavior with security/user-consent rules before implementation.
- Expand DEC mode and query coverage based on observed application requirements, not speculative completeness.
- Improve Unicode width/grapheme tables and resize reflow without moving cells into Compose state.
- Add selection/copy/paste UI with bounded content and explicit multiline-paste protection.

## Priority 3 — SSH resilience and key lifecycle

- Add generated test keys for every format/algorithm actually advertised as supported.
- Add rename and profile-to-identity defaults without ever storing a passphrase.
- Decide manual reconnect UX. Password retention remains explicit opt-in; automatic reconnect must not silently broaden that consent.
- Add clearer typed disconnect/authentication errors without exposing server output or secrets.
- Review Android background behavior before offering persistent sessions or foreground-service behavior.
- Keep agent forwarding and SFTP out of scope unless separately approved.

## Priority 4 — Reproducible performance evidence

Create a device harness for exact-target scenarios:

- idle;
- preload 100,000 and fling;
- 5,000 lines/s following;
- 5,000 lines/s held above the bottom;
- 60 full-screen updates/s;
- parser-heavy alternate-screen trace.

Collect `gfxinfo`, process memory, device/API/refresh rate, thermal state, scenario duration, and build revision. Keep raw captures ignored and check in only privacy-safe summaries. Add Macrobenchmark and Baseline Profile modules only after these scenarios are stable.

## Priority 5 — Release hardening

- Review the resolved runtime graph, notices, shrinker behavior, manifest exports, backups, screenshots/clipboard, and store disclosures.
- Test accessibility, hardware keyboards, common IMEs, landscape, tablets/foldables, low-memory recovery, and network transitions.
- Keep signing material and SDK paths outside Git.
- Do not add telemetry or crash reporting without a separate privacy/product decision.

## Mosh gate

Mosh work starts only after the device and terminal gates above are credible.

The first Mosh deliverable is an ADR/research report, not production integration. It must verify the exact upstream licences and source obligations, native/NDK and cryptographic dependencies, server bootstrap, UDP roaming, Android background/battery behavior, IPv4/IPv6, and state-model integration.

A separate free/open-source APK may be evaluated as a distribution boundary, but it is not described as a licence bypass. The analysis must cover whether the programs are genuinely separate given both the IPC mechanism and the semantics exchanged. No GPL/AGPL/native Mosh code enters the paid app or product branch before an explicit legal and architecture decision.

## Immediate next action

Finish the exact-target wireless device run, fix what it reveals, and record the results. After that, take terminal conformance gaps in evidence order before starting the Mosh ADR.
