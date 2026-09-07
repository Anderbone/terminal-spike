# ADR-004: One public repository for Terminal Spike and Mosh

- Date: 2026-09-07
- Status: Accepted for source consolidation; GitHub cutover in progress
- Supersedes: ADR-003's private main-source assumption and separate source-maintenance entry point

## Decision

The owner requested merging the Mosh extension into one public GitHub repository to reduce
maintenance. `Anderbone/terminal-spike` is the canonical source, issue and contribution repository
for the main app, Mosh API, native Mosh implementation, tests and build tooling.

Project-owned source is GPL-3.0-or-later. Existing Apache-2.0 grants on `mosh-api`, third-party
licences and the upstream Mosh OpenSSL exception remain intact. The root licence and packaged
main-app notices state the grant. This source publication supersedes the historical proposal
to keep the main source private. It does not introduce a new dependency.

The standalone repository's implementation already matches the modules here. Preserve its unique
publication materials with commit/hash provenance, retain this repository's newer build settings,
and point the old repository to this one. Archive the old maintenance entry point after the main
repository is publicly accessible; keep its release tags and source downloads available.

## Packaging scope

The requested end state is one public repository. A single APK with built-in Mosh was proposed
as an additional way to reduce maintenance; the owner has not selected that packaging change.
Do not turn repository consolidation into an unrequested runtime migration. The current main
application and optional Mosh APK retain their package identities, independent Play versions,
AIDL/file-descriptor contract, signature checks and native worker process isolation. All are built
and tested in this one repository.

If the owner chooses one APK later, a separate implementation must convert the extension into a
library, make the broker private while retaining a real process boundary, update discovery,
packaging, notices and user-facing installation flows, and prove real Mosh lifecycle behavior.
Publishing source does not constitute that implementation or its acceptance evidence.

## Source and distribution

The root licence applies to project-owned material unless a file or component supplies a
different licence. Preserve component notices, fonts and attribution. Source snapshots include
the exact native archives, Android patches, generated inputs, checksums and build scripts.
[LICENSING.md](https://github.com/Anderbone/terminal-spike/blob/main/LICENSING.md) describes modified builds with the builder's own signing key.
Mosh identifies protocol compatibility; Terminal Spike is not affiliated with or endorsed by
its upstream project.

This decision authorizes the requested public source consolidation. It does not publish a Play
release, upload either APK, remove existing apps from a phone or authorize new device tests.

References: [Mosh upstream](https://mosh.org/),
[GNU GPL FAQ](https://www.gnu.org/licenses/gpl-faq.en.html), and
[Apache's GPLv3 compatibility explanation](https://www.apache.org/licenses/GPL-compatibility.html).

## Acceptance

- One publicly readable main repository contains all current implementation and unique standalone
  publication materials, plus licences and build instructions.
- Existing standalone releases remain accessible; the old repo points to the canonical repo and
  is archived so there is one active maintenance location.
- No source regression from copying the standalone repository's older SDK/version settings.
- Unit tests, lint and debug/release builds pass with the final notice changes.
- Review source history and hosted logs/artifacts before changing visibility.
- Attempt the required completed-build foldable installation if it is available; never test there.
- Verify public GitHub visibility, anonymous source access, the root licence, and old-repo archival.
