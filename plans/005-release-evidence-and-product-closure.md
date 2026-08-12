# Plan 005: Establish green release evidence and close product, accessibility, asset, and documentation gates

> **Executor instructions**: Start only when Plans 001–004 are DONE and the
> shared source tree is frozen. This is both a final implementation sweep for
> explicitly deferred product requirements and the release acceptance gate.
> Never rewrite a failing test merely to reduce counts; diagnose product versus
> synchronization/fixture defects with evidence. After all code gates pass,
> follow `AGENTS.md`: resolve every connected authorized phone from fresh
> `adb devices -l`, install/launch exact artifacts on each serial, and verify
> MainActivity foreground. Update `plans/README.md` with exact results.
>
> **Drift check (run first)**: the original hashes in `plans/README.md` are
> historical context, not the expected post-dependency tree. Confirm Plans
> 001–004 are DONE, run `git status --short`, and record the current HEAD plus a
> content hash of every tracked and untracked source file (excluding ignored
> build/IDE output and `plans/`) with the manifest command below. Executor-owned
> edits in Steps 1–6 are expected; STOP only for an unexpected concurrent writer
> or out-of-scope file change. After implementation stabilizes, stop all writers,
> record the **final frozen** manifest, and require that exact manifest to remain
> unchanged through every final build, test, artifact inspection, and device run.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: Plans 001, 002, 003, and 004
- **Category**: tests / release / performance / accessibility / licensing / docs
- **Planned at**: commit `ff8435bc100f381f6925202259017e7834f7233d`, 2026-08-09
- **Execution status**: IN PROGRESS, 2026-08-10 — reviewed release assets, the
  pinned local OpenSSH fixture, and the disposable release-test AVD are present.
  A new final frozen source/build/install gate is still required after the last
  source and documentation edits. The locked/dozing Wi-Fi full app suite plus
  manual accessibility, device, backup, Mosh, and performance matrices remain
  open. Production signing and public Mosh approval are separate external gates.
- **Post-Plan-004 baseline**: HEAD
  `ff8435bc100f381f6925202259017e7834f7233d`; content manifest excluding
  plans/build/IDE output
  `5d1af9e873e964956087e39f316cdeafaa0bc0e77bf10bfb726a29e2e7291ad5`.

## Why this matters

The original red connected reports and the later 227-test baseline are
historical. The latest recorded frozen gate has green JVM/lint/build/package
evidence, a USB app runner report of 249 tests with no failures (with the credentialed
real-server fixture assumption-skipped without runtime arguments), and
focused Mosh API `5/5` plus extension `3/3` device contracts on both USB and
Wi-Fi. The locked/dozing Wi-Fi phone still lacks a green full-app UI suite and
unobscured foreground proof.

Custom themes, reviewed bundled fonts and symbol fallback, current-value
Settings summaries, a resource-backed UI-text boundary, dirty-row rendering,
Android Mosh network hints, the pinned local OpenSSH fixture, generated Baseline
and Startup Profiles, and the disposable release-test AVD are now implemented.
The recorded release-like run used an existing debug identity, not production
signing, and did not prove real SSH with the extension absent. Manual device,
accessibility, backup/provider, Mosh lifecycle, and performance matrices remain
open, as do the external production-signing and public-Mosh decisions.

This plan closes those gaps in a controlled order: characterize current truth,
finish release-scope assets/settings, measure before optimizing the renderer,
add reproducible integration fixtures, then run one immutable source-to-artifact
test/install matrix and reconcile documentation to only that evidence.

## Current state

- Latest recorded frozen source gate: app JVM `783/0/0/0`, Mosh API
  `11/0/0/0`, and extension `8/0/0/0` tests/failures/errors/skipped, with
  debug/release lint, builds, package verification, Android-test APKs, dual-ABI
  extension outputs, and benchmark assembly green.
- Latest recorded connected evidence: the USB app runner reported 249 tests with no failures;
  the credentialed real-server fixture remains assumption-skipped. Focused Mosh API
  `5/5` and extension `3/3` on both USB and Wi-Fi. Both latest debug APKs
  installed by exact serial on both phones. USB foreground proof passed; the
  locked/dozing Wi-Fi full app suite and unobscured foreground proof did not.
- Connected Baseline/Startup Profile generation and 12 benchmark tests passed on
  USB API 36. Input-to-render, tap-to-Connecting, refresh/power/thermal, memory,
  resize, and simultaneous live-session evidence remain open.
- Engine dirty rows now cross `TerminalController` and invalidate exact visible
  row rectangles in `FastTerminalView`; full invalidation remains for structural
  changes. Device before/after performance acceptance is still required.
- Four reviewed monospace families plus Symbols Nerd Font Mono are bundled,
  checksum-pinned, and covered by the canonical notices. Custom font import
  rejects proportional fonts. UUID-backed custom themes round-trip through Room,
  backup, Settings, and renderer paths.
- Product-authored copy now uses a resource-backed `UiText` boundary; live
  Settings landing summaries project committed `SettingsUiState`. A reviewed
  direct-literal allow-list and Spanish locale smoke exist. Manual TalkBack,
  large-text, OEM, and full locale/adaptive matrices remain open.
- The pinned `integration-tests/openssh` fixture exists and supports disposable
  password/key and host-key-mismatch flows. Clean-install backup/provider and
  release-like extension-absent real-SSH acceptance remain open.
- The API 35 `terminal-spike-release-test` AVD accepted matching release-like app
  and extension APKs signed with the existing Android debug identity. This is
  not production signing and did not prove store readiness, same-certificate
  data retention with real SSH, or extension-absent SSH.
- Latest-build controlled-server password-auth Mosh E2E passed on both Android
  16 phones, and Android connectivity generations are wired to live sessions.
  Private-key bootstrap, multi-session, resize/death, observed transitions and
  roaming, extension-absent SSH, and the user's saved-server retest remain open.
- ADR-003 explicitly blocks public Mosh extension distribution pending external
  GPL/signing/installation-information/name review. Local/debug evaluation and
  an SSH-only main-app release are separate decisions.

## Commands you will need

Use the repository-compatible JDK and one Gradle worker for reproducibility:

```bash
export JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk
```

Record a content-sensitive source manifest. This includes tracked and untracked
source content, not merely the tracked diff or filenames:

```bash
set -euo pipefail
mkdir -p build/release-evidence
python3 - <<'PY' | tee build/release-evidence/source-manifest.before.sha256
from hashlib import sha256
from pathlib import Path
from subprocess import check_output

def listed(*args: str) -> set[str]:
    raw = check_output(["git", "ls-files", "-z", *args])
    return {p.decode() for p in raw.split(b"\0") if p}

files = listed() | listed("--others", "--exclude-standard")
excluded_parts = {"build", ".gradle", ".idea", ".kotlin"}
files = {
    p for p in files
    if not p.startswith("plans/")
    and not (set(Path(p).parts) & excluded_parts)
}
manifest = sha256()
for name in sorted(files):
    path = Path(name)
    if not path.is_file():
        continue
    manifest.update(name.encode("utf-8"))
    manifest.update(b"\0")
    manifest.update(sha256(path.read_bytes()).digest())
print(manifest.hexdigest())
PY
```

At the final source freeze, rerun the same script with output file
`build/release-evidence/source-manifest.final.sha256`. Rerun it after the last
device gate as `source-manifest.post-gate.sha256` and require `cmp` success.
As the first executor-owned edit in Step 1, add
`scripts/source-manifest.py` with the exact Python body above (without the shell
heredoc/`tee`) so both frozen hashes use one reviewed implementation; its own
content is included in the hash.

Full source gate after implementation:

```bash
./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false \
  :mosh-api:testDebugUnitTest :mosh-api:lintDebug :mosh-api:assembleDebug \
  :mosh-extension:testDebugUnitTest :mosh-extension:lintDebug :mosh-extension:lintRelease \
  :mosh-extension:assembleDebug :mosh-extension:assembleRelease \
  :mosh-extension:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :benchmark:assemble
```

Expected: exit 0, no test/lint errors, fresh debug/release/test APKs, and no
release-fixture leakage.

Performance gates:

```bash
./gradlew --no-daemon --max-workers=1 \
  :app:testDebugUnitTest \
  --tests 'com.yanjiyu.terminalspike.terminal.benchmark.TerminalParserControllerBenchmarkTest'
./gradlew --no-daemon --max-workers=1 :app:generateBaselineProfile
./gradlew --no-daemon --max-workers=1 :benchmark:pixel6Api35BenchmarkAndroidTest
```

Device discovery/install is always resolved fresh without a remembered serial.
This is an update install on user phones; clean-install tests run only on a
disposable emulator/test profile unless the operator explicitly authorizes data
erasure:

```bash
set -euo pipefail
adb devices -l
readarray -t terminal_targets < <(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1 }')
if ((${#terminal_targets[@]} == 0)); then
  echo 'No authorized Android debugging phone; installation skipped.'
  exit 0
fi
for serial in "${terminal_targets[@]}"; do
  adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s "$serial" install -r mosh-extension/build/outputs/apk/debug/mosh-extension-debug.apk
  adb -s "$serial" shell am start -W -n com.yanjiyu.terminalspike/.MainActivity
  adb -s "$serial" shell dumpsys activity activities |
    rg 'topResumedActivity.*com\.yanjiyu\.terminalspike/.MainActivity'
done
```

Expected: every authorized `device` target installs successfully and output
contains `topResumedActivity` for `com.yanjiyu.terminalspike/.MainActivity`.
Report offline/unauthorized targets without pretending they were tested.

## Scope

**In scope**:

- failing JVM/Compose/instrumentation tests and their directly implicated
  production code
- `terminal/model/TerminalTheme.kt`, renderer profile/view/typeface registry,
  Settings appearance models/UI, Room profile/theme schema/repositories, backup
  payload/migration where custom themes require it
- reviewed production font assets and exact licence/provenance documentation
- string resources and current-value Settings summaries
- terminal controller/listener/view dirty-region contracts and benchmarks
- local test-only OpenSSH/tmux server files/scripts and integration tests
- backup version fixtures/migrators and clean-install SAF provider tests
- benchmark module/journeys/generated Baseline Profile and privacy-safe summaries
- README, CHANGELOG, implementation status, architecture, security, backup,
  Mosh, performance, dependency, asset-licensing, and notice docs
- release packaging/signing verification and exact device evidence

**Out of scope**:

- changing primary navigation or compact terminal chrome against later user
  requirements
- adding per-cell/per-line Compose state
- copying source/assets from other terminal apps
- adding unstable dependencies, analytics, cloud, accounts, ads, or AI
- committing SDK paths, signing material, credentials, APKs, raw device dumps,
  raw benchmark traces, or machine-specific files
- public distribution of the GPL Mosh extension without the ADR-003 external
  decision
- inventing a historical backup format if no documented pre-release contract
  exists; use the STOP condition below

## Git workflow

- Freeze the source manifest before gates and re-hash it after each artifact run;
  no source may change between final build and device evidence.
- Keep logical commits reviewable if authorized, using messages such as
  `test: restore connected device baseline` and `docs: record release evidence`.
- Do not push, publish, or sign with production credentials unless explicitly
  authorized.

## Steps

### Step 1: Establish a trustworthy test baseline

Run the full source gate once against the frozen post-Plan-004 tree. Parse every
test XML rather than relying on Gradle's last console line. Classify each failure:

1. product defect with a reproducible assertion;
2. test fixture/controlled-state defect;
3. OEM/system-window synchronization defect;
4. unavailable external prerequisite.

For categories 1–3, add a deterministic focused reproduction, fix the narrowest
root cause, and rerun focused test three times before the full gate. Do not use
sleeps, broad exception swallowing, weakened assertions, blind Back/Home, or
production changes solely to satisfy a test.

Add the exact `scripts/source-manifest.py` helper specified above. Record source
revision/hash, APK/test-APK SHA-256, Gradle tasks, test counts,
failures/skips, device serial/model/API, and environmental skips in an ignored
build evidence directory while iterating.

**Verify**: full JVM/lint/build gate exits 0 and every generated unit-test XML has
zero failures/errors.

### Step 2: Finish appearance assets and custom-theme persistence

**Current status**: the original asset precondition is satisfied and is not a
Plan 005 blocker. `docs/asset-licensing.md` records immutable sources, versions,
hashes, licences, and notice obligations for the four bundled text families and
Symbols Nerd Font Mono; the canonical notices are packaged into the app. The
same review is required again before any future asset substitution or update.

After that precondition, before adding any binary:

1. pin an immutable stable upstream release/commit and SHA-256;
2. record filename, purpose, source, licence, copyright, modification status,
   and notice obligations in `docs/DEPENDENCIES.md` and
   `docs/asset-licensing.md`;
3. add full required text/attribution to `THIRD_PARTY_NOTICES.md` and confirm the
   About surface packages the exact canonical file;
4. measure APK size before/after.

The required text-font families are fixed by `docs/asset-licensing.md`: Source
Code Pro, JetBrains Mono, IBM Plex Mono, and Cascadia Mono, regular/bold faces
only, plus System monospace. The executor must not substitute other families.
Select one immutable stable upstream release/commit per listed canonical source,
record each exact file checksum, and bundle unmodified artifacts only after the
OFL obligations are complete. Add a metrics-compatible Powerline/Nerd symbol
fallback only after the complete compiled-font component notice audit in
`docs/asset-licensing.md:50-64` passes; otherwise STOP and report that exact
external/legal asset gate rather than calling it complete.

Validate SAF-imported fonts as monospace with representative ASCII advances and
a documented tolerance before acceptance. Test combining/wide/emoji fallback
separately.

Add a UUID-backed `TerminalTheme` persistence entity/repository and Settings
editor for default colours, ANSI 16, cursor, selection, and restore-to-preset.
Carry custom themes through catalog, renderer live apply, backup export/import,
conflict rewrite, recovery, and schema migration. The product brief explicitly
requires bold-as-bright, ligature policy, bounded pinch zoom, and configurable
copy-on-selection; implement all four and keep clipboard writes
sensitive/token-scheduled.

**Verify**: Room/backup/theme tests pass; release APK contains only reviewed font
files and complete notices; imported proportional font is rejected; custom theme
round-trips and applies live without recreating sessions.

### Step 3: Complete accessibility, localization, and Settings summaries

Extract all production user-visible text, content descriptions, state
descriptions, plurals, and error templates from Kotlin into resources. Keep
non-user internal protocol tokens in code. Add a pseudolocale/non-default-locale
smoke that traverses Workspace, Terminal actions, Connections editors, Settings,
backup, security, and Mosh status.

Derive Settings landing summaries from current `SettingsUiState` (theme/font,
scrollback/TERM, keyboard preset/layout, session/background policy, security,
backup status) rather than static prose. Preserve search aliases in localized
resources or a locale-aware index.

Audit TalkBack focus order, roles/actions/state descriptions, 44dp targets,
contrast, 2× font scale, IME, portrait/landscape, split-window, gesture and
three-button navigation, and expanded list/detail. Keep the terminal Canvas as
one bounded semantic surface plus selection/action semantics; do not create a
semantic node per terminal cell.

**Verify**: lint has no new hardcoded/accessibility errors; Compose tests cover
pseudolocale and live summaries; the device matrix in Step 7 records manual
TalkBack/Accessibility Scanner results.

### Step 4: Measure, then consume dirty rows in the native renderer

First run existing benchmark journeys and capture baseline distributions. Add
missing journeys for input-to-render, tap-to-Connecting, terminal resize/rotation,
scrollback fling, and two/four simultaneous sessions. Record device/API/refresh,
build hash, power mode, and thermal state.

After the baseline is captured, carry engine `dirtyRows` through
`TerminalController` and listener APIs to `FastTerminalView`. Invalidate exact
pixel rectangles for dirty visible rows, with full invalidation on viewport,
selection, cursor geometry, theme/typeface/size, resize, alternate-screen, and
buffer-trim changes. Cache run advances/display data only by immutable content
revision and renderer geometry. Never create Compose row/cell state.

Generate/review the Baseline Profile after the final journeys, commit only the
generated profile intended by the plugin, and keep raw JSON/traces as CI/ignored
artifacts. Add regression thresholds only after stable repeated distributions.

**Verify**: visual digest/cursor/selection tests prove no stale cells; all
existing workload tests pass; before/after summary shows equal output digests and
reports the measured delta without overclaiming GPU latency.

### Step 5: Add reproducible SSH and backup compatibility fixtures

**Current status**: the original OpenSSH fixture prerequisite is satisfied and
is not a Plan 005 blocker. `integration-tests/openssh/Dockerfile` pins Alpine
3.24.1 by OCI digest and OpenSSH 10.3p1-r0; its documented local fixture creates
ignored disposable keys, supports password/key smoke and host-key rotation, and
is outside Android source sets. App-level release-like SSH and clean-install
backup/provider acceptance remain open.

The implemented fixture lives under `integration-tests/openssh/` and documents
its lifecycle and safe LAN opt-in. Its current executable smoke contract is:

```bash
set -euo pipefail
docker compose --project-directory integration-tests/openssh \
  -f integration-tests/openssh/compose.yaml config --quiet
./integration-tests/openssh/smoke.sh
```

Use the fixture for the remaining app-level password, private key, Trust
once/save/changed replace, startup/TERM, cancellation, reconnect, and staged Test
Connection acceptance. Generated private material stays under ignored `.state`.
A release leak test must continue to reject fixture credentials/private keys
from app APK/AAB resources and strings.

For backup compatibility, first identify a documented prior wire contract. If
one exists, check in project-authored non-secret fixtures and an isolated
version-to-current migrator with bounds/authentication preserved. If no prior
released or documented schema exists, record version 1 as the first supported
release and replace the impossible “older released schema” checkbox with tested
unknown-optional-field forward compatibility—do not invent history silently.

Add clean-install Standard and Full export/import through a local test
DocumentsProvider, including wrong passphrase, tamper, cancel, provider failure,
Replace crash recovery, and restart. Repeat with Drive only when an account is
available; report external skip explicitly.

**Verify**: Compose server tests are reproducible from README commands; release
leak scan finds no fixture material; clean-install tests pass on an emulator and
at least one available phone.

### Step 6: Complete Mosh and service acceptance

Using the already genuine separately installed extension, run:

- main app with extension absent: normal SSH still works and Mosh shows honest
  install guidance;
- password and private-key bootstrap;
- one through four simultaneous sessions;
- resize/orientation and Activity recreation;
- worker/Binder death and cleanup;
- SSH fallback as an explicit user choice, never silent downgrade;
- Android connectivity generation hints and Wi-Fi/cellular/VPN transition where
  available;
- background/foreground, notification denied, service start denial, CPU/screen
  policies, reconnect cancel/exhaustion, and Disconnect-all confirmation.

Use IPv4/global IPv6 and verified server UTF-8 locale for current Mosh acceptance;
link-local IPv6 zones remain a documented limit unless separately implemented.

**Verify**: per-serial sanitized result table names every case and exact skip.
Do not claim public distribution or proactive roaming beyond observed evidence.

### Step 7: Run the full connected-device and adaptive matrix

Resolve `adb devices -l` immediately before testing. Use this fail-fast full-suite
loop; it preserves user data with update installs and reports non-`device` rows
separately. Each module result is copied immediately after its run to a
serial-keyed ignored evidence path, so a later target cannot overwrite it and a
historical XML cannot satisfy the gate:

```bash
set -euo pipefail
adb devices -l
readarray -t terminal_targets < <(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1 }')
mkdir -p build/release-evidence/connected
for serial in "${terminal_targets[@]}"; do
  [[ "$serial" =~ ^[A-Za-z0-9._:-]+$ ]]
done
for serial in "${terminal_targets[@]}"; do echo "$serial"; done \
  > build/release-evidence/authorized-serials.txt
if ((${#terminal_targets[@]} == 0)); then
  echo 'No authorized Android debugging phone; connected gate skipped.'
else
  for serial in "${terminal_targets[@]}"; do
    evidence_dir="build/release-evidence/connected/$serial"
    mkdir -p "$evidence_dir"
    adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s "$serial" install -r mosh-extension/build/outputs/apk/debug/mosh-extension-debug.apk

    app_marker="$evidence_dir/app-start.marker"
    touch "$app_marker"
    ANDROID_SERIAL="$serial" ./gradlew --no-daemon --max-workers=1 \
      :app:connectedDebugAndroidTest
    readarray -d '' app_reports < <(find \
      app/build/outputs/androidTest-results/connected/debug -type f \
      -name 'TEST-*.xml' -newer "$app_marker" -print0)
    ((${#app_reports[@]} == 1))
    cp -- "${app_reports[0]}" "$evidence_dir/app.xml"
    python3 scripts/assert-test-report.py "$evidence_dir/app.xml"

    extension_marker="$evidence_dir/mosh-extension-start.marker"
    touch "$extension_marker"
    ANDROID_SERIAL="$serial" ./gradlew --no-daemon --max-workers=1 \
      :mosh-extension:connectedDebugAndroidTest
    readarray -d '' extension_reports < <(find \
      mosh-extension/build/outputs/androidTest-results/connected/debug -type f \
      -name 'TEST-*.xml' -newer "$extension_marker" -print0)
    ((${#extension_reports[@]} == 1))
    cp -- "${extension_reports[0]}" "$evidence_dir/mosh-extension.xml"
    python3 scripts/assert-test-report.py "$evidence_dir/mosh-extension.xml"

    adb -s "$serial" shell am start -W \
      -n com.yanjiyu.terminalspike/.MainActivity
    adb -s "$serial" shell dumpsys activity activities |
      rg 'topResumedActivity.*com\.yanjiyu\.terminalspike/.MainActivity'
  done
fi
```

Add `scripts/assert-test-report.py` before this step. It accepts exactly one XML
path, requires a `testsuite`/`testsuites` root, integer `tests > 0`, and zero
`failures`/`errors` (summing child suites when the root omits totals), prints one
privacy-safe count line, and exits nonzero for a missing/malformed/empty/failing
report. Unit-test it with pass, failure, error, empty, malformed, and aggregate
fixtures.

For each exact authorized serial:

1. keep the device unlocked and dismiss only known system prompts through
   deterministic platform/UI automation;
2. update-install current app and extension APKs; Gradle installs matching test
   APKs for the selected serial;
3. run the full app and extension connected suites with `ANDROID_SERIAL` set to
   exactly that serial;
4. parse XML and require zero failures/errors; document intentional skips;
5. install the final debug APK again, launch MainActivity, and verify it is
   top-resumed;
6. exercise two open tabs, navigation away/back, 9×2 deck, one-tap saved host,
   double-tap duplicate, backup recovery, app lock, and Mosh status.

Run destructive clean-install/restore only on an explicitly disposable managed
device or test user; never uninstall or clear data on a user's phone without
fresh explicit authorization.

Add managed devices for API 33–36 and tablet/foldable/split-window configurations
where the SDK supports them. Record gesture/three-button modes, font scale,
TalkBack, rotation, notification denial, and battery restrictions.

**Verify**: every authorized target has a zero-failure XML and foreground proof.
Offline/unauthorized targets are reported, never silently excluded.

### Step 8: Produce release-like/signed artifacts and reconcile docs

Build minified unsigned release first and inspect manifest, R8/JSch reflection,
native/library separation, notices, fixture/debug leakage, and APK contents. With
non-repository release-like signing material, test independent fresh and
same-certificate update installs plus extension-absent SSH. Use the production identity only when the
operator supplies/authorizes it; never record its values.

**Current status**: the named API 35 `terminal-spike-release-test` AVD is
provisioned, so its former unavailability is not a blocker. Matching
release-like APKs signed with the existing Android debug identity installed and
launched there. Production signing, real extension-absent SSH, and the complete
same-certificate data-retention flow remain unproved.

The executable release-like signing gate uses an ephemeral local acceptance
identity outside the repository, not production signing. Run only against a
disposable managed emulator named `terminal-spike-release-test`; never uninstall
or replace packages on a user's phone for this gate:

```bash
set -euo pipefail
release_serial=${TERMINAL_SPIKE_RELEASE_TEST_SERIAL:?Set the disposable emulator serial}
[[ "$release_serial" == emulator-* ]]
avd_name=$(adb -s "$release_serial" emu avd name | tr -d '\r' | head -n 1)
[[ "$avd_name" == 'terminal-spike-release-test' ]]
mkdir -p build/release-evidence

release_signing_dir=$(mktemp -d "${TMPDIR:-/tmp}/terminal-spike-signing.XXXXXX")
trap 'rm -rf "$release_signing_dir"' EXIT
release_password=$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')
"$JAVA_HOME/bin/keytool" -genkeypair -noprompt -storetype PKCS12 \
  -keystore "$release_signing_dir/release-like.p12" \
  -storepass "$release_password" -keypass "$release_password" \
  -alias terminal-spike-release-like -keyalg RSA -keysize 3072 -validity 30 \
  -dname 'CN=Terminal Spike Local Acceptance,O=Local Test,C=GB'
export TERMINAL_SPIKE_RELEASE_STORE_FILE="$release_signing_dir/release-like.p12"
export TERMINAL_SPIKE_RELEASE_STORE_PASSWORD="$release_password"
export TERMINAL_SPIKE_RELEASE_KEY_ALIAS='terminal-spike-release-like'
export TERMINAL_SPIKE_RELEASE_KEY_PASSWORD="$release_password"
./gradlew --no-daemon --max-workers=1 \
  :app:assembleRelease :app:bundleRelease :mosh-extension:assembleRelease

app_release_apk='app/build/outputs/apk/release/app-release.apk'
app_release_aab='app/build/outputs/bundle/release/app-release.aab'
extension_release_apk='mosh-extension/build/outputs/apk/release/mosh-extension-release.apk'
test -f "$app_release_apk"
test -f "$app_release_aab"
test -f "$extension_release_apk"
android_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_SDK_ROOT or ANDROID_HOME}}
apksigner=$(find "$android_sdk/build-tools" -mindepth 2 -maxdepth 2 \
  -type f -name apksigner -print | sort -V | tail -n 1)
test -n "$apksigner"
app_cert=$($apksigner verify --verbose --print-certs "$app_release_apk" \
  | tee build/release-evidence/app-release-signature.txt \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
extension_cert=$($apksigner verify --verbose --print-certs "$extension_release_apk" \
  | tee build/release-evidence/mosh-extension-release-signature.txt \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
test -n "$app_cert"
test "$app_cert" = "$extension_cert"
"$JAVA_HOME/bin/jarsigner" -verify -strict -verbose -certs "$app_release_aab" \
  > build/release-evidence/app-release-aab-signature.txt
aapt="$(dirname "$apksigner")/aapt"
test -x "$aapt"
$aapt dump badging "$app_release_apk" \
  | tee build/release-evidence/app-release-badging.txt \
  | rg "^package: name='com\.yanjiyu\.terminalspike' versionCode='[1-9][0-9]*' versionName='[^']+'"
$aapt dump badging "$extension_release_apk" \
  | tee build/release-evidence/mosh-extension-release-badging.txt \
  | rg "^package: name='com\.yanjiyu\.terminalspike\.mosh' versionCode='[1-9][0-9]*' versionName='[^']+'"

adb -s "$release_serial" uninstall com.yanjiyu.terminalspike >/dev/null 2>&1 || true
adb -s "$release_serial" uninstall com.yanjiyu.terminalspike.mosh >/dev/null 2>&1 || true
if adb -s "$release_serial" shell pm path com.yanjiyu.terminalspike.mosh \
  >/dev/null 2>&1; then
  echo 'Mosh extension unexpectedly present before SSH-only smoke.' >&2
  exit 1
fi
integration-tests/openssh/release-app-install-update-smoke.sh \
  "$release_serial" "$app_release_apk"
adb -s "$release_serial" install "$extension_release_apk"
adb -s "$release_serial" shell am start -W \
  -n com.yanjiyu.terminalspike/.MainActivity
adb -s "$release_serial" shell dumpsys activity activities \
  | rg 'topResumedActivity.*com\.yanjiyu\.terminalspike/.MainActivity'
cp -- "$app_release_apk" build/release-evidence/app-release-like.apk
cp -- "$app_release_aab" build/release-evidence/app-release-like.aab
cp -- "$extension_release_apk" \
  build/release-evidence/mosh-extension-release-like.apk
sha256sum build/release-evidence/app-release-like.apk \
  build/release-evidence/app-release-like.aab \
  build/release-evidence/mosh-extension-release-like.apk \
  > build/release-evidence/release-like-artifacts.sha256
unset TERMINAL_SPIKE_RELEASE_STORE_FILE TERMINAL_SPIKE_RELEASE_STORE_PASSWORD
unset TERMINAL_SPIKE_RELEASE_KEY_ALIAS TERMINAL_SPIKE_RELEASE_KEY_PASSWORD
unset release_password
case "$release_signing_dir" in
  "${TMPDIR:-/tmp}"/terminal-spike-signing.*) ;;
  *) echo 'Refusing to remove unexpected signing directory.' >&2; exit 1 ;;
esac
rm -rf -- "$release_signing_dir"
trap - EXIT
```

Implement `integration-tests/openssh/release-app-install-update-smoke.sh` as a
single-process lifecycle using the same pinned server and disposable
credentials. It installs the supplied release APK, exercises the shipped UI as a
black box, asserts a real SSH connection with the extension absent, writes one
non-secret sentinel profile, performs a same-certificate `adb install -r` while
the same fixture endpoint remains alive, then proves that profile and SSH still
work afterward. It must not add a debug/exported test hook to the release app.
Future reruns must continue to use the named disposable emulator; its current
availability is not permission to substitute a personal phone for destructive
release-like install checks.

Verify APK/AAB signatures, version metadata, same-certificate update/data
retention, and matching main/extension signatures where the distribution model
requires them. This repository has no documented earlier signed app release, so
do not label a same-version `install -r` as a version upgrade. If a genuine prior
signed/versioned artifact is later documented and supplied, add a separate
lower-version-to-current migration run; otherwise record version-upgrade
compatibility as not applicable to this first release. If
production signing or ADR-003 specialist review is unavailable, record those as
external public-distribution gates. This plan's acceptance target is a finished
local/debug app plus an independently installable release-like signed main app;
it does not authorize or claim Play/public Mosh distribution.

First run the Step 7 matrix diagnostically, then reconcile README, CHANGELOG,
implementation status, architecture, security,
backup, Mosh, performance, dependencies, asset licensing, and notices against
those fresh results. Separate:

- implemented and verified;
- implemented but not accepted on required hardware/provider;
- unsupported/known limit;
- external release blocker.

Remove stale claims such as “automatic reconnect/selection/configurable fonts
are absent” only when corresponding current tests/evidence pass.

Documentation reconciliation is the final tracked edit. Stop every source
writer, create the final manifest, record a final-gate epoch, rerun the full
source gate with `--rerun-tasks`, then rerun the complete Step 7 serial loop and
the release-like gate above. Do not edit documentation or source afterward. If
final counts/capabilities/skips differ from the reconciled docs, update the docs,
refreeze, and repeat all final gates; never bless mismatched evidence.

```bash
set -euo pipefail
mkdir -p build/release-evidence
python3 scripts/source-manifest.py \
  | tee build/release-evidence/source-manifest.final.sha256
date +%s > build/release-evidence/final-gate-start.epoch
./gradlew --no-daemon --max-workers=1 --rerun-tasks \
  -Pkotlin.incremental=false \
  :mosh-api:testDebugUnitTest :mosh-api:lintDebug :mosh-api:assembleDebug \
  :mosh-extension:testDebugUnitTest :mosh-extension:lintDebug \
  :mosh-extension:lintRelease :mosh-extension:assembleDebug \
  :mosh-extension:assembleRelease :mosh-extension:assembleDebugAndroidTest \
  :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  :benchmark:assemble
```

Run exact machine-verifiable closure checks:

```bash
set -euo pipefail
./gradlew --no-daemon --max-workers=1 \
  :app:verifyReleasePackaging :app:verifyReleaseBundlePackaging
mosh-extension/scripts/verify-sources.sh
source_bundle_a=$(mktemp -d "${TMPDIR:-/tmp}/terminal-spike-source-a.XXXXXX")
source_bundle_b=$(mktemp -d "${TMPDIR:-/tmp}/terminal-spike-source-b.XXXXXX")
trap 'rm -rf "$source_bundle_a" "$source_bundle_b"' EXIT
mosh-extension/scripts/create-source-bundle.sh "$source_bundle_a"
mosh-extension/scripts/create-source-bundle.sh "$source_bundle_b"
source_archive='terminal-spike-mosh-extension-source-1.0.0.tar.gz'
cmp "$source_bundle_a/$source_archive" "$source_bundle_b/$source_archive"
(cd "$source_bundle_a" && sha256sum --check "$source_archive.sha256")
(cd "$source_bundle_b" && sha256sum --check "$source_archive.sha256")

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

patterns = (
    "**/build/test-results/**/*.xml",
)
start_ns = int(Path("build/release-evidence/final-gate-start.epoch").read_text()) * 1_000_000_000
unit_files = sorted({
    p for pattern in patterns for p in Path(".").glob(pattern)
    if p.stat().st_mtime_ns >= start_ns
})
if not unit_files:
    raise SystemExit("No fresh unit-test XML reports found")
serials = [
    line.strip()
    for line in Path("build/release-evidence/authorized-serials.txt").read_text().splitlines()
    if line.strip()
]
connected_files = []
for serial in serials:
    for module in ("app", "mosh-extension"):
        path = Path("build/release-evidence/connected") / serial / f"{module}.xml"
        if not path.is_file() or path.stat().st_mtime_ns < start_ns:
            raise SystemExit(f"Missing fresh {module} report for {serial}")
        connected_files.append(path)
files = unit_files + connected_files
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
bad = []
for path in files:
    root = ET.parse(path).getroot()
    report = {}
    for key in totals:
        raw = root.attrib.get(key)
        if raw is None and root.tag == "testsuites":
            raw = str(sum(int(x.attrib.get(key, 0)) for x in root.findall("testsuite")))
        report[key] = int(raw or 0)
        totals[key] += report[key]
    if report["failures"] or report["errors"]:
        bad.append(str(path))
if bad:
    raise SystemExit("Failing test XML:\n" + "\n".join(bad))
print("PASS", len(files), "reports", totals)
PY

mkdir -p build/release-evidence
find app/build/outputs mosh-api/build/outputs mosh-extension/build/outputs \
  -type f \( -name '*.apk' -o -name '*.aab' -o -name '*.aar' \
  -o -name 'baseline-prof.txt' -o -name '*NOTICE*' \) -print0 \
  | sort -z | xargs -0 -r sha256sum \
  > build/release-evidence/artifacts.sha256
test -s build/release-evidence/artifacts.sha256
find app/src -type f -name 'baseline-prof.txt' -print -quit \
  | rg . >/dev/null
sha256sum "$source_bundle_a/$source_archive" \
  > build/release-evidence/mosh-source-bundle.sha256
test -f CHANGELOG.md

assert_no_match() {
  set +e
  rg "$@"
  status=$?
  set -e
  if ((status == 0)); then return 1; fi
  if ((status != 1)); then return "$status"; fi
}
assert_no_match -n 'automatic reconnect is not implemented|selection is absent|configurable fonts are absent' \
  README.md IMPLEMENTATION_STATUS.md docs
python3 scripts/source-manifest.py \
  | tee build/release-evidence/source-manifest.post-gate.sha256
cmp build/release-evidence/source-manifest.final.sha256 \
  build/release-evidence/source-manifest.post-gate.sha256
```

The task names above are registered in `app/build.gradle.kts`; the source scripts
are checked in under `mosh-extension/scripts/`. A missing task/script is a source
or checkout defect and must fail the gate, not trigger a guessed substitute. The
XML parser must be run after both unit and connected gates; its output, the
artifact hash file, signature verification output, device matrix, and final
manifest comparison form the durable release-evidence report.

## Test plan

- Preserve every existing unit, Room, migration, renderer, workload, smoke, and
  Android test; fix only evidence-backed defects.
- Add deterministic local OpenSSH and DocumentsProvider integration layers.
- Add managed API/adaptive journeys plus manual evidence for TalkBack/OEM
  behavior that cannot be automated reliably.
- Add release-negative tests proving benchmark/debug/fixture/native-extension
  code and test credentials are absent from the main release APK.
- Store only privacy-safe summaries in Git; raw traces/device dumps stay ignored.

## Done criteria

- [x] The final frozen JVM, lintDebug/lintRelease, debug/release, Android-test
      assembly, Mosh API/extension, and benchmark gate exits 0 after the last
      source edit.
- [ ] Full connected app suite has zero failures/errors on every currently
      connected authorized phone. USB is green; the locked/dozing Wi-Fi full app
      suite is environment-blocked, while its focused Mosh contracts passed.
- [ ] Final debug app and extension are installed on every authorized target and
      MainActivity is verified unobscured foreground. Installs passed on both;
      USB foreground passed and Wi-Fi remained behind keyguard/notification shade.
- [x] Default deck remains 18 keys at 9×2; primary navigation/chrome match later
      user requirements.
- [x] Custom themes and all release-required font/fallback assets are live,
      migrated/backed up, and licence-cleared.
- [x] Resource-backed localization and Settings live summaries are implemented
      and covered by focused tests; the manual accessibility/adaptive matrix is
      still unproved.
- [ ] Performance journeys, generated Baseline Profile, and privacy-safe
      before/after evidence exist; renderer digests remain exact.
- [ ] Reproducible local SSH and clean-install backup/provider tests pass.
- [ ] Mosh/service lifecycle matrix is recorded without overclaiming public
      distribution or roaming.
- [ ] Release-like signed clean install and same-certificate update/data-retention
      pass; a genuine prior-version upgrade is either proved from a documented
      artifact or explicitly not applicable to this first release. Unavailable
      production signing and public-Mosh approval are recorded distinctly
      without a public-release claim.
- [ ] README, CHANGELOG, status, architecture, security, backup, performance,
      dependencies, and notices match the final source/artifact hashes.

Plan status rule: mark `DONE` only when every checkbox above is true for the
defined local/debug + release-like main-app target. Production signing, a cloud
provider not available to the operator, and public Mosh approval are recorded
external gates and do not make this narrower target BLOCKED. Missing licence
clearance for an asset actually bundled by this plan, a required connected phone
that is present but cannot be updated, or a failing executable gate does make it
`BLOCKED` with one exact reason.

## STOP conditions

Stop and report if:

- Plans 001–004 are not DONE or source changes during the final gate;
- a test “fix” requires weakening safety assertions or adding sleeps after two
  deterministic attempts;
- a font/palette lacks immutable provenance, complete licence/notice obligations,
  or acceptable name/trademark terms;
- Nerd/Powerline compiled-font component licences cannot be fully cleared;
- there is no documented older backup schema and someone proposes inventing one
  without a product decision;
- performance optimization would add per-cell/per-line Compose state or changes
  output digests;
- any release step requires committing signing material, SDK paths, endpoints,
  credentials, APKs, or raw private evidence;
- production signing or public Mosh distribution requires authority not supplied
  by the operator—record that external gate and do not attempt or claim public
  distribution; continue the defined local/release-like target.

## Maintenance notes

- Every release claim must name source revision/hash and artifact/test hashes;
  prose without durable output is not evidence.
- Keep Mosh Corresponding Source and the main app's Apache boundary independently
  reproducible.
- Update font/palette notices whenever an asset version changes.
- Benchmark thresholds must be revisited when target devices, refresh rates, or
  renderer geometry change.
