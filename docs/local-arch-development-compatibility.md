# Local Arch development compatibility

Status: tested workloads passed on the authorized old USB phone, 2026-09-07.
Compatibility claims below apply only to the recorded versions and scenarios. This is the acceptance ledger for the implementation in
[local-arch-linux-analysis.md](local-arch-linux-analysis.md).

## Measured environment

The pinned archive is Arch Linux ARM aarch64 `archlinux-aarch64-pd-v4.37.0`,
SHA-256 `718151cc4adad701223c689a7e4690cb7710b7b16e9b23617b671856ff04d563`.
Offline package metadata shows glibc 2.42, OpenSSL 3.6.1 and pacman 7.1.0.r9.
Those are archive observations, not successful executions or versions after an upgrade.

The Android-side PRoot 5.1.107.81 executable and original controlling-PTY bridge
passed four primitive tests on USB `RZCW81JZ9CP`, model `SM-S911B`. Actual Arch
bootstrap passed in the isolated verification package on the same old USB phone
(`/tmp/local-arch-isolated-device-gate-2.log`); its separate application ID prevents
other main-app installs from interrupting it. The isolated real-image test does not
replace UI acceptance.

## Required real-guest matrix

Record the APK SHA-256, exact package versions, device/kernel, command, exit status,
bounded output and persistence evidence for each row. Run only on the authorized
old USB phone, never the Wi-Fi foldable. Do not substitute host-machine execution.

| Workload | Evidence required | Result |
| --- | --- | --- |
| First launch | Production downloader, checksum, staged extraction, signed keyring init, `pacman --version` | Passed: real bootstrap and full first-install UI |
| Package upgrade | Real `pacman -Syu`, signatures enabled, no fabricated package manager | Passed after archive timestamp/cache correction; signed full upgrade completed in real PRoot. |
| Git | `pacman -S git`, clone a public fixture, read its checked-out files | Passed: Git 2.55.0; pinned is-number 7.0.0 commit |
| Node/npm | `pacman -S nodejs npm`, record versions and `process.platform`/`process.arch` | Passed: Node 26.8.1, npm 12.0.2, linux/arm64 |
| TypeScript | Global TypeScript install and version; compile and run a small typed project | Passed: 7.0.2 global and project compiler; executed output |
| Development server | `npm install`, `npm run dev`, real HTTP response over guest loopback | Passed HTTP response and process cleanup; Ctrl+C gate separate |
| esbuild | Install, bundle TypeScript, execute the emitted JavaScript | Passed: 0.28.2 |
| sharp | Install, generate/resize/read an image using its Linux ARM64 native payload | Passed: 0.35.4; resized image dimensions asserted |
| better-sqlite3 | Install, write/read a SQLite database | Passed: 13.0.3 native payload; no source-build claim |
| Compiler toolchain | Install real Arch build dependencies; distinguish installation from compilation | GCC 16.1.1, Python 3.14.7 and make 4.4.1 installed; source build not needed by tested packages |
| Shared tabs | Two simultaneous Arch PTYs see the same project; closing one preserves the other | Passed: simultaneous PTYs, two UI tabs and surviving-tab command after closing the other |
| Restart | Reopen the app, verify packages, project and database persist | Passed: new app process read UI project, Node and prior native SQLite data |

Pin stable npm fixture versions when executing the matrix and retain the generated
lockfile in ignored test evidence. These are guest validation workloads, not bundled
Android application dependencies. Do not silently replace a failing package with
a different library and report the original package as supported.

Node documents GNU/Linux ARM64 as a supported target with minimum kernel/glibc
requirements. The guest appears to meet those numeric prerequisites, but PRoot's
syscall translation and Android policy still require actual execution tests.
[Node build/platform requirements](https://github.com/nodejs/node/blob/main/BUILDING.md).

## Prisma: separate investigation

Prisma does not gate the basic Arch shell. The documented ARM64 Linux prerequisites
include glibc 2.24+ and OpenSSL 1.x or 3.x, plus zlib and libgcc. Current Prisma ORM
documents Node ranges `^20.19.0`, `^22.12.0` or `^24.0.0`; the rolling Arch `nodejs`
package must be checked against the exact tested Prisma version.
[Prisma system requirements](https://docs.prisma.io/docs/orm/reference/system-requirements).

For Prisma's native-engine configurations, its target table includes
`linux-arm64-openssl-3.0.x`. The pinned Arch archive's ARM64/glibc/OpenSSL combination
is therefore a plausible match. This is an inference from library requirements,
not confirmation of Arch-on-Android support. OpenSSL detection, engine download,
dynamic loading and filesystem behavior can still fail under PRoot.
[Prisma schema target reference](https://docs.prisma.io/docs/orm/reference/prisma-schema-reference).

The separate device experiment must record the Prisma major/version and engine
mode. Test schema validation, client generation, a SQLite migration and a real
create/read query. For a JavaScript-engine configuration, record and test the
driver adapter and any native database dependency too. For a Rust/native engine,
record the detected binary target, OpenSSL version and actual loaded engine.
Neither `prisma --version` nor a successful npm download alone proves compatibility.

Current verdict: **Prisma 7.10.0 SQLite configuration passed under PRoot**, with the exact native-script approval described below. Other Prisma versions and database configurations remain unverified. No Prisma dependency is added to the
Android app, and no workaround weakens package signatures or changes the basic
Arch installation to satisfy Prisma.


## Stable Prisma fixture selection (2026-09-07)

Registry metadata was checked explicitly: `prisma/latest` pointed to
`8.0.0-rc.13`, while `@prisma/client/latest` was stable `7.10.0`. The separate probe
pins all three Prisma packages (CLI, client and better-sqlite3 adapter) to stable
**7.10.0**. Their published Node engine range is `^20.19 || ^22.12 || >=24.0`,
which includes the measured Arch Node 26.8.1, though the older general requirements
page describes narrower LTS ranges. Version-specific package metadata is the
relevant installation constraint; it does not prove runtime compatibility.

The probe uses the JavaScript client with the better-sqlite3 adapter, and records
CLI engine/platform output plus schema validation, migration, generation and a
real SQLite create/read. The follow-up device result passed. Setup follows the official
[SQLite quickstart](https://www.prisma.io/docs/v7/prisma-orm/quickstart/sqlite).


## Completed development gate

`/tmp/local-arch-development-device-gate-2.log`: **1/1 passed**, 500.854 seconds,
USB `RZCW81JZ9CP` / verified `SM-S911B`, isolated application APK SHA-256
`1b4b08c0f4593b20482a44174712d93c55b21bc64be3ceef230556b3222f9456`.
After signed upgrade: glibc `2.43+r22+g8362e8ce10b2-2`, OpenSSL `3.6.4-1`,
pacman `7.1.0.r9.g54d9411-2`, Node `26.8.1-2`, npm `12.0.2-1`.
All markers from `ARCH_PACKAGES_OK` through `ARCH_NODE_NATIVE_OK` were produced
by real guest workloads, including TypeScript, native image/SQLite operations and
HTTP response. npm 12 blocked esbuild's unapproved postinstall; its platform
package still passed actual bundling. No global script-approval bypass was used.
The native packages passed using their installed payloads; no node-gyp/source-build
compatibility claim is inferred. Full UI/restart confirmation is recorded separately.


The first stable Prisma probe reached a generated client but its SQLite adapter
could not load `better_sqlite3.node`. npm 12 had skipped the adapter's transitive
`better-sqlite3@12.11.1` install script. That package declares Node 26 support and
its reviewed install command is `prebuild-install || node-gyp rebuild --release`.
The follow-up explicitly checks that exact version, approves only its version-pinned
script and rebuilds it. No global script-policy bypass or package substitution is
used. This follows [npm's install-script approval mechanism](https://docs.npmjs.com/cli/v11/commands/npm-install-scripts/).
Before Prisma, the same new-process probe successfully reopened the existing
Node installation and the development fixture's persistent SQLite database
(`ARCH_DEV_RESTART_OK`).


## Final Prisma result

`/tmp/local-arch-prisma-device-gate-2.log`: **1/1 passed**, 89.371 seconds,
USB `RZCW81JZ9CP` / `SM-S911B`, isolated APK
`085fe73cd4de1a760d9c869b24cc6d795048ba7392c98c96970fce49d54be563`.
Prisma CLI/client/adapter 7.10.0, Node 26.8.1, OpenSSL 3.6.4; schema engine
`schema-engine-linux-arm64-openssl-3.0.x`, engine hash
`0edf323efd1d98336f3f0a68684b56f689b900d3`. The JavaScript query compiler/client
used the SQLite adapter's better-sqlite3 12.11.1 native payload.

Schema validation, migration workflow, generation and real upsert/read succeeded.
Migration `20260907224227_init` created the Project table during the first probe;
the follow-up confirmed it was in sync and executed the query successfully after
repairing the skipped native install. No engine checksum/TLS verification or
package signatures were disabled. No claim is made for other databases, legacy
Rust query-engine clients or Prisma 8 release candidates.

Full app UI, controls and final installation evidence is in the analysis document's
Completion evidence section. The temporary verification app is removed after
retaining its logs and exact guest dependency lockfiles in ignored local evidence.

## 2026-09-08: starter defaults, PostgreSQL, Cable Flow and Codex

Actual USB SM-S911B (`RZCW81JZ9CP`) verification now establishes:

- Fresh signed starter-tool installation with Node 24 LTS; nonmodal progress,
  Settings navigation, Home/task restoration, activity recreation and shared tabs.
- Existing-tool upgrade preserves projects and customized shell configuration;
  interactive login Bash initializes zoxide's `z` function.
- PostgreSQL 18.6 runs through bundled PRoot `--sysvipc`. A stale-local-key
  deadlock in libandroid-shmem required the original included patch; its real
  guest syscall regression changed from a 32.018-second timeout to a 2.251-second
  pass. The runtime identity now ends `/shmem-2`.
- Cable Flow commit `707411505830f257a1e40ea2d82fde938cb9e0ae` ran with Node
  24.20.0, pnpm 10.29.2, Prisma 7.8.0, PostgreSQL 18.6 and pgvector 0.8.2.
  Frozen dependency installation, Prisma generation, existing migration deployment,
  clean migration status, both managed SQL passes and standard seed all passed.
  `/login` returned HTTP success and actual `Login | Cable Flow` HTML with a
  password field. This used fresh phone-only seed databases and the repository's
  existing CI license fixture; no workstation database or credentials were used.
- Codex CLI 0.153.4 ran and rendered real interactive onboarding in the existing
  native Local Arch terminal. Account sign-in and model use remain user steps.
- Native PTY regression: 4/4. Main app unit tests: 1,027/1,027. Lint and debug/
  release builds passed. Completed debug APK installed and foregrounded on the
  Wi-Fi SM-F976B; no tests ran there.

Exact commands, red/green evidence and remaining limits are in the persistent
Obsidian note:
`/home/jiyu/Documents/Jiyu-obsidian/plannow/2026-09-08-phone-arch-starter-tools-and-cable-flow-verification.md`.
The opt-in driver is `scripts/run-local-arch-cable-flow-device-test.py`, using the
credential-free committed source archive and the real guest test script. Servers
are stopped after the probe; the isolated USB test environment retains its files.
