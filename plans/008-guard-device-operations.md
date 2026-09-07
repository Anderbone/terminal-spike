# Plan 008: Guard every physical-device install and test by serial and model

> **Executor instructions**: This plan fixes repository tooling that currently
> permits forbidden-device operations. Do not run the unsafe legacy commands
> while implementing it. Tests must use a fake `adb`; do not test the script by
> deliberately targeting the foldable.
>
> **Drift check (run first)**:
> `git diff --stat 93d070a..HEAD -- scripts/install-wireless.sh README.md AGENTS.md plans/005-release-evidence-and-product-closure.md`

## Status

- **Priority**: P0
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug / DX / tests
- **Planned at**: commit `93d070a`, 2026-09-02

## Why this matters

The repository requires exact-serial/model checks and explicitly prohibits
tests or incremental installs on the Wi-Fi `SM_F976B`. The current helper and
README still accept generic wireless targets or unscoped Gradle/ADB commands,
so following checked-in instructions can affect the wrong personal device.

## Current state

- `scripts/install-wireless.sh:31-40` verifies only that a target is connected
  and that its serial looks wireless; it does not inspect `ro.product.model`.
- `scripts/install-wireless.sh:48-50` immediately builds, update-installs, and
  launches on that unchecked model.
- `README.md:91-110,155-164` documents unscoped `connectedDebugAndroidTest`,
  `adb install`, and the generic wireless installer.
- `AGENTS.md` permits tests only on the old `SM_S911B`, forbids tests on
  `SM_F976B`, and permits one final main-APK install/launch on the fold only
  after the whole requested feature is finished or the user explicitly asks.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Script tests | `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` | exit 0 |
| Shell syntax | `bash -n scripts/install-wireless.sh` | exit 0 |
| Full gate | `JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk ./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest :benchmark:assemble` | exit 0 |
| Hygiene | `git diff --check` | exit 0 |

## Scope

**In scope**:

- `scripts/install-wireless.sh`
- `scripts/tests/test_install_wireless.py` (new)
- device-command examples in `README.md`
- unsafe physical-device snippets in `plans/005-release-evidence-and-product-closure.md`

**Out of scope**:

- changing `AGENTS.md` safety policy
- installing to or testing any real device during script unit tests
- Mosh-extension publication or installation on the fold
- accepting arbitrary models through an override flag

## Git workflow

Preserve the dirty tree. Do not commit or push unless explicitly requested.

## Steps

### Step 1: Replace the generic operation with explicit modes

Keep `--list`, but require one of these mutually exclusive modes:

- `--test-old-phone <serial> <gradle-task>...`: require connected/authorized,
  resolve `ro.product.model` immediately before invoking Gradle, require exact
  `SM-S911B`, export `ANDROID_SERIAL`, and reject tasks outside an allowlist of
  connected Android-test/profile/benchmark tasks.
- `--install-old-phone <serial> <apk>`: verify exact `SM-S911B` immediately
  before `adb -s ... install -r`, then launch and require `MainActivity` as
  `topResumedActivity` for the main package.
- `--final-fold-install <serial> <apk>`: require exact `SM-F976B` and an explicit
  `--feature-complete` acknowledgement in the same invocation; allow only main
  debug APK install plus launch/foreground verification and never execute tests.

Validate serial characters and require the APK argument to resolve beneath the
repository's expected build-output directory. Use the discovered `adb_bin` for
all calls. Re-resolve authorization and model immediately before every install
or test, not just once at script startup.

**Verify**: shell syntax passes.

### Step 2: Add hermetic fake-ADB tests

Create Python unittest coverage with a temporary fake `adb` executable and fake
Gradle wrapper. Assert:

- absent/unauthorized/offline serials fail;
- model mismatch fails before install/test;
- the fold is rejected by old-phone modes;
- test mode can never select the fold mode;
- fold mode requires `--feature-complete`, refuses extension/test operations,
  and checks foreground state;
- old-phone test mode exports the exact serial;
- every generated `adb` command contains `-s <exact serial>`;
- an APK outside the allowed output directory is rejected.

The tests must inspect fake command logs and must not invoke the real SDK.

**Verify**: Python test discovery passes.

### Step 3: Replace unsafe documentation and plan snippets

Replace generic README examples with the guarded modes. In Plan 005, remove the
loop that treats every connected device as a test/install target and point its
physical-device steps to this guard. Preserve historical evidence, but label old
fold runs historical rather than a current instruction.

**Verify**:

`rg -n '(^|[[:space:]])\./gradlew connectedDebugAndroidTest|^[[:space:]]*adb install|for serial in.*terminal_targets' README.md plans/005-release-evidence-and-product-closure.md`

Expected: no executable unscoped physical-device instruction remains.

### Step 4: Run final checks

Run script tests, shell syntax, full local gate, and `git diff --check`. Do not
perform a real fold install as part of this plan.

## Test plan

The fake-ADB suite is the acceptance proof. Include command-log assertions for
ordering so model verification immediately precedes each destructive/external
operation. Include paths containing spaces and a serial containing the wireless
service punctuation used by ADB.

## Done criteria

- [ ] The fold cannot receive a test or incremental/non-final install through
  checked-in device tooling.
- [ ] Old-phone operations require exact serial and `SM-S911B` immediately
  before each action.
- [ ] MainActivity foreground is verified after installs.
- [ ] README and Plan 005 contain no unscoped physical-device commands.
- [ ] Hermetic script tests and the full local gate pass.

## STOP conditions

- A requested mode would broaden the current device authorization policy.
- Testing would require touching a real phone instead of fake ADB.
- An operation cannot prove its model immediately before acting.

## Maintenance notes

Future device helpers must call the same guard rather than copy target logic.
Model names from `adb devices -l` are discovery hints; the authoritative check
is `adb -s <serial> shell getprop ro.product.model` immediately before action.
