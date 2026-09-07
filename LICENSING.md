# Licensing

Copyright 2026 Terminal Spike contributors.

Unless a file or component states otherwise, project-owned source, build scripts, tests and
documentation in this repository are licensed under the **GNU General Public License, version 3
or (at your option) any later version** (`GPL-3.0-or-later`). The complete licence is in
[LICENSE](LICENSE). The software is provided without warranty.

The root licence does not replace component licences:

- `mosh-api` retains its existing Apache-2.0 grant and licence/notice files.
- Mosh 1.4.0 retains GPL-3.0-or-later and its upstream OpenSSL linking exception. The exception
  applies to the upstream component; this repository does not extend it to unrelated code.
- GNU Nettle, Protocol Buffers, Android/Kotlin libraries, fonts, palette inputs and toolchain
  materials retain their original terms and attribution. See [the main dependency inventory](docs/DEPENDENCIES.md),
  [main notices](THIRD_PARTY_NOTICES.md), [native inventory](mosh-extension/DEPENDENCIES.md)
  and [native notices](mosh-extension/THIRD_PARTY_NOTICES.md).
- Third-party source archives and licence texts are preserved unmodified. Imported historical
  publication material is identified by its provenance in `docs/archive/standalone-mosh-extension`.

Mosh and other third-party names and logos remain their owners' trademarks. The source licence
does not grant trademark rights or imply endorsement by the Mosh project.

## Building modified versions and distributing source

The [README](README.md) and [native build guide](mosh-extension/BUILDING.md) describe the pinned
toolchain and builds. A normal debug build uses the builder's Android debug key. A release build
can use the builder's own external signing key; the production certificate is not hard-coded.
Both existing APKs must use compatible signing keys for the signature-protected IPC interface.
Android will not install a differently signed APK over an existing installation: use a clean test
device/profile, or explicitly back up and uninstall the old installation before installing a
modified pair. Uninstalling removes app data.

For binaries you redistribute, provide the matching complete Corresponding Source and required
installation information under the GPL. Preserve the exact source revision, native archives,
patches, generation inputs, build scripts, checksums and licence notices. For a repository-wide
source snapshot, use `git archive --format=tar.gz --output=terminal-spike-source.tar.gz <commit>`
outside the working tree. The tree includes the native source archives; there are no Git
submodules to retrieve. The extension-specific source bundle script remains available for
existing standalone extension releases.

Publishing this source repository does not itself publish or change an app-store binary.
