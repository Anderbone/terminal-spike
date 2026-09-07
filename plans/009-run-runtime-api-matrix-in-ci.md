# Plan 009: Run Android runtime and OpenSSH E2E gates in CI across supported APIs

> **Executor instructions**: Build on the existing untracked CI workflow. Keep
> actual-Codex/tmux and personal-phone acceptance out of hosted CI; they remain
> exact-device gates. Do not turn a failed runtime test into an assumption skip.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- .github/workflows/android-ci.yml app/build.gradle.kts integration-tests/openssh app/src/androidTest`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: Plans 006 and 007 for the final SFTP E2E expectations
- **Category**: tests / CI
- **Planned at**: commit `93d070a`, 2026-09-02
- **Execution status**: IN PROGRESS — implementation and the complete local
  runtime matrix are green; the first hosted pull-request run remains pending.

## Why this matters

CI currently compiles 311 default instrumentation tests but executes none. The
app declares API 26 support, while current runtime evidence starts at API 33 and
production contains distinct API 26, 28, 29, and 33 branches. A public release
needs repeatable runtime evidence that does not depend solely on one developer's
phone or preconfigured AVDs.

## Current state

- `.github/workflows/android-ci.yml:38-56` runs JVM/lint/build tasks and only
  assembles Android-test APKs.
- `app/build.gradle.kts:621-628` declares `minSdk = 26`, `targetSdk = 37`.
- `AndroidAppLockAuthenticator.kt:50-54`, `TerminalTypefaceRegistry.kt:68-98`,
  and `AndroidMoshExtensionPlatform.kt:82-105` have API-boundary branches not
  exercised by the API 33-36 evidence.
- `benchmark/build.gradle.kts:30-35` is the local Gradle-managed-device exemplar.
- `integration-tests/openssh` provides a digest-pinned disposable server; real
  SSH/SFTP tests opt in through instrumentation arguments.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Workflow syntax | `ruby -e 'require "yaml"; YAML.load_file(".github/workflows/android-ci.yml")'` | exit 0 |
| Default device APK | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | exit 0 |
| OpenSSH fixture | `./integration-tests/openssh/smoke.sh` | exit 0 and fixture responds |
| Full local gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | exit 0 |

## Scope

**In scope**:

- `.github/workflows/android-ci.yml`
- `app/build.gradle.kts` managed-device declarations if used
- a checked-in hermetic emulator runner under `scripts/` and its tests
- focused compatibility tests under `app/src/androidTest`
- instrumentation argument plumbing for the existing OpenSSH/SFTP tests

**Out of scope**:

- actual Codex/tmux acceptance, physical phones, or fold installs
- public internet services or long-lived credentials
- Mosh UDP roaming in hosted CI
- lowering `minSdk` or removing compatibility code merely to shrink the matrix

## Git workflow

Preserve unrelated changes; do not commit/push unless asked.

## Steps

### Step 1: Define the compatibility matrix by branch boundary

Use official stable x86_64 images. Run the complete default suite on API 26 and
one modern pre-33 target (API 32), plus API 35. Add focused jobs on API 28 and 29
for biometric/font/signature boundary behavior and API 33 for typed
PackageManager/notification behavior. Keep API 34-36 covered by the existing
local/release device matrix until an official CI image is available; do not use
preview/canary images.

PR policy:

- required: API 26 full default suite and API 35 full default suite;
- required focused boundary suite: APIs 28, 29, 32, and 33;
- scheduled/manual: the complete suite on every stable API 26-36 image that is
  available, sharded as independent jobs.

Each job must start from a wiped disposable AVD and upload JUnit XML/logcat only
after redaction. A skipped default test is allowed only for its already-
documented platform assumption; unexpected zero-test runs fail.

**Verify**: local script dry-run prints the exact APIs, AVD names, and test
filters without starting an emulator.

### Step 2: Add a repository-owned emulator runner

Use the current `android sdk` installer (with `sdkmanager` only as a compatibility
fallback), `avdmanager`, `emulator`, and exact `adb -s emulator-*` commands rather
than adding a third-party CI action. Put `ANDROID_AVD_HOME` under
a temporary directory, wait for `sys.boot_completed`, disable animations, wipe
data, install exact debug/test APKs, run `am instrument -w -r`, capture its exit
code and final `OK (N tests)`, then always shut down and remove the temporary
AVD directory. Validate every resolved serial is an emulator before any install.

Add shell/Python tests for argument validation, timeout, zero tests, failure,
cleanup, and exact serial targeting using fake SDK tools.

**Verify**: runner tests pass and one local API 26 focused smoke completes.

### Step 3: Execute deterministic OpenSSH/SFTP E2E in a separate job

Start the existing Docker fixture, expose it only to the job host/emulator, and
run the existing real SSH password, imported-key, trust-change, and SFTP tests
on API 35 through an explicit ADB reverse tunnel to `127.0.0.1:22222`. Read the generated private key at
runtime and pass it only as an instrumentation argument; never print its bytes
or upload it. Tear the fixture down in an `always()` step and retain only
privacy-safe test reports.

The E2E job must require the expected named tests to run—an assumption skip is
a failure in this job.

**Verify**: CI-local invocation reports every named E2E test with zero skips and
zero failures.

### Step 4: Wire CI dependencies and artifacts

Keep JVM/lint/release build as the fast prerequisite. Runtime jobs may run in
parallel after APK artifacts are built once. Give each API its own timeout and
artifact name. Add workflow concurrency cancellation only across superseded
runs, not sibling matrix jobs.

**Verify**: a pull request run shows green build, API 26, API 35, boundary, and
OpenSSH jobs; scheduled workflow expands the full stable matrix.

## Test plan

- API 26/35: all default instrumentation tests, nonzero exact count.
- API 28/29/32/33: focused app-lock, font, clipboard, extension discovery,
  notification, Room/Keystore, and launch contracts.
- API 35 OpenSSH: password, imported Ed25519 saved-host flow, first trust,
  changed-key block, image side-channel upload, and SFTP operations.
- Runner unit tests: startup timeout, failure parsing, signal cleanup, and no
  physical serial acceptance.

## Done criteria

- [ ] Required PR CI executes—not merely assembles—instrumentation on API 26 and
  API 35.
- [x] Every distinct pre-33 compatibility branch has a focused runtime gate.
- [x] OpenSSH/SFTP E2E names run with zero skips/failures.
- [x] CI cannot target a physical device or upload fixture private material.
- [x] Full local gate and workflow validation pass.

## 2026-09-02 execution evidence

- The repository-owned runner uses Android Test Orchestrator 1.6.1 and Test
  Services 1.6.0 with `clearPackageData=true`; the 57-test host/script gate
  covers exact emulator targeting, bounded setup failures, line-local redaction, zero-test
  rejection, exact source/runtime membership and skip identities, installer fallback, and
  OpenSSH routing.
- Current full isolated suites passed on API 26 (`316/0/0/17`) and API 35
  (`316/0/0/13`). Focused boundary suites passed on API 28 (`33/0/0/2`), API 29
  (`33/0/0/2`), API 32 (`33/0/0/2`), and API 33 (`33/0/0/1`), where tuple order
  is tests/failures/errors/skips. Every full/boundary run also passed the checked-in exact
  membership and API-specific skip contract.
- The API 35 disposable-emulator OpenSSH/SFTP suite passed all five required
  tests with zero skips through the loopback ADB reverse tunnel. The initial
  guest-NAT attempt failed with `ENETUNREACH`; that red evidence was retained in
  `build/local-api35-openssh-orchestrated`, and the green evidence is in
  `build/local-api35-openssh-adb-reverse`.
- The same complete default suite passed on the exact model-checked authorized
  old `SM_S911B` (`312/0/0/10`). The foldable was not tested or installed.
- Source-side workflow and local evidence are complete. The first actual hosted
  workflow execution requires a commit/push or pull request and is therefore
  deliberately not claimed here.

## STOP conditions

- A required system image is preview/RC/nightly or unavailable from the stable
  SDK repository.
- Hosted virtualization cannot run the emulator reliably after two diagnosed
  attempts; report evidence instead of adding sleeps or weakening assertions.
- E2E setup would expose the fixture outside the CI host/emulator boundary.

## Maintenance notes

When `minSdk` or an API conditional changes, update the boundary matrix in the
same change. Keep physical actual-Codex/tmux acceptance separate: a synthetic CI
terminal cannot satisfy that contract.
