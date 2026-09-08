status: done
created_at: 2026-09-07T20:20:07.000Z
updated_at: 2026-09-07T22:50:10+00:00
done_at: 2026-09-07T22:50:10+00:00
independent: yes
dependencies: none

# Local Arch Linux: research and implementation contract

This records the requested pre-implementation analysis, reported before beginning
the runtime source work. The feature is implemented and validated. Actual Arch bootstrap, full UI acceptance,
restart/reset, terminal controls and development workloads passed on the authorized
old USB phone. The completed main debug APK is installed and foregrounded on the
Wi-Fi foldable without tests. See Completion evidence below for exact results.
Scope remains the entire requested Local Arch feature, with
`pacman --version` as the first vertical milestone, not the final acceptance gate.

## Decisions and open questions

- Implement **Local Arch Linux**, only Android arm64-v8a / guest aarch64. One
  persistent Arch filesystem, multiple independent Bash processes and terminal tabs.
  No distribution registry, other distributions, emulation, accounts, shared-storage
  permission, renderer changes, or managed local tmux integration.
- Reuse `Connection`, `TerminalController`, VT parsing, terminal input and the custom
  Android View. Give local processes their own application-scoped owner; preserve
  SSH/Mosh authentication, connection requests, reconnect and persistence unchanged.
- Architecture/licensing decision: PRoot is a separately executed native program,
  communicating through argv/environment and a PTY. Do not link its code into JNI
  or copy another terminal application's source. Bundle its matching loader and
  two supporting libraries, with exact corresponding source/build inputs and notices.
  This explicitly records the GPL dependency decision required by `AGENTS.md`;
  it does not authorize publication or relicense third-party components.
- Google Play distribution remains an unresolved **release** question. Proceed with
  the requested local implementation and device validation; do not infer Play
  approval from Conduit's implementation. No store upload is part of this task.
- Native loader operation on the current target SDK and actual Samsung kernel,
  PRoot descendant cleanup, package upgrades and Node native modules require
  runtime evidence. They are implementation tests, not reasons to replace Arch
  with a shell imitation or reduce the target SDK.

## Current architecture

Inspected repository HEAD `224aaac58cd5c693e885f6d4c3edea265887f33d` and the live
worktree on 2026-09-07. Other work was already changing Mosh history and preparing
an open-source/built-in-Mosh transition. Its uncommitted files are not this task's
output; re-read relevant integration files before editing them.

- `connection/Connection.kt` already provides `connect(columns, rows, onBytes,
  onState)`, bounded `trySend`, `resize` and `close`. Its authentication methods
  can be harmless no-ops for a local backend; its default tmux capability is false.
- `JschSshConnection` opens a remote shell channel, negotiates a **remote** PTY
  using `SshPtyConfiguration`, reads raw bytes and calls the supplied callback.
  Resizing calls the channel's PTY-size API.
- `MoshConnection` bootstraps over SSH and reads terminal output from an extension
  file descriptor. `SshSessionRepository` feeds both transports into
  `DefaultSshSessionTerminal.accept`. That class owns `VtTerminalEngine` and a
  `TerminalController(TerminalBuffer(...))`, handles terminal replies, titles,
  clipboard requests and bells. Mosh-specific displayed-history handling remains
  selected by the actual Mosh connection type.
- The controller publishes batched native terminal frames; the Android terminal
  View renders them with Canvas/Paint. Compose owns surrounding screens and tab
  metadata. No per-line/per-cell Compose state is needed.
- There is **no local Android PTY implementation** in the main app. Native Mosh
  code is not a local subprocess/PTY API.
- Despite its name, `SshSessionRepository` owns SSH and Mosh process lifetimes,
  foreground-service startup, trust prompts, secrets, retries and recent endpoints.
  Its request model requires SSH configuration. A fabricated localhost SSH request
  for Local Arch would break this boundary.
- `SessionTabUi.isLocalTerminal` currently means the workspace/demo terminal
  (reserved ID `0`), not a Linux shell. Do not reinterpret that flag as Arch.
  `ConnectionProtocol` is persisted remote connection data; it should remain SSH/Mosh.
- `TerminalSpikeViewModel.controllerFor` and tab actions route to the remote owner.
  `TerminalSpikeScreen` opens the saved-connection picker on New Session.
  `SessionForegroundService` currently observes only the remote repository.
- Main app currently has min SDK 26, target/compile SDK 37, backup disabled,
  INTERNET permission and no broad filesystem permission. Preserve these choices.

## Conduit reference: what was actually inspected

Reference commit: `9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742`.
Source was read in a temporary checkout; no Conduit application code was copied.
Its current implementation has expanded beyond Arch; that expansion is outside
our scope. Its Flutter UI and `flutter_pty` dependency are not proposed dependencies.

- [Local session adapter](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/lib/features/local_shell/data/local_terminal_session.dart)
  wraps a PTY as the normal terminal session: output stream, input writes, resize,
  completion and close. This is the backend seam to reproduce in our own architecture.
- [PRoot command construction](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/lib/features/local_shell/domain/proot_command.dart)
  uses fake root, explicit loader, kill-on-exit, link-to-symlink translation,
  a clean guest environment, `/root`, device/proc bindings and a kernel-version hint.
- [Downloader](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/lib/features/local_shell/data/rootfs_downloader.dart)
  supports partial downloads and verifies SHA-256.
  [Extractor](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/lib/features/local_shell/data/rootfs_extractor.dart)
  invokes bundled GNU tar and xz through PRoot, stripping one archive directory.
- [Install controller](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/lib/features/local_shell/presentation/local_shell_controller.dart)
  downloads, clears rootfs, extracts, configures and then writes a version.
  Our recovery requirement calls for staging and atomic activation, retaining a
  working installation until a confirmed replacement validates.
- [Native build recipe](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/tools/build-local-shell-binaries.sh)
  builds pinned Termux recipes, packages executables as `lib*.so`, and rewrites
  versioned dynamic-loader names. Android Gradle legacy JNI packaging and
  `extractNativeLibs=true` place them in `applicationInfo.nativeLibraryDir`.

Conduit's complete native payload is: PRoot and loader; BusyBox launcher/library;
GNU tar; xz/liblzma; talloc; libandroid-shmem, libandroid-glob, libandroid-selinux;
PCRE2; libacl, libattr, libiconv and libcharset. The versions are recorded in its
[component table](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/THIRD_PARTY_NOTICES.md).
Do not inherit its notices without auditing the exact sources: its table groups
ACL with attr 2.5.2, but the pinned upstream
[libacl recipe](https://github.com/termux/termux-packages/blob/ac296452b8ebec390cad3bce9060577c96099b10/packages/libacl/build.sh)
actually builds ACL 2.3.2 from a separate source archive.

## Proposed LocalSession architecture

```text
Compose New Session / tabs / Settings
                   |
       LocalSessionRepository (app lifetime)
                   |
 LocalSession : Connection + existing terminal adapter
                   |
        NativePty (small original JNI bridge)
                   |
       APK-bundled Android PRoot + loader
                   |
      shared private Arch generation/rootfs
                   |
        independent /bin/bash --login per tab
```

`LocalSession` implements raw byte transport, bounded input writes, window resizing,
state transitions and idempotent close. `LocalSessionRepository` holds the terminal
adapter, child ownership and rootfs lease for each tab in application scope. Reuse
the existing terminal factory by making its factory entry accessible internally;
do not edit its parser, history scheduling, mouse, viewport or fling algorithms.

Use a transient session-backend discriminator for workspace, SSH, Mosh and Local
Arch. Keep the existing remote protocol field for remote metadata only. Local
IDs can occupy the negative range, with `0` retaining its workspace meaning and
positive remote IDs untouched. Audit notification navigation for assumptions
that valid session IDs must be positive. Do not persist fake host profiles,
credentials, endpoint identities or SSH recent-session records for Arch.

Aggregate local/remote tab metadata and foreground-service demand at the existing
UI/service boundary. Local install progress also counts as user-started work.
Retain service privacy/CPU-awake settings and failure handling; a service failure
must close local children as well as exercising the existing remote handling.
Network loss never automatically replaces a local shell. Closing/restarting the
app can end shell processes; the installed filesystem survives and a new shell
opens on return. Do not promise detached daemons survive Android process death.

PTY implementation requirements: allocate `/dev/ptmx`, grant/unlock and open the
slave, create a session/controlling terminal, connect stdin/out/err, initialize
termios and winsize, and exec the bundled PRoot. Prebuild argv/environment before
fork; the child path must avoid JVM/allocator locks. Use a CLOEXEC error pipe to
distinguish launch failure from successful exec. Read/write off the main thread;
handle partial writes, EINTR, EOF and Linux PTY EIO. Serialize close against resize
and I/O so an fd cannot be reused underneath another operation. Reap the child,
bound shutdown and prove all its guest descendants end without affecting other
tabs. `TIOCSWINSZ` must reach the guest foreground process group. Ctrl+C/Ctrl+D
are PTY input bytes processed by the terminal line discipline, not app commands.

## Arch bootstrap: exact artifact and integrity

Selected initial bootstrap is the exact image currently used by Conduit:

- Version: `archlinux-aarch64-pd-v4.37.0`
- URL: `https://github.com/gwitko/conduit-rootfs/releases/download/rootfs-pd-v4.37.0/archlinux-aarch64-pd-v4.37.0.tar.xz`
- Bytes: `176379228`
- SHA-256: `718151cc4adad701223c689a7e4690cb7710b7b16e9b23617b671856ff04d563`
- GitHub mirror release ID: `352817765`, published `2026-07-12T18:39:30Z`.

This is a Conduit-hosted mirror described as proot-distro-derived, not an official
Arch upstream immutable release. The actual archive was downloaded and SHA-256
computed locally; it matches both Conduit's pinned
[configuration](https://github.com/gwitko/Conduit/blob/9cd2ff9174e4ee8e9f0679d1da6aa4aa0edcf742/lib/features/local_shell/local_shell_config.dart)
and GitHub's asset digest. The older README attribution to Termux release assets
does not describe the current download URL. The Termux v4.37.0 release API returned
404 during this research; its accessible tagged plugin instead references a
v4.36.0 image with a different digest. Do not silently substitute either artifact.

Archive inspection: one `archlinux-aarch64` prefix; 26,587 regular files, 8,314
symlinks, 1,609 directories; no hardlink/device/FIFO entries and no absolute or
`..` entry names. Regular-file sizes total `882731922` bytes; tar stream is about
902 MiB. `/usr/lib/os-release` identifies `Arch Linux ARM`, `ID=archarm`.
Installed package metadata includes Bash 5.3.9-1, glibc
2.42+r50+g453e6b8dbab9-1, OpenSSL 3.6.1-1, pacman 7.1.0.r9.g54d9411-1 and
archlinuxarm-keyring 20240419-1. These are observations of the pinned rolling
distribution, not promises about versions after `pacman -Syu`.

Commit the URL, digest, exact size and extracted bounds in one Arch-only manifest.
Do not fetch a remote manifest or a checksum beside an unpinned rolling download
and call that pinning. Future bootstrap upgrades require an explicit manifest
change; existing user rootfs stays installed until reset/reinstall is confirmed.

Installation state machine:

1. `NotInstalled` or `Ready` comes from validated active-generation metadata, never
   merely a rootfs directory's presence. Unsupported ABI reports its reason before
   downloading. Show size, private storage location and an Install action.
2. Acquire an environment-wide operation lock. Download to a persistent `.part`
   under `filesDir/local-arch`, using HTTPS and streaming hashing. Validate exact
   `Content-Range` for resume; a 200 response restarts safely, a 416 response leads
   to local size/hash validation or restart. Bound redirects to HTTPS, length,
   timeouts and disk consumption. Never log signed redirect query strings.
3. Verify full expected size and SHA-256 before parsing. Keep a verified archive
   reusable after extraction failure. Check free space before extraction and
   report ENOSPC distinctly; allocation checks are advisory, not guarantees.
4. Extract to an unexposed staging generation, strip the exact known prefix,
   preserve executable modes and guest symlink text. Stream TAR with Commons
   Compress and XZ for Java; apply explicit entry/count/expanded-byte/memory bounds.
   Reject path traversal, duplicate conflicts, unsupported special entries and
   writes beneath symlink parents. Create symlinks after ordinary files and do not
   follow them when changing permissions, measuring usage or deleting. This
   archive contains no hardlinks; reject unexpected ones until deliberately supported.
5. Validate packaged native payload, configure guest DNS/hosts/locale and pacman
   trust, then execute guest checks through the **same** PRoot launcher that tabs use.
   Check `/usr/bin/env`, Bash, aarch64 ELF loader, pacman and the package database;
   run Bash, `id -u`, `uname -m`, `pacman --version` and signed-keyring initialization.
   Do not disable signature verification. Bootstrap currently contains pacman's
   `DisableSandboxFilesystem`/`DisableSandboxSyscalls`; record their PRoot purpose.
6. Close validation processes before committing. Fsync a completed manifest and
   atomically activate the generation on the same filesystem. The marker lives
   outside the guest rootfs and records bootstrap digest and runtime version.
   Report Ready only after activation succeeds. Remove obsolete staging/download
   files safely; a cleanup failure must not misreport a valid installation as lost.
7. On next launch after a kill: ignore incomplete staging, resume/restart download
   safely, retain a previously valid active generation, and offer Retry with the
   failure stage. Never install over a filesystem in use by a tab.

Use Android's active-network DNS addresses when available; do not inherit Conduit's
silent public-resolver fallback. Report unavailable DNS clearly and refresh when
starting subsequent shells without repeatedly clobbering user configuration.
Prefer a verified HTTPS Arch mirror and retain package signatures. Conduit's
first-boot script overwrites mirrorlist with HTTP and selects public resolvers;
those are reference choices, not requirements to copy. Use `C.UTF-8` initially or
generate `en_US.UTF-8` before selecting it. Never silently run a whole `pacman -Syu`
as an installation-complete shortcut.

## Native runtime and dependencies

Selected runtime is smaller than Conduit's because extraction and deletion remain
Kotlin operations. No BusyBox, native tar/xz, ACL, iconv, SELinux or PCRE2 payload is
needed for those operations. The exact proposed Android native payload is:

| Packaged file | Source/version | Purpose and licence |
| --- | --- | --- |
| `liblocalpty.so` | Original project JNI, NDK r29 | PTY operations only; project licence |
| `libproot.so` | `termux/proot` tag `v5.1.107.81` | Executed PRoot tracer; audited source headers grant GPL-2.0-or-later |
| `libproot_loader.so` | Same PRoot source/build | Executable guest loader; same source obligations |
| `libtalloc.so` | talloc 2.4.3 | PRoot allocation support; library LGPL-3.0-or-later |
| `libandroid-shmem.so` | libandroid-shmem 0.7 | Android shared-memory compatibility; BSD-3-Clause |

Conduit's table labels PRoot GPL-2.0-only; all 76 GPL-bearing C/header notices
examined in its exact PRoot source archive explicitly include a later-version
grant. Verify remaining build/source files for the shipped artifact; do not resolve
licensing from the abbreviated Termux package field alone. Check talloc's exact
library source licence too, rather than treating its package-level GPL label as
the library licence.

Pinned upstream source hashes already identified by the reviewed recipes:

- PRoot zip: `08c9071fb0d208cdaaf98a29ba4293716fa7ec0f875c51eab153b35b53a4f4d6`
- talloc tar.gz: `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`
- libandroid-shmem tar.gz: `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867`

Build from upstream component sources using reviewed Termux configuration, not
Conduit application code or unexplained prebuilt `.so` files. Pin source hashes,
NDK, flags, any patches and final output hashes. Resolve `DT_NEEDED`/SONAME from
`nativeLibraryDir`, remove Termux-private absolute runtime path assumptions and
verify the dependency closure using `readelf`. Preserve source/build instructions
for every redistributed GPL/LGPL component and support rebuilding/replacing it.
Do not make a written source offer on someone else's behalf.

New JVM direct dependencies proposed: `org.apache.commons:commons-compress:1.28.0`
(TAR reader; Apache-2.0) and `org.tukaani:xz:1.12` (XZ stream; 0BSD). These are
stable published versions per [Apache](https://commons.apache.org/proper/commons-compress/dependency-info.html)
and [Tukaani](https://tukaani.org/xz/java.html). Inspect their resolved transitives,
Android API compatibility and packaged notices before adding them; document all
new direct dependencies in `docs/DEPENDENCIES.md` and `THIRD_PARTY_NOTICES.md`.
No Flutter or terminal-emulator dependency is proposed.

Proposed invocation, passed as argv rather than interpolated shell text:

```text
host env: PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so
          PROOT_TMP_DIR=<private per-session tmp>
          LD_LIBRARY_PATH=<nativeLibraryDir>
<nativeLibraryDir>/libproot.so
  --kill-on-exit --link2symlink -0 -r <active-rootfs>
  -b /dev -b /proc -b /sys -b /dev/pts
  -b /dev/urandom:/dev/random
  -b /proc/self/fd:/dev/fd
  -b /proc/self/fd/0:/dev/stdin
  -b /proc/self/fd/1:/dev/stdout
  -b /proc/self/fd/2:/dev/stderr
  --cwd=/root -k 5.4.0
  /usr/bin/env -i HOME=/root USER=root LOGNAME=root
  TERM=xterm-256color LANG=C.UTF-8
  PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
  /bin/bash --login
```

The kernel version is a compatibility hint taken from the reference, not kernel
emulation. Validate this hint and each binding on the device; never expose shared
phone storage as a convenience fallback. Keep host environment clean and guest
`LD_LIBRARY_PATH` unset. Do not add `PROOT_NO_SECCOMP` unless device evidence calls
for that compatibility adjustment. `--kill-on-exit` still needs death-path testing.

## Settings, persistence and recovery

Settings shows Installed/Not installed plus operational progress/error, bootstrap
version, measured storage used, Reset and Reinstall. Measure off-main without
following symlinks; distinguish “calculating”/unavailable from zero bytes.

Reset requires a dedicated destructive confirmation naming the entire filesystem:
packages, `/root`, projects and all local tab processes. Require a typed `RESET`
or equivalent strong deliberate confirmation. Cancel preserves everything.
After confirmation stop/reap local sessions, acquire the exclusive environment
lease, invalidate activation atomically and delete only this environment with
no-follow traversal. Never clear SSH credentials or remote tabs.

Reinstall also confirms destructive replacement. Prepare and validate a fresh
generation while the old valid filesystem remains recoverable; switch only after
successful validation and then delete the confirmed old data. Reject stale dialog
confirmations against a different environment generation. Existing settings backup
must not unexpectedly include this large Linux filesystem; Android backup remains
disabled. Uninstall and explicit app-data clearing still delete private storage.

## Android, Play and PRoot risks

Android [API-29 execution restrictions](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
prevent directly executing files from a writable app home. Package Android runtime
executables in the APK and execute their installer-extracted native-library paths;
copying/chmodding them into `filesDir` is not a solution. PRoot rewrites guest exec
to its explicit loader and maps guest ELF contents. That explains the proposed
mechanism; it does not prove this kernel/SELinux configuration permits it.

All generated ELF objects and the loader need appropriate alignment and runtime
validation for [16 KB Android pages](https://developer.android.com/guide/practices/page-sizes).
The guest ELF/glibc loader must also work at the actual page size. APK ZIP/native
packaging alignment checks alone do not establish guest compatibility. Keep
unsupported-ABI behavior explicit without dropping existing remote ABI support.

Google Play's [Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
restricts executable-code downloads outside Play and describes a VM/interpreter
exception. PRoot runs native Linux instructions; classifying it under that
exception is not established here. A downloaded rootfs and subsequent pacman/npm
native binaries make this material. Store eligibility needs an explicit release
assessment; no claim of approval and no policy-evasion workaround is justified.

PRoot shares the Android kernel and app UID. Fake root does not grant Android
root, mounts, kernel modules, namespaces or a normal systemd boot. It is not a
security isolation boundary from the app's own files/permissions. Never put app
credentials in guest environment variables. Android process limits, OEM battery
management, seccomp/ptrace interactions and syscall overhead can affect package
builds and long-running servers. See upstream [PRoot-Distro limitations](https://github.com/termux/proot-distro#limitations).

## Exact file-level implementation sequence

Paths below `java/` are relative to `app/src/main/java/com/yanjiyu/terminalspike/`.
Keep the first code change after this analysis and its report.

1. Add `docs/ADR-LOCAL-ARCH-RUNTIME.md`, `local-arch-runtime/README.md`,
   `local-arch-runtime/sources.lock`, source licences/notices and an original
   `scripts/build-local-arch-runtime.py`. Build ignored outputs for arm64 only.
   Record source delivery and audit GPL/LGPL grants before adding binary payload.
2. Add `app/src/main/cpp/local_pty.cpp` (compiled by the pinned native build script),
   `java/localarch/NativePty.kt`, `ProotCommand.kt`, `LocalSession.kt` and
   `LocalSessionRepository.kt`. Configure NDK/native payload extraction in
   `app/build.gradle.kts`; retain stable dependency versions in
   `gradle/libs.versions.toml`. Do not modify Mosh CMake or native sources.
3. Add `java/localarch/ArchRootfsManifest.kt`, `ArchEnvironmentStore.kt`,
   `ArchRootfsDownloader.kt`, `ArchRootfsExtractor.kt`, `ArchBootstrap.kt` and
   `ArchEnvironment.kt` (including its install state).
   The manager serializes installs/reset/reinstall and supplies session leases.
4. Small integration in `java/connection/SshSessionRepository.kt`: expose the
   existing terminal factory for reuse, without altering its behavior. Wire local
   ownership in `java/AppContainer.kt`. Extend `java/SessionForegroundService.kt`
   metadata aggregation and `app/src/main/AndroidManifest.xml` service description
   to include user-started local terminals/installation; preserve remote semantics.
5. Add `java/ui/LocalArchUi.kt` (chooser, install and settings),
   `java/ui/LocalArchSessionProjection.kt` and a transient `isLocalArch` tab flag. Integrate New Session choices SSH/Mosh/Local Arch
   in `java/ui/TerminalSpikeScreen.kt`; reuse the existing SSH/Mosh flow after
   choosing the remote transport. Route tabs/controllers/close/duplicate/new-shell
   in `java/ui/TerminalSpikeViewModel.kt` and labels/actions in
   `java/ui/TerminalSessionSwitchers.kt`. Hide inapp SFTP/SSH trust/reconnect and
   managed-tmux tools for Local Arch. Continue normal terminal input/clipboard.
6. Add the settings section through `java/ui/settings/SettingsScreen.kt` and its
   settings model/viewmodel boundary, plus `app/src/main/res/values/local_arch.xml`.
   Scope local resets separately from `LocalAppDataResetter.kt`'s existing app reset.
7. Add meaningful `app/src/test/.../localarch/` install/transport/ownership tests,
   `app/src/androidTest/.../LocalArchRealEndToEndTest.kt`, a guarded
   `scripts/run-local-arch-device-tests.sh`, and
   `docs/local-arch-development-compatibility.md` with measured results, including
   a separate Prisma investigation. Update both dependency/notice documents.

## Verification and evidence contract

First vertical milestone: New Session → Local Arch Linux → visible install → real
guest Bash in the existing terminal → `pacman --version`. No fake output or shell
outside Arch can satisfy this. Continue to all feature gates below before completion.

| Requirement | Required evidence |
| --- | --- |
| Install/pin | Real HTTPS download, exact SHA-256, Arch identity, PRoot guest validation and Ready only after activation |
| Recovery | Inject kill/network failure/bad hash/truncated XZ/path and symlink traversal/extraction error/ENOSPC/activation error; restart remains recoverable and incomplete generations never appear Ready |
| PTY | `test -t 0`, Ctrl+C interrupts `sleep`, Ctrl+D exits, control keys and UTF-8 echo correctly, `stty size` and WINCH trap reflect resize |
| Existing terminal | Local interactive output, input, alternate screen and ordinary history run through existing parser/controller/View; existing terminal regressions remain intact |
| Tabs | At least two local shells see the same files, have distinct shell PIDs and terminal sizes, and closing one leaves the other usable |
| Persistence | Install packages/create project, close and force-stop/relaunch app, verify packages and files remain without reinstall |
| Cleanup | Repeated open/close, cancellation during launch, background/foreground, tracer death and service loss leave no owned orphan process/fd and do not close remote sessions |
| Settings | Accurate status/version/storage, strong Reset and Reinstall confirmation, cancellation, active tabs and stale confirmations tested |
| Package/development | Actual `pacman -Syu`, `pacman -S git nodejs npm`, Node/npm versions, `npm install -g typescript`, `tsc --version`, git clone, npm install, npm run dev and npx tsc in a real TypeScript project |
| Native npm | Version-pinned esbuild, sharp and better-sqlite3 install **and execute** smoke functions; source-build test with node-gyp/Python/base-devel, watch/HMR behavior and loopback HTTP probe; record concrete failures |
| Prisma separate | Record Node, libc, OpenSSL and exact Prisma versions; distinguish client/runtime from schema-engine/native paths, generate/migrate/query against disposable SQLite; do not block core Arch completion on Prisma success |
| Distribution inputs | Stable dependencies, resolved ELF/JVM closure, licence text/notices, exact native source/build scripts, generated outputs excluded from Git, page alignment checked |
| Non-regression | Full JVM tests, lint and build; existing SSH/Mosh session and scrollback tests preserved; focused remote/device gates appropriate to the small integration changes |

Prisma's [system requirements](https://www.prisma.io/docs/orm/reference/system-requirements)
include Linux ARM64, glibc and OpenSSL combinations. The inspected rootfs has
glibc 2.42 and OpenSSL 3.6.1, which makes investigation plausible but does not
establish PRoot compatibility. Test specific Prisma major/version and execution
paths independently; a successful `prisma --version` is not a database workflow.

Required build command: `./gradlew test lint assembleDebug assembleRelease
assembleDebugAndroidTest` using the repository's compatible JDK/SDK. Add focused
local tests, source checks and APK ELF packaging inspection. Re-run broad checks
after final integration; cached baseline evidence is not final feature evidence.

Resolve `adb devices -l` before device work and verify the model before every
install/test. Research observed old phone `RZCW81JZ9CP` over USB and a separate
wireless transport for that same `SM_S911B`. Use the freshly verified USB serial
for this feature; the wireless exception in AGENTS.md is for tmux-scroll work.
Use a disposable test environment so failure/reset tests cannot delete user Arch
projects. Never test on `SM_F976B`. At full-feature completion resolve that foldable,
install/launch the completed debug APK and verify MainActivity foreground, or
report its unavailable/offline/unauthorized state. No foldable was present in the
research device listing. Do not run an incremental installation there.

## Historical implementation log (superseded by Completion evidence)

- This session completed repository/reference analysis, a real 176,379,228-byte
  rootfs download, local hash and archive-content audit, native dependency/licence
  review and device discovery. It did not implement or install Local Arch.
- Baseline `./gradlew test lint assembleDebug` succeeded. Inspection found its app
  test output retained a previous filtered run; an explicit
  `:app:testDebugUnitTest --rerun --tests '*'` then passed **977 tests**, zero
  failures/errors/skips across 130 XML suites. Mosh API/extension retained green
  11/8-test results. This is a baseline, not proof of Local Arch functionality.
- After reporting the analysis, implementation started with
  `ADR-LOCAL-ARCH-RUNTIME.md`, the native `sources.lock`, three complete upstream
  source archives independently downloaded from upstream and hash-verified, their
  licence texts, and dependency/notice entries. This was the source-only starting point.
- After the source/notice changes, `:app:testDebugUnitTest --rerun --tests '*' lint
  assembleDebug` passed in 12 seconds: 977 app tests, zero failures/errors/skips;
  lint and debug build succeeded. Source hashes and `git diff --check` also passed.
  No device install or runtime test was performed for this source-only increment.
- Native build now packages original JNI, PRoot 5.1.107.81 + loader, talloc 2.4.3 and
  shmem 0.7. NDK r29, source hashes, patches, dynamic dependency closure and 16 KiB
  LOAD alignment are checked by the build script. Source inputs and notices are
  included; generated binaries remain ignored. Only arm64 has an Arch runtime.
- Primitive device gate is green: **4/4 tests** on USB `RZCW81JZ9CP`, verified
  `SM-S911B`, in `/tmp/local-arch-pty-device-gate-5.log`. This proves packaged PRoot
  execution, real controlling PTY resize, Ctrl+C status 130, Ctrl+D, independent
  PTYs and launch errors. It does not prove a Linux guest or the UI feature.
- Kotlin implementation now includes verified/resumable pinned HTTPS download,
  bounded TAR/XZ extraction with deferred links, atomic generation activation,
  leases preventing reset while shells run, and no-follow storage/deletion.
  Interrupted staging and failed replacement preserve active user data. Unreadable
  active metadata is an error, not permission to delete existing generations.
- Bootstrap configuration uses Android network DNS, preserves subsequent user DNS
  edits and initializes signed Arch keyrings through the production PRoot launcher.
  The official mirror `https://de3.mirror.archlinuxarm.org/aarch64/core/core.db`
  returned HTTP 200 over verified TLS on 2026-09-07; the GeoIP HTTPS endpoint failed
  TLS/connectivity in the host probe. See [Arch ARM mirrors](https://archlinuxarm.org/about/mirrors).
- Application-scoped LocalSessionRepository now owns independent negative-ID tabs
  over one leased filesystem, reuses the unchanged terminal adapter, and contributes
  process/install demand to the existing foreground service. New Session offers SSH,
  Mosh and Local Arch. Settings show status/version/usage and require typing
  `DELETE ARCH` for reset/reinstall. These source integrations need device UI QA.
- Local storage/transport tests passed (22 tests before the UI integration tests).
  The full app unit run subsequently executed 1,016 tests, with two failures:
  the new literal UI label (fixed by resources), and the concurrently edited
  Mosh history regression (200 expected markers vs only rows 192–200). The Mosh
  algorithm/test belongs to other ongoing work and has not been changed here.
- Full lint/release verification is still pending after UI edits. A combined run
  hit a lint FIR internal exception in TerminalSpikeViewModel while source was being
  edited; an earlier combined run lost a generated Room release source during
  concurrent build activity. Neither is reported as green or suppressed.
- Real pinned-image device gate is implemented separately in ArchBootstrapDeviceTest,
  using `files/local-arch-device-bootstrap`, never the user's active environment.
  Initial runner signature error was corrected. Later attempts reported process
  death; Android exit-info confirms PACKAGE UPDATED during instrumentation
  (22:24:07 and 22:27:52 phone local time). Another task repeatedly installs the
  same package, despite our checking for a live runner before each command. Its
  results cannot validate or invalidate Arch. A coordination question is pending.
- Next: finish clean unit/lint/build checks, reserve a quiet authorized USB old-phone
  window and run the real bootstrap gate; fix observed guest/runtime issues; validate
  actual New Session/UI, install recovery, controls/resize, shared tabs and restart
  persistence; then execute the full pacman/Node/TypeScript/native-npm matrix and
  investigate Prisma separately. Full acceptance and completed-build foldable
  install remain outstanding. No Play upload or completed-feature claim.

### Later host verification in this session

- A clean `:app:testDebugUnitTest --rerun --tests '*' :app:assembleDebug
  :app:assembleDebugAndroidTest --no-parallel` passed: **1,019 app tests**, zero
  failures/errors/skips. This includes the current Mosh changes as they stood at
  that run; no Mosh fix was made by the Local Arch work.
- Separate `lint --no-parallel` passed, followed by `:app:assembleRelease
  --no-parallel` passing. Logs: `/tmp/local-arch-ui-checks-3.log`,
  `/tmp/local-arch-lint-2.log`, `/tmp/local-arch-release-build-2.log`.
- The production Kotlin extractor successfully parsed the complete hash-verified
  pinned archive on the host. It retained executable Bash and regular ARM64 Bash,
  env, pacman and loader files; Arch os-release matched. Stored file/link bytes:
  882,934,660. Extraction took 12.02 seconds; owned temporary rootfs was deleted
  without following links. `/tmp/local-arch-pinned-extraction.log`. This is archive
  validation, not a replacement for Android/PRoot execution.
- The inspected debug APK was SHA-256
  `7b512bcbe58b58fbbb058a3d11ecc5cfe57b0daa7828c52081297cca6fd2df0d`, with five
  arm64 local-runtime files and 18 runtime license/notice assets. Main app already
  restricted packaged ABIs to arm64-v8a and x86_64 before this task; the bridge keeps
  those existing ABIs available, and Arch remains arm64-only.
- The other device runner stopped, and guarded real bootstrap retry 5 is running
  in `/tmp/local-arch-bootstrap-device-gate-5.log`. Do not treat it as passed until
  its live process completes and full instrumentation results are inspected.

### First actual guest failure and focused correction

- Real-image retry 5 completed the verified download and extraction on the old
  phone and executed the guest validation script. It reached pacman but failed
  signed-keyring initialization: gpg-agent exit 2 / general error, followed by
  pacman-key reporting no secret signing key. The installer did not activate this
  generation. Full result: `/tmp/local-arch-bootstrap-device-gate-5.log`.
- Pinned upstream PRoot `src/syscall/socket.c` redirects Unix socket paths longer
  than `sun_path` into `PROOT_TMP_DIR`, rejecting the operation when the fallback
  is also too long. Our UUID generation directory made both paths exceed the
  limit (the production gpg socket path is 142 bytes). Temporary runtime files
  now live in short app-private `files/arch-tmp`, separately from persistent rootfs
  generations. Test temp storage uses `files/arch-test-tmp`. Command construction
  rejects an overlong fallback directory; reset and storage accounting include it.
  This is a focused correction pending a green real-guest test, not yet proof that
  all keyring issues are fixed. No signature check was disabled.
- Retries 6 and 7 were again interrupted by app-package updates. A temporary
  source snapshot at `/tmp/terminal-spike-arch-verification-8z8ks0l9` changes only
  the debug application ID to `com.yanjiyu.terminalspike.archverify` for isolated
  guest validation. No extra module or production application ID change is added
  to the repository. This avoids repeated updates to the main package killing
  the background guest probe; actual UI/device acceptance still needs inspection.
- Local active-tab restoration also now retains a separate pending negative ID,
  so the initial remote-session projection cannot consume its restoration request.
  These latest fixes require another appropriate host verification run.


### Real guest bootstrap green; package timestamp correction

- Isolated full bootstrap passed on USB `RZCW81JZ9CP`, verified `SM-S911B`:
  `/tmp/local-arch-isolated-device-gate-2.log`, 1/1 in 96.472 seconds. APK SHA-256
  `51570a897797e6af6bc05e795d15ec8c0118a5b672329ca80d14775f8a818acb`.
  This executed the pinned download, extraction, signed keyring initialization,
  real interactive Arch Bash/pacman, PTY resize and two simultaneous shells sharing
  a persistent project. A fresh environment owner reopened the same filesystem.
  It does not yet establish app-process restart or full UI acceptance.
- The first development workload failed with HTTP 404 for obsolete image-era
  packages: pacman incorrectly considered archived repository databases current.
  The extractor had replaced archive modification times with extraction time.
  It now preserves file/directory modification times. Initial configuration also
  clears only cached sync databases, preserving installed-package metadata;
  the first real `pacman -Syu` must fetch current repository data. Signatures remain
  enabled. Regression tests cover both behaviors.
- Forced isolated reinstall activated the new image, but shell checks failed with
  a Compose-generated `$stable` field linkage error. A cached clean build retained
  it; an uncached clean compile resolved it. The subsequent bootstrap shell gate
  passed 1/1 in 5.497 seconds (`/tmp/local-arch-isolated-device-gate-5.log`).
  Do not classify those failed cached APKs as a guest incompatibility.
- Post-socket main unit/debug checks passed (1,022 tests at that point), followed
  by lint and release build; logs `/tmp/local-arch-after-socket-tests.log` and
  `/tmp/local-arch-after-socket-lint-release.log`. Later timestamp/UI tests compile
  and pass targeted host checks in `/tmp/local-arch-ui-device-compile.log`.
- Current development retry: `/tmp/local-arch-development-device-gate-2.log`.
  Pacman is executing actual signed upgrades; final exit status is pending.
  Full UI installation/tab/settings, actual guest Ctrl+C/Ctrl+D and app-process
  restart gates are added but not yet green. No finished-feature claim.


### Development and full UI evidence

- Real signed upgrade and Node development gate passed 1/1 in 500.854 seconds:
  `/tmp/local-arch-development-device-gate-2.log`, USB `RZCW81JZ9CP` / `SM-S911B`.
  Node 26.8.1, npm 12.0.2, global/project TypeScript 7.0.2, Git clone,
  esbuild 0.28.2 bundle execution, sharp 0.35.4 image processing, better-sqlite3
  13.0.3 persisted database and an actual npm development-server HTTP response
  all passed. Detailed versions and qualifications are in the compatibility ledger.
- First complete UI attempt passed installation, real pacman renderer/input,
  activity recreation and two shared tabs, then selected the wrong (background)
  Reset button in its assertion. Fixed the test to select the dialog's button.
- The app-process restart probe read the saved project but exposed a real reset
  race: a Settings storage refresh could hold the environment mutex while reset's
  tryLock rejected the action. The repository swallowed that early exception.
  Mutations now suspend behind storage refresh; the repository publishes failures
  even if the environment could not publish a stage error.
- Restart plus confirmed Settings reset then passed 1/1 in 10.397 seconds:
  `/tmp/local-arch-ui-restart-device-gate-2.log`. The fresh full UI rerun passed
  1/1 in 60.741 seconds: `/tmp/local-arch-ui-device-gate-2.log`. Both used isolated
  APK SHA-256 `085fe73cd4de1a760d9c869b24cc6d795048ba7392c98c96970fce49d54be563`
  on the freshly verified old USB phone. This exercised actual New Session →
  Local Arch → installer → renderer, shared tabs, activity recreation, exact
  DELETE ARCH enablement and non-destructive cancellation. A separate Android
  process reopened persisted files; confirmed reset actually removed the guest.
- The notification's disconnect-all action now includes local shells, and shared
  foreground text describes terminal sessions. No remote transport, tmux or
  renderer algorithm was changed by this feature.
- Main unit/lint/debug/release checks passed in
  `/tmp/local-arch-final-candidate-checks.log` before the reset correction.
  Focused reset-correction host checks passed; the post-correction full run is
  `/tmp/local-arch-post-reset-full-checks.log` (result pending at this entry).
- Actual Arch Ctrl+C passed. The new Ctrl+D assertion timed out with an unreaped
  process; prompt timing/exit output is under investigation. Separate stable
  Prisma 7.10.0 probe is running in `/tmp/local-arch-prisma-device-gate-1.log`.
  Final control verification, evidence reconciliation and finished-build phone
  installation remain outstanding. No Play upload.


## Completion evidence — 2026-09-07

- Full main checks: **1,026 unit tests**, zero failures/errors/skips; lint, debug,
  Android-test APK and release builds passed. Final log:
  `/tmp/local-arch-completion-checks.log`. `git diff --check` also passed.
- Real old-phone gates used freshly resolved USB `RZCW81JZ9CP`, verified
  `SM-S911B` before every install/test. PTY primitives 4/4; real pinned Arch
  bootstrap; signed pacman upgrade; Node/TypeScript/native-module development;
  first-install UI; two shared tabs; activity recreation; app-process persistence;
  exact reset confirmation/cancellation and actual confirmed reset all passed.
- Ctrl+D's initial timeout was a test timing issue: it was sent before the next
  Bash prompt. The gate now waits for an unmistakable real prompt and captures
  exit output. Ctrl+C (130), Ctrl+D (0), PTY resize and simultaneous real Arch
  shells passed together, **1/1 in 4.415 seconds**:
  `/tmp/local-arch-controls-device-gate-2.log`. No runtime change was required.
- Separate Prisma **7.10.0** compatibility passed, **1/1 in 89.371 seconds**:
  `/tmp/local-arch-prisma-device-gate-2.log`. The Node installation and prior native
  SQLite data survived new Android processes. Prisma used native
  `schema-engine-linux-arm64-openssl-3.0.x`, validated a schema, ran the migration
  workflow, generated a client and completed SQLite upsert/read through its
  JavaScript client plus better-sqlite3 12.11.1 adapter dependency. The initial
  migration `20260907224227_init` creates the Project table; its SQL was verified.
  npm 12 required approval of that exact dependency's reviewed install script.
  This validates the tested SQLite setup, not every Prisma version/database mode.
- Completed main APK SHA-256:
  `06bd46914fb8882922d62e63c1850b8a9c289d468a2876199abc47380fb072be`.
  Installed with `adb -s 192.168.0.33:36441 install -r`, target verified
  `SM-F976B`, and launched `com.yanjiyu.terminalspike/.MainActivity`.
  Launch returned `Status: ok`; dumpsys confirmed `topResumedActivity` was that
  MainActivity. **No tests ran on the foldable.** Evidence:
  `/tmp/local-arch-foldable-install.json`.
- User instructions: [Local Arch Linux](local-arch-linux.md). Exact development
  package versions and limits: [compatibility ledger](local-arch-development-compatibility.md).
  Source/build inputs and licenses are recorded; no store upload, Mosh extension
  version change, shared-storage/account feature or generic distro framework.
- The independent tmux/Mosh worktree changes remain intact. This task added the
  local backend and small shared UI/service integration only; it does not claim
  or certify completion of the separate tmux investigation.
