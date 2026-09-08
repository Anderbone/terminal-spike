# Local Arch runtime build inputs

The Android runtime builds from these sources and is packaged in the main APK.
Real-device gates cover PTY primitives, the PRoot executable, pinned Arch bootstrap
and package/development workloads. Full feature acceptance and current evidence
are recorded in the implementation contract and development compatibility ledger.

`sources.lock` identifies the three approved Android runtime components and the
stable NDK. Source archives belong under `third_party/distfiles`; their licences
remain inside those exact archives. Build outputs belong under ignored `build/`.
The app will execute PRoot and its loader separately from its original JNI PTY
bridge. No Conduit/Termux terminal application source is incorporated.

The [architecture decision](../docs/ADR-LOCAL-ARCH-RUNTIME.md) records licensing and
distribution requirements. The [implementation contract](../docs/local-arch-linux-analysis.md)
records the full feature and tests.

## Build and replacement

Prerequisites: Linux x86_64 build host, Python 3.12+, make, patch, a host C compiler
and binutils for configure checks, and Android NDK `29.0.14206865` (r29).
The build needs no root, Termux installation, emulator or network access.
All upstream archives are included and hash-checked before extraction.

```sh
python3 scripts/build-local-arch-runtime.py --ndk /absolute/path/to/ndk/29.0.14206865
```

The normal main-app Gradle build invokes the same script with the NDK in its
configured Android SDK. SDK paths stay out of Git. Generated JNI inputs reside
under `build/jniLibs`. Only arm64 receives PRoot and its dependencies; a JNI-only
x86_64 bridge preserves remote-terminal/emulator availability. No x86 Arch runtime
or PRoot 32-bit loader is provided.

The script verifies ELF64 AArch64 identity, 16 KB LOAD alignment and the shared-library
dependency closure. Output hashes are in `build/runtime-manifest.json`. Generated
files never belong in Git. A filesystem lock rejects simultaneous native builds.

To modify the runtime, change the included source/patch inputs, update the source
lock for deliberately changed source archives, and rebuild/install the main APK
using your own signing key. No production signature is required for modified
builds. Android requires uninstalling or changing the application ID to replace an
installation signed by another key; uninstalling deletes its private data.
Preserve user data before deliberately replacing an installation. The dynamically
linked talloc library can be rebuilt/replaced through the same route.

## Integration changes and source delivery

- `talloc-cross-answers.txt` supplies bionic/aarch64 runtime answers while Waf still
  compiles header/symbol checks. Its supported unversioned-symbol configuration
  avoids upstream ELF-script references to unavailable Android `_end` symbols.
- `patches/shmem-private-tmp.patch` replaces a Termux compile-time temporary path
  with bounded `PROOT_TMP_DIR` lookup and stops spinning on storage errors.
- `patches/proot-string-header.patch` declares upstream string functions for modern Clang.
- `patches/proot-exitkill.patch` enables kernel tracee cleanup when PRoot dies.
- The build rewrites talloc SONAME/NEEDED text using same-length NUL padding to
  match Android's extracted `lib*.so` names, sets an explicit non-Termux loader
  default, and pins the version independently of repository HEAD.

The talloc library retains its LGPL-3.0-or-later grant. The combined PRoot binary
uses GPL-3.0-or-later pursuant to the later-version permission in its source grant;
source files retain their existing notices. Packaged `licenses/` assets include
the PRoot GPL text, talloc LGPL text, libandroid-shmem notice and exact NDK/LLVM
runtime notices. The main application's notice also contains GPL version 3.

Release source delivery must include this directory's complete archives, patches,
source lock and cross answers; `scripts/build-local-arch-runtime.py`;
`app/src/main/cpp`; and the relevant app/Gradle sources. Never distribute a changed
binary without its matching updated sources and build inputs. A source link or
binary manifest alone does not fulfill corresponding-source delivery.

### 2026-09-08 PostgreSQL compatibility change

`--sysvipc` enables the already-bundled PRoot System V IPC emulation. The original
`shmem-stale-local-key.patch` prevents libandroid-shmem from connecting to its own
listener while holding its mutex when a removed segment leaves a stale key link.
Without this fix PostgreSQL initdb can hang during repeated shared-memory checks.
Dependency versions, source archives and licences are unchanged. This patch is
part of corresponding source for the shipped runtime (`.../shmem-2`). Validate
with the opt-in real USB `ArchSysVIpcDeviceTest` and actual PostgreSQL startup;
plain PTY or Node tests do not prove shared-memory compatibility.
