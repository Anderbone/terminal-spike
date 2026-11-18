---
status: active
updated_at: 2026-11-16
---

# Validation and next steps

The [August–September record](archive/2026-09-07-validation-and-release-gates.md) preserves the
earlier programme's evidence and then-open gates. Use the maintained contracts below for work on
the repository. Historical unapproved-release or separate-APK statements retain their dated scope.

## Source verification

Inspect the worktree and identify the reviewed inputs before relying on past results. Preserve
concurrent work and state which inputs were excluded. Run `scripts/verify-android.sh` for the
shared local/CI unit-test, lint and build gate; [DEVELOPING](DEVELOPING.md) explains the workflow.
Reuse passing evidence for unchanged inputs and rerun affected checks after a fix.

Follow [AGENTS.md](../AGENTS.md) for authorized device selection, explicit ADB targeting,
debug-key continuity and preservation of app data. The Wi-Fi foldable receives a completed build
for install/launch verification and is excluded from tests and incremental installs. Device and
real SSH/Mosh/Codex acceptance must identify their actual scenario and artifact.

For terminal history work, preserve the real-Codex, ordered-marker, first-gesture, zero-wheel,
pixel-drag/fling and saved-Mosh contracts in AGENTS and the linked terminal regression documents.
Controller, parser and emulator evidence each retain their tested scope.

## Architecture and provenance

[ADR-005](ADR-005-BUNDLED-MOSH.md) supersedes the separate-Mosh-APK design: the main APK includes
the private broker and workers, while `mosh-api` retains its bounded IPC contract and Apache-2.0
licence. [The Local Arch ADR](ADR-LOCAL-ARCH-RUNTIME.md) approves the separately executed PRoot
runtime. [DEPENDENCIES](DEPENDENCIES.md) and [notices](../THIRD_PARTY_NOTICES.md) record source
provenance and component terms.

## Release work

Use [AGENTS.md](../AGENTS.md), [CI and releasing](CI-AND-RELEASING.md) and
[PUBLISHING](PUBLISHING.md) for the requested release scope and required prepare/publish/promote
commands. Verify the Play tracks, GitHub release/source/checksums and download/website links with
the publication gate. Report Google-controlled review and availability separately. Hosted CI
waiting follows the explicit task's scope. This page does not authorize or perform a release.

## Last recorded candidate evidence

Interpret this machine-maintained block with its matching candidate manifest. It records the
tested source/artifacts; store publication and availability need their own evidence.

<!-- release-evidence-current:start -->
Current release evidence: app JVM `971` tests (`971` passed, `0` skipped, `0` failures/errors); Mosh API JVM `11` tests (`11` passed, `0` skipped, `0` failures/errors); Mosh extension JVM `8` tests (`8` passed, `0` skipped, `0` failures/errors); old-phone Android app `332` tests (`307` passed, `25` skipped, `0` failures/errors). The source and artifact hashes and any pending external gates are recorded in `build/release-evidence/candidate-manifest.json`.
<!-- release-evidence-current:end -->
