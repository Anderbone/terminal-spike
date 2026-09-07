---

> The separate-APK packaging described in historical sections is superseded by
> [ADR-005](ADR-005-BUNDLED-MOSH.md): one app now includes the private Mosh broker and workers.
status: active
updated_at: 2026-09-07
starting_baseline_commit: 765380e
---

# Remaining validation and external release gates

## Current release-candidate snapshot (supersedes older evidence below)

<!-- release-evidence-current:start -->
Current release evidence: app JVM `971` tests (`971` passed, `0` skipped, `0` failures/errors); Mosh API JVM `11` tests (`11` passed, `0` skipped, `0` failures/errors); Mosh extension JVM `8` tests (`8` passed, `0` skipped, `0` failures/errors); old-phone Android app `332` tests (`307` passed, `25` skipped, `0` failures/errors). The source and artifact hashes and any pending external gates are recorded in `build/release-evidence/candidate-manifest.json`.
<!-- release-evidence-current:end -->

Plan 018 is locally complete: tmux reconciliation is capped at 2,000 comparison/rebuild rows per
display callback and publishes atomically. The model-verified old-phone run passed all seven
terminal fixture journeys; the new 20,480-row live-reset journey measured CPU-frame
P50/P90/P95/P99 2.2/3.6/4.8/5.8 ms. Actual Codex remained local with zero wheels and passed paging,
sub-row/fling/catch, reader-anchor, and live-bottom assertions; real Mosh passed 5+1. The full local
gate passed 89 script tests and 475 Gradle tasks. This removes Plan 018 from the remaining source
work; hosted CI, Android 17 public-Internet evidence, manual product/device matrices, fold/user
acceptance, signing/Play, and Mosh legal/listing decisions remain.
The process-isolated old-phone app evidence now passes the expanded exact API-36 contract. Its
three disposable-host-only backup portability methods remain intentionally skipped in the default
runner and pass in the dedicated clean-install gate.

The disposable API 35 clean-install backup gate is green and CI-required: encrypted Standard and
Full exports crossed separate uninstall/reinstall boundaries, prior Room/Keystore state was absent,
Standard restored the documented non-exported-secret placeholder, and Full restored the portable
secret under a fresh Keystore. Three exact instrumentation methods passed without skips or failures.
The post-change forced source gate executed 451/451 tasks in 6m19s with app/Mosh API/Mosh extension
JVM totals `971/11/8`, zero failures/errors/skips, both app lint variants, debug/release/Android-test
packages, both Mosh ABIs, and benchmark assembly green. All 95 host-script tests also pass.
A focused old-phone navigation rerun reproduced CPU P95 `29.35 ms`. Perfetto attributes the
repeatable Settings-opening cost to initial category-list composition/layout, with one extra
26.29 ms rounded-rectangle shader cache miss. A harness-correctness follow-up now clicks the
clickable Material navigation ancestor instead of its described child, eliminating all 20
UIAutomator non-clickable warnings; the repeat measured P95 was `22.66 ms`. This does not establish
a product optimization, and Settings first-frame work plus the other real-session measurements
remain open.

The dated 2026-09-03 source gate was green: app JVM `960/0/0/0`, Mosh API
`11/0/0/0`, extension `8/0/0/0`, lint, debug/release builds,
release packaging checks, Android-test APKs, native extension outputs, and benchmark assembly all
passed. Stable API 37.0 passed exact boundary membership `42/42` with no skips,
including real permission grant/denial, Activity recreation, public-host bypass,
and the permission-preserving Nearby system picker. Its external gate also proved
that revocation terminates the original process and that explicit relaunch stays
denied. That API 35 full run accounted for all 329 exact methods (`307`
passed, `22` reviewed skips), including all seven API-37-only permission/network
methods by exact lower-platform skip identity. The real API 37 LAN gate passed
2/2: denied permission blocked raw TCP and production SSH before protocol traffic, while a
grant completed SSH terminal and SFTP upload/download/delete against the same
RFC1918 OpenSSH endpoint. Arbitrary hostnames are resolved off the UI thread and
request permission when any answer is local; public answers and resolution
failure do not trigger a broad prompt. Earlier clean/wiped
full emulator coverage passed on APIs 26 (`316/0/0/17`) and 35
(`316/0/0/13`), with focused 33-test boundary coverage on APIs 28, 29, and 32
(`33/0/0/2` each) and API 33 (`33/0/0/1`), plus deterministic OpenSSH/SFTP coverage on API 35.
Every full/boundary result passed exact class/method membership and API-specific skip validation.
Those emulator runs used the preceding 316-method contract; the expanded 318-method contract adds
the opt-in combined real-mouse-app and Mosh/tmux lifecycle tests and is exact/green on the
authorized old phone.

An external wiped-API-35 lifecycle runner also passed real SSH before and after exact app-PID
death. Ordinary backgrounding, deep idle, restricted standby, and Data Saver retained the same
live PID/service/notification and restored every changed policy. After ownership-verified
`kill -9`, the process stayed dead until explicit launch, the service/notification were absent,
one saved host and one recent row remained with zero stored session-only secrets, the new PID was
distinct and showed no live session, the password was requested again, and real SSH passed.

The minified release/update smoke is now CI-wired through a fresh ephemeral PKCS12 identity. Its
local CI-equivalent run signer-matched the APK and AAB and passed extension-absent SSH before and
after same-certificate reinstall with non-secret data retention and password re-prompt. Cleanup
retained only a sanitized status summary; hosted proof still requires an authorized commit/push.

On the exact model-checked authorized old `SM-S911B`, that dated default runner passed 318 tests:
303 passed, 15 expected opt-in real-server tests skipped, and none failed. A separately enabled,
privacy-preserving three-test SSH/Codex/tmux matrix passed direct actual Codex, local pixel scroll
inside app-selected tmux, and real GNU `less --mouse`, Vim, and htop. Their visible fixture tops
advanced 1→9, 1→10, and 1→30 respectively; each selected `REMOTE_MOUSE` /
`EXPLICIT_REMOTE_MOUSE`, sent three wheel reports, and applied zero local scroll updates. The
separately enabled,
model-locked real-Mosh gate then passed five password/lifecycle cases and one private-key case with
no failures or skips, including production Mosh/tmux Activity recreation, reader anchoring, and
the real pre-existing-copy-mode Auto/Remote policy. A fresh ephemeral-key `0.0.2` release-like AVD gate verified package, version,
and signature continuity; real extension-absent SSH passed before and after `install -r`, the saved
non-secret host survived, and the deliberately unsaved password was requested again after process
restart. New hosts now default password storage off, with focused UI regression coverage. The
that run's debug APK SHA-256 is
`0aef8e22429ed88c2e72fb44c8467309873659af006fffb3cddb80b4052af2ec`; it is installed and
`MainActivity` is top-resumed on the old phone. The final same APK installed successfully on the
fold without tests; `MainActivity` is the resumed activity, but the dozing locked device's
`NotificationShade` prevented top-resumed foreground proof.

The source is a strong local release candidate, but the project is not yet authorized for public
production distribution. Remaining gates are the first hosted CI run after commit/push; observed
Wi-Fi/cellular/VPN roaming, an external server, and completed SSH through an
actual public-Internet route while local-network permission is denied; user confirmation of the completed tmux build,
physical TalkBack focus-order traversal, hardware-keyboard/OEM battery-restriction and extended
soak checks; production key/Play/store-listing work; and the separate Mosh legal, trademark,
Corresponding Source, signing, and listing decisions. See [PUBLISHING.md](PUBLISHING.md) for the
operator checklist. Evidence below predating this snapshot is retained as historical progression,
not as the current result.

The planned product slices are present in the current source tree. The phone shell uses
**Connections**, **Terminal**, and **Settings** as its three primary destinations. Connections is
the main catalogue, and Terminal owns active sessions.

The current implementation includes:

- polished host, key, and snippet management with search, editors, confirmations, staged connection
  testing, strict host-key verification, favourites-first presentation of every saved host, one-tap
  authoritative saved authentication, preserved Quick Connect private-key association, and
  foreground LAN SSH discovery;
- app-private Room/DataStore models and migrations, Keystore-backed authenticated credential
  encryption, retry plus a separately confirmed full app-data reset from every fail-closed startup
  and Settings recovery gate, app lock, privacy controls, and redacted logging;
- built-in and custom terminal themes, bundled reviewed text fonts, Symbols Nerd Font Mono fallback,
  private SAF font import with proportional-font rejection, explicit opt-in portable font backup,
  and safe System-monospace restore fallback when font bytes are excluded,
  live previews, and persisted terminal/keyboard profiles;
- custom Canvas terminal rendering, selection/copy/link actions, OSC 8/52 policy, bounded history,
  swipe/Settings-selected Raw/Text input without persistent selector labels, standalone at-least-48
  dp accessory targets while preserving the exact default 20-key 10 × 2 deck, editable presets,
  local/remote/two-finger scrolling, and multi-session chrome with safe immediate duplication
  whenever authentication is reloadable;
- foreground-service session ownership, privacy-aware notification actions, protocol keepalive,
  bounded connectivity-aware reconnect, background diagnostics, and optional wake policies;
- one complete portable backup/restore through Android's document picker with no passphrase prompt,
  authenticated validation, replacement preview, transactional apply, and
  recovery snapshots;
- a project-owned versioned `mosh-api`, strict SSH `mosh-server` bootstrap, signature/API-verified
  PFD transport, Android network-hint publication, explicit Never/Ask/Automatic fresh-SSH fallback,
  and a separate genuine Mosh 1.4.0 extension APK for `arm64-v8a` and `x86_64`;
- project-authored multi-megabyte terminal fixtures, a Macrobenchmark/Baseline Profile module, and a
  documented disposable local OpenSSH integration environment.

No GPL/AGPL code is included in the main APK or `mosh-api` AAR. GPL/native Mosh code exists only in
the optional `com.yanjiyu.terminalspike.mosh` application and its separately inventoried artifact
and Corresponding Source bundle. Cloud sync, accounts, analytics, advertising, and remote AI remain
absent.

The 2026-08-19 launcher/store-branding refresh completed a newer full current-source gate: 482
Gradle tasks passed across unit tests, lint, debug/release builds, Android-test assembly, release
APK/AAB integrity, native Mosh outputs, and benchmark assembly. Focused device contracts passed
app 2/2 and extension 4/4 on exact authorized Wi-Fi target `SM-F976B`; final installs succeeded and
`MainActivity` cold-launched in 716 ms as `topResumedActivity`. The remaining store, signing,
manual, and legal actions are consolidated in [PUBLISHING.md](PUBLISHING.md).

## Current-source verification evidence

The commands below remain the reproducible reference gates. The exact current combined result is
recorded immediately after them and in `IMPLEMENTATION_STATUS.md`:

```bash
./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew :mosh-api:test :mosh-api:lint :mosh-api:assemble
./gradlew :mosh-core:test :mosh-core:lint \
  :mosh-core:assembleDebug :mosh-core:assembleRelease \
  :mosh-core:assembleDebugAndroidTest
./gradlew :app:verifyReleasePackaging :app:verifyReleaseBundlePackaging :benchmark:assemble
```

The frozen 2026-08-10 gates completed successfully. Fresh JUnit totals in
tests/failures/errors/skipped order were app JVM `785/0/0/0`, `mosh-api`
`11/0/0/0`, and extension `8/0/0/0`. Debug and release lint/build gates, release APK/AAB packaging
verification, both Android-test APKs, the `arm64-v8a` and `x86_64` native extension outputs, and
benchmark assembly were green.

The frozen debug artifacts are:

- main: `9cc65469c2ec982801d51b78e67ef3d2fd592020e4aa4f38e5e93de973e08408`;
- Mosh extension: `3adccc800da4fd94623b43130f7c84e55ccb3816be7f16f48e6f62d901d88bd3`.

Fresh exact-serial installs of both version `0.0.1` artifacts returned `Success` on authorized USB
`RZCW81JZ9CP` (`SM-S911B`) and Wi-Fi
`adb-R5GYB530AJJ-GqQiUw._adb-tls-connect._tcp` (`SM-S936U`). The USB full app connected gate
reported 252 tests with no failures. The runtime-credentialed real JSch/OpenSSH fixture passed on the
disposable AVD with Mosh absent; Standard/Full opaque-provider backup and physical-KeyEvent proofs
also passed. `mosh-api` passed 5/5 and the Mosh extension passed 3/3 on each phone.

The Wi-Fi full app UI suite was attempted, then stopped because secure keyguard/dozing prevented
Compose activities from owning a hierarchy. It is environment-blocked, not green. On USB,
`MainActivity` was verified as `topResumedActivity` and focused. On Wi-Fi, it was reported as
`ResumedActivity` with the app focused behind `NotificationShade`/keyguard; unobscured foreground
proof remains incomplete.

The strengthened Mosh E2E harness writes every command byte separately and
reconstructs the resulting VT screen. Against the disposable local server, Wi-Fi passed once and
again in a repeated 5/5 run, while USB passed 1/1. This evidence is deliberately limited to that
controlled earlier device run. The fresh frozen-artifact module contracts above do not replace a
live Mosh session. The user's own saved Mosh server still requires their unlocked manual retest; no
behavior on an arbitrary external server is claimed.

## Local release-like evidence

An additional local acceptance gate used the existing Android debug keystore only; it was not
production signing. `:app:assembleRelease :app:bundleRelease :mosh-core:assembleRelease`
completed with `BUILD SUCCESSFUL` in 1 min 28 s with 159 tasks. The artifacts were:

- app release APK SHA-256
  `29f64e9b6ac233a8546f5ae934962212804d8a62e6a7004657cc47d307990b1b`;
- Mosh extension release APK SHA-256
  `206c74610de6cc808dcccae8e14d32dedc8e99efea2e32a85f60b004174c4c49`;
- app release AAB SHA-256
  `27ae1393b34ed1a42edc031352229ef3106049adbe0ce033c893f7519355f61f`.

Both APK signatures verified with matching certificate SHA-256 `946b2b…a3ee`. AAB
`jarsigner -verify` passed with the expected self-signed and missing-timestamp warnings. Both APK
installs returned `Success` on disposable API 35 AVD `terminal-spike-release-test`; the app
cold-launched in 504 ms and `MainActivity` was `topResumedActivity`. A later guarded smoke proved
real SSH before/after same-certificate `install -r`, saved-host preservation, and extension absence.
This does not establish production signing, public Mosh distribution, or store readiness.

## Performance evidence — partial

Connected Baseline/Startup Profile generation passed on exact serial `RZCW81JZ9CP` (`SM-S911B`,
Android 16/API 36) in 4 min 46 s. The full connected benchmark suite then passed in 5 min 31 s: 12
tests, zero failures/errors, two expected generator skips, with Startup 2/2, navigation 1/1,
terminal fixtures 6/6, and the asset contract 1/1. Traces and `benchmarkData` JSON were collected;
both generated profile files are hash-recorded in [PERFORMANCE.md](PERFORMANCE.md).

Do not infer numeric input latency, tap-to-Connecting latency, refresh/power/thermal conditions,
memory, rotation/resize, or simultaneous live-session behavior from the green instrumentation run.
Those exact-device/manual observations remain open.

## Manual/device matrix still requiring explicit evidence

- Pre-existing tmux copy mode and Auto-mode vertical-gesture ownership are automated and green:
  Auto leaves copy mode active while scrolling locally with zero wheels, and explicit Remote drives
  it. New-output reader anchoring and the transition back to live bottom are also automated. Keep
  manual exploratory coverage for taps, selection, links, and unusual inner mouse applications;
  explicit Remote input to real GNU less, Vim, and htop is already automated and green.
- Physical old-phone checks now prove Samsung IME text entry, landscape, maximum system text at
  `font_scale=2.0`, and real Samsung split screen with Terminal Spike in a bounded top pane.
  Automated compact/large-text, navigation-mode, semantics, touch-target, and notification-denied
  contracts are also green. Physical TalkBack focus-order traversal, hardware-keyboard behavior,
  OEM Optimised/Restricted battery modes, and extended session soak remain.
- Exercise the user's own saved Mosh server and available Wi-Fi/cellular/VPN transition paths. The
  disposable server's password/private-key bootstrap, concurrent resize isolation, worker death,
  broker death/rebind, and cleanup are green; link-local IPv6 zone identifiers remain unsupported.
- Backup round trip between two clean app installations and a cloud document provider when one is
  installed.

## External Mosh distribution gate

The local/debug architecture decision and APK/source boundary are documented in
[ADR-003](ADR-003-MOSH-EXTENSION-BOUNDARY.md). Public extension distribution, production signing,
combined store presentation, and use of the Mosh name remain blocked until specialist GPL
Corresponding Source/installation-information, signing/rotation, and trademark review is complete.
APK/process separation is not presented as a licence bypass. This external review does not prevent
the independently useful main SSH APK from building or operating without the extension.

## Immediate next action

Freeze the reconciled source, run the complete local and exact-old-phone gates once, generate the
sanitized candidate manifest, and install/foreground the resulting main debug APK on the fold when
that device is available. After explicit commit/push authorization, hosted API/runtime/SSH jobs can
produce their first authoritative run. Physical TalkBack focus order, OEM battery modes, roaming,
the user's external server, production signing/Play, and public-Mosh review remain separate observed
or operator-controlled gates; update them only from actual evidence.
