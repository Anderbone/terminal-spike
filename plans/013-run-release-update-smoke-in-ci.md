# Plan 013: Run the signed release-like update and real-SSH smoke in CI

> **Executor instructions**: Implement a hermetic CI caller for the existing release smoke. Use an
> ephemeral acceptance key created inside the job; never use production signing material. Do not
> upload the key, password, signed APK, fixture key, or raw logs. Preserve exact AVD identity checks
> and fixture cleanup. Record hosted evidence only after an actual workflow run.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- .github/workflows/android-ci.yml app/build.gradle.kts integration-tests/openssh/release-app-install-update-smoke.sh integration-tests/openssh/README.md scripts/tests docs/PUBLISHING.md`
> The worktree was dirty when planned; review unstaged changes against the facts below.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: Plans 009 and 011
- **Category**: tests / CI / release
- **Planned at**: commit `93d070a`, 2026-09-02
- **Execution status**: IN PROGRESS — hermetic orchestration, local real acceptance, and CI wiring
  are green; the required first hosted run cannot occur without commit/push authority.

## Why this matters

The release-like smoke exercises the shipped Connections UI, minified APK, real SSH, update
install, restart, data retention, extension absence, and password non-retention. It has no caller
outside manual commands, so a regression can merge while hosted debug-runtime jobs pass. A
hermetic CI job closes that repeatability gap without confusing an ephemeral acceptance signature
with production or Play signing.

## Current state

- `integration-tests/openssh/release-app-install-update-smoke.sh:82-114` accepts only the exact
  disposable AVD and verifies emulator/package/version/certificate identity.
- `integration-tests/openssh/release-app-install-update-smoke.sh:377-417` resets the fixture, uses an
  ADB reverse tunnel, proves real SSH before/after `install -r`, and cleans up on exit.
- `integration-tests/openssh/README.md:82-99` states that one APK proves same-certificate reinstall
  and data retention, not migration from an older version.
- `scripts/tests/test_release_app_update_smoke.py:114-177` covers serial, AVD, certificate,
  package/version, fixture, install, cleanup, and current-UI failure contracts.
- `.github/workflows/android-ci.yml` builds unsigned release surfaces and runs debug emulator and
  OpenSSH jobs, but never calls the release smoke.
- `app/build.gradle.kts:799-856` wires release APK/AAB packaging verification into assemble,
  bundle, and check tasks.

Conventions: pin Actions by immutable SHA, use stable Android images, own AVDs with repository
scripts, use strict dependency verification, upload only sanitized short-lived evidence, and avoid
new third-party emulator actions.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Host tests | `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` | all pass |
| Workflow syntax | `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/android-ci.yml")'` | exit 0 |
| Shell syntax | `sh -n integration-tests/openssh/release-app-install-update-smoke.sh` | exit 0 |
| Signed local build | export four temporary documented `TERMINAL_SPIKE_RELEASE_*` values, then `./gradlew :app:assembleRelease :app:bundleRelease` | build successful |
| Full gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | build successful |

## Scope

**In scope**:

- `.github/workflows/android-ci.yml`
- one repository-owned release-smoke orchestration script under `scripts/`, if needed
- the existing release smoke only for reusable tested CI compatibility, never weaker assertions
- host tests under `scripts/tests/`
- OpenSSH README, publishing/status docs, and Plan 005/009 evidence

**Out of scope**:

- production, upload, or Play App Signing keys
- Google Play upload or track mutation
- Mosh extension publication or installation in this extension-absent SSH gate
- claiming same-version reinstall proves an older-version database migration
- uploading signed APKs, key material, raw logs, or fixture secrets
- physical devices

## Git workflow

Preserve unrelated dirty changes. Do not commit, push, or open a PR without operator authorization.

## Steps

### Step 1: Add hermetic release-smoke orchestration

Create one script that owns a temporary `ANDROID_AVD_HOME`, stable API 35 image, AVD named exactly
`terminal-spike-release-test`, exact emulator serial, bounded boot/package-manager waits, and all
cleanup. Generate a random acceptance password and temporary PKCS12 key in a mode-0700 directory;
pass the four signing values only through the build environment. Build the current release APK and
AAB with strict dependency verification, run both packaging verifiers, verify signatures, then call
the existing smoke with the signed APK as initial and update input.

Never print the password, keystore arguments, private material, or absolute temporary paths. Remove
the signing workspace on every exit. The smoke remains responsible for exact AVD re-verification
and fixture teardown.

**Verify**: a local invocation reports verified APK/AAB packaging and one
`RELEASE_APP_UPDATE_SMOKE` result with `ssh_before=pass`, `ssh_after=pass`, `data=preserved`,
`credential=prompted_after_update`, and `extension=absent`; no emulator, fixture, or signing
workspace remains.

### Step 2: Test every orchestration failure boundary

Using fake SDK, keytool, Gradle, signing, Docker, and smoke commands, add Python tests for invalid
serial/name, key-generation failure, partial signing input, build failure, unsigned/mismatched
artifact, AVD boot failure, smoke failure, signal exit, and cleanup. Assert output contains no
acceptance password, fixture credential, LAN address, or private-key block.

**Verify**: focused new host tests and the complete `scripts/tests` suite pass.

### Step 3: Wire a bounded CI job

Add a job after fast verification. Prefer required pull-request/push execution if measured runtime
fits the workflow budget; otherwise make it scheduled/manual and document that limitation. Reuse
pinned checkout/setup actions and repository SDK tooling. Upload only a sanitized status/result
file, not signed artifacts. Run Docker teardown in an `always()` step in addition to script traps.

**Verify**: workflow YAML parses, action references remain immutable SHAs, and provenance host tests
recognize the new job and its no-artifact/no-secret contract.

### Step 4: Obtain hosted evidence and reconcile claims

After an authorized commit/push or dispatch, inspect the exact job conclusion and sanitized
artifact. Do not mark done from local emulation of Actions. Update publishing/status docs with the
run URL/commit, API image, result, and explicit ephemeral-acceptance-signing limitation.

**Verify**: hosted job is green; retained artifacts contain none of the banned values/files; local
full Gradle gate and `git diff --check` pass on the same source.

## Test plan

- Host tests for signing/AVD/build/smoke failures and unconditional cleanup.
- One local real API 35 release-like run with real SSH before and after reinstall.
- CI provenance checks for pinned actions, stable image, strict dependency verification, and no
  APK/keystore upload.
- One actual hosted workflow run tied to the candidate commit.

## Done criteria

- [ ] CI builds a minified APK/AAB with a fresh ephemeral acceptance identity and verifies both.
- [ ] The black-box release/update real-SSH smoke passes in the hosted job.
- [ ] The Mosh extension is absent throughout the SSH proof.
- [x] No key, password, signed APK, fixture private material, or raw log is retained/uploaded.
- [x] Emulator, Docker fixture, and signing workspace are removed on every exit path.
- [ ] Host tests, workflow validation, full Gradle gate, and one actual hosted run are green (all
  local portions are green; the first hosted run remains pending).
- [x] Documentation does not call the result production signing or older-version migration.

## STOP conditions

- CI cannot keep acceptance signing material out of logs/artifacts after two diagnosed attempts.
- The job would require a production secret or public network fixture.
- Stable API 35 images are unavailable; do not substitute preview/RC images.
- Passing requires weakening package/version/certificate/AVD checks or allowing Mosh installation.
- No commit/push/dispatch authority exists for hosted proof; leave hosted status pending.

## Maintenance notes

This complements debug runtime CI; it does not replace it. When versioned upgrade migrations need
proof, supply separately reviewed older signed input and use the smoke's two-APK contract—never
relabel same-version reinstall as migration coverage.

## 2026-09-02 local execution evidence

- `scripts/run-release-update-ci-smoke.py` rejects inherited signing inputs, creates a random
  mode-0700 PKCS12 workspace, builds with strict dependency verification and no persistent
  configuration cache, and compares both APK and AAB certificate fingerprints to its generated
  identity before starting the emulator.
- The local CI-equivalent real run passed the exact `terminal-spike-release-test` API 35 AVD and
  existing black-box shipped-UI gate: extension-absent real SSH before install replacement,
  same-certificate `install -r`, preserved non-secret data, password re-prompt, and real SSH after.
  This is explicitly same-version reinstall under ephemeral acceptance signing.
- Cleanup left one 510-byte `release-smoke-result.txt` and removed the PKCS12 key, private build and
  smoke logs, signed APK/AAB, temporary AVD, and Docker fixture. The retained output contains no
  fixture credential, address, private-key marker, key path, or artifact name.
- Ten focused orchestration tests cover dry-run/provenance, stable-only build-tool selection,
  external/partial signing input,
  key-generation failure, build and missing artifacts, unsigned/mismatched APK/AAB, boot and AVD
  identity failures, smoke failure/missing assertions, signal cleanup, and status-only retention.
- The required PR/push `release-update-e2e` job uses only SHA-pinned actions, repeats Docker teardown
  in an `always()` step, and uploads only the sanitized status file. No hosted result is claimed;
  commit/push/dispatch authority was not provided.
- The complete script suite passed 86 tests, workflow YAML parsed with seven jobs, and the full
  Gradle gate passed 475 tasks (`test`, `lint`, debug/release builds, Android-test APKs, and
  benchmark assembly).
