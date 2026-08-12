---
status: active
updated_at: 2026-08-10
starting_baseline_commit: 765380e
---

# Remaining validation and external release gates

The planned product slices are present in the current source tree. The phone shell deliberately uses
**Workspace**, **Terminal**, and **Settings** as its three primary destinations; the first-class
Connections catalogue is reached from Workspace and terminal context. This later product decision
supersedes the earlier Connections-as-primary navigation draft.

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
  dp accessory targets while preserving the exact default 18-key 9 × 2 deck, editable presets,
  local/remote/two-finger scrolling, and multi-session chrome with safe immediate duplication
  whenever authentication is reloadable;
- foreground-service session ownership, privacy-aware notification actions, protocol keepalive,
  bounded connectivity-aware reconnect, background diagnostics, and optional wake policies;
- versioned Standard and Full encrypted backup/restore through Android's document picker with
  wipeable passphrase buffers, authenticated preview, conflict handling, transactional apply, and
  recovery snapshots;
- a project-owned versioned `mosh-api`, strict SSH `mosh-server` bootstrap, signature/API-verified
  PFD transport, Android network-hint publication, explicit Never/Ask/Automatic fresh-SSH fallback,
  and a separate genuine Mosh 1.4.0 extension APK for `arm64-v8a` and `x86_64`;
- project-authored multi-megabyte terminal fixtures, a Macrobenchmark/Baseline Profile module, and a
  documented disposable local OpenSSH integration environment.

No GPL/AGPL code is included in the main APK or `mosh-api` AAR. GPL/native Mosh code exists only in
the optional `com.yanjiyu.terminalspike.mosh` application and its separately inventoried artifact
and Corresponding Source bundle. SFTP, cloud sync, accounts, analytics, advertising, and remote AI
remain absent.

## Current-source verification evidence

The commands below remain the reproducible reference gates. The exact current combined result is
recorded immediately after them and in `IMPLEMENTATION_STATUS.md`:

```bash
./gradlew test lint assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew :mosh-api:test :mosh-api:lint :mosh-api:assemble
./gradlew :mosh-extension:test :mosh-extension:lint \
  :mosh-extension:assembleDebug :mosh-extension:assembleRelease \
  :mosh-extension:assembleDebugAndroidTest
./gradlew :app:verifyReleasePackaging :app:verifyReleaseBundlePackaging :benchmark:assemble
```

The frozen current-source gates completed successfully. Fresh JUnit totals in
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
production signing. `:app:assembleRelease :app:bundleRelease :mosh-extension:assembleRelease`
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

- Retry the full Wi-Fi app UI suite with the phone securely unlocked and awake, and obtain
  unobscured `MainActivity` foreground proof; the keyguard/doze-blocked attempt is not a pass.
- Password/private-key SSH, first trust, matching reconnect, and changed-key block against the local
  disposable OpenSSH server.
- Notification denied, Optimised/Restricted battery modes, process-death limitations, IME/hardware
  keyboard behavior, accessibility text/TalkBack, orientation, split screen, and available Android
  13–16 targets.
- The user's own saved Mosh server while unlocked, followed by private-key bootstrap, resize,
  simultaneous sessions, extension death/cleanup, and available Wi-Fi/cellular/VPN transition paths.
  The disposable local server is green; link-local IPv6 zone identifiers remain unsupported.
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

Retry the environment-blocked Wi-Fi app UI/foreground checks with the device unlocked and awake,
then complete the user's unlocked saved-server Mosh retest and the open manual/performance
observations. Continue to use exact serials and update only evidence that was actually observed.
