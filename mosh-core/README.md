# Built-in Mosh transport

This Android library is packaged into Terminal Spike's single APK. It has no standalone
application, launcher, signing configuration or version. The broker runs in the private
`:mosh_broker` process and ten workers use `:mosh_session_0` through `:mosh_session_9`.
The app binds only its own private service. The legacy standalone extension is not required.

The main app performs SSH host verification, authentication and `mosh-server` bootstrap.
Only a numeric UDP endpoint, bounded options/dimensions, a one-shot session key and terminal
pipes cross the IPC boundary. Native workers retain process isolation for upstream global state.

Pinned upstream source, generated inputs, patches, native licences and tests are kept here.
No code is copied from another Android terminal application. This library is GPL-3.0-or-later;
`mosh-api` retains Apache-2.0. See [DEPENDENCIES.md](DEPENDENCIES.md),
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [BUILDING.md](BUILDING.md) and
[ADR-005](../docs/ADR-005-BUNDLED-MOSH.md).

Mosh is a registered trademark. Terminal Spike is not affiliated with or endorsed by the Mosh
project. Create complete Corresponding Source using `scripts/create-source-bundle.sh` at the
repository root after committing the exact source revision.
