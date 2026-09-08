# ADR: Local Arch Linux native runtime

- Date: 2026-09-07
- Status: Accepted architecture; implemented and validated on local devices
- Scope: Android arm64-v8a, Arch Linux ARM aarch64 only

The requested local development environment needs a real PTY and Linux userland
without Android root. Implement LocalSession through the existing Connection byte
interface, with an original JNI PTY bridge. Execute PRoot as a separate program;
do not link PRoot or another terminal application's implementation into that bridge.
Multiple local processes share one persistent app-private Arch environment.

Approve the following new native dependencies for this feature: Termux PRoot
5.1.107.81 (including its matching guest loader), talloc 2.4.3, and
libandroid-shmem 0.7. PRoot's reviewed source headers grant GPL-2.0-or-later;
talloc's library is LGPL-3.0-or-later, and libandroid-shmem is BSD-3-Clause.
Preserve their actual file-level grants, notices and corresponding source. This
decision satisfies the project's explicit GPL architecture/licensing requirement.
It does not change the independent licensing of any existing component.

Native executable files must be distributed in APK native-library packaging and
executed from Android's extracted nativeLibraryDir. No Android runtime executable
is downloaded by the app or copied into writable data storage for execution.
The separately downloaded, pinned Arch rootfs is validated and staged before atomic
activation. Guest programs are launched through the bundled PRoot loader.

Build inputs, exact source archive digests, original integration patches and build
instructions must remain available with each distributed runtime. Generated binary
outputs stay out of Git. Do not release binaries for which the corresponding source
and replacement/rebuild instructions are incomplete. No promise of a written source
offer or third-party fulfilment is made by this document.

PRoot is not an Android security boundary: guest processes share the app UID and
kernel constraints. Preserve target SDK and Android security settings. Technical
execution success does not establish eligibility for Google Play's executable-code
download exception. A Play release assessment remains separate; this task neither
publishes nor authorizes a store upload.

See [the complete research and acceptance contract](local-arch-linux-analysis.md).


## 2026-09-08: user-requested Arch starter tools

Architecture/licensing decision: install the following stable packages from the
signed Arch Linux ARM core/extra repositories into the user's guest filesystem,
using a full pacman upgrade. No additional native binary is linked into or bundled
with the Android application. GPL guest tools are explicitly approved in this
architecture; preserve their installed notices and corresponding source obligations
if ever distributing a populated filesystem. The application distributes only its
original setup script; users fetch packages from their official repositories.

| Direct guest package | Purpose | Upstream source | License / notice obligations |
| --- | --- | --- | --- |
| git | Clone and manage projects | https://git-scm.com/ | GPL-2.0-only; retain notices/source on redistribution |
| bash-completion | Shell completion | https://github.com/scop/bash-completion | GPL-2.0-or-later; retain notices/source |
| zoxide | Directory navigation | https://github.com/ajeetdsouza/zoxide | MIT; retain copyright/license |
| ripgrep | Search project text | https://github.com/BurntSushi/ripgrep | MIT/Unlicense; retain chosen license |
| fd | Find files | https://github.com/sharkdp/fd | MIT/Apache-2.0; retain license/notices |
| fzf | Interactive selection | https://github.com/junegunn/fzf | MIT; retain copyright/license |
| bat | Read source files | https://github.com/sharkdp/bat | MIT/Apache-2.0; retain license/notices |
| eza | Directory listings | https://github.com/eza-community/eza | MIT; retain copyright/license |
| jq | Inspect JSON | https://jqlang.org/ | MIT; retain copyright/license |
| nano | Terminal editor | https://nano-editor.org/ | GPL-3.0-or-later; retain notices/source |
| less | Pager | https://www.greenwoodsoftware.com/less/ | GPL-3.0-or-later or Less license; preserve chosen license |
| unzip | Extract ZIP files | https://infozip.sourceforge.net/ | Info-ZIP; retain copyright/license |
| zip | Create ZIP files | https://infozip.sourceforge.net/ | Info-ZIP; retain copyright/license |
| curl | Download files / HTTP tools | https://curl.se/ | curl license; retain copyright/license |
| openssh | SSH client and Git transport | https://www.openssh.com/ | BSD and other file-level permissive licenses; retain notices |
| rsync | Copy project files | https://rsync.samba.org/ | GPL-3.0-or-later; retain notices/source |
| base-devel | Build guest software | https://archlinuxarm.org/packages/aarch64/base-devel | Meta-package; dependencies retain their licenses, including GCC/binutils/make GPL and runtime exceptions |
| nodejs-lts-krypton | Node 24 LTS for Cable Flow | https://nodejs.org/ | MIT plus bundled component notices |
| npm | Install JavaScript tools | https://github.com/npm/cli | Artistic-2.0 and bundled notices |

Versions are resolved by signed stable repository metadata and recorded by pacman
in the guest; no testing/staging or prerelease channel is enabled. Arch's full
upgrade avoids partial upgrades. Omarchy's CLI package list is a selection reference
(https://github.com/omacom/omarchy/blob/master/install/omarchy-base.packages), not
copied application code or a desktop installation. AUR helpers are not a bootstrap
requirement: makepkg requires non-root execution and AUR recipes need ARM review.
