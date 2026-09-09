# Local Arch Linux

Local Arch Linux runs Arch Linux ARM aarch64 through the bundled Android PRoot
runtime. It requires a 64-bit ARM Android device; Android root is not required.

## Open a shell

Open the terminal, choose **New session → Local Arch Linux**, then **Install Arch
Linux**. The first installation downloads the pinned 168 MiB image, verifies its
SHA-256, extracts it into private storage, initializes package signatures, and
installs signed starter packages with a full system upgrade, plus the stable
official Codex npm package. Keep at least 4.3 GiB
free, plus space for projects. The dialog closes when installation starts. A
nonmodal progress strip remains visible while you navigate Settings or other
screens. Choose **Open local shell** when it says **Arch is ready**; completion
does not unexpectedly change your screen. The foreground service holds a partial
wake lock during setup, including when you open another app or the screen sleeps.
Android force-stop or OS process termination can still end running processes.
If interrupted, choose Local Arch Linux again and retry. An incomplete generation
is never marked installed. Reinstallation retains the previous environment until
the replacement has validated successfully.

Fresh installations include Git, GitHub CLI (`gh`), Codex, Bash completion, zoxide (`z`), ripgrep (`rg`), fd,
fzf, bat, eza, jq, Nano, less, ZIP tools, curl, OpenSSH, rsync, base-devel, Node 24
LTS and npm. Existing installations can choose **Settings → Local Arch Linux →
Install starter tools** after closing local shells. This preserves projects,
existing Node and Codex installations, credentials, and shell customizations.
The version-2 upgrade is offered even if the earlier starter tools are installed. It includes a full package
upgrade; it does not reinstall the root filesystem. AUR helpers are separate:
`makepkg` refuses root, and AUR recipes need ARM compatibility review.

For later package management, use a full upgrade:

```sh
pacman -Syu
git --version
zoxide --version
node --version
npm --version
mkdir -p ~/projects
cd ~/projects
```

Arch is rolling release software: perform a full upgrade before adding packages,
and read pacman's prompts. The app does not silently upgrade installed packages.
Package operations use real pacman with package signature verification enabled.
Run one pacman transaction at a time across all tabs.

Open another **Local Arch Linux** session for another shell in the same filesystem.
Files and installed packages survive closing tabs and restarting the app. Shell
processes do not survive Android killing the app; reopen a shell to resume work.
Terminal input, Ctrl+C/Ctrl+D and resize use a real PTY and the existing renderer.

## Storage and reset

**Settings → Local Arch Linux** shows installation status, rootfs image version
and storage usage. The image version identifies the bootstrap image; it does not
track the subsequent rolling package versions. Use **Refresh storage usage** after
large package changes.

Close all local shells before reset or reinstall. Both require typing **DELETE
ARCH**. Reset removes the entire guest, including projects, installed packages,
keys and configuration. Reinstall replaces it with the pinned image. Uninstalling
Terminal Spike or clearing Android app data also deletes the guest. No shared
storage permission or automatic backup/sync is added.

## Limits

PRoot emulates guest root privileges; it does not give Android root access or a
separate Linux kernel. Linux services that require systemd as PID 1, kernel
modules, containers, mounts or privileged networking are outside this MVP.
Package hooks for booted system services may skip their actions. PRoot is not a
security boundary for untrusted guest programs. Keep work in private guest storage;
Android shared-storage integration is not included.

The runtime and guest are ARM64-only. Development-package evidence and the
separate Prisma investigation are recorded in
[the compatibility ledger](local-arch-development-compatibility.md). A successful
npm install alone is not evidence that every native package works.

Runtime source and distribution obligations are in
[the runtime ADR](ADR-LOCAL-ARCH-RUNTIME.md), [dependencies](DEPENDENCIES.md) and
[third-party notices](../THIRD_PARTY_NOTICES.md). Local device validation does not
establish eligibility for Google Play distribution.

## Cable Flow and Codex on a phone

The implementation and exact USB evidence are maintained in
`/home/jiyu/Documents/Jiyu-obsidian/plannow/2026-09-08-phone-arch-starter-tools-and-cable-flow-verification.md`.
The opt-in test driver is `scripts/run-local-arch-cable-flow-device-test.py`; its
guest script is `app/src/androidTest/assets/local-arch-cable-flow.sh`. These require
an isolated `.archverify` app and verify the authorized USB SM-S911B before device
actions. They never install or test the Wi-Fi foldable. The source archive excludes
host env, credentials and licenses, and databases exist only inside the phone.
The probe uses Cable Flow's existing CI license fixture, not a production license.
Codex executable startup does not establish successful authentication or model use.

The default installer includes GitHub CLI and Codex as of starter version 2.
Follow [the foldable setup guide](../app/src/main/assets/local-arch/cable-flow-phone.md)
for browser logins, cloning and the Codex handoff. The same guide is installed at
`/usr/local/share/terminal-spike/cable-flow-phone.md` inside Arch so the phone agent
can read the verified PostgreSQL recipe without access to the workstation vault.

### GitHub and Codex sign-in from Settings

Open **Settings → Local Arch Linux → Sign in to GitHub / Codex** after installation.
The app starts the installed CLI, displays its one-time device code, and offers
**Copy code and open browser**. Approve in the browser and return to Settings for
the completion result. No terminal commands or token pasting are required.
GitHub uses HTTPS and configures Git's credential helper after successful login.
Codex requires device-code login enabled in ChatGPT security settings or by the
workspace administrator: https://developers.openai.com/codex/auth/.
GitHub's browser flow is documented at https://cli.github.com/manual/gh_auth_login.

Login runs under the existing foreground service, survives activity recreation,
can be cancelled, and times out after 16 minutes. Android receives only the
short-lived code and completion state; raw CLI output is not logged or displayed.
The CLIs retain their own credentials in the private Arch filesystem. Resetting
Arch deletes those credentials too. Completion describes this login attempt,
not a continuously refreshed account-status check. Existing environments can
use **Install starter tools** without resetting files; new installs automatically
include GitHub CLI and the pinned stable Codex package.

Settings login verification (2026-09-09): the opt-in
`ArchLoginDeviceTest#settingsRequestRealCodesAndCancelAcrossRecreation` passed
on USB `RZCW81JZ9CP` / `SM-S911B` using the isolated `.archverify` app
(1 test, 20.532 seconds). Both real providers issued codes; recreation retained
the code, cancellation cleared it, and buttons became available again.
The test never opens the approval page or completes account authorization.
Initial device failures exposed UI-thread process startup and Codex's longer
code format; the final implementation dispatches startup to IO and waits for
a complete, whitespace-terminated code. Unit coverage includes one-byte
transport chunks and rejects truncated Codex codes.
