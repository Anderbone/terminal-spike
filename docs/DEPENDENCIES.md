# Direct dependencies

All selected versions are stable. No GPL or AGPL dependency is included. Transitive components are resolved by Gradle and should be reviewed from the generated dependency graph before commercial release.

| Dependency | Version | Purpose | Licence | Source / obligations |
|---|---:|---|---|---|
| Android Gradle Plugin (`com.android.application`) | 9.3.1 | Android build plugin and built-in Kotlin support | Apache License 2.0 | [Android tools source](https://android.googlesource.com/platform/tools/base/); retain licence/notice when redistributing covered binaries |
| Kotlin Compose compiler Gradle plugin | 2.4.10 | Compose compiler integration | Apache License 2.0 | [JetBrains Kotlin](https://github.com/JetBrains/kotlin); retain licence/notice when applicable |
| Compose BOM | 2026.04.01 | Align Compose library versions | Apache License 2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/); BOM is build metadata |
| Compose UI, Graphics, Tooling Preview | BOM-managed | Compose shell and previews | Apache License 2.0 | AndroidX; retain licence/notice |
| Compose Material 3 | BOM-managed | Controls, scaffold, theme | Apache License 2.0 | AndroidX; retain licence/notice |
| Compose UI Tooling (debug) | BOM-managed | Debug inspection | Apache License 2.0 | AndroidX; debug-only |
| Compose UI Test JUnit4 and Test Manifest | BOM-managed | Instrumentation smoke testing | Apache License 2.0 | AndroidX; test/debug only |
| AndroidX Core KTX | 1.17.0 | Android Kotlin conveniences | Apache License 2.0 | AndroidX; retain licence/notice |
| AndroidX Activity Compose | 1.12.4 | Compose Activity host and edge-to-edge | Apache License 2.0 | AndroidX; retain licence/notice |
| AndroidX Lifecycle Runtime KTX / Runtime Compose / ViewModel Compose | 2.10.0 | lifecycle, retained state, lifecycle-aware Flow collection | Apache License 2.0 | AndroidX; retain licence/notice |
| kotlinx-coroutines-android | 1.10.2 | structured workload execution | Apache License 2.0 | [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines); retain licence/notice |
| kotlinx-coroutines-test | 1.10.2 | deterministic coroutine unit tests | Apache License 2.0 | Kotlin coroutines; test-only |
| mwiede JSch (`com.github.mwiede:jsch`) | 2.28.3 | Pure-Java SSH2 transport, password/private-key authentication and validation, PTY channels, keepalives, and host-key negotiation | Revised BSD (JSch/JZlib portions) and ISC (jBCrypt portion) | [Upstream source and pinned tag](https://github.com/mwiede/jsch/tree/jsch-2.28.3); reproduce the bundled copyright notices, licence conditions, and disclaimers in binary distribution materials. The resolved Android runtime graph adds no mandatory transitive dependency. Optional Bouncy Castle, JNA, junixsocket, and logging integrations are not included. |
| JUnit 4 | 4.13.2 | JVM unit tests | Eclipse Public License 1.0 | [JUnit 4](https://github.com/junit-team/junit4); test-only, preserve licence if redistributed |
| AndroidX Test Ext JUnit | 1.3.0 | Android JUnit integration | Apache License 2.0 | [AndroidX Test](https://android.googlesource.com/platform/frameworks/testing/); test-only |
| AndroidX Test Runner | 1.7.0 | Instrumentation test runner | Apache License 2.0 | AndroidX Test; test-only |
| Espresso Core | 3.7.0 | Instrumentation synchronization/assertions | Apache License 2.0 | AndroidX Test; test-only |
| Gradle Wrapper | 9.5.0 | reproducible build bootstrap | Apache License 2.0 | [Gradle](https://github.com/gradle/gradle); wrapper JAR is committed, distribution downloads on demand |

Android SDK Platform and Build Tools are developer prerequisites and are not vendored or packaged as application libraries.
