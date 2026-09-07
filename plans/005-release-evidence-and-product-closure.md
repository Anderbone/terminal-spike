# Plan 005: Establish green release evidence and close product, accessibility, asset, and documentation gates

> **Executor instructions**: Start only when Plans 001–004 are DONE and the
> shared source tree is frozen. This is both a final implementation sweep for
> explicitly deferred product requirements and the release acceptance gate.
> Never rewrite a failing test merely to reduce counts; diagnose product versus
> synchronization/fixture defects with evidence. After all code gates pass,
> follow the current `AGENTS.md` device policy and Plan 008's guarded entrypoint.
> Tests may run only on the exact model-verified old `SM-S911B`; never test the
> Wi-Fi `SM-F976B`. Install the final main debug APK on the fold only after the
> whole requested feature is finished or the user explicitly asks, then verify
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
- **Depends on**: Plans 001-004 and 006-018
- **Category**: tests / release / performance / accessibility / licensing / docs
- **Planned at**: commit `ff8435bc100f381f6925202259017e7834f7233d`, 2026-08-09
- **Execution status**: IN PROGRESS, reconciled 2026-09-07 after the final frozen-source and
  default old-phone gates — the production-signed `0.0.3 (6)` source gate passed all 485 tasks in
  6m03s with app JVM `971/0/0/0`,
  Mosh API `11/0/0/0`, and
  extension `8/0/0/0`; all 95 host-script tests also pass. The latest process-isolated
  old-phone app evidence matches its current 332-method contract (`307` passes, `25` approved
  API-36 skips, zero failures/errors). Seven physical terminal journeys, the guarded
  actual-Codex 3/3 matrix, and the fixed real-Mosh 5+1 matrix are green on the exact
  model-verified `SM-S911B`. Plan 018's maximum 20,480-row tmux reconciliation is now
  hard-capped at 2,000 comparison/rebuild rows per display callback and publishes atomically.
  Plans 009, 012, and 013 are locally green but cannot obtain their first hosted conclusions
  until an authorized commit/push; the repository currently has no hosted workflow runs.
  Plan 017 is locally complete except completed public-Internet SSH while Android 17 local-network
  permission is denied, which remains external evidence. Locally actionable closure is now the
  deterministic candidate manifest/document reconciliation, an explicit disposition of
  the diagnosed physical primary-navigation frame miss, and the
  physical TalkBack/hardware-keyboard/OEM observations after the old phone passed IME, landscape,
  maximum-text, and split-screen checks. Final fold/user acceptance, production signing/Play, and
  public Mosh approval remain distinct final or external gates.
- **Post-Plan-004 baseline**: HEAD
  `ff8435bc100f381f6925202259017e7834f7233d`; content manifest excluding
  plans/build/IDE output
  `5d1af9e873e964956087e39f316cdeafaa0bc0e77bf10bfb726a29e2e7291ad5`.

## Why this matters

The original red connected reports and early 227/249-test baselines are
historical. Current evidence in `IMPLEMENTATION_STATUS.md` has green
JVM/lint/build/package results, exact 332-method old-phone membership, and a
seven-test credentialed SSH/Mosh/SFTP matrix on the exact model-checked old
phone. Current policy deliberately excludes the connected fold from tests.

Custom themes, reviewed bundled fonts and symbol fallback, current-value
Settings summaries, a resource-backed UI-text boundary, dirty-row rendering,
Android Mosh network hints, the pinned local OpenSSH fixture, generated Baseline
and Startup Profiles, and the disposable release-test AVD are now implemented.
The release-like run used a local acceptance identity rather than production
signing and proved extension-absent SSH plus same-certificate data retention.
Manual accessibility, OEM battery behavior, wider real-session performance, tmux mouse/copy-mode
scenarios, and observed Mosh roaming remain open, as do the external
production-signing and public-Mosh decisions.

This plan closes those gaps in a controlled order: characterize current truth,
finish release-scope assets/settings, measure before optimizing the renderer,
add reproducible integration fixtures, then run one immutable source-to-artifact
test/install matrix and reconcile documentation to only that evidence.

## Current state

- Latest local source gate: app JVM `971/0/0/0`, Mosh API `11/0/0/0`, and
  extension `8/0/0/0` tests/failures/errors/skipped. After adding the clean-install backup
  gate and CI job, the forced Plan 005 command passed all 451 tasks in 6m19s; all 95
  host-script tests pass. The preceding Plan 018 gate recorded 475 tasks.
- Latest physical default-runtime evidence: the exact 332-method contract passed on the
  model-checked Android 16 `SM-S911B` with 307 executed passes and 25 approved API-36 skips.
  The disposable-host-only export, standard clean-install restore, and full clean-install restore
  methods are intentionally skipped by the default runner and pass in their dedicated API 35
  clean-install gate.
  The preceding clean AVD matrix remains historical evidence: 316-method full suites on APIs 26
  and 35 and focused 33-test boundary suites on APIs 28, 29, 32, and 33. Stable API 37 now passes
  its exact 42-method boundary plus the separate 2/2 denied/granted LAN gate. Plan 009 has wired
  these gates into CI and awaits its first hosted run.
- The separately enabled real-Mosh acceptance repeated 5 password/lifecycle plus 1 private-key
  test without failures or skips. The guarded actual-Codex matrix passed 3/3 with app-selected
  tmux local routing, zero remote wheels, ordered 5,000-row paging, sub-row drag/fling/catch,
  reader anchoring, and live-bottom transition.
- The deterministic seven-test network matrix passed password/imported-key SSH,
  tmux, 5,000-row paging, two SFTP paths, and real UDP Mosh on the old phone.
  Plans 006-007 address newly audited SFTP state/path/bounds/atomicity risks
  before that E2E becomes a CI requirement.
- Baseline/Startup Profile generation and the earlier 12-test connected API-36
  Macrobenchmark suite remain valid historical evidence. Input-to-render,
  tap-to-Connecting, refresh/power/thermal, memory, resize, and simultaneous-session
  measurements are useful future diagnostics but are not release blockers without a reported
  defect. The measured primary-navigation P95 remains a documented shell-performance limitation. The exact old-phone
  navigation rerun after correcting UIAutomator to click each described node's nearest clickable
  ancestor removed all 20 non-clickable warnings and measured CPU P50/P90/P95/P99
  `7.93/22.13/22.66/24.32 ms`; because the app UI was unchanged and the earlier shader outlier did
  not recur, this is harness-correctness evidence rather than a product-performance closure.
- Engine dirty rows now cross `TerminalController` and invalidate exact visible
  row rectangles in `FastTerminalView`; full invalidation remains for structural
  changes. All seven physical terminal journeys are green, including live tmux
  reconciliation at CPU-frame P50/P90/P95/P99 `2.2/3.6/4.8/5.8 ms`. The separate
  Connections-to-Settings journey reproduced at `29.35 ms` CPU P95. Perfetto attributes repeatable
  slow frames to initial Settings category-list composition/layout, with one additional 26.29 ms
  rounded-rectangle shader cache miss. Lifting the shared navigation shell above the route content
  would be a broad pre-release rewrite; defer it unless user acceptance shows a material problem.
- Four reviewed monospace families plus Symbols Nerd Font Mono are bundled,
  checksum-pinned, and covered by the canonical notices. Custom font import
  rejects proportional fonts. UUID-backed custom themes round-trip through Room,
  backup, Settings, and renderer paths.
- Product-authored copy uses a resource-backed `UiText` boundary; live
  Settings landing summaries project committed `SettingsUiState`. A reviewed
  direct-literal allow-list and Spanish locale smoke exist. Automated light/dark,
  expanded, compact 200%-text, and 48dp contracts now exist. The exact old phone additionally
  passed Samsung IME entry, physical landscape, maximum `font_scale=2.0`, and real Samsung
  split screen with positive-area controls. TalkBack binds and sees the labelled semantic tree,
  but physical focus-order traversal and OEM battery observation remain open.
- The pinned `integration-tests/openssh` fixture supports disposable password,
  key, host-key-mismatch, SFTP, tmux, and Mosh flows. Same-install opaque
  DocumentsProvider backup integration and release-like extension-absent SSH are green. A dedicated
  API 35 AVD gate exported Standard and Full archives, rejected plaintext secret leakage, crossed
  two uninstall/reinstall boundaries, restored Standard metadata with the expected unavailable
  secret placeholder, and restored the Full portable secret under a fresh Android Keystore.
- The API 35 `terminal-spike-release-test` AVD accepted matching release-like app
  and extension APKs signed with the existing Android debug identity. This is
  not production signing and does not prove store readiness, but same-certificate
  data retention with real SSH and extension absence passed.
- A newer ephemeral-key `0.0.2` release-like run follows the current Connections host editor and
  tmux chooser. It passed clean install, real extension-absent SSH, same-certificate update, process
  restart, non-secret host retention with password re-prompt, and a second real SSH marker. The
  runner now re-verifies the named AVD around destructive operations and always tears down its
  fixture. APK/AAB signatures verified and the app/extension APK signers matched.
- Current controlled-server Mosh E2E passed password and private-key bootstrap,
  200-row terminal reconstruction, four simultaneous sessions with independent
  resize/close, worker death/slot reuse, and broker death/rebind on the authorized
  old Android 16 phone. Android connectivity generations are wired to live sessions.
  Observed transitions/roaming and the user's saved-server retest remain open.
- The fixed real-Mosh old-phone entrypoint now resets and tears down the disposable fixture, binds
  SSH and UDP to one acknowledged LAN IPv4 address, re-verifies the exact `SM-S911B` before every
  install/test launch, rejects skips and incomplete result sets, and emits only sanitized evidence.
  Its current run passed the required password/lifecycle class 5/5 and private-key method 1/1.
- ADR-003 explicitly blocks public Mosh extension distribution pending external
  GPL/signing/installation-information/name review. Local/debug evaluation and
  an SSH-only main-app release are separate decisions.
- Repository-owned `source-manifest.py`, candidate-manifest generation, documentation
  verification, and strict input/artifact-metadata templates now exist. Five focused tests prove
  deterministic byte-identical JSON plus rejection of stale, contradictory, failing, duplicate,
  tampered, path-escaping, and privacy-leaking evidence. The release-facing documents now contain
  one mechanically checked 971/11/8/332 evidence block, historical competing totals are explicitly
  dated, and the checked-in input example matches 332 methods with 25 reviewed skips. All 95
  host-script tests pass. The resulting source-manifest identity is
  `0744cfcdd34b1da0431c923fa05e9c7d1bcb86bface6a19f4081d9ec036bb9f8` after the
  focused adaptive-evidence reconciliation.
  The deterministic final candidate JSON is generated and documentation-verified. It records the
  remaining manual, hosted, network, signing/store, and public-Mosh gates explicitly instead of
  fabricating conclusions for unavailable evidence.

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

Physical-device commands in the original plan are superseded by Plan 008. Do
not enumerate every connected device into an install/test loop. Use its guarded
mode so each operation rechecks the exact serial and model immediately before
acting. Clean-install tests remain limited to disposable emulators/test users.

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
smoke that traverses Connections, Terminal actions, Connections editors, Settings,
backup, security, and Mosh status.

Derive Settings landing summaries from current `SettingsUiState` (theme/font,
scrollback/TERM, keyboard preset/layout, session/background policy, security,
backup status) rather than static prose. Preserve search aliases in localized
resources or a locale-aware index.

Audit TalkBack focus order, roles/actions/state descriptions, 48dp targets,
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
is outside Android source sets. App-level release-like SSH and disposable-AVD
clean-install backup/provider acceptance are now green.

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
leak scan finds no fixture material; clean-install tests pass on a disposable emulator.
A physical-phone clean install is destructive and requires explicit data-loss approval.

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

Resolve `adb devices -l` immediately before testing, then invoke only Plan 008's
guarded old-phone mode. Copy each report immediately to a serial-keyed ignored
evidence directory and validate it with `scripts/assert-test-report.py`; a
historical XML cannot satisfy the gate. Never select the fold or another
connected phone for tests.

Add `scripts/assert-test-report.py` before this step. It accepts exactly one XML
path, requires a `testsuite`/`testsuites` root, integer `tests > 0`, and zero
`failures`/`errors` (summing child suites when the root omits totals), prints one
privacy-safe count line, and exits nonzero for a missing/malformed/empty/failing
report. Unit-test it with pass, failure, error, empty, malformed, and aggregate
fixtures.

For the exact authorized old-phone serial:

1. keep the device unlocked and dismiss only known system prompts through
   deterministic platform/UI automation;
2. model-check `SM-S911B`, then update-install only the artifacts required by the
   selected old-phone gate;
3. run the full app and extension connected suites with `ANDROID_SERIAL` set to
   exactly that serial;
4. parse XML and require zero failures/errors; document intentional skips;
5. install the final main debug APK again on that old phone, launch MainActivity,
   and verify it is top-resumed;
6. exercise two open tabs, navigation away/back, 10×2 deck, one-tap saved host,
   double-tap duplicate, backup recovery, app lock, and Mosh status.

Run destructive clean-install/restore only on an explicitly disposable managed
device or test user; never uninstall or clear data on a user's phone without
fresh explicit authorization.

Add managed devices for API 33–36 and tablet/foldable/split-window configurations
where the SDK supports them. Record gesture/three-button modes, font scale,
TalkBack, rotation, notification denial, and battery restrictions.

After the whole requested feature is complete, use Plan 008's explicit final-fold
mode to install and launch the latest main debug APK once on `SM-F976B`; do not
run tests or install the Mosh extension there. Report unavailable/unauthorized
targets rather than silently excluding them.

**Verify**: the old phone has a zero-failure XML and foreground proof, and any
allowed final fold install has foreground proof but no test report.

### Step 8: Produce release-like/signed artifacts and reconcile docs

Build minified unsigned release first and inspect manifest, R8/JSch reflection,
native/library separation, notices, fixture/debug leakage, and APK contents. With
non-repository release-like signing material, test independent fresh and
same-certificate update installs plus extension-absent SSH. Use the production identity only when the
operator supplies/authorizes it; never record its values.

**Current status**: the named API 35 `terminal-spike-release-test` AVD is
provisioned, so its former unavailability is not a blocker. A fresh ephemeral
acceptance identity produced and signer-matched the minified APK/AAB, and real
extension-absent SSH passed before and after same-certificate reinstall with
non-secret data retention and password re-prompt. This is not production signing
or a genuine prior-version upgrade; both remain external/not-applicable as described below.

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
"$JAVA_HOME/bin/jarsigner" -verify "$app_release_aab" \
  > build/release-evidence/app-release-aab-signature.txt 2>&1
grep -Fxq 'jar verified.' build/release-evidence/app-release-aab-signature.txt
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

As part of this final step, add one repository-owned release-evidence manifest
generator and focused tests under `scripts/`. It must produce deterministic,
sanitized JSON in `build/release-evidence/candidate-manifest.json` containing:

- commit ID, dirty/source-manifest identity, and test-contract SHA-256;
- exact JVM and Android method/pass/failure/error/approved-skip counts by suite;
- artifact paths, package/version identities, signer fingerprints, and SHA-256;
- device model, API, transport class, scenario, and result without publishing raw
  serials, credentials, private paths, host addresses, or terminal contents;
- hosted workflow/run URLs and conclusions when an authorized hosted run exists;
- explicit `pending` entries for external/manual gates rather than absent fields.

The generator must fail on missing, stale, contradictory, duplicate, or failing
inputs. Add a documentation check that either derives duplicated current totals
from this manifest or rejects mismatched “current” totals in `README.md`,
`docs/PUBLISHING.md`, `docs/NEXT_STEPS.md`, and `IMPLEMENTATION_STATUS.md`.
Historical totals may remain only when clearly labelled historical. Attach the
JSON beside release artifacts; do not treat prose or an unhashed local build
directory as the authoritative candidate record.

**Verify**:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*release*evidence*.py'
python3 scripts/create-release-evidence-manifest.py \
  --evidence-dir build/release-evidence \
  --output build/release-evidence/candidate-manifest.json
python3 -m json.tool build/release-evidence/candidate-manifest.json >/dev/null
python3 scripts/verify-release-documentation.py \
  --manifest build/release-evidence/candidate-manifest.json
```

Expected: every command exits 0, rerunning the generator without changing an
input produces byte-identical JSON, and the documentation verifier accepts no
stale current totals.

**Implementation status (2026-09-07)**: the three commands and shared standard-library helper are
implemented, with `scripts/release-evidence-inputs.example.json` and
`scripts/release-artifact-metadata.example.json` documenting the strict input contracts. The
focused five-test suite and all 95 host-script tests pass. The actual candidate manifest is
generated from the final frozen evidence, and its exact marker block is mechanically consistent
across all four release-facing documents. Unavailable external/manual gates remain explicit
pending entries rather than synthesized passes.

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

- [x] After the remaining Plan 005 source edits, the final frozen JVM,
      lintDebug/lintRelease, debug/release, Android-test assembly, Mosh API/extension,
      and benchmark gate exits 0. The pre-closure Plan 018 source passed 475 tasks.
- [x] After that same freeze, the full default connected app suite matches the exact
      contract with no failures/errors on the model-checked old `SM-S911B`; the current
      pre-closure evidence is 332 methods, 307 passes, and 25 approved skips.
- [x] The final frozen main debug APK is installed and foregrounded on the old phone;
      the current pre-closure APK has already passed this check.
- [ ] Only when the whole requested feature is complete, install/foreground the final main debug
      APK once on the fold for user acceptance. No tests or extension install may target the fold.
- [x] Default deck remains 20 keys at 10×2; primary navigation/chrome match later
      user requirements.
- [x] Custom themes and all release-required font/fallback assets are live,
      migrated/backed up, and licence-cleared.
- [x] Resource-backed localization and Settings live summaries are implemented
      and covered by focused tests.
- [ ] The manual accessibility/adaptive matrix is recorded against the final candidate.
- [x] Current Baseline/Startup Profile generation and all seven physical terminal
      Macrobenchmark journeys passed with privacy-safe summaries.
- [x] Record the diagnosed physical primary-navigation miss as an accepted known limitation or
      fix it only if user acceptance shows a material problem. Do not broaden release scope with
      speculative real-session measurements; the terminal hot-path journeys remain the performance gate.
- [x] Reproducible local SSH/SFTP and same-install opaque DocumentsProvider backup tests pass.
- [x] A disposable clean-install Standard/Full backup export/reinstall/restore gate proves
      portability after the originating app-private and Keystore state is gone.
- [x] Mosh/service lifecycle matrix is recorded without overclaiming public
      distribution or roaming.
- [x] Release-like signed clean install and same-certificate update/data-retention
      pass; a genuine prior-version upgrade is either proved from a documented
      artifact or explicitly not applicable to this first release. Unavailable
      production signing and public-Mosh approval are recorded distinctly
      without a public-release claim.
- [x] README, CHANGELOG, status, architecture, security, backup, performance,
      dependencies, and notices match the final source/artifact hashes.
- [x] One deterministic sanitized candidate manifest owns the current source,
      test-contract, artifact, device, hosted-CI, and pending-external-gate facts;
      duplicated documentation totals are mechanically consistent with it.

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
