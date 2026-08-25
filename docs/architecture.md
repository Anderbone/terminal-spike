status: active
created_at: 2026-08-08T10:42:15.487Z
updated_at: 2026-08-10
done_at: none
independent: yes
dependencies: none

# Product architecture

## Open Questions

- None for the main-app implementation. Distribution of the GPL Mosh extension still requires the legal review recorded in `docs/mosh-extension-protocol.md`; that review does not block the SSH-capable main app, local/debug evaluation, or the permissive IPC contract.

## Goal

Record the implemented production structure for a local-first SSH terminal with an optional separately installed Mosh transport. The bounded VT engine and custom Android Canvas renderer remain intact while product data, credentials, navigation, and live session ownership use process-durable boundaries.

`docs/ARCHITECTURE.md` is the concise implemented overview. This lowercase document is authoritative for the detailed production and migration boundaries requested by the completion brief.

## Module boundaries

The project stays package-oriented inside the main `app` module until a boundary changes packaging, licensing, or testability enough to justify extraction.

```text
app (com.yanjiyu.terminalspike)
├── app                    Application, activity, service, notifications, assembly
├── core/model             Stable UUID models and enums; no Android dependencies
├── core/data              Room database, DAOs, migrations, global settings, repositories
├── core/security          CredentialStore, Keystore crypto, backup crypto, redaction
├── connection             JSch/Mosh adapters, trust, connection tests, sessions/reconnect
├── terminal               VT engine, buffers, viewport, selection, renderer and input
└── ui                     Compose navigation, screens, immutable UI state and ViewModels

mosh-api (com.yanjiyu.terminalspike.mosh.api)
└── permissive AIDL, parcelables, capabilities and error codes only

mosh-extension (com.yanjiyu.terminalspike.mosh)
└── separate Android application/APK, service, JNI/native client and GPL materials
```

No GPL Mosh implementation, native object, resource, or implementation dependency may appear in the main app's packaged dependency graph. `mosh-api` is deliberately small, project-owned, and permissively licensed for both applications.

### Implemented Mosh slice

The optional transport now follows these concrete boundaries:

1. The main app discovers only the exact extension component, verifies its signature-protected permission and signing lineage, then negotiates API version and capabilities.
2. The existing strict JSch path resolves the authenticated TCP peer, verifies the host key, authenticates with a password or imported private key, and runs one safely quoted `mosh-server` bootstrap command.
3. A bounded parser accepts exactly one official `MOSH CONNECT` response. The main app gives the extension a numeric address, UDP port, dimensions, locale, reviewed flags, and a one-shot key PFD; it never sends the SSH credential or known-host state.
4. `MoshConnection` adapts the extension to the same `Connection`/terminal-controller boundary as SSH. Binder carries bounded control/state only; terminal input and output use PFD streams with bounded writer backpressure and coalesced resize.
5. The separate extension broker assigns each session to one of ten private worker processes. Each worker owns one pinned upstream Mosh 1.4.0 transport/terminal engine compiled for `arm64-v8a` or `x86_64`, avoiding unsupported cross-session sharing of upstream process globals.
6. Settings observes the real extension client and shows exact package/trust/API/capability state. When Mosh is unavailable, saved profiles remain intact and SSH stays available as an explicit manual fallback.

This slice is implemented and buildable. The latest recorded controlled-server runs completed password-authenticated SSH bootstrap and real UDP terminal input/output on both authorized Android 16 phones. The application-owned session repository now publishes bounded Android connectivity generations to live Mosh sessions. Private-key device bootstrap, simultaneous live sessions, resize, failure injection, extension-absence SSH, and observed network-transition/roaming acceptance remain open. The host model still cannot represent link-local IPv6 zone identifiers; the native Mosh protocol retains its authenticated UDP roaming and port-hopping behavior.

## Runtime ownership

```text
Compose screen / ViewModel
        │ commands + immutable StateFlow snapshots
        ▼
AppContainer.SshSessionRepository ── application-process scope
        │
        ├── foreground-start gate ── SessionForegroundService
        │                              └── ongoing private notification/actions
        ├── SSH SessionRuntime ── JSch ── remote SSH server
        │       └── VtTerminalEngine → TerminalController → FastTerminalView
        │
        └── Mosh SessionRuntime ── explicit verified Binder ── Mosh extension APK
                └── PFD input/output pipes → same terminal engine/controller
```

The application-owned repository owns transports, parser instances, controllers, session metadata, retry jobs, connectivity observation, and the Mosh binding. `SessionForegroundService` observes that same repository, owns the foreground notification and optional CPU-awake lease, handles notification actions, and fails active sessions if its required service lifetime is lost. Activities and ViewModels own presentation state and short-lived commands only. Neither a screen nor navigation owns a raw socket.

The ownership cutover is implemented for SSH and Mosh. Activity or ViewModel recreation reattaches to process-owned snapshots and controllers; it does not recreate or own the connection. Remaining notification, battery, process-death, and multi-device walkthroughs are acceptance gaps rather than an ownership defect.

The service starts only after a visible, user-initiated connect action. It stops when no session is connecting, connected, reconnecting, or awaiting an explicit trust/authentication decision. Activity recreation reattaches to repository state. Process death does not claim to preserve live SSH/Mosh sockets; persisted recent-session metadata can offer an honest reconnect.

## Foreground-service decision

The current target SDK is 37. An active remote terminal does not truthfully fit media, remote messaging, connected-device, or data-sync categories. Use `specialUse` with:

- `android.permission.FOREGROUND_SERVICE`;
- `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`;
- `android:foregroundServiceType="specialUse"`;
- `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value: `active user-started remote terminal sessions`.

The service is started while the app is visible and immediately posts its notification. The Play Console declaration must describe why deferral or interruption breaks the explicitly user-visible terminal connection and include the required demonstration. This follows the current official [Android service-type guidance](https://developer.android.com/develop/background-work/services/fgs/service-types), [manifest guidance](https://developer.android.com/develop/background-work/services/fgs/declare), and [Play declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821).

Notification denial never causes a hidden session. The app explains the platform limitation, handles start/notification failures, and either keeps the connection only while legally permitted or disconnects safely. It never mislabels the service to evade policy.

## Navigation and adaptive layout

Phones expose exactly three primary destinations:

1. Connections
2. Terminal
3. Settings

Connections is the first bottom destination and owns saved hosts, keys, and snippets. Terminal is the second destination and owns active sessions. Back first lets the IME hide when appropriate; leaving terminal detail keeps every session active; disconnect is explicit. Predictive back animates toward the owning surface without binding transport lifetime to the transition.

Compact width uses Material `NavigationBar`. Expanded width uses `NavigationRail`; Connections can show list/detail. Terminal detail consumes all additional width rather than retaining a phone-width column. Every shell handles status/navigation/IME/cut-out insets edge to edge.

## UI state and dependency assembly

- Repositories and service clients expose `StateFlow` of immutable models.
- ViewModels map domain models into screen-specific immutable UI state. Product-authored copy uses resource-backed `UiText`; dynamic text is reserved for bounded user/provider/runtime data.
- Decrypted credentials are requested just in time through opaque credential references and never enter UI state, saved state, routes, or navigation arguments.
- Constructor injection is implemented with a small application-owned container. No heavy DI framework is added.
- `TerminalSpikeApplication` creates exactly one `AppContainer` per process; that container owns the
  singleton Room database, Proto DataStore repository, application coroutine scope, and logger.
- All production components receive clocks, ID generators, dispatchers, stores, and transport factories through constructors where determinism/security testing benefits.
- User-visible strings are resources. A reviewed direct-literal allow-list and Spanish non-default-locale smoke cover the release UI boundary; manual language/accessibility matrices remain separate. Terminal cells, lines, scroll offsets, selection internals, and byte flow do not become Compose state.

## Product data

Room 2.8.4 owns relational non-secret data:

- host profiles;
- credential metadata/references;
- SSH key public metadata;
- known hosts;
- snippets;
- terminal profiles and custom palettes/font references;
- keyboard profiles and ordered keys;
- recent-session metadata and favourites/tags.

Foreign keys are explicit and nullable only where the UI can represent “missing reference.” Stable UUID strings are generated at creation and retained across merge/restore. Created/updated/first-seen/last-seen timestamps are epoch milliseconds supplied by an injected clock. Exported Room schemas and explicit migration tests are committed. Destructive fallback is prohibited.

Room schema version 3 uses canonical lowercase UUID `TEXT` primary keys, stable string wire codes rather than Kotlin enum names, and these twelve tables:

The checked-in version 1 and version 2 exports are deliberately identical pre-release
checkpoints: the privacy-safe Recent token and Mosh locale/fallback columns were already present
when the first Room schema was captured, so the registered 1→2 migration is a tested no-op rather
than a fabricated field history. Version 2→3 is the first structural Room migration and adds custom
themes plus terminal-profile rendering flags. No public or production-signed version 1 artifact
exists; future migrations must preserve these exports without rewriting that history.

| Table | Purpose and key columns |
|---|---|
| `terminal_profiles` | Theme/font/size/geometry/cursor/scrollback/bell/scroll/link/TERM/alternate-history plus timestamps |
| `custom_terminal_themes` | UUID-backed default/ANSI/cursor/selection colours, bold-as-bright policy, and timestamps |
| `keyboard_profiles` | Name, row count, modifier policy, haptic/repeat, input mode, tmux prefix, timestamps |
| `keyboard_profile_keys` | `(profile_id, position)` primary key, stable action code, unique action per profile; cascade on profile deletion |
| `encrypted_secrets` | Kind, envelope/key version, nonce, ciphertext, READY/LEGACY_UNAVAILABLE state, stable failure code/legacy ID, timestamps; accessible only through `CredentialStore` |
| `ssh_key_identities` | Public metadata/key/fingerprint/provenance/passphrase flag/comment and private-secret reference |
| `ssh_credentials` | Password/private-key/keyboard-interactive kind and kind-valid secret/key references |
| `host_profiles` | Complete host model, optional credential/terminal/keyboard references and per-host SSH/Mosh overrides |
| `recent_sessions` | Display-safe host snapshot/reference, protocol/state, start/activity/end timestamps, and sanitized optional terminal title |
| `known_hosts` | Canonical host, port, algorithm, fingerprint, public key, first/last seen; unique host+port+algorithm |
| `snippets` | Name/group/command/insert-or-send/append-Enter/multiline-confirm/favourite/update time |
| `legacy_migration_state` | Per-source digest/version/state/error/warnings/completion so settings and known hosts migrate independently |

`encrypted_secrets` stores only authenticated ciphertext/nonce; plaintext never enters SQLite or its WAL. Crypto runs before a transaction, then secret plus referencing metadata commit atomically. Host credential/profile references use `SET NULL`, referenced identities use `RESTRICT`, and ordered keyboard children use `CASCADE`. Repository validation enforces bounded strings/blobs and kind-dependent combinations that are awkward as SQL constraints. UI observes aggregate repository `Flow`s rather than secret DAOs.

Proto DataStore 1.2.1 owns typed global preferences through a checked-in protobuf schema and explicit migrations: appearance, default terminal/keyboard profile, global keepalive/reconnect, background/notification, security, and backup UX state. It holds no credential payload, imported private key, transcript, Mosh key, or complete connection URI.

### Legacy migration

The existing encrypted `UserSettingsCodec` v1–v3 file, password files, private-key files, and OpenSSH known-host file are inputs to a one-time idempotent migration:

1. Open/decrypt legacy inputs without modifying them.
2. Validate all bounded records and derive UUIDv5 identifiers from committed namespace
   `4b15d1d9-31d7-4d0e-9ea9-bd041f34fb72` and exact type/legacy-ID names; new records use UUIDv4.
3. Insert non-secret records and global settings in a database transaction.
4. Re-encrypt portable secret payloads through `CredentialStore` under UUID identifiers.
5. Verify every migrated secret can be reopened before recording migration success.
6. Retain legacy inputs as a device-encrypted recovery snapshot through the migration grace version; never delete them merely because decryption failed.
7. On restart, repeat safely using deterministic identifiers and upsert rules.

The importer does not call the compatibility settings load path. It uses an existing-key-only legacy reader returning typed Missing/Key unavailable/Corrupt/Unsupported results. It decrypts passwords with the exact stored legacy host/port/username AAD and private keys with legacy ID AAD, then re-encrypts under immutable secret UUID+kind AAD. An unavailable individual secret becomes a referenced `LEGACY_UNAVAILABLE` row while source ciphertext/key are retained; an unreadable aggregate records BLOCKED and exposes recovery rather than silently starting empty.

All rows plus the terminal marker use `INSERT(ABORT)` in one transaction. A missing settings source
seeds the fixed default terminal and keyboard profiles and records `ABSENT` in that same
transaction; a missing known-host source records an empty `ABSENT` marker. An explicitly confirmed
recovery reset replaces a blocked source with `DISCARDED_AFTER_RECOVERY`. `COMPLETE`, `ABSENT`, and
`DISCARDED_AFTER_RECOVERY` are authoritative before any retained source is opened, so a file that
appears or changes later cannot resurrect data. Known hosts migrate independently, parse
`[host]:port` or default port 22, retain the current last-valid-duplicate behavior, and record
malformed lines as warnings. Old files and aliases remain in place as retained recovery inputs; the
terminal Room marker, rather than file deletion, prevents re-import.

The application container owns one explicit, single-flight migration entry point. The production
Room-consumer cutover starts and awaits it before catalog reads, writes, deletion, and recovery-reset
paths proceed. An application-wide authority gate also serializes pending Replace recovery against
normal mutations. This prevents retained legacy files or a stale cached catalogue from resurrecting
data that the user changed or explicitly removed.

The cutover closes the historical identity-ID collision, hostname-case/AAD mismatch, destructive settings reset, and incomplete `AtomicFile` recovery risks catalogued in `docs/current-state-audit.md`.

## Terminal boundary

`VtTerminalEngine` remains Android-independent and bounded. Transport byte reads and parser work run off the main thread. Completed scrollback and immutable screen changes enter `TerminalController`, which coalesces work to display frames. `FastTerminalView` draws calculated visible rows plus overscan using cached metrics and Paint objects.

Required invariants:

- no Compose node or state per terminal cell or line;
- no parser publication per received byte;
- bounded normal/alternate history, queues, CSI/OSC strings, replies, selection, transcript, and paste;
- no disk/network I/O on the main thread;
- invisible sessions parse transport state but do not render at full frame frequency;
- font/theme changes invalidate cached renderer metrics once, not screen state per cell;
- resize/IME/fold changes settle PTY size without repeated redraw storms.

## SSH boundary

The current JSch engine remains. `JschSshConnection` owns DNS/TCP/negotiation/auth/channel work, `KnownHostManager` owns strict trust decisions backed by Room, and the application-owned `SshSessionRepository` owns connectivity-aware bounded retries. A staged connection test uses the same trust/authentication path but does not run saved startup input or leave a shell running.

First contact supports trust once or trust and save. Any presented key for a previously trusted host/port that does not match an explicitly stored key blocks, including algorithm changes. Key replacement is a separate confirmed management action showing old and new fingerprints.

Keepalives use the SSH protocol, not shell characters. Reconnect creates a new shell and is labelled as such. Intentional disconnect cancels retry. Errors have a concise typed summary plus optional redacted technical detail.

## Backup boundary

Backup serializes a repository snapshot, never Room/Datastore files directly. Import authenticates and parses a bounded stream before preview. The repository applies Merge/Replace/Keep-both in one transaction; Replace first creates a device-encrypted recovery snapshot. Details are normative in `docs/backup-format.md`.

## Release/debug boundary

Renderer workloads, performance overlays, fake Mosh services, test hosts, verbose protocol capture, and developer menus live under debug-only sources or a `BuildConfig.DEBUG` route that release cannot reach or retain. Release verification inspects the shrunk artifact for old debug labels/routes and checks the runtime dependency graph.

## Verification

- Room schema and every migration path, including legacy import and rollback.
- Service ownership across Activity recreation/navigation and honest process-death behavior.
- Notification permission denied, service-start restriction, action, privacy, and teardown behavior.
- Main APK build/install/SSH with the extension absent.
- Main and extension independent installation, verified negotiation, strict bootstrap, real UDP terminal I/O/resize, explicit SSH fallback, and teardown on extension removal/death.
- Core Mosh roaming across IPv4/global-IPv6/VPN changes; Android connectivity-hint publication is implemented, while real transition/roaming evidence remains open and link-local IPv6 zone IDs are out of scope for the current host model.
- Renderer/parser/buffer/viewport/batching/workload regression suites.
- Compact/expanded/large-text/IME/multi-window behavior.
- Full commands and exact-device installation gates in `IMPLEMENTATION_STATUS.md`.
