# Terminal Spike Mosh extension

[![Android CI](https://github.com/Anderbone/terminal-spike-mosh-extension/actions/workflows/android.yml/badge.svg)](https://github.com/Anderbone/terminal-spike-mosh-extension/actions/workflows/android.yml)

This is the standalone, open-source Mosh transport extension for Terminal Spike. It is a separate
Android application with package ID `com.yanjiyu.terminalspike.mosh`; it does not contain the main
Terminal Spike application and cannot manage hosts or credentials by itself.

Terminal Spike performs SSH authentication and trusted `mosh-server` bootstrap. The extension
receives only a numeric UDP endpoint, a one-shot ephemeral Mosh key, reviewed terminal options, and
terminal byte pipes. It never receives or stores SSH passwords, private keys, passphrases, saved
credential identifiers, or the main application's host database.

## Build

The build uses pinned official Mosh 1.4.0, GNU Nettle 3.10.2, Protocol Buffers 21.12, Android NDK
r29, and stable Android/Kotlin dependencies. Exact source archives and checksums are committed so a
build does not depend on mutable native downloads.

Requirements:

- Linux x86-64
- JDK 17
- Android SDK platform 36
- Android NDK `29.0.14206865`

```bash
export ANDROID_HOME=/path/to/android-sdk
./mosh-extension/scripts/verify-sources.sh
./gradlew :mosh-extension:test \
  :mosh-extension:lint \
  :mosh-extension:bundleRelease \
  :mosh-extension:assembleDebugAndroidTest
```

The native client is built for `arm64-v8a` and `x86_64`. The build verifies ELF architecture,
16 KiB page alignment, and the complete dynamic-library allowlist.

See [BUILDING.md](mosh-extension/BUILDING.md) for the full reproducible build and signing process.

## Corresponding Source

Every distributed APK or App Bundle must have matching Corresponding Source. Generate the
deterministic source archive with:

```bash
./mosh-extension/scripts/create-source-bundle.sh
```

The archive contains the two Android modules, exact native sources, generated-source inputs,
patches, build scripts, Gradle wrapper, dependency inventory, and licence notices. It deliberately
contains no private main-application source or signing material.

## Licence

The combined extension is free software under **GPL-3.0-or-later**. The IPC-only `mosh-api` module
is Apache-2.0. Component terms and required notices are recorded in
[DEPENDENCIES.md](mosh-extension/DEPENDENCIES.md) and
[THIRD_PARTY_NOTICES.md](mosh-extension/THIRD_PARTY_NOTICES.md).

Mosh is a registered trademark. This Mosh-compatible extension is not affiliated with or endorsed
by the Mosh project.

## Privacy and security

The extension has no analytics, advertising, cloud account, or tracking SDK. Read
[PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md) before reporting an issue.

Google Play listing sources, declarations, graphics, and release checklists live under
[`store-listing/`](store-listing/).
