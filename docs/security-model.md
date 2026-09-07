status: active
created_at: 2026-08-08T10:42:15.487Z
updated_at: 2026-08-10
done_at: none
independent: no
dependencies: docs/architecture.md

# Security model

## Open Questions

- None for local implementation. Final Mosh-extension distribution needs the specialist GPL/Play-signing review described in `docs/mosh-extension-protocol.md`.

## Security goals

- Unknown SSH host keys are never silently trusted; mismatches always block.
- Passwords, private keys, saved passphrases, and Mosh session keys are encrypted or absent in app-private storage and narrowly scoped in memory. Portable backup files are a user-controlled exception and must be kept private.
- Terminal contents, snippets, credentials, and complete connection strings do not enter ordinary logs, notifications, analytics, crash upload, or hidden network requests.
- A lost/corrupt/invalidated device key produces an explicit recoverable state; it never silently erases user data.
- Main-app compromise is not expanded by an unverified extension package, and the extension never receives saved SSH authentication material.
- Backup confidentiality and authenticity do not depend on exporting Android Keystore material.

## Trust boundaries

1. Android application sandbox and app-private Room/DataStore/files.
2. Non-exportable Android Keystore keys used by the main app.
3. Remote SSH server and all terminal output, which are untrusted.
4. Android IME and clipboard, which are user-selected system components but may retain entered data.
5. Storage Access Framework providers, which receive only user-selected encrypted backup/custom-font/transcript files.
6. Optional Mosh extension APK, which is a separate UID/process and remains untrusted until package, signature, permission, and API checks pass.
7. Android notifications/recents/screenshots, which can expose UI to the lock screen or physical observers unless privacy controls apply.
8. The user-installed Android speech recognition service. Voice input requests offline processing,
   holds interim/final text only in the terminal composer, and never opens an app-owned network
   connection; provider availability and processing behavior remain controlled by Android.

The design does not claim protection from a rooted/compromised OS, a malicious accessibility service, a hostile IME selected by the user, an attacker controlling an unlocked app, or a remote shell that the user deliberately authenticated to.

## CredentialStore

`CredentialStore` exposes opaque UUID references and purpose-specific operations. It does not expose a generic “list plaintext secrets” API.

Credential kinds:

- saved SSH password;
- imported private-key payload;
- explicitly saved key passphrase, if that option is enabled;
- internal migration/recovery snapshot key material.

Each payload is bounded before allocation, encrypted with AES-256-GCM, and authenticated with canonical associated data containing format version, credential UUID, credential kind, and relevant immutable scope. Device-local AES keys are generated in Android Keystore, non-exportable, app-specific, and never backed up.

Decryption occurs on an I/O dispatcher immediately before transport or backup use. Callers receive mutable byte arrays or a scoped callback. Arrays are zeroed in `finally` blocks when practical. Passwords/passphrases/private keys never enter Compose `rememberSaveable`, ViewModel state, Room, DataStore, navigation, notifications, or logs.

Credential metadata contains only kind, display-safe reference information, creation/update timestamps, protection/provenance flags, and public key material/fingerprint where applicable. Hosts reference a credential UUID; they do not duplicate secrets.

### Failure and recovery

- Missing ciphertext, invalid GCM tag, invalidated/unavailable Keystore key, and malformed version are distinct typed errors.
- The store does not delete ciphertext or Keystore aliases on a read failure.
- UI explains that device-bound credentials cannot be decrypted and points to Full encrypted backup restore where available.
- Explicit “clear saved credentials” requires strong confirmation and reports partial file failures.
- Migration deletes no legacy source until the target record has been decrypted, re-encrypted, reopened, and transactionally referenced.

The legacy settings/key readers now preserve unreadable ciphertext and Keystore aliases, use
`AtomicFile.openRead()`, and block accidental overwrite while recovery is required. The Room
migration still keeps the legacy files and aliases as recovery inputs until a later explicit cleanup.

## SSH host identity

Known-host identity is keyed by canonical host plus explicit port. Stored records include algorithm, full public host key, SHA-256 fingerprint, first-seen, and last-seen timestamps.

- No prior record: show canonical host/port, algorithm, and fingerprint; offer Reject, Trust once, or Trust and save.
- Exact stored match: proceed and update last-seen.
- Any non-matching key for a previously trusted host/port, including an algorithm change: block before authentication and show previous/new algorithms and fingerprints.
- Replacement/removal: separate Security action with destructive confirmation. No global accept-all option exists.

“Trust once” is session-memory only. Backup includes saved known-host public records in both modes. Import conflicts never silently replace a different key.

## Authentication and transport

- JSch strict host-key checking remains enabled.
- Password, private-key, passphrase-protected key, and keyboard-interactive prompts are bounded and delivered only to the active transport.
- Connection tests use the same verifier and never weaken trust to collect diagnostics.
- Keepalive sends protocol messages, not visible terminal characters.
- Retry is bounded, connectivity aware, cancelable, and disabled after intentional disconnect.
- Ordinary UI receives typed redacted errors. Raw stack traces/JSch messages are debug-only and pass through redaction.
- Startup commands and snippets are treated as user content and are not logged.

## Redaction and privacy

A central redactor is used at every logging/error boundary. It removes or replaces:

- passwords and passphrases;
- PEM/OpenSSH private-key blocks and long key-like Base64 tokens;
- `MOSH_KEY` and Mosh connection keys;
- user-info in URIs and complete connection strings;
- terminal lines and snippets unless the user explicitly creates a local debug capture;
- query/detail fields known to contain authentication responses.

The implementation boundary is `core/security/AppLogger`. Release builds emit only stable event
codes and never include caller-provided detail, endpoint text, exception messages, or stack traces.
Debug diagnostics are capped at 2,048 characters and pass through `SensitiveLogRedactor`; redaction
is defense in depth, so production callers must still avoid supplying decrypted secret material.

Release builds disable verbose JSch/native protocol logs, transcript logs, automatic crash upload, analytics identifiers, telemetry, advertising, cloud sync, and account requests. About includes a concise local-first statement and the exact backup/extension exceptions.

## Terminal-originated capabilities

Remote terminal output is untrusted input.

- CSI/OSC/control strings and replies are length/count bounded.
- OSC 8 links are parsed and displayed safely; activation requires an explicit user gesture and validated scheme.
- OSC 52 clipboard writes are Disabled or Ask, with Disabled/Ask as defaults; never silently allowed globally.
- Multiline clipboard/snippet/text sends follow the configured confirmation policy.
- Selection/copy is bounded. Sensitive clipboard content can be auto-cleared after a user-selected delay where Android still contains the exact copied value.
- Transcript export occurs only through explicit SAF action and never includes credentials supplied outside terminal output.

## Backup security

The app exposes one complete, authenticated portable backup without a passphrase prompt. It contains portable saved passwords, private keys, saved passphrases, settings, profiles, snippets, and referenced fonts. Its app-defined portability material detects corruption but is not a confidentiality boundary, so possession of the file must be treated as access to those credentials. Android Keystore masters, live sockets, temporary prompts, Mosh keys, logs, and transcripts are never exported. The exact format and transaction behavior are in `docs/backup-format.md`.

## Mosh extension security

The implemented local/debug boundary is defensive on both sides:

- The main app binds one explicit package/component protected by a signature permission. It validates the service declaration, permission owner/protection, installed signing certificate/lineage, API range, model version, capabilities, and session limit before use.
- The extension verifies the Binder caller UID resolves to the exact main-app package with a matching signature on every entry point. Its broker and ten private worker processes are separate from the main application's UID/process.
- The main app performs DNS/TCP, strict SSH host verification, password/private-key authentication, and one bounded `mosh-server` bootstrap. Only session ID, numeric endpoint, UDP port, ephemeral Mosh key, dimensions, locale, and reviewed option flags cross the boundary; the public request model has no hostname credential, password, private key, passphrase, or saved credential ID field.
- The ephemeral 22-character key crosses a one-shot PFD. The extension requires exactly 22 bytes plus EOF, avoids environment variables and persistence, and clears mutable printable/decoded key buffers after native initialization. Bootstrap and failure cleanup close descriptors and wipe main-app mutable key material.
- Terminal bytes use separate PFD pipes with bounded backpressure, not repeated high-frequency Binder transactions. Binder carries only bounded control and redacted state events.
- Each worker process owns one native session, disables core dumps, validates every request, and tears down on caller death, Binder death, pipe closure, cancellation, or service destruction. A lost session cannot be restored because the key is intentionally not retained; retry requires a new strict SSH bootstrap.
- The application-owned session repository publishes changed, non-identifying Android connectivity generations only to currently connected Mosh transports. The extension receives bounded family/metered hints, not account, location, endpoint credential, or device identifiers. Core authenticated Mosh UDP roaming remains in the native transport. Link-local IPv6 zone identifiers are rejected by current host validation and are not silently stripped.

The Settings status surface reports absent, disabled, untrusted, incompatible, available, and operational-error states without binding an unverified package. SSH remains an explicit fallback and no failure silently changes the requested protocol.

APK/process separation is an engineering security boundary, not a claim that GPL obligations are resolved. ADR-003 permits local/debug evaluation only; public Mosh distribution remains blocked pending the recorded GPL, installation-information, store/signing-model, and trademark review. Production signing authority has not been supplied and no production-signing claim is made.

## Device/UI controls

- Optional app lock uses `BiometricPrompt` with device-credential fallback and immediate/delayed/background policies.
- Optional screenshot blocking applies while terminal or credential material is visible and clearly explains its scope.
- Notification privacy hides username, hostname/IP, terminal title, and command content; it can show a generic session count.
- Background health uses public Android settings intents only and never nags or silently requests unrestricted battery access.
- CPU wake and screen-on behavior are opt-in, scoped to active/visible sessions, and released immediately.
- Voice input requests microphone permission only after the composer microphone is tapped. The
  active control visibly changes to Listening/Processing with an explicit Stop action, and
  recognised text is reviewable before the user sends it to a terminal.

## Security verification

- Credential encrypt/decrypt/AAD mismatch/corrupt/truncated/missing/invalidated-key tests.
- Legacy migration collision, interruption, idempotency, and rollback tests.
- Same/different-algorithm host mismatch, trust-once, trust-save, explicit replacement, and concurrent trust tests.
- Redaction fixtures for every secret type and release-log inspection.
- Backup wrong-passphrase/tamper/truncation/newer-schema/transaction rollback tests.
- Extension missing/wrong signer/wrong caller/API mismatch/death/FD cleanup tests.
- Release APK scan for test keys, PEM blocks, credential fixtures, debug screens, protocol logs, analytics/ads/account/network SDKs, and native Mosh objects.

The API, trust-decision, bootstrap/parser, PFD, redaction, application-owned lifecycle,
network-hint, and native-build paths have host/unit or focused instrumentation coverage. The latest
controlled-server runs on the exact model-checked old `SM-S911B` completed password and private-key
bootstrap, real UDP terminal I/O, four simultaneous sessions with independent resize/close,
isolated-worker death with slot reuse, and broker death with automatic rebind and fresh traffic.
The worker-death run also proved that an extension failure wins the bounded PFD-EOF/Binder-event
race instead of being reported as a clean disconnect. The full current old-phone application suite
passed 317 tests with zero failures/errors and 14 expected opt-in skips; current policy forbids
tests on the connected fold. A separate external API 35 gate proved ownership-checked exact-PID
death, clean service/notification teardown, retained host/recent metadata, zero persisted
session-only secret credentials, honest empty-session restart, password re-prompt, and real SSH
afterward. Its deep-idle, restricted-standby, and Data Saver mutations were restored exactly.
Security acceptance remains open for observed network transitions/roaming, OEM notification/battery
modes, the user's external Mosh server, and the final manual device matrix. The current CI-equivalent
release-like run used a fresh ephemeral acceptance identity that was removed with its signed
outputs; production signing and public Mosh distribution remain separate external gates.
