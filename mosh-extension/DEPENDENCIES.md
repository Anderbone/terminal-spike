# Mosh extension dependency inventory

Every native input is immutable, official source. `third_party/SOURCES.tsv` is machine-readable and
`scripts/verify-sources.sh` verifies the archives before any native compilation.

| Dependency | Pinned version | Purpose | Source and checksum | Licence and obligations |
|---|---:|---|---|---|
| Mosh | 1.4.0, commit `bc73a26316ede2a79259d859f8ee309b32412420` | SSP/Mosh UDP transport, state synchronization, terminal prediction model and ANSI frame generation | Official release archive; SHA-256 `872e4b134e5df29c8933dff12350785054d2fd2839b5ae6b5587b14db1465ddd` | GPL-3.0-or-later with upstream OpenSSL exception. Deliver Corresponding Source/build scripts; preserve COPYING, modification dates, OCB notice, copyright/author inventory and warranty disclaimer. This build uses Nettle, not OpenSSL. |
| GNU Nettle | 3.10.2 | AES-128 primitive used by Mosh's upstream internal OCB implementation | GNU release archive; SHA-256 `fe9ff51cb1f2abb5e65a6b8c10a92da0ab5ab6eaf26e7fc2b675c45f1fb519b5` | AES files offer LGPL-3.0-or-later or GPL-2.0-or-later. This GPL-3.0-or-later combined work selects the GPL-compatible route; preserve notices and all supplied GPL/LGPL texts. Built static with public-key algorithms disabled, so GMP is absent. |
| Protocol Buffers C++ | 21.12 | Lite proto2 runtime for Mosh's three upstream wire schemas | Official `protobuf-all-21.12` release archive; SHA-256 `2c6a36c7b5a55accae063667ef3c55f2642e67476d96d355ff0acb13dbb47f09` | BSD-3-Clause. Preserve copyright, conditions and disclaimer. Generated files are derived only from the Mosh `.proto` inputs and reproducible with the source-built matching `protoc`. |
| Android NDK | 29.0.14206865 (r29) | Clang/LLD, Android sysroot and ABI libraries | Official Google Linux archive SHA-1 `87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b`; installed outside the repository | Toolchain input. LLVM libc++ is linked statically under Apache-2.0 with LLVM exception; exact r29 NDK and LLVM toolchain notices are checked in and packaged. r29 provides 16 KiB ELF alignment by default. |
| Android platform zlib | API 26+ system library | Mosh transport compression | Public NDK `libz` API; resolved from the pinned NDK sysroot and not packaged in the APK | zlib licence / Android system library. Preserve the zlib notice in distribution materials. |
| `mosh-api` | protocol/model v1 | Project-owned AIDL control contract and bounded parcelables | Repository module `../mosh-api` | Apache-2.0. No Mosh implementation or native transport code. |
| AndroidX Activity Compose / Compose UI / Material 3 | Activity Compose 1.12.4; Compose BOM 2026.04.01 | Small informational launcher activity for licence, source and installed-state disclosure | Google AndroidX Maven artifacts | Apache-2.0 notices. No network or account behavior. |
| Kotlin standard library | 2.4.10 | Extension service and UI implementation | JetBrains Kotlin | Apache-2.0 notice. |
| JUnit 4 | 4.13.2 | Host tests only | JUnit upstream | EPL-1.0; not packaged. |

No OpenSSL, GMP, analytics, advertising, cloud, AI, account, sync, or terminal-application source is
compiled into this extension.

The only patch applied to an upstream archive is
`patches/mosh-1.4.0-android-key-hygiene.patch`, SHA-256
`5240b0a7e90c1d223acb1f793e80787211ad88bc201fb1e87777d757c428d6ec`. The build applies it to a
fresh Mosh extraction with `--fuzz=0`; it removes avoidable printable/copy key material from the
Android client path and does not modify the checked-in archive.
