# Plan 017: Make Android 17 local-network access contextual and runtime-proven

> **Executor instructions**: Android 17/API 37 is a stable released platform and
> the app already targets it. Use only stable SDK/system images. Preserve public
> Internet SSH when local-network permission is denied, and never run this plan's
> device tests on either connected phone.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/yanjiyu/terminalspike/ui app/src/test app/src/androidTest scripts .github/workflows/android-ci.yml docs plans/009-run-runtime-api-matrix-in-ci.md`

## Status

- **Priority**: P0
- **Effort**: L
- **Risk**: MED
- **Depends on**: Plans 009 and 011
- **Category**: correctness / privacy / tests / CI
- **Planned at**: commit `93d070a`, 2026-09-03
- **Execution**: IN PROGRESS locally on 2026-09-03

Stable API 37.0 (`PreviewSdkInt=0`) is supported by the exact runner and
checked-in contract, required in the pull-request boundary matrix, and included
in the scheduled full matrix. The exact API 37 boundary passes 42/42 with zero
skips. Real system-dialog tests cover denial, grant, exact saved-host
continuation, Activity recreation, public-host bypass, and the system-mediated
Nearby SSH picker. A ViewModel-owned one-action coordinator retains memory-only
credentials across configuration change, rejects duplicate staging, and wipes
on denial or final teardown. Arbitrary hostnames are resolved off the UI thread:
any local answer requests permission, while public answers and resolution failure
do not cause a broad prompt. The host runner separately proves grant, original
process termination on revoke, explicit relaunch, and retained denial. A second
API 37 gate proves denied raw TCP and production SSH to a live RFC1918 endpoint,
then completes production SSH and SFTP against the same endpoint after grant.
Completed SSH over an actual public-Internet route while permission is denied
remains external evidence.

## Why this matters

The app targets SDK 37, where local-network traffic is blocked by default
without `ACCESS_LOCAL_NETWORK`. Yet runtime coverage stops at API 36 and the app
requests broad nearby-device access immediately at launch, before the user asks
to discover or connect to anything. Denial/revocation and the requirement that
ordinary Internet SSH continue to work are unproved.

Official contract: <https://developer.android.com/privacy-and-security/local-network-permission>
and <https://developer.android.com/about/versions/17/behavior-changes-17>.

## Original state

- `app/build.gradle.kts:619-624` sets both `compileSdk` and `targetSdk` to 37.
- `AndroidManifest.xml:4-6` declares Internet and local-network permissions.
- `TerminalSpikeScreen.kt:713-729` launches the local-network runtime request
  from `LaunchedEffect(Unit)` whenever API 37 permission is absent.
- `shouldRequestLocalNetworkPermission()` has three unit assertions but no real
  API 37 permission-dialog, denial, revocation, or socket coverage.
- `AndroidNearbySshDiscovery.kt:80-81` already uses API 37's
  `DiscoveryRequest.FLAG_SHOW_PICKER`, which is the privacy-preserving discovery
  route and does not require the broad permission for picker-mediated endpoints.
- `scripts/run-android-emulator-tests.sh:19,42`,
  `scripts/android-test-contract.json:426-438`, and scheduled CI at
  `.github/workflows/android-ci.yml:196-204` stop at API 36.

## Scope

**In scope**:

- local-network permission state and user-intent routing in the app UI/ViewModel
- nearby SSH discovery behavior on API 37
- direct SSH, SFTP, and Mosh LAN-connect permission handling
- API 37 instrumentation tests, exact suite contract, emulator runner, and CI
- documentation of denial/revocation and Internet-vs-LAN behavior

**Out of scope**:

- new discovery protocols, address-range scans, accounts, analytics, or cloud services
- weakening host-key verification or automatically falling back protocols
- preview/beta/RC SDK images
- physical-phone testing for this plan

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| API 37 dry run | `scripts/run-android-emulator-tests.sh --api 37 --suite boundary --dry-run` | stable API 37 plan printed |
| Runner tests | `python3 -m unittest discover -s scripts/tests -p 'test_*android*runner*.py'` | exit 0 |
| Focused JVM | `./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.ui.*'` | exit 0 |
| Full gate | `./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | `BUILD SUCCESSFUL` |

## Git workflow

Preserve unrelated dirty-tree work. CI edits remain local until the operator
explicitly authorizes a commit/push or pull request.

## Steps

### Step 1: Specify the permission decision table in tests

Replace the launch-only Boolean helper with a testable state machine around an
explicit user action. At minimum specify:

| Situation | Expected behavior |
|---|---|
| cold launch, permission absent | no prompt and no network attempt |
| API 37 system-mediated Nearby picker | no broad permission prompt |
| explicit direct LAN connection, permission absent | explain/request, then resume the exact pending action on grant |
| denial | show a stable actionable message; do not loop prompts |
| later revocation | return to the same guarded state without crash |
| public Internet endpoint while denied | connection remains available |
| API 26-36 | unchanged Internet-permission behavior |

Do not synchronously resolve arbitrary hostnames on the main thread to classify
them. If reliable LAN classification cannot be obtained before connect, use an
explicit local-network access control/rationale and a permission-blocked error
path rather than prompting every app launch or breaking public Internet SSH.

**Verify**:
`./gradlew :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.ui.*'`
→ new state-machine cases pass.

### Step 2: Move the request to a contextual user action

Remove the `LaunchedEffect(Unit)` request. Wire one Activity Result launcher to
the decision state so grant resumes exactly one pending user action and denial
does not create a session. Ensure duplicate taps, Activity recreation, and
navigation cannot duplicate an SSH/Mosh/SFTP start. Add resource-backed rationale,
denial, and Settings-recovery copy.

Keep the API 37 system picker path permissionless as documented. Direct LAN
connections must never proceed under a false “connecting” state when Android has
blocked the socket.

**Verify**: focused Compose tests cover prompt trigger, grant continuation,
denial, duplicate tap, and recreation state.

### Step 3: Add API 37 black-box permission and network E2E

On a disposable stable API 37 Google APIs x86_64 AVD, add instrumentation/host
fixture coverage that:

1. clears app data and proves cold launch does not prompt;
2. denies local network and proves a hermetic fixture address that Android
   classifies as non-LAN still works;
3. exercises the same controlled server through an address Android classifies
   as LAN, grants permission from
   the real system dialog, and completes SSH plus one SFTP operation;
4. revokes permission through shell/system settings, returns to the app, and
   proves honest failure/recovery without session duplication;
5. verifies the system-mediated Nearby picker route does not require broad access.

Validate the two address classifications from observed API 37 behavior; do not
call an ADB-reversed loopback connection “public Internet” without proof. If
emulator networking cannot truthfully distinguish LAN from non-LAN traffic,
keep the dialog/denial/revocation tests on API 37 and record the exact missing
network case as a manual physical-device gate; do not fake it with controller-only tests.

**Verify**: XML reports show every new named API 37 method, zero failures, and
only explicitly reviewed environment skips.

### Step 4: Extend the exact runtime contract and CI

Teach the repository runner and `android-test-contract.json` about stable API 37.
Add API 37 to scheduled full coverage and make a focused API 37 permission/network
job required on pull requests. Preserve membership and skip-identity enforcement.
Do not silently lower target SDK to avoid the gate.

**Verify**:
`scripts/run-android-emulator-tests.sh --api 37 --suite boundary --dry-run`
→ prints an API 37 stable-image plan.

**Verify**: all runner tests and workflow YAML parsing pass; a local API 37
focused run passes before hosted CI is claimed.

### Step 5: Run full gates and update evidence

Update `docs/PUBLISHING.md`, `docs/NEXT_STEPS.md`, and
`IMPLEMENTATION_STATUS.md` with exact API 37 results, distinguishing local from
hosted evidence. Update Plan 009 status only after an actual hosted run.

**Verify**:
`./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble`
→ `BUILD SUCCESSFUL`.

## Done criteria

- [x] Fresh API 37 launch never asks for unrelated broad access.
- [x] Local permission grant, denial, revocation, and exact-action resume are tested.
- [ ] Public Internet SSH remains usable when local access is denied.
- [x] Nearby system picker remains permission-preserving.
- [x] Stable API 37 is in the exact runtime contract and CI matrix.
- [x] Full repository gates pass and evidence is current.

## Execution evidence — 2026-09-03

- `build/api37-boundary-final`: exact API 37 boundary
  `42/42`, no failures/errors/skips, followed by four green host revocation
  stages (`grant`, `running`, `revoked`, `relaunched-denied`).
- `build/api37-lan-openssh-final`: both required real-network methods passed
  without skips. With permission absent, raw TCP timed out and production SSH
  failed before protocol bytes or host-identity state; after a real grant, the
  same `10.0.2.2:22222` server carried an SSH marker and SFTP create, upload,
  download, byte comparison, and deletion.
- `build/api35-full-final-6g`: exact 329-method API 35 result, 307 passes and 22
  reviewed skips, including all seven API-37-only permission/network methods by
  exact skip identity. Two preceding 4 GiB attempts are rejected infrastructure
  evidence: Android killed `androidx.test.services` at methods 21 and 16 and the
  orchestrator reported `ClientNotConnected`; neither was treated as a test pass.
- Full Gradle gate: `BUILD SUCCESSFUL` in 1m58s; app JVM `960/0/0/0`, Mosh API
  `11/0/0/0`, extension `8/0/0/0`, lint, debug/release packaging,
  instrumentation APK, and benchmark assembly green.
- Host tooling: all 89 script tests pass. The runner's bounded 2–8 GiB memory
  override is test-covered and defaults to the existing 4 GiB; the accepted
  API 35 refresh used 6 GiB after the explicit low-memory failures.
- Emulator NAT and ADB-reversed loopback do not truthfully prove completed
  public-Internet SSH. Per the STOP condition, that route remains an explicit
  external/manual gate rather than being mislabeled from session-start evidence.

## Test plan

- Pure state machine: launch, explicit intent, grant, deny, revoke, duplicate,
  recreation, API boundary, and public-endpoint continuation.
- API 37 instrumentation: real system permission UI/state plus socket outcomes.
- Runner/contract: API acceptance, exact membership, expected skip identities,
  cleanup, and hosted matrix expansion.

## STOP conditions

- Only preview/RC/nightly API 37 images are available to the executor.
- A truthful LAN-vs-Internet E2E cannot be constructed on the emulator; preserve
  that case as an explicit manual gate rather than weakening it.
- Correct handling appears to require target-SDK rollback or blanket startup prompting.

## Maintenance notes

When target SDK or network APIs change, update the permission decision table,
runtime matrix, and exact skip contract together.
