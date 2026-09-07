status: implemented-acceptance-pending
created_at: 2026-08-08T10:42:15.487Z
updated_at: 2026-08-10
done_at: none
independent: no
dependencies: docs/architecture.md, docs/security-model.md

# Mosh extension protocol and distribution boundary

## Open Questions

- Question: Do the main app and GPL extension constitute legally separate works given the AIDL control semantics and terminal byte stream?
  Recommended answer: obtain specialist open-source counsel review before distributing either package together or linking their store listings; APK/process separation is necessary architecture evidence but not a licensing conclusion.
  Why it matters: Mosh is GPL-3.0-or-later and Corresponding Source/further-restriction obligations can extend beyond a single native library depending on the relationship between programs.
- Question: Can the intended Play App Signing/key-rotation model satisfy GPL installation-information/no-further-restriction obligations and the signature-permission design?
  Recommended answer: document exact Play/direct/F-Droid signing variants and receive legal review before public distribution.
  Why it matters: both packages need verifiable compatible signing, while users' ability to install modified GPL builds must be assessed.
- Question: What product wording can use the registered “Mosh” name?
  Recommended answer: use factual “Mosh-compatible extension, not affiliated with the Mosh project” wording pending trademark review.
  Why it matters: upstream identifies Mosh as a registered trademark.

These are external distribution decisions. They do not justify bundling native Mosh code in the main APK or weakening the technical boundary.

## Current implementation state

The local/debug architecture in ADR-003 is implemented rather than simulated:

- `mosh-api` is a version-1 Apache-2.0 Android library containing the bounded AIDL control plane, parcelables, capabilities, errors, and PFD ownership contract. Its AAR contains no upstream Mosh or native object.
- The main app has exact-package/component discovery, signature-permission and signer-lineage checks, API/capability negotiation, Binder death/rebind handling, strict JSch SSH bootstrap, a one-response parser, one-shot key transfer, and a `Connection` adapter that feeds the existing terminal engine through PFD streams.
- `mosh-extension` is a distinct GPL application. Its broker allocates one of ten separate private worker processes per session; each worker owns one genuine pinned Mosh 1.4.0 native client, terminal input/output pipes, resize state, and cleanup.
- The reproducible native build produces `arm64-v8a` and `x86_64` libraries from the checked-in verified Mosh, GNU Nettle, and Protocol Buffers source archives. Debug and unsigned release APKs, local native symbols, packaged notices, and a standalone Corresponding Source archive can be produced independently.
- Settings presents live Checking, Absent, Disabled, Untrusted, Incompatible, Available, and Error states, including the installed version, negotiated API, capabilities, maximum sessions, and refresh/retry. SSH remains selectable and usable as an explicit fallback; Mosh is never silently relabelled as SSH.

This is not yet a completed release-acceptance claim. The latest controlled-server run on the only
currently test-authorized physical target, the exact model-checked old `SM-S911B`, completed strict
password and private-key SSH bootstrap, real UDP terminal I/O, four simultaneous sessions,
independent resize/close, isolated-worker death with slot reuse, and broker death with automatic
rebind and fresh traffic. The application-owned session repository publishes Android connectivity
generations through the negotiated hint API. Observed network transitions/roaming, the user's
external server, and manual battery/background behavior still require evidence. Production signing
authority has not been supplied; public Mosh distribution remains blocked by ADR-003.

## Verified upstream baseline

- Stable release: [Mosh 1.4.0](https://github.com/mobile-shell/mosh/releases/tag/mosh-1.4.0).
- Pinned release commit: [`bc73a26316ede2a79259d859f8ee309b32412420`](https://github.com/mobile-shell/mosh/commit/bc73a26316ede2a79259d859f8ee309b32412420).
- Official GitHub release tarball SHA-256: `872e4b134e5df29c8933dff12350785054d2fd2839b5ae6b5587b14db1465ddd`.
- Licence: GPL-3.0-or-later with the upstream OpenSSL linking exception; preserve [COPYING](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/COPYING), [copyright inventory](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/debian/copyright), exception, OCB notices, modification dates, source, and reproducible build scripts.
- Upstream build: GNU Autotools; required native dependencies include Protocol Buffers C++, crypto (OpenSSL or Nettle), curses/tinfo for the stock frontend, zlib, libc++, and platform facilities. `--disable-server` exists.
- Upstream ships no maintained Android/Gradle/CMake/ndk-build/JNI port. Android support is a substantive project-owned port, not configuration-only work.

The extension source distribution must archive the exact upstream source, all JNI/Android modifications, generated-source provenance, native dependency sources/checksums/licences, NDK/tool versions, and scripts needed to reproduce/install the APK. Do not depend on an upstream URL remaining available.

## Package identities

| Component | Gradle type | Identity | Licence/content |
|---|---|---|---|
| Main app | Android application | `com.yanjiyu.terminalspike` | Private product code; no native/GPL Mosh implementation |
| Shared API | Android library | namespace `com.yanjiyu.terminalspike.mosh.api` | Project-owned Apache-2.0 AIDL/models only |
| Extension | Android application | `com.yanjiyu.terminalspike.mosh` | Separate APK/UID/artifact/store entry; GPL Corresponding Source/notices |
| Test doubles | JVM/instrumentation fixtures | No installed package | Test-only Binder/platform fakes, never packaged/reachable in release |

The extension is not a dynamic feature. The main app must assemble, install, launch, and provide SSH with the extension absent.

## Discovery and trust

- Main manifest queries only `com.yanjiyu.terminalspike.mosh`.
- Bind using an explicit `ComponentName`; never accept an implicit service responder.
- Service is exported only for cross-app binding and protected by `com.yanjiyu.terminalspike.permission.BIND_MOSH_EXTENSION` at signature level.
- Before binding, inspect installed package and service enabled state, version metadata, and the rotation-aware PackageManager signature relationship. Require the same application signer.
- Extension validates calling UID packages and signature on every Binder entry point.
- Reject absent, disabled, wrong-package, wrong-signer, incompatible, or ambiguous installations with distinct safe UI state.
- Negotiate API version and capabilities before opening a session.

Same-certificate signature permissions require coordinated signing variants. Package/signature checks are defense in depth, not alternatives.

## API version 1

All parcelables are explicitly versioned, size-bounded, immutable at the Binder boundary, and contain no password/private-key/saved-credential reference.

```aidl
interface IMoshPlugin {
    int getApiVersion();
    MoshCapabilities getCapabilities();
    MoshSessionHandle startSession(in MoshSessionRequest request);
    void resizeSession(String sessionId, int columns, int rows);
    void updateNetworkHint(String sessionId, in MoshNetworkHint hint);
    void stopSession(String sessionId, int reason);
    void registerCallback(IMoshCallback callback);
    void unregisterCallback(IMoshCallback callback);
}
```

`MoshSessionRequest`:

- model version (the protocol API is negotiated before this request);
- random main-app session UUID;
- resolved numeric server address and address family;
- UDP port;
- one-shot read end containing the 22-character Mosh session key (preferred over a String/Parcelable/environment value);
- initial columns/rows;
- bounded locale;
- reviewed prediction/display option flags.

It never contains hostname credentials, SSH password, private key, passphrase, agent handle, known-host data, saved credential UUID, startup snippet, or complete SSH connection URI.

`MoshSessionHandle`:

- version/session UUID;
- writable terminal-input `ParcelFileDescriptor`;
- readable terminal-output `ParcelFileDescriptor`;
- initial state;
- negotiated capability bits.

Binder is the control plane only. Terminal bytes use reliable PFD pipes or a socket pair because Binder has a shared transaction-size limit and per-byte calls would be unsafe/slow.

`IMoshCallback` reports bounded state changes:

- Connecting;
- Connected;
- Roaming/network changed;
- Suspended;
- Disconnected with reason;
- Error with stable code and bounded redacted detail.

Stable error groups include absent/incompatible/untrusted extension, invalid request, key-read failure, UDP timeout/firewall, locale, native initialization, extension death, cancelled, and internal redacted failure.

## Main-app bootstrap

1. Verify/negotiate the extension before creating a server key; fail honestly to explicit SSH selection when it is absent, untrusted, or incompatible.
2. Use the existing SSH path for DNS, strict host-key verification, and authentication.
3. Run a bounded, safely quoted command compatible with pinned Mosh 1.4.0 and the host's executable/UDP range/locale settings.
4. Parse only the official `MOSH CONNECT <port> <22-character base64 key>` response with line/byte/time bounds; reject extra ambiguous connection lines.
5. Resolve/choose the endpoint, validate port/key, and close the bootstrap channel as appropriate.
6. Create terminal/key pipes against the already verified extension.
7. Write the ephemeral key once, close both main-app key-pipe ends, and zero mutable key buffers.
8. Pass terminal output into the existing `VtTerminalEngine`; input/resize/control use the same session abstraction as SSH.

If extension start fails, clear the key and report a redacted terminal failure. A user can start a fresh Mosh bootstrap or explicitly select SSH; the app never reuses an old ephemeral key, silently downgrades the protocol, or implies that a live Mosh session was resumed.

## Extension native architecture

The stock CLI is process-global: STDIN/STDOUT, environment, termios, signals, process exit, and a singleton select loop. The Android port replaces the CLI frontend with supplied file descriptors/callbacks, explicit resize/cancel, bounded output, and structured errors.

Implemented concurrency decision:

- Upstream's process-global assumptions are not claimed to be re-entrant.
- The extension exposes ten private worker-service processes and assigns at most one native client to each process. This transport-specific capacity remains independent of the application's uncapped tab model and prevents one Mosh session's timestamp, locale, signal, or terminal globals from colliding with another.

The extension builds client-only code for `arm64-v8a` and `x86_64`, pins/checksums every native dependency and NDK/tool version, emits Android 16 KiB-aligned libraries, hides native symbols except JNI entry points, strips release binaries, retains local symbol artifacts, and stops when FDs close or Binder requests cancellation.

Native key handling reads the one-shot key into mutable memory, avoids environment variables, never persists it, and zeros it after client initialization. The extension never receives SSH authentication data.

## Lifecycle

- `AppContainer.sshSessionRepository` owns every live SSH and Mosh runtime on the application process scope. Activities and ViewModels observe snapshots and issue commands; they do not own the connection, parser, controller, binding, or retry job.
- A visible user action must start `SessionForegroundService` before transport startup. The service observes the repository for its ongoing notification, actions, and optional CPU-awake lease; loss of the required service fails active sessions rather than leaving a hidden transport.
- The extension is bound only while the main client needs it and does not add a duplicate session notification.
- Binder or isolated-worker death closes PFDs and moves the session to an extension failure. Since
  the byte pipe can close just before the Binder callback arrives, EOF gives the authoritative
  terminal event a bounded one-second precedence window; an eventless EOF remains a clean
  disconnect.
- Extension death cannot restore the session because the ephemeral key is intentionally not persisted; retry performs a new strict SSH bootstrap.
- The API and extension accept bounded connectivity-generation/family/metered hints and contain no account, location, or device identifier. The application repository fans each changed generation only to currently connected Mosh transports, including the current generation after connection.
- The genuine Mosh transport still performs its protocol-native authenticated UDP roaming and port hopping. What remains missing is real-device transition/roaming acceptance evidence, not Android hint publication or the core roaming algorithm.
- Link-local IPv6 addresses requiring a zone identifier such as `%wlan0` are unsupported by the current host model and bootstrap validation. Globally routable IPv6 addresses/names and IPv4 remain in scope.
- Stop is idempotent and closes native session, pipes, callbacks, and all key material promptly.

## User states

Host profiles remain editable/savable when the extension is absent. Mosh connect is honestly unavailable with:

- extension status and reason;
- refresh/retry for a local package-state check (there is no in-app downloader or installer);
- explicit SSH selection as the fallback, with no automatic downgrade;
- server `mosh-server`/UDP explanation;
- retry after absent/incompatible/server/locale/firewall/native failures.

Settings shows package/version, protocol API, signing verification, licence/source/build information, and a refresh/retry action. It never silently downloads or installs an APK from an unverified URL.

## Verification status

Implemented host/build evidence covers:

- the main dependency/AAR/APK boundary, versioned parcel bounds, exact discovery/trust decisions, and bounded redacted status presentation;
- strict password/private-key SSH bootstrap parsing and cleanup, one-shot key/PFD ownership, terminal backpressure, resize coalescing, cancellation, Binder death, and rebinding paths;
- application-process session ownership, foreground-service start/loss policy, and Android connectivity-generation dispatch to live Mosh sessions;
- a ten-worker process-isolation equivalent for concurrent sessions;
- pinned dual-ABI native builds, source checksums, 16 KiB LOAD alignment, restricted symbols/dependencies, packaged notices, local symbols, and the standalone Corresponding Source archive.

Current controlled-device evidence on the exact model-checked old `SM-S911B` covers a `4/0/0/0`
real-Mosh class: 200 ordered terminal rows, four simultaneous process-isolated sessions with unique
live dimensions, closing one session without affecting the other three, killed-worker failure and
slot reuse, and killed-broker failure, automatic rebind, and fresh terminal traffic. Separate
password and private-key bootstrap runs passed. The worker-death test first reproduced and then
verified the fix for the PFD-EOF/Binder-event race. The full default app suite passed
`315/0/0/13`; the 13 skips are opt-in external-server cases, including these four Mosh tests.

The acceptance gates still open are:

- preserve the green disposable-AVD extension-absence/update/real-SSH gate; under current device
  policy, do not install or test the extension on the connected fold, and install the final main
  debug APK there only after the whole requested feature is complete;
- use the user's saved external server for an unlocked manual retest; the controlled private-key,
  resize, concurrent-session, worker-death, and broker-death paths are now green;
- verify IPv4 and global IPv6 network changes, VPN transitions, UDP firewall/timeout behavior, protocol roaming, and Android background behavior. Link-local IPv6 zone identifiers are excluded; connectivity-hint publication is implemented but its transition behavior is not yet accepted on devices;
- complete the remaining manual behavior matrix on the old phone and the final non-test main-app
  launch on the fold when the whole requested feature is complete;
- supply production signing authority and complete the specialist GPL, installation-information, store-linking, and trademark review before any public extension distribution claim.
