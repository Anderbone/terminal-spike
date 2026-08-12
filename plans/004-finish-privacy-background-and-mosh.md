# Plan 004: Finish privacy, background-session, recent-identity, and Mosh transition policies

> **Executor instructions**: Execute only after Plans 001 and 002. This plan
> crosses persistence, Android services, and the optional Mosh boundary; keep its
> types non-secret and lifecycle-owned. Preserve the separate extension APK and
> ADR-003 boundary. Run every gate and update `plans/README.md`.
>
> **Drift check (run first)**: confirm Plans 001 and 002 are DONE, record the
> current HEAD plus `git diff --binary -- . ':(exclude)plans' | sha256sum` and
> `git status --short` as this plan's post-dependency baseline, then
> run `git diff --stat ff8435bc100f381f6925202259017e7834f7233d..HEAD -- app/src/main/java/com/yanjiyu/terminalspike/SessionForegroundService.kt app/src/main/java/com/yanjiyu/terminalspike/connection app/src/main/java/com/yanjiyu/terminalspike/core app/src/main/java/com/yanjiyu/terminalspike/ui app/src/main/proto app/schemas`. Stop on semantic drift in recent-session schema, notification actions, credential aggregates, or the Mosh client API.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: Plans 001 and 002
- **Category**: security / correctness / Android lifecycle / migration
- **Planned at**: commit `ff8435bc100f381f6925202259017e7834f7233d`, 2026-08-09
- **Execution status**: DONE, 2026-08-09
- **Post-Plan-003 baseline**: HEAD
  `ff8435bc100f381f6925202259017e7834f7233d`; tracked diff excluding
  `plans/` SHA-256
  `ce46a70d55a330b8af210051cfcb53ca31ffb316ee8c598b836c2b99be7609cb`;
  `git status --short` SHA-256
  `54349a164caf3f5668feda61b2fae2321ee4da1f5c369c89f02346d86aeea322`.

Recorded conservative product decisions: existing Mosh hosts migrate to fallback
`Never` because automatic fallback must remain opt-in; sensitive clipboard
presets are Off, 30 seconds, 1 minute, and 5 minutes; a dual-stack default
network reports the API's unspecified family instead of claiming a single
family; notification education is consumed when first shown or dismissed; and
credential-clear previews report credential and key-identity counts separately.

Completion evidence on 2026-08-09:

- production, JVM-test, and Android-test Kotlin source sets compiled together;
- the focused JVM contract suite, Room/KSP schema gate, lintDebug, and debug APK
  assembly passed;
- the required repository-wide `test lint assembleDebug assembleRelease
  assembleDebugAndroidTest` gate passed (408 tasks);
- the Plan 004 device regressions passed 4/4 and the clipboard lifecycle
  regressions passed 3/3 on authorized USB SM-S911B (`RZCW81JZ9CP`);
- current main and Mosh-extension debug APKs both installed successfully, and
  `com.yanjiyu.terminalspike/.MainActivity` was verified top-resumed;
- the previously paired Wi-Fi SM-S936U was absent from both `adb devices -l`
  and mDNS discovery, so it could not be updated in this run;
- `git diff --check` passed.

## Why this matters

Several persisted privacy/background controls are currently descriptive only.
Sensitive clipboard data is never scheduled for token-safe clearing,
notification privacy does not change content, and the notification can
disconnect all sessions immediately. Repeated unsaved endpoints can produce
duplicate Recent rows because only a saved host retains endpoint identity.
Finally, Android network transitions never reach the real Mosh extension even
though the versioned API already supports them.

This plan finishes those contracts without persisting raw quick-connect
endpoints or weakening the application-owned session lifecycle.

## Current state

- `core/model/RecentSession.kt:3-35` intentionally stores display-safe history
  and only reaches raw endpoint data through nullable `hostProfileId`.
- `core/data/db/Entities.kt:312-349` stores no endpoint identity token; deleting
  a host sets `host_profile_id` to null.
- `ui/TerminalSpikeViewModel.kt:364-387,457-471` deduplicates saved endpoints by
  protocol/user/host/port but falls back to unique session UUID for unsaved or
  deleted endpoints.
- `ui/connections/ConnectionsComponents.kt:648-666` makes compact row activation
  Connect, but expanded row activation only selects details; Connect becomes a
  second action at `:1256-1264`.
- `app_settings.proto:22,31` persists notification privacy and sensitive
  clipboard-clear seconds.
- `terminal/view/TerminalClipboardWriter.kt:29-77` marks clips sensitive and
  has a robust opaque-token `clearIfCurrent`, but no production scheduler uses
  the returned token.
- `SessionForegroundService.kt:63-93` observes keep-awake and event toggles but
  not notification privacy.
- `SessionForegroundService.kt:98-103` immediately calls `disconnectAll()` from
  the notification service action.
- `SessionForegroundService.kt:307-380` publishes only generic counts with
  `VISIBILITY_PRIVATE`; it never applies privacy policy or a sanitized active
  friendly name.
- `connection/NetworkAvailability.kt:11-60` publishes only a validated-online
  Boolean.
- `connection/mosh/MoshExtensionClient.kt` already exposes
  `updateNetworkHint(sessionId, MoshNetworkHint)` with generation, family, and
  metered state, but `MoshConnectionExtension` at `MoshConnection.kt:567-588`
  omits it and there is no production caller.
- `RoomCredentialAggregateStore.kt:206-276` has safe per-record secret/metadata
  deletion, but no one-transaction “clear all saved credentials” boundary.
- Plan 001 supplies the shared authoritative mutation gate; use it for bulk
  credential and recent-identity migration writes.

## Commands you will need

```bash
export JAVA_HOME=/home/jiyu/.cache/terminal-spike-tools/jdk
```

| Purpose | Command | Expected on success |
|---|---|---|
| Focused JVM | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false :app:testDebugUnitTest --tests 'com.yanjiyu.terminalspike.connection.*' --tests 'com.yanjiyu.terminalspike.core.data.*' --tests 'com.yanjiyu.terminalspike.SessionServiceReliabilityPolicyTest' --tests 'com.yanjiyu.terminalspike.ui.TerminalSpikeUiStateTest'` | exit 0 |
| Room schema | `./gradlew --no-daemon --max-workers=1 :app:kspDebugKotlin :app:compileDebugAndroidTestKotlin` | exit 0; exported schema updated intentionally |
| Lint/build | `./gradlew --no-daemon --max-workers=1 :app:lintDebug :app:assembleDebug` | exit 0 |
| Focused device | run the block below | exit 0 on every resolved target, or no-device skip is reported |
| Required full gate | `./gradlew --no-daemon --max-workers=1 -Pkotlin.incremental=false test lint assembleDebug assembleRelease assembleDebugAndroidTest` | exit 0 |
| Static | `git diff --check` | no output |

Focused device command:

```bash
set -euo pipefail
adb devices -l
readarray -t terminal_targets < <(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1 }')
if ((${#terminal_targets[@]} == 0)); then
  echo 'No authorized Android debugging phone; focused installation/test skipped.'
else
  for serial in "${terminal_targets[@]}"; do
    ANDROID_SERIAL="$serial" ./gradlew --no-daemon --max-workers=1 \
      :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.yanjiyu.terminalspike.RecentEndpointIdentityTest,com.yanjiyu.terminalspike.SessionNotificationActionTest,com.yanjiyu.terminalspike.connection.MoshNetworkHintInstrumentedTest
  done
fi
```

After the required full gate, run this exact update/foreground handoff and report
offline/unauthorized rows from the first command:

```bash
set -euo pipefail
adb devices -l
readarray -t terminal_targets < <(adb devices -l | awk 'NR > 1 && $2 == "device" { print $1 }')
if ((${#terminal_targets[@]} == 0)); then
  echo 'No authorized Android debugging phone; installation skipped.'
else
  for serial in "${terminal_targets[@]}"; do
    adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
    adb -s "$serial" shell am start -W -n com.yanjiyu.terminalspike/.MainActivity
    adb -s "$serial" shell dumpsys activity activities |
      rg 'topResumedActivity.*com\.yanjiyu\.terminalspike/.MainActivity'
  done
fi
```

## Scope

**In scope**:

- `core/model/RecentSession.kt`, Room recent-session entity/DAO/mappers,
  `AppDatabase.kt`, exported schemas, and an explicit migration
- a new device-local endpoint-identity provider under `core/security`
- `connection/SshSessionRepository.kt` recent writer/session metadata
- `ui/TerminalSpikeViewModel.kt`, Workspace/Connections presentation, and
  `ConnectionsComponents.kt`
- `app_settings.proto`, serializer/validation, Settings models/ViewModel/Screen
- `terminal/view/TerminalClipboardWriter.kt` and a new application-scope clear
  scheduler
- `core/data/credential/RoomCredentialAggregateStore.kt` and related DAO for one
  explicit bulk-clear transaction
- `SessionForegroundService.kt`, `MainActivity.kt`, and notification intent/UI
  confirmation contracts
- `connection/NetworkAvailability.kt`, `MoshConnection.kt`,
  `connection/mosh/MoshExtensionClient.kt` adapters only—not AIDL shape
- `core/model/ConnectionModels.kt`, HostProfile Room entity/DAO/mappers,
  `AppDatabase.kt`, exported schemas, and the explicit migration for Mosh locale
  and fallback fields
- backup host payload/mappers/planner/conflict rewrite/recovery for those same
  Mosh fields
- `ui/connections/ConnectionsManagement.kt`, `ConnectionsEditors.kt`, and the
  Mosh bootstrap/request mapping needed to validate and apply locale/fallback
- focused unit/Room/Compose/instrumentation tests and localized strings

**Out of scope**:

- persisting raw unsaved hostname/IP/username in recent-session rows
- changing what constitutes the same connection without tests: canonical key is
  protocol + normalized host + port + username
- storing endpoint tokens in portable backup; recents remain excluded
- deleting host profiles during bulk credential clearing
- directly disconnecting all sessions from a notification tap
- changing Mosh AIDL/API version, native transport, GPL boundary, or public
  distribution policy
- analytics, network logging, SSID/BSSID/address persistence

## Git workflow

- Preserve the current dirty tree and all schema history.
- If authorized to commit, use focused messages such as
  `fix: deduplicate recent endpoints privately`.
- Never commit Keystore material, endpoint fixtures, APKs, or device dumps.

## Steps

### Step 1: Persist a privacy-safe endpoint identity for Recent rows

Add a nullable fixed-format `endpoint_identity_token` to `recent_sessions` and
the domain/mappers. Derive it with device-local HMAC-SHA-256 over this exact
versioned/domain-separated binary encoding:

`UTF8("terminal-spike:recent-endpoint:v1")`, then for each variable field a
four-byte unsigned big-endian length followed by bytes: protocol wire code,
tagged canonical host, and exact UTF-8 username; encode port as a four-byte
unsigned big-endian integer. The host payload starts with exactly one type byte:

- `0x01` followed by exactly four raw network-order IPv4 bytes;
- `0x02` followed by exactly sixteen raw network-order global/unscoped IPv6
  bytes;
- `0x04` followed by sixteen raw network-order IPv6 bytes, then a four-byte
  unsigned big-endian zone length and the exact ASCII zone bytes for a scoped
  literal; validate the zone as `[A-Za-z0-9_.-]{1,32}` and preserve its case;
- `0x03` followed by DNS bytes from
  `IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)`, with one trailing dot removed
  and `Locale.ROOT` lowercase applied.

Remove one surrounding IPv6 bracket pair before parsing. Parse IP literals
without DNS. The IPv4/IPv6 payload is raw binary—not decimal text, RFC text, or
hex text—and the entire tagged payload is the length-framed host field. Reject
invalid/ambiguous literals rather than treating them as a different identity.
Username remains byte-exact because remote account names can be case- and
normalization-sensitive. Output 32-byte HMAC as 64 lowercase hex characters.

Generate/hold the HMAC key in Android Keystore under a project-owned alias. The
token is pseudonymous local metadata: never expose it in UI/logs, never export it
in backups, and never use a raw/reversible hash. Provide an injected fake HMAC
for JVM tests. If Keystore is temporarily unavailable, do not persist a new
unsaved Recent row and surface only a redacted diagnostic; saved-host history can
still use its UUID. If the HMAC key is permanently invalidated while Recent rows
remain, generate a new key and transactionally clear only ended Recent history
before issuing new tokens; null old tokens on any still-active rows and recompute
them from their in-memory non-secret connection seeds under the new key. Never
mix generations or persist raw endpoint.

Add a migration with nullable column and a one-time application backfill for
rows that still reference an existing host. The backfill joins/loads host
metadata under Plan 001's authority gate, computes tokens, and does not retain
plaintext beyond the operation.

**First-release implementation record**: Room version 1 was captured only after the nullable
Recent token and Mosh locale/fallback columns had landed, and no public version 1 artifact exists.
The checked-in version 1 and 2 schemas therefore have the same identity and the registered 1→2
migration is intentionally a tested no-op; reconstructing a fictional earlier export would be less
truthful. The authority-gated application backfill remains responsible for null pre-token rows.
Version 2→3 is the first structural Room migration.

**Verify**: migration tests preserve all old rows; identical canonical endpoints
produce identical tokens, while different protocol/user/port/host do not;
Unicode/hostname normalization cases are explicit; token output contains no
input substring.

### Step 2: Store and present only one ended Recent connection per identity

Attach the token to every new SSH/Mosh session, including quick/unsaved,
reconnect, and double-tap duplicate. Keep separate active session rows so four
live tabs remain independently owned. When a session ends, transactionally
retain the newest ended row for its endpoint token and remove older ended rows
with that token; never delete another active row.

Workspace presentation deduplicates by endpoint token first, saved-profile
canonical identity second for pre-backfill rows, and session ID only as the final
legacy fallback. An open session suppresses the matching ended Recent row by
token even when its saved profile was deleted.

**Verify**: tests cover repeated quick connect, saved then deleted host, two
concurrent duplicate tabs ending in both orders, reconnect replacement, SSH vs
Mosh, different username/port, migration rows, and open-row suppression. No raw
endpoint appears in Recent domain snapshots.

### Step 3: Make saved hosts one-tap at every width

In expanded list/detail layout, make a host row's primary activation Connect,
matching compact behavior. Preserve details through a separate visible and
accessible detail affordance (for example trailing chevron/info action) and
keyboard focus action. Keys/snippets may retain selection-first list/detail
behavior; this change is host-specific.

Workspace pinned/recent row behavior remains one-tap and must preserve
authentication/capability gates: saved password connects directly, missing
secret opens the prefilled auth flow, unsupported Mosh/private-key/KBI never
silently becomes SSH/password.

**Verify**: compact and expanded Compose tests assert one row activation starts
exactly one connection, detail affordance opens detail without connecting, and
disabled/unsupported rows do neither.

### Step 4: Wire token-safe clipboard clearing

Expose validated presets (including Off) for
`sensitiveClipboardClearSeconds` in Security Settings and map them through
`AppPreferences`. Create one application/lifecycle-owned scheduler. Every
accepted selection/link/allowed OSC52 write passes its returned
`TerminalClipboardToken` to the scheduler; a newer write cancels/replaces the
old job. At expiry call only `clearIfCurrent(token)`.

Handle app process death honestly: no background worker should clear an
unverifiable clip after restart. Do not read clipboard in background solely to
enforce the timer. Preserve API 33 sensitive marking and foreground/window-focus
restrictions.

**Verify**: virtual-time tests cover Off, each delay, newer different clip,
newer identical clip, external clip replacement, cancellation, process-scope
close, denied/unavailable clipboard, selection/link/OSC52 writers, and token
wiping/non-disclosure.

### Step 5: Add strongly confirmed atomic credential clearing

Add a Security action “Clear all saved credentials” with an explicit typed
confirmation and a preview count. Under the shared authority gate, perform one
Room transaction in reference-safe order:

1. detach host credential references or delete credential rows so hosts become
   prompt-on-connect;
2. delete all credential metadata;
3. delete every imported/generated key identity and its private-key reference;
4. delete now-unreferenced encrypted secret envelopes;
5. invalidate/reload catalog before reporting success.

Do not delete hosts, terminal/keyboard profiles, snippets, known hosts, recents,
or Keystore master keys. On any failure roll the entire Room transaction back and
retain the confirmation/result state. If product intent is to keep public key
metadata while destroying a non-null private-secret reference, STOP: that
requires a schema/state-model decision rather than an invalid placeholder.

The confirmation copy must explicitly say that all saved passwords,
keyboard-interactive reusable responses, key passphrases, and imported/generated
private keys will be removed; host profiles remain and will prompt again. There
is no ambiguous “passwords only” interpretation in this plan.

**Verify**: Room tests cover shared credentials, key-bound credentials, all
secret kinds, rollback at each stage, host survival/prompt state, cache refresh,
and no remaining referenced ciphertext. Compose tests require the strong
confirmation and preserve error/retry state.

### Step 6: Apply notification privacy and safe Disconnect all

Extend service runtime state with `notificationPrivacyEnabled` and sanitized
active friendly-name metadata from repository snapshots. Behavior:

- privacy enabled: generic count only and secret lock-screen visibility;
- privacy disabled: sanitized friendly name for one active session, otherwise a
  count; never username/host/IP/terminal title/startup command;
- notification remains low-noise, private/local, ongoing, and permission-safe.

Replace the direct service disconnect action with an immutable Activity pending
intent carrying only an action constant. After app-lock/startup gates pass,
MainActivity displays a current-session-count confirmation. Confirmation calls
repository `disconnectAll`; Cancel/no response changes nothing. A stale intent
with zero sessions becomes a no-op.

Persist a one-time notification-permission education marker so denied/dismissed
users are not prompted on every session start. Settings may expose an explicit
Open notification settings action for later opt-in.

**Verify**: pure notification tests inspect both privacy modes and redaction;
instrumentation invokes the PendingIntent and proves no disconnect before
unlocked confirmation, Cancel, confirm, zero-session, Activity recreation, and
permission-denied/no-repeat cases.

### Step 7: Publish bounded Android network hints to active Mosh sessions

Evolve `NetworkAvailability` to publish a non-identifying snapshot containing:

- validated online state;
- monotonically increasing process connectivity generation;
- current default network address family (IPv4/IPv6/unknown using the API's
  supported constants);
- metered Boolean.

Do not inspect/store SSID, BSSID, IP address, carrier, VPN owner, or traffic.
Extend `MoshConnectionExtension` with `updateNetworkHint` and forward the API's
existing `MoshNetworkHint`. `SshSessionRepository` observes one process flow and
sends only changed generations to active Mosh sessions. Serialize with start/
stop/replacement ownership; stale session UUIDs must receive no call. Failures
are redacted and do not tear down an otherwise functioning Mosh transport.

**Verify**: tests cover Wi-Fi↔cell/VPN-style generation changes through fakes,
meter/family changes, duplicate callback coalescing, offline/online, four active
sessions, replacement/close races, Binder death, and zero calls for SSH. A device
test verifies the Binder call reaches the extension test seam.

### Step 8: Add explicit Mosh locale and SSH-fallback policy

Extend the HostProfile/editor/backup schema with a validated Mosh locale and an
explicit fallback enum: Never, Ask, or Automatic. Locale is a bounded ASCII
locale token ending in `.UTF-8` plus the portable `C.UTF-8`; default to the
current documented bootstrap value only when the host has no override. Pass it
to `MoshBootstrapRequest` without treating it as shell syntax.

Fallback may run only after a classified Mosh extension/bootstrap/UDP failure,
never after a host-key or authentication rejection. Ask shows a clear “start a
new SSH shell” decision. Automatic is opt-in and uses a newly created SSH
transport with honest discontinuity/tmux guidance; it must not reuse wiped
transient authentication, silently mutate the saved protocol, or leave a
partially started Mosh session/worker owned. If fresh authentication is required,
open the existing prompt instead of falling back silently.

**Verify**: model/editor/backup tests cover locale and all policies; runtime tests
cover absent/incompatible extension, bootstrap failure, UDP failure, auth/trust
non-fallback, cleanup, reusable versus transient auth, and a visible fresh-shell
notice.

## Test plan

- Extend Room `AppDatabaseTest` and repository tests for schema/backfill/cleanup.
- Extend `TerminalSpikeUiStateTest` and Workspace/Connections Compose tests for
  unique recents and one-tap expanded hosts.
- Extend clipboard writer instrumentation using its current strict foreground
  synchronization; never weaken production safety to make OEM tests pass.
- Extend service contract tests into actual action/lifecycle tests with an
  injected repository.
- Extend Mosh connection/client tests and controlled Binder instrumentation; do
  not require a real server until Plan 005 acceptance.

## Done criteria

- [x] Repeated same-endpoint quick/saved/deleted-host sessions show and retain
      one Recent connection without storing raw endpoint metadata.
- [x] Saved host primary activation connects in one tap at compact and expanded
      widths.
- [x] Sensitive clipboard timer is user-configurable and every terminal copy
      path uses exact-token clearing.
- [x] Strongly confirmed clear-all removes saved credentials atomically while
      preserving unrelated profiles/data and refreshing live UI.
- [x] Notification privacy changes content/visibility, and notification
      Disconnect all cannot act before current-state confirmation.
- [x] Notification permission denial is not repeatedly nagged.
- [x] Every active Mosh session receives bounded generation/family/meter hints;
      SSH receives none and stale sessions receive none.
- [x] Mosh locale is configurable/validated and fallback is explicit, bounded,
      cleanup-safe, and never disguises a fresh SSH shell.
- [x] Schema, JVM, Android compile, lint/build, focused device tests, and diff
      check pass.
- [x] Required full unit/lint/build gate passes; current APK is installed/launched
      on every authorized phone with foreground proof, or no-device skip is
      explicitly reported.

## STOP conditions

Stop and report if:

- endpoint deduplication would require storing raw unsaved endpoint metadata or a
  reversible/unkeyed stable hash;
- Room migration cannot preserve active/recent rows losslessly;
- bulk clear cannot be one transaction or would delete host profiles;
- private-key metadata cannot represent the intended post-clear state;
- notification confirmation would bypass app lock/startup recovery;
- the only Mosh hint design changes the frozen AIDL/API or records network
  identity data;
- an OEM clipboard test requires weakening token/focus checks in production.

## Maintenance notes

- Endpoint identity is local pseudonymous metadata and remains excluded from
  portable backups and user-visible logs.
- New clipboard writers must return/register the ownership token.
- New notification actions must route through current-state confirmation.
- The Mosh hint generation is process-local; monotonicity across process restart
  is neither needed nor claimed.
