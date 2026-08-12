# Direct dependencies

All selected versions are stable. No GPL or AGPL dependency is included in the main APK or the
shared `mosh-api` AAR. The separately installed GPL Mosh extension has its own complete inventory
in `mosh-extension/DEPENDENCIES.md` and is never linked into either artifact.

The resolved `releaseRuntimeClasspath` was reviewed on 2026-08-08. In addition to the direct dependencies below, it contains AndroidX support modules, the Kotlin standard library, kotlinx-coroutines core, kotlinx-serialization core, JetBrains annotations, JSpecify annotations, and Guava's standalone `listenablefuture` compatibility artefact. Those transitive families are Apache License 2.0 and are covered by the Apache licence reproduced in the packaged notices. JSch adds no mandatory transitive runtime dependency; its optional Bouncy Castle, JNA, junixsocket, GSSAPI, and logging integrations are not resolved into the release APK. Repeat this review whenever the version catalog or resolved graph changes.

| Dependency | Version | Purpose | Licence | Source / obligations |
|---|---:|---|---|---|
| Android Gradle Plugin (`com.android.application`, `com.android.library`, `com.android.test`) | 9.3.1 | Android application/library/test build plugins, AIDL generation, and built-in Kotlin support | Apache License 2.0 | [Android tools source](https://android.googlesource.com/platform/tools/base/); retain licence/notice when redistributing covered binaries |
| AndroidX Baseline Profile Gradle plugin | 1.4.1 | Variant-safe generation, collection, and release consumption of ART Baseline/Startup Profiles | Apache License 2.0 | [AndroidX Benchmark source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/benchmark/benchmark-baseline-profile-gradle-plugin/); build-time only; retain licence/notice if redistributed |
| Kotlin Compose compiler Gradle plugin | 2.4.10 | Compose compiler integration | Apache License 2.0 | [JetBrains Kotlin](https://github.com/JetBrains/kotlin); retain licence/notice when applicable |
| Kotlin Symbol Processing Gradle plugin | 2.3.10 | Generate Room persistence code with AGP built-in Kotlin | Apache License 2.0 | [Google KSP](https://github.com/google/ksp); build-time only; retain licence/notice if redistributed |
| AndroidX Room Gradle plugin | 2.8.4 | Export versioned Room schemas for migration verification | Apache License 2.0 | [AndroidX Room](https://developer.android.com/jetpack/androidx/releases/room); build-time only; retain licence/notice if redistributed |
| kotlinx.serialization BOM | 1.8.1 | Align the transitive serialization core/JSON ABI used by Lifecycle and Room migration tests | Apache License 2.0 | [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization); version-alignment metadata only; licence reproduced in packaged notices |
| Protocol Buffers Gradle plugin | 0.9.5 | Generate lite Kotlin/Java settings messages from the checked-in schema | Revised BSD | [Google protobuf-gradle-plugin](https://github.com/google/protobuf-gradle-plugin); build-time only; preserve its copyright notice, conditions, and disclaimer if redistributed |
| Compose BOM | 2026.04.01 | Align Compose library versions | Apache License 2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/); BOM is build metadata |
| Compose Foundation, UI, Graphics, Tooling Preview | BOM-managed | Swipeable input-mode pager, Compose shell, and previews | Apache License 2.0 | AndroidX; retain licence/notice |
| Compose Material 3 | BOM-managed | Controls, scaffold, theme | Apache License 2.0 | AndroidX; retain licence/notice |
| Compose UI Tooling (debug) | BOM-managed | Debug inspection | Apache License 2.0 | AndroidX; debug-only |
| Compose UI Test JUnit4 and Test Manifest | BOM-managed | Instrumentation smoke testing | Apache License 2.0 | AndroidX; test/debug only |
| AndroidX Core KTX | 1.17.0 | Android Kotlin conveniences | Apache License 2.0 | AndroidX; retain licence/notice |
| AndroidX Activity Compose | 1.12.4 | Compose Activity host and edge-to-edge | Apache License 2.0 | AndroidX; retain licence/notice |
| AndroidX Lifecycle Runtime KTX / Runtime Compose / ViewModel Compose | 2.10.0 | lifecycle, retained state, lifecycle-aware Flow collection | Apache License 2.0 | AndroidX; retain licence/notice |
| AndroidX ProfileInstaller | 1.4.1 | Install packaged Baseline Profiles on supported sideloaded/pre-Android-12 devices where store-side compilation is unavailable | Apache License 2.0 | [AndroidX ProfileInstaller](https://developer.android.com/jetpack/androidx/releases/profileinstaller); packaged at runtime; retain licence/notice |
| AndroidX Room Runtime | 2.8.4 | Transactional local metadata, relationship, migration, and query storage | Apache License 2.0 | AndroidX; packaged at runtime; retain licence/notice |
| AndroidX Room Compiler | 2.8.4 | Generate Room database and DAO implementations through KSP | Apache License 2.0 | AndroidX; build-time only |
| AndroidX Room Testing | 2.8.4 | Device-side schema, migration, DAO, and transaction verification | Apache License 2.0 | AndroidX; test-only |
| AndroidX DataStore Core | 1.2.1 | Typed, atomic Proto DataStore for small global application settings | Apache License 2.0 | [AndroidX DataStore](https://developer.android.com/topic/libraries/architecture/datastore); packaged at runtime; retain licence/notice |
| Protocol Buffers Kotlin Lite | 4.32.1 | Compact runtime for the typed application-settings message | Revised BSD | [Protocol Buffers](https://github.com/protocolbuffers/protobuf); packaged at runtime; preserve the upstream copyright notice, conditions, and disclaimer |
| Protocol Buffer Compiler (`protoc`) | 4.32.1 | Build-time generation of lite application-settings sources | Revised BSD | Protocol Buffers; downloaded for builds, not packaged in the APK; preserve the upstream notice if redistributed |
| kotlinx-coroutines-android | 1.10.2 | structured workload execution | Apache License 2.0 | [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines); retain licence/notice |
| kotlinx-coroutines-test | 1.10.2 | deterministic coroutine unit tests | Apache License 2.0 | Kotlin coroutines; test-only |
| mwiede JSch (`com.github.mwiede:jsch`) | 2.28.3 | Pure-Java SSH2 transport, password/private-key authentication and validation, PTY channels, keepalives, and host-key negotiation | Revised BSD (JSch/JZlib portions) and ISC (jBCrypt portion) | [Upstream source and pinned tag](https://github.com/mwiede/jsch/tree/jsch-2.28.3); reproduce the bundled copyright notices, licence conditions, and disclaimers in binary distribution materials. The resolved Android runtime graph adds no mandatory transitive dependency. Optional Bouncy Castle, JNA, junixsocket, and logging integrations are not included. |
| JUnit 4 | 4.13.2 | JVM unit tests | Eclipse Public License 1.0 | [JUnit 4](https://github.com/junit-team/junit4); test-only, preserve licence if redistributed |
| AndroidX Test Ext JUnit | 1.3.0 | Android JUnit integration | Apache License 2.0 | [AndroidX Test](https://android.googlesource.com/platform/frameworks/testing/); test-only |
| AndroidX Test Runner | 1.7.0 | Instrumentation test runner | Apache License 2.0 | AndroidX Test; test-only |
| Espresso Core | 3.7.0 | Instrumentation synchronization/assertions | Apache License 2.0 | AndroidX Test; test-only |
| AndroidX Macrobenchmark JUnit4 | 1.4.1 | Cold-start, Baseline Profile, primary-navigation, and test-only terminal-fixture frame measurements | Apache License 2.0 | [AndroidX Benchmark](https://developer.android.com/jetpack/androidx/releases/benchmark); benchmark APK only; not packaged in the main application |
| AndroidX UI Automator | 2.3.0 | Stable black-box interaction and completion synchronization for Macrobenchmark journeys | Apache License 2.0 | [AndroidX Test](https://android.googlesource.com/platform/frameworks/testing/); benchmark APK only; not packaged in the main application |
| Alpine container / OpenSSH server | Alpine 3.24.1 (digest `28bd5fe8…943f8b`) / OpenSSH 10.3p1-r0 | Disposable local password, key-authentication, first-trust, and changed-host-key integration server | Alpine package-specific licences; OpenSSH BSD/ISC | Development-only Docker image defined under `integration-tests/openssh`; downloaded locally and never packaged or redistributed in an APK. Preserve image/package notices if the built image itself is redistributed. |
| Gradle Wrapper | 9.5.0 | reproducible build bootstrap | Apache License 2.0 | [Gradle](https://github.com/gradle/gradle); wrapper JAR is committed, distribution downloads on demand |

## Bundled terminal palette data

The named palettes are project-authored Android ARGB mappings, not executable dependencies or
copied upstream UI assets. Their names and colour inputs retain the following immutable provenance.
The standard MIT terms and every upstream attribution are packaged in `THIRD_PARTY_NOTICES.md`;
`docs/asset-licensing.md` records the review decisions and licence-file checksums.

| Preset | Pinned colour input and SHA-256 | Licence / notice obligation |
|---|---|---|
| Ayu Dark | Exact terminal colours from stable alacritty-themes 6.0.2 commit [`809b6382`](https://github.com/rajasegar/alacritty-themes/blob/809b638240a28b9ccfc0ba715c113ea7f3b92145/themes/Ayu-Dark.toml), SHA-256 `2feb7a7b20eae601b2a30aebd443bf189b487d0b7ace1ef28bf77f0d182ffd83`; original Ayu stable 8.0.1 source commit [`60fdf5d3`](https://github.com/ayu-theme/ayu-colors/blob/60fdf5d39c5ef36c081b081c1fb90b5e7cc24f4d/src/dark.ts), SHA-256 `adae149f592a7a1084f9d5c26037740bb9ca6d6d71993740819a607f31230056` | MIT; preserve Konstantin Pschera and Rajasegar Chandran notices and licence text. |
| One Dark | Stable 1.8.4 commit [`9c96f445`](https://github.com/atom/one-dark-syntax/blob/9c96f4454362267ac45322063e193ccf9d2debb1/styles/colors.less), SHA-256 `3f060ae1d0f2ebb27ccb60cf5088bd68ddaa8f38288569bf4ac47015b4595438` | MIT; preserve Copyright 2016 GitHub Inc. and licence text. Use “One Dark”, not “One Dark Pro”. |
| Dracula | Official iTerm commit [`e5749bcd`](https://github.com/dracula/iterm/blob/e5749bcd07764ad6e154038892b118ea45f88cc0/Dracula.itermcolors), SHA-256 `5fefc1d659b2020803e66224d33364b853ec52a54d209ef53c3d7c9e2127fd20` | MIT; preserve Copyright 2013-present Dracula Theme and licence text; no PRO asset or endorsement. |
| Nord | Commit [`1cef7160`](https://github.com/nordtheme/nord/blob/1cef71605416a222e57225b544540ce0fcec18d4/src/nord.css), SHA-256 `b931ac3732582b2066b2d6cadec02d9820ba7081e6e3e404c31cb62d9315a962` | MIT; preserve Copyright 2016-present Sven Greb and licence text. |
| Solarized Dark / Light | Commit [`62f656a0`](https://github.com/altercation/solarized/tree/62f656a02f93c5190a8753159e34b385588d5ff3/iterm2-colors-solarized), dark/light iTerm SHA-256 values `b17fd6ccd78663088e3c396a3cdc894e41e2db5acad2f51f6c6114506c33fb2c` / `8c6875470be3038f96ceacd89c2ba60f02b0395595d93556547b0707f91ddfc2` | MIT; preserve Copyright 2011 Ethan Schoonover and licence text. |
| Gruvbox Dark | Stable 2.0.0 commit [`5d15b276`](https://github.com/morhetz/gruvbox/blob/5d15b2765f59754d7ac263c88a0f6e3e58124951/colors/gruvbox.vim), SHA-256 `55116926ba2b625837d9ae89349a5688d60d0b32acdbd8887e1c0d225f079c3d` | `package.json` identifies Pavel Pertsev and declares MIT; README declares MIT/X11. Upstream has no standalone licence file, so retain that provenance exception, author metadata, and the complete standard MIT terms. |
| Tokyo Night | Stable 1.1.2 commit [`7c0f11ea`](https://github.com/enkia/tokyo-night-vscode-theme/blob/7c0f11eaef322f293621ca7befe462214b7ea468/tokyo-night.itermcolors), SHA-256 `7d8285ff85a4dd10404981c3331ae139056c3e24930a068d4966122c8e1f3071` | MIT; preserve Copyright 2018-present Enkia and licence text. |
| Catppuccin Mocha | Official Alacritty commit [`f6cb5a5c`](https://github.com/catppuccin/alacritty/blob/f6cb5a5c2b404cdaceaff193b9c52317f62c62f7/catppuccin-mocha.toml), SHA-256 `949cef17c3ec50420b82c70b22e3aa2ea0a5d27dfcc13284192bd5284b5db2c5`; palette 1.8.0 commit [`07d02aa1`](https://github.com/catppuccin/palette/blob/07d02aa110ef9eb7e7427afca5c73ba9cf7f8ebd/palette.json), SHA-256 `4bc114bb6b3c9a9c9e156564aa84625aef32c5da514d9dd431cf1fcad433a05f` | MIT; preserve Copyright 2021 Catppuccin and licence text. |

`Current`, `High Contrast`, and user-created custom themes are project-owned mappings and add no
third-party palette input. Cursor and selection choices, bright-colour adaptations, Android ARGB
encoding, and the catalogue implementation are project-authored even where an upstream source
supplies the base or ANSI colours.

## Bundled terminal font assets

The following unmodified static TTF files are direct runtime assets in the main APK. On
2026-08-09 each local file was compared byte-for-byte with the named official stable release
archive. GitHub currently marks these releases as mutable, so both the release archive SHA-256
and the dereferenced tag commit are pinned below; `scripts/verify-bundled-fonts.sh` independently
guards the exact files shipped from this repository.

| Asset | Stable release and purpose | Licence | Immutable provenance and notice obligations |
|---|---|---|---|
| Source Code Pro Regular/Bold | Upright 2.042R-u; primary terminal text option | SIL OFL-1.1; Reserved Font Name “Source” | Official `TTF-source-code-pro-2.042R-u_1.062R-i.zip` from [Adobe release 2.042R-u/1.062R-i/1.026R-vf](https://github.com/adobe-fonts/source-code-pro/releases/tag/2.042R-u/1.062R-i/1.026R-vf), archive SHA-256 `0c85bac90d15c040b82939aa92bc8404420fccc02e37bbcb9c93a7f21abb52c6`, dereferenced tag commit [`d3f1a5962cde503f9409c21e58527611d4a19ef1`](https://github.com/adobe-fonts/source-code-pro/tree/d3f1a5962cde503f9409c21e58527611d4a19ef1). Local `source_code_pro_regular.ttf`: 210,312 bytes, SHA-256 `74bd80d3e42a08517cd7e1108ba3d86f2da29ac0f3065be95e0357956ab9db37`; `source_code_pro_bold.ttf`: 206,804 bytes, SHA-256 `b2095e0d657e6d28dc32444a9dacabab0c9241d0bf39d96371756cc9bdbc3a5f`. Preserve Adobe copyright, OFL text, RFN, and trademark notice. |
| JetBrains Mono Regular/Bold | 2.304; ligature-capable terminal text option | SIL OFL-1.1 | Official `JetBrainsMono-2.304.zip` from [JetBrains release v2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304), archive SHA-256 `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`, tag commit [`cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9`](https://github.com/JetBrains/JetBrainsMono/tree/cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9). Local `jetbrains_mono_regular.ttf`: 273,900 bytes, SHA-256 `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f`; `jetbrains_mono_bold.ttf`: 277,828 bytes, SHA-256 `5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb`. Preserve project copyright and OFL text. |
| IBM Plex Mono Regular/Bold | Package 2.5.0, font version 2.005; terminal text option | SIL OFL-1.1; Reserved Font Name “Plex” | Official `ibm-plex-mono.zip` from [IBM release @ibm/plex-mono@2.5.0](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-mono%402.5.0), archive SHA-256 `6d23f01257663d8cc49a0d64c22ced630b79e0e2a0ac08a0da86e9a38bbc481c`, dereferenced tag commit [`2f9ba1b25957d958db71a849e85d72e3ecfb845a`](https://github.com/IBM/plex/tree/2f9ba1b25957d958db71a849e85d72e3ecfb845a). Local `ibm_plex_mono_regular.ttf`: 173,052 bytes, SHA-256 `7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43`; `ibm_plex_mono_bold.ttf`: 175,096 bytes, SHA-256 `74e5eedcfa4596497d34e19023cabdabd3a8c852b903007a5654a59591a72ffb`. Preserve IBM copyright, OFL text, RFN, and trademark notice. |
| Cascadia Mono Regular/Bold | 2407.24, static non-ligature faces; terminal text option | SIL OFL-1.1; Reserved Font Name “Cascadia Code” | Official `CascadiaCode-2407.24.zip` from [Microsoft release v2407.24](https://github.com/microsoft/cascadia-code/releases/tag/v2407.24), archive SHA-256 `e67a68ee3386db63f48b9054bd196ea752bc6a4ebb4df35adce6733da50c8474`, tag commit [`56bcca3f2c1e4cb19458954f0e2bb4635960df91`](https://github.com/microsoft/cascadia-code/tree/56bcca3f2c1e4cb19458954f0e2bb4635960df91). Local `cascadia_mono_regular.ttf`: 575,912 bytes, SHA-256 `06520d032ec274fa5040b22c6f4a1d829081b24ba40b2da56dae89bf10c7b481`; `cascadia_mono_bold.ttf`: 581,704 bytes, SHA-256 `b22cb603ed23cac36e8444846e1841caca21719a5e852780a8d37ae7a49b0a36`. Preserve Microsoft copyright, OFL text, RFN, and trademark notice. |
| Symbols Nerd Font Mono Regular | 3.5.0; fallback glyphs for Powerline and Nerd-symbol terminal output | Composite: MIT, CC BY 4.0, Apache-2.0, SIL OFL-1.1, Unlicense, and Bitstream Vera terms; logo trademarks remain with their owners | Unmodified `SymbolsNerdFontMono-Regular.ttf` from official `NerdFontsSymbolsOnly.tar.xz` in [Nerd Fonts release v3.5.0](https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.0), archive SHA-256 `b7ef2283462b435f1fe91d729dc412d5dbe34269dd2c7f4e1d803e4105c8d883` (also published in the release’s `SHA-256.txt`), tag commit [`bbb2db23a131139161d66a9b526a6fdc79875c92`](https://github.com/ryanoasis/nerd-fonts/tree/bbb2db23a131139161d66a9b526a6fdc79875c92). Local `symbols_nerd_font_mono_regular.ttf`: 2,564,060 bytes, SHA-256 `2dc316f2505a0cbfbcf6060a1b4ba85b0a2974189e30c0037cdedc436a25a4ff`. The archive’s short MIT file is not the complete licensing story: preserve the component attributions, change statements, RFNs, licence texts, and trademark/non-endorsement statement reproduced in `THIRD_PARTY_NOTICES.md`. |

The nine TTF files total 5,038,668 bytes before APK compression. System monospace is an Android
platform font and is not redistributed by this project. A user-selected SAF font remains private
application data and is not a project-distributed asset.

Android SDK Platform and Build Tools are developer prerequisites and are not vendored or packaged as application libraries.

The project-owned `mosh-api` Android library adds no direct runtime dependency. It reuses the
catalogued Android Gradle Plugin, JUnit 4, AndroidX Test Ext JUnit, and AndroidX Test Runner entries
above for building and verification. Its AIDL, models, and tests are licensed under Apache-2.0 in
`mosh-api/LICENSE`; it contains no upstream Mosh, GPL/AGPL, or native implementation.
