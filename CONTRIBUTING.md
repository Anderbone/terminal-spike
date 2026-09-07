# Contributing

Use this repository for Terminal Spike and its Mosh implementation. Report reproducible bugs
with the app version, Android version, transport, and steps using a disposable host. Remove
credentials, private host details and terminal transcripts from public reports.

Discuss architectural, dependency, licence and package-identity changes before implementing them.
Follow [AGENTS.md](AGENTS.md). Use native Kotlin Android, Compose for the application shell and the
custom renderer for the terminal. Preserve bounded terminal state, batching, history and
performance tests. Do not copy another terminal application's source.

Use stable dependencies and record their source, purpose, licence and notice obligations. Keep
SDK paths, secrets, signing material, APKs, build output and machine-specific files out of Git.

Before submitting a change, use the README's toolchain and run:

```bash
mosh-extension/scripts/verify-sources.sh
./gradlew --dependency-verification=strict test lint assembleDebug assembleRelease
```

Native changes must preserve both supported ABIs, source checksums, generated-source provenance
and 16 KiB alignment verification. Follow the repository's device-authorization rules for device
tests. Do not infer permission to run tests on an arbitrary connected phone.

Contributions use the licence of the files they modify; see [LICENSING.md](LICENSING.md).
