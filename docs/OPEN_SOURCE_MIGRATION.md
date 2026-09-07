# Open-source consolidation evidence

Updated: 2026-09-07

## Repository comparison

The main repository is `Anderbone/terminal-spike`, currently private. Its inspected baseline
is `224aaac`. The standalone repository is `Anderbone/terminal-spike-mosh-extension`, currently
public, at `4fe63fd4e02225a4ecba3b993a28bd9db72029be`. Its local checkout is clean and matches
GitHub's `main` revision. It also has a published `v1.0.0` source release, which must remain
accessible for existing binary recipients.

Comparing the standalone repository's 125 tracked files against the main repository found:

- 94 byte-identical files, including implementation, tests, upstream source archives, patches,
  native generation inputs and component notices;
- 11 differing build/documentation files; the main repository already uses newer SDK and
  extension version settings, so replacing them with the standalone copies would regress it;
- 20 standalone-only files, primarily public-facing documents and store listing materials.

The unique publication documents, listing assets and video are preserved under
[`archive/standalone-mosh-extension`](archive/standalone-mosh-extension/ARCHIVE.md). The import
contains 22 files, including overlapping historical documents needed to interpret the standalone
release. `IMPORT.json` records exact per-file hashes and the source commit. Runtime sources were
not overwritten. The archived workflow is documentation, outside `.github/workflows`.

## Publication audit performed

Gitleaks 8.30.1, downloaded from its official release with the archive checksum verified, scanned
all locally reachable main-repository Git history at baseline `224aaac`. It reported ten matches
in test sources: three uses of the fixed Mosh key in mocked bootstrap/pipe tests, four credential
record UUIDs, and three local UI recovery UUIDs. Those are test fixtures and identifiers, not
production authentication material. No history rewrite was performed.

All four existing main-repository Actions runs were downloaded and their full log archives
scanned with redaction enabled. Each had zero findings:

- `34146109527`
- `34143782926`
- `34143670317`
- `34143537087`

The latest run's `verify` job passed, but several device/emulator jobs failed; this is not evidence
that all hosted CI is green. Raw scanner reports and logs remain outside Git. An automated secret
scan is not a proof about screenshots, video frames, Actions artifacts or every possible secret.

## Local verification

`./gradlew --dependency-verification=strict test lint assembleDebug assembleRelease` completed
with `BUILD SUCCESSFUL` in 2 minutes 21 seconds (311 tasks). JVM reports contained app 976,
Mosh API 11 and Mosh implementation 8 tests, with zero failures, errors or skips. All 22 imported
files were rehashed successfully and `git diff --check` passed.

This was a check of the shared worktree during documentation consolidation. Concurrent
`MoshDisplayHistory` implementation/test edits were present and were not modified by this task.
It is neither an immutable release manifest nor proof of a single-APK integration, which has
not been implemented. No device installation or device test was run in this session.

## Final source verification and publication preparation

An isolated checkout of `224aaac` plus only the consolidation changes passed:

- `./gradlew --dependency-verification=strict --max-workers=2 test lint assembleDebug assembleRelease`
  in 1 minute 59 seconds (311 tasks); app 971, API 11 and extension 8 JVM tests, all passing;
- 96 Python script tests, including a new regression for API-key values emitted by emulator
  Google Setup Wizard logs;
- two byte-identical extension Corresponding Source archives, with the root GPL licence and
  consolidation ADR included;
- packaged main-app notices include the project licence and are verified by the release build.

All 46 tracked raster assets were visually reviewed as terminal demonstrations, settings and
store graphics. They contain visible demonstration host/system metadata but no visible passwords
or private-key material. All 12 unexpired hosted Actions artifacts were downloaded and scanned.
One API 28 emulator log contained a Google Setup Wizard API-key-shaped value. Artifact
`10027974496` was backed up locally (ZIP integrity verified), sanitized successfully with the new
redactor, and removed from GitHub before the visibility change. The remaining 11 artifacts had no
scanner findings. This does not claim that arbitrary binary payloads are exhaustively scanned.

The completed-build device check found only the authorized old `SM_S911B` on USB and wireless
transports. The Wi-Fi foldable `SM_F976B` was unavailable; no foldable installation/launch could be
performed and no device tests were run for this source-only change.

## Cutover status

The licence and source migration are prepared locally. Remaining publication steps are committing
and pushing the reviewed change, changing the main repository to public, verifying anonymous
source/licence access, enabling its private security-reporting endpoint, and adding a relocation
notice and archiving the standalone repository. Keep its existing `v1.0.0` source release available.

The owner requested one repository. Single-APK integration was an additional proposal, not an
accepted packaging change; [ADR-004](ADR-004-OPEN-SOURCE-APP.md) records that distinction. This
consolidation retains two APKs and independent Play versioning and does not publish to Play.
