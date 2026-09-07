# Contributing

Contributions are welcome when they preserve the narrow, separately installed Mosh transport
boundary.

Before opening a pull request:

1. Discuss architectural, protocol, dependency, licence, or package-identity changes in an issue.
2. Do not copy source from another Android terminal application.
3. Use stable dependency releases only. Record every new direct dependency, source, licence,
   purpose, and notice obligation in the dependency and notice documents.
4. Keep credentials, signing files, SDK paths, APKs, App Bundles, and IDE state out of Git.
5. Run source verification, unit tests, lint, and a release bundle build.

```bash
./mosh-extension/scripts/verify-sources.sh
./gradlew :mosh-extension:test :mosh-extension:lint :mosh-extension:bundleRelease
```

Changes to native code must retain both supported ABIs, 16 KiB alignment verification, exact source
checksums, and deterministic Corresponding Source generation. Contributions are licensed under the
licence of the files they modify; extension implementation changes are GPL-3.0-or-later.
