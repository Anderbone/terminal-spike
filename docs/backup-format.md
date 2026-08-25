status: implemented-manual-provider-validation-pending
created_at: 2026-08-08T10:42:15.487Z
updated_at: 2026-08-08T11:14:08.829Z
done_at: none
independent: no
dependencies: docs/architecture.md, docs/security-model.md

# Portable backup format

## Open Questions

- None. The product exposes one complete backup type with no passphrase prompt.

## User contract

- Export label: **Save backup to Drive or device**.
- Import label: **Restore from Drive or device**.
- Extension: `.terminalspike-backup`.
- MIME type: `application/vnd.yanjiyu.terminalspike.backup`, with `application/octet-stream` accepted on import for providers that discard custom MIME types.
- Export uses `ACTION_CREATE_DOCUMENT`; import uses `ACTION_OPEN_DOCUMENT` through Activity Result APIs.
- No storage permission, Google login, direct Drive API, local-path assumption, or custom cloud backend.

Any installed SAF provider—including Google Drive—can appear in the system picker. This follows the official [Storage Access Framework guidance](https://developer.android.com/training/data-storage/shared/documents-files).

## Stored contents and privacy

The UI always exports the existing Full envelope mode and includes referenced custom fonts. The
Standard mode remains readable at the codec layer for backwards compatibility, but is not offered
as a product choice.

### Standard encrypted backup

Includes:

- hosts without portable secret payloads;
- snippets;
- known hosts;
- app appearance;
- terminal profiles and complete custom palettes (foreground/background, ANSI 16, cursor,
  selection, and bold-bright behaviour);
- keyboard profiles;
- session/background/notification settings;
- Mosh host settings;
- favourites, groups/tags, and safe timestamps.

Credential references that cannot resolve after import are represented as “authentication required,” never as a working saved credential.

### Full encrypted backup

Includes Standard plus:

- saved SSH passwords;
- imported private-key payloads;
- explicitly saved key passphrases;
- other user-approved portable credential payloads.

Before export, device-bound secrets are decrypted just in time and immediately re-encrypted inside the backup payload. Android Keystore keys are never exported.

The product export includes only profile-referenced, content-addressed imported font bytes. Import
rechecks the SHA-256 content ID, 16 MiB per-font cap, supported TrueType/OpenType/collection header,
and fixed-grid representative glyph advances before any profile can reference the file. Active
sockets, recent terminal contents, transcripts, temporary prompts, Mosh ephemeral keys, debug logs,
and recovery keys are always excluded.

The envelope retains authenticated encryption for corruption/tamper detection, but the portability
material is app-defined rather than supplied by the user. It is not a confidentiality boundary:
anyone who obtains the backup file must be treated as able to recover its credential contents.

## Envelope version 1

All integers are unsigned big-endian. Parsers reject integer overflow, negative platform conversions, duplicate required fields, lengths beyond configured bounds, and trailing bytes after the declared envelope.

```text
8 bytes   magic ASCII "TSPBKP01"
4 bytes   envelope header length
N bytes   canonical header TLV
8 bytes   ciphertext length
M bytes   AES-GCM ciphertext followed by 16-byte tag
```

The exact magic plus canonical header bytes are AES-GCM associated data. Header TLV uses `field-id:u16`, `length:u32`, `value:length` and is serialized in ascending field order. Unknown optional header fields are retained for display where useful and skipped for decryption. Unknown required-field IDs have the high bit set and cause an unsupported-format error.

Required header fields:

| ID | Field | Encoding |
|---:|---|---|
| 1 | Envelope schema version | `u32`, value `1` |
| 2 | Backup mode | `u8`: `1` Standard, `2` Full |
| 3 | Created at | epoch milliseconds `u64` |
| 4 | App version code/name | nested bounded UTF-8 fields |
| 5 | Payload schema version | `u32` |
| 6 | KDF | `u8`: `1` PBKDF2-HMAC-SHA256 |
| 7 | KDF iterations | `u32` |
| 8 | KDF salt | 32 random bytes |
| 9 | AEAD | `u8`: `1` AES-256-GCM |
| 10 | Nonce | 12 random bytes |
| 11 | Non-personal metadata | min/target SDK and format-capability bits only |

No device serial, Android ID, account, hostname, IP, user name, advertising identifier, or analytics identifier is present.

## Cryptography

1. Encode the app-defined portability material as UTF-8 for KDF input.
2. Generate a fresh 32-byte salt and 12-byte GCM nonce with `SecureRandom` for every export.
3. Derive 32 bytes using `PBKDF2WithHmacSHA256`.
4. Calibrate iterations on first use to a target of roughly 750 ms on the current device, clamp to a reviewed minimum/maximum, and store the chosen count in the envelope. Import enforces bounds before doing KDF work to avoid denial-of-service files.
5. Encrypt the complete payload using `AES/GCM/NoPadding` and the canonical envelope header as associated data.
6. Zero portability-material copies, derived keys, and plaintext credential byte arrays where practical after use.

The implementation uses Android/JCA primitives rather than inventing a cipher. A future Argon2id envelope ID can be added only after a stable, maintained, licence-reviewed Android implementation is selected; version 1 remains readable.

## Payload

The decrypted payload begins with `TSPPAY01`, then a sequence of bounded TLV record collections. Every record has a stable UUID, record schema version, and per-field TLV encoding. Unknown optional fields and record types are skipped by length. Unknown required fields make only that record incompatible unless they affect global integrity.

Collections:

1. host profiles;
2. credential metadata and, for Full, encrypted-envelope plaintext secret fields;
3. SSH key public metadata and, for Full, private payload/passphrase;
4. known hosts;
5. snippets;
6. terminal profiles/custom themes;
7. keyboard profiles/ordered actions;
8. global settings;
9. optional custom fonts (maximum 32 records; 16 MiB each; total payload cap still applies).

Every list, string, blob, nesting level, and total decrypted payload has a hard limit. The initial total input/output limit is reviewed against realistic fonts/key counts and enforced while streaming; no parser trusts `available()` or requires a filesystem path.

## Export flow

1. Obtain one consistent complete repository snapshot and count summary.
2. Resolve credentials only within a scoped background operation.
3. Serialize the bounded payload and authenticated envelope.
4. Launch/create the chosen SAF document and stream the envelope.
5. Close/flush the provider stream and show exact success/failure; never claim success from picker return alone.

Cancellation deletes no app data and leaves no plaintext temporary file. If a provider cannot remove a partial document, the result explicitly warns that the incomplete encrypted file is unusable.

## Import flow

1. Open a provider stream and validate magic/header/length bounds.
2. Decrypt/authenticate the entire payload before parsing records, without prompting for a passphrase.
3. Parse into an isolated bounded import model and validate UUIDs/references.
4. Show creation/app/schema information, counts, incompatible/skipped records, and conflicts.
5. Preview replacement of corresponding data.
6. Before replacement, create a device-encrypted internal recovery snapshot.
7. Stage and validate included font files behind a durable content-ID journal, then apply all
   selected Room/DataStore/credential changes through one transaction coordinator.
8. Reopen all newly written credentials and validate foreign references before commit/finalization.
9. On failure, roll back newly staged font files and restore database/settings/credential state
    from the recovery snapshot. On success, clear the font journal and temporary snapshot and show
    exact data/font result counts. After process death, authoritative Room profile references decide
    whether journalled content-addressed files are retained or removed.

Merge preserves existing records unless UUID/content match permits a safe update. Keep both generates new UUIDs and rewrites internal references. Known-host conflicts never merge different keys silently. Imported custom themes/fonts receive distinct names when necessary.

## Compatibility and errors

- Invalid portability material and authenticated tamper produce the same non-oracular unlock failure internally.
- Truncated/oversized/malformed input fails before repository mutation.
- A newer unsupported required envelope/payload schema shows the producing app version and makes no changes.
- Older schemas migrate in the isolated import model before preview.
- Terminal-profile rendering flags added to payload version 1 are optional: older archives default
  to bold rendering and pinch zoom enabled, with ligatures and copy-on-selection disabled. Missing
  custom-theme bold-bright fields default to enabled.
- The tmux-selector disable flag is optional; older archives retain the enabled-by-default behavior.
- Skippable unknown optional records appear in preview/result counts.
- KDF iteration/salt/nonce/ciphertext limits are checked before expensive allocation/work.

## Verification

- Standard and Full deterministic-model round trips through non-seekable streams.
- Wrong passphrase, changed header, changed ciphertext/tag, truncation at every boundary, trailing bytes, duplicate required fields, excessive counts/lengths/KDF work.
- Older payload migration and newer required/optional fields.
- Merge/Replace/Keep-both for duplicate hosts, keys, known hosts, profiles, fonts, and cross-references.
- Repository/credential/DataStore injected failures at every transaction stage and recovery-snapshot restore.
- SAF fake-contract UI tests plus manual local provider and Google Drive provider checks when installed.
- Cross-install restore: export from one clean installation, clear/install clean app, restore, verify settings/data/credentials, and prove Android Keystore masters were not copied.
