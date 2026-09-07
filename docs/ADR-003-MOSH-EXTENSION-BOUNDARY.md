# ADR-003: Separate GPL Mosh extension boundary

> Historical licensing rationale: [ADR-004](ADR-004-OPEN-SOURCE-APP.md) supersedes the private
> main-source assumption and authorizes consolidated public source. The existing APK/process
> boundary remains implemented. This repository change does not authorize a new store release.

- Status: Accepted for local and debug development; public distribution blocked
- Date: 2026-08-08

## Context

The product requires a genuine Mosh transport while the main application may remain privately licensed. Mosh 1.4.0 is GPL-3.0-or-later with its upstream OpenSSL linking exception. The upstream project does not provide a maintained Android/JNI client, so Android support necessarily includes project-owned native adaptation and reproducible build work.

Pretending that a settings row is a Mosh implementation is not acceptable. Bundling upstream Mosh code into the main APK is also not acceptable: it would erase the intended package, process, update, and licensing boundary. Android process separation and Binder IPC are useful architecture facts, but this ADR does not claim that they settle every copyright question for public distribution.

## Decision

Build Mosh as a separately installed application and APK with package identity `com.yanjiyu.terminalspike.mosh`. The main application must continue to build, install, launch, and provide SSH when that extension is absent.

The boundary is:

- `mosh-api` contains only project-owned, versioned Android IPC declarations and bounded data models. It is licensed under Apache-2.0 and contains no upstream Mosh implementation.
- `mosh-extension` contains the Android service, JNI adapter, pinned upstream Mosh client code, and approved native dependencies. The extension and its Corresponding Source are distributed under GPL-3.0-or-later while preserving the upstream OpenSSL exception and all component notices.
- the extension is a distinct APK, UID, artifact, and release surface. It is not a dynamic feature and no extension native object is linked into or packaged in the main APK.
- Binder is the bounded control plane. Terminal bytes and the one-shot session key use file descriptors. No SSH password, private key, passphrase, credential identifier, known-host database, or complete SSH URI crosses the IPC boundary.
- the main app performs strict SSH host verification and authentication, launches `mosh-server`, parses one bounded `MOSH CONNECT` response, passes the resulting ephemeral key once, and wipes it. The extension never performs SSH authentication.
- discovery and binding use an explicit package/component, signature permission, signer verification, caller verification, and API/capability negotiation. A missing, disabled, untrusted, or incompatible extension is an honest unavailable state and never weakens SSH.

Local/debug development is approved using the common Android debug signer. Production signing must support the same trust model without hard-coding a developer-machine certificate.

## Pinned baseline

- Mosh: stable release 1.4.0, commit `bc73a26316ede2a79259d859f8ee309b32412420`.
- Official release tarball SHA-256: `872e4b134e5df29c8933dff12350785054d2fd2839b5ae6b5587b14db1465ddd`.
- Android NDK: stable r29, Gradle version `29.0.14206865`. NDK r29 is selected because it is stable and produces 16 KiB-aligned native libraries by default.
- Android ABIs: `arm64-v8a` and `x86_64` initially. Both must be built and tested; no ABI may silently use an unreviewed prebuilt.

Every additional native source must be recorded with an immutable version, source URL, cryptographic checksum, licence, notice obligations, and modification/build provenance before it is compiled. Pre-release, nightly, moving-branch, and opaque binary dependencies are prohibited.

## Source and distribution obligations

Any distributed extension APK must be accompanied by a durable offer or direct delivery of complete, corresponding, installable source containing:

- the exact upstream and native-dependency sources;
- Android/JNI modifications with modification dates;
- generated-source inputs and generation commands;
- build scripts, patches, checksums, NDK/tool versions, and signing-independent installation instructions;
- Mosh COPYING, exception, copyright inventory, OCB notices, and every dependency licence/notice.

The main app's dependency inventory must state that it contains only the permissive IPC API, not the GPL implementation. APK scans must enforce that claim.

## Public-release gate

This ADR authorizes implementation, local installation, device testing, and private evaluation. It does **not** authorize publishing either APK in an app store, selling a combined offering, or claiming legal separation. Public distribution remains blocked until the exact artifacts and release process receive review covering:

- GPL Corresponding Source and installation-information obligations;
- the relationship between the two programs and their store listings;
- Play App Signing, key rotation, direct/F-Droid builds, and modified-extension installation;
- factual use of the Mosh name and upstream non-affiliation wording.

## Verification consequences

A working claim requires more than a successful native compile. It requires an independently installed extension APK, verified Binder negotiation, strict SSH bootstrap, real UDP session establishment against a controlled `mosh-server`, terminal input/output and resize, network change/roaming behavior, cancellation and extension-death cleanup, key wiping, and proof that both the main app and SSH remain usable with the extension uninstalled.
