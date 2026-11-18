# Implementation status and verification references

Navigation updated: 2026-11-16. The August–September implementation programme is preserved
in the [dated archive](docs/archive/2026-09-07-implementation-programme.md). Its checked boxes,
test totals, device identities and remaining gates describe those checkpoints.

## Maintained contracts

- [README](README.md) describes the product and download entry points.
- [Architecture](docs/ARCHITECTURE.md) and [ADR-005](docs/ADR-005-BUNDLED-MOSH.md) describe the
  single main APK, its private Mosh broker/workers and the Apache-2.0 IPC module.
- [Local Arch ADR](docs/ADR-LOCAL-ARCH-RUNTIME.md), [dependencies](docs/DEPENDENCIES.md) and
  [notices](THIRD_PARTY_NOTICES.md) identify the approved native runtime and its sources.
- [Development](docs/DEVELOPING.md) and [next steps](docs/NEXT_STEPS.md) define source verification.
- [CI and releasing](docs/CI-AND-RELEASING.md), [publishing](docs/PUBLISHING.md) and
  [AGENTS.md](AGENTS.md) govern release scope, signing, device safeguards and publication checks.

A verification result applies to its source and artifact hashes. A new checkout or a concurrent
change needs the corresponding evidence before it can be described as verified. The historical
separate-APK checklist does not change the accepted bundled-Mosh architecture.

## Last recorded candidate evidence

The machine-maintained block below describes the source and artifacts in its matching candidate
manifest. Staging can refresh it. Read that manifest before using its results; publication and
public availability require the separate release checks.

<!-- release-evidence-current:start -->
Current release evidence: app JVM `971` tests (`971` passed, `0` skipped, `0` failures/errors); Mosh API JVM `11` tests (`11` passed, `0` skipped, `0` failures/errors); Mosh extension JVM `8` tests (`8` passed, `0` skipped, `0` failures/errors); old-phone Android app `332` tests (`307` passed, `25` skipped, `0` failures/errors). The source and artifact hashes and any pending external gates are recorded in `build/release-evidence/candidate-manifest.json`.
<!-- release-evidence-current:end -->

## Historical programme

The [complete phase ledger](docs/archive/2026-09-07-implementation-programme.md) retains all
original checkbox states, source/build identities, performance measurements and failed attempts.
It remains a historical record rather than an operational instruction to install on every phone.
