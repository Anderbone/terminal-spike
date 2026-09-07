# Plan 010: Enforce release input provenance and repository validators

> **Executor instructions**: Do not guess checksums or CI action revisions.
> Resolve them from authoritative upstream release metadata, record provenance,
> and stop if they cannot be independently verified.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- gradle/wrapper/gradle-wrapper.properties gradle/verification-metadata.xml .github/workflows/android-ci.yml app/build.gradle.kts scripts store-assets docs/DEPENDENCIES.md`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: Plan 009 if both edit the CI workflow
- **Category**: security / dependencies / DX
- **Planned at**: commit `93d070a`, 2026-09-02
- **Execution status**: DONE — all source, validator, and clean-cache strict
  verification criteria passed locally; hosted execution belongs to Plan 009.

## Why this matters

The Gradle wrapper, repository artifacts, and GitHub Actions are release build
inputs, but the wrapper lacks its published checksum, dependency verification
metadata is absent, and actions use movable major tags. Existing font, store-
asset, and evidence-parser validators also are not part of CI. A compromised or
silently changed input can therefore enter a nominally green public build.

## Current state

- `gradle/wrapper/gradle-wrapper.properties:3` pins the Gradle 9.5.0 URL but has
  no `distributionSha256Sum`.
- `settings.gradle.kts:1-15` permits Google, Maven Central, and Plugin Portal;
  `gradle/verification-metadata.xml` and lock state do not exist.
- `.github/workflows/android-ci.yml:21-32` references checkout/setup-java/
  setup-gradle by movable major tag.
- `scripts/verify-bundled-fonts.sh`,
  `store-assets/google-play/validate-store-assets.sh`, and
  `scripts/tests/test_assert_test_report.py` already provide deterministic
  validators that CI does not execute.
- `app/build.gradle.kts:800-836` shows the local convention: release packaging
  verification is attached directly to release/check tasks.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Wrapper validation | `./gradlew --version` | Gradle 9.5.0, exit 0 with checksum enforced |
| Dependency verification | `./gradlew --dependency-verification=strict help test assembleDebug assembleRelease` | exit 0; no unverified artifact |
| Existing validators | `scripts/verify-bundled-fonts.sh && store-assets/google-play/validate-store-assets.sh && python3 -m unittest discover -s scripts/tests -p 'test_*.py'` | all exit 0 |
| Full gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | exit 0 |
| Hygiene | `git diff --check` | exit 0 |

## Scope

**In scope**:

- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/verification-metadata.xml` and narrowly required Gradle settings
- `.github/workflows/android-ci.yml`
- `app/build.gradle.kts` task dependency for bundled-font verification
- tests for any new repository validator
- `docs/DEPENDENCIES.md` provenance/process notes

**Out of scope**:

- dependency upgrades
- new runtime libraries or repositories
- signing credentials, Play upload, or publishing
- disabling verification because one artifact lacks metadata

## Git workflow

Preserve unrelated work. Do not commit/push unless asked.

## Steps

### Step 1: Pin the Gradle distribution checksum

Obtain the SHA-256 for exactly `gradle-9.5.0-bin.zip` from Gradle's official
checksum endpoint/release metadata and independently compare it with the
downloaded distribution. Add `distributionSha256Sum` to the wrapper properties.
Do not regenerate or upgrade the wrapper.

**Verify**: `./gradlew --version` succeeds; changing one checksum character in a
temporary copy causes wrapper verification to fail.

### Step 2: Generate and review strict dependency verification metadata

Generate SHA-256 metadata for all configurations exercised by the full app,
Mosh API/extension, and benchmark gate. Review every component against the
version catalog/project modules and remove unrelated artifacts introduced only
by opportunistic configuration resolution. Prefer checksums; add trusted signing
keys only when their ownership is verified. Enable strict verification for CI
and documented release gates.

Run once with an empty Gradle cache in a disposable `GRADLE_USER_HOME` to prove
the metadata is complete. Never add a wildcard trust rule for an entire group or
repository merely to make the build pass.

**Verify**: strict dependency verification passes from the disposable cache.

### Step 3: Pin GitHub Actions immutably

Resolve each current action tag to its full 40-character commit SHA using the
official upstream repository. Replace movable tags with SHAs and retain comments
naming the human-readable release. Confirm the commit belongs to the intended
official repository/release before editing.

**Verify**: every `uses:` line is either a local action or matches
`owner/repo@<40 lowercase hex>`; workflow YAML parses.

### Step 4: Make existing validators mandatory

Add CI steps for bundled fonts, Google Play assets, and Python report-parser
tests. Add a small Gradle `Exec` verification task for the bundled-font script
and make app release packaging/check depend on it, following the existing
variant verification pattern. Keep the store listing validator in CI rather
than the APK build because listing assets are not application inputs.

**Verify**: alter one byte in a temporary font copy and prove the validator
fails, restore it, then run all validators and the full gate.

### Step 5: Document the provenance boundary

Update `docs/DEPENDENCIES.md` with the wrapper checksum source, strict metadata
review procedure, CI action pin/update procedure, and which validators protect
release assets. Do not add action/tool licences to app notices unless they are
actually distributed in the APK.

## Test plan

- Positive clean-cache strict verification.
- Negative wrapper checksum and font checksum checks using temporary copies.
- Existing ten report-parser unit tests.
- Store metadata/image validation.
- Workflow assertion that no movable external action tag remains.

## Done criteria

- [x] Gradle wrapper distribution is checksum-pinned.
- [x] All release-gate artifacts pass strict dependency verification from a
  disposable cache.
- [x] Every external CI action is pinned to a reviewed immutable commit.
- [x] Font, store-asset, and report-parser validators are mandatory in CI.
- [x] Release packaging directly depends on font verification.
- [x] Full local gate and `git diff --check` pass.

## 2026-09-02 execution evidence

- Gradle 9.5.0 is pinned by its official distribution SHA-256, and external
  workflow actions use reviewed 40-character commit revisions with readable
  version comments.
- `gradle/verification-metadata.xml` covers the full app, Mosh API/extension,
  Android-test, Orchestrator/Test Services, and benchmark graphs. A first empty
  cache exposed four plugin-resolution metadata omissions; their exact freshly
  downloaded SHA-256 values were reviewed and added. A second brand-new Gradle
  home then passed strict verification across 451 tasks.
- The comprehensive source gate passed app/Mosh unit tests, debug and release
  lint/build/package verification, both native Mosh ABIs, Android-test APKs,
  staged runtime-test utilities, and benchmark assembly.
- All nine bundled fonts, all localized Play metadata and graphics, 37
  repository script tests, workflow YAML parsing, and `git diff --check` passed.

## STOP conditions

- An upstream checksum/action commit cannot be verified from its authoritative
  source.
- Strict verification requires broad wildcard trust or ignoring a repository.
- Metadata contains a credential, local path, or private repository identity.

## Maintenance notes

Regenerate and review verification metadata only in the same change that updates
a dependency. Update action SHAs with readable version comments so automated
security tooling can propose reviewable changes without restoring movable tags.
