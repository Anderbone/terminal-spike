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

Fresh installations include Git, GitHub CLI (`gh`), Codex, Claude Code (`claude`), OpenCode (`opencode`), Pi (`pi`), Bash completion, zoxide (`z`), ripgrep (`rg`), fd,
fzf, bat, eza, jq, Nano, less, ZIP tools, curl, OpenSSH, rsync, base-devel, Node 24
LTS and npm. Existing installations can choose **Settings → Local Arch Linux →
Install starter tools** after closing local shells. This preserves projects,
existing Node and coding CLI installations, credentials, and shell customizations.
The version-3 upgrade is offered even if the earlier starter tools are installed. It includes a full package
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

### Android process start times

When Android denies access to `/proc/stat`, new local shells bind a small
compatibility file supplying `btime`, the boot epoch in seconds. It is
derived from Android's wall clock and sleep-inclusive `SystemClock.elapsedRealtime()`,
then retained for the same device boot so separate shells agree when checking
a running process. A kernel boot UUID, or Android's boot count when that UUID is
restricted, selects a new file after reboot. Files are published atomically.
Readable kernel statistics are used directly, and per-process `/proc` entries
remain real. libproc2 requires an aggregate CPU header before it can read `btime`;
this header uses zero placeholders for unavailable counters, with an explanatory
comment in the file. These are not real CPU measurements. Other unavailable
system counters and per-CPU statistics are omitted.

Local Arch also uses stable, private aliases for Unix socket paths that exceed
the kernel path limit after PRoot translation. Separate local shells can connect
to the same Codex background server, and closing a client does not unlink the
server's socket. Full canonical paths distinguish guests and guard against short
name collisions. The original PRoot patch is included in corresponding source.

This lets Arch's `ps -o lstart=` and Codex's PID-managed background server read
process start times. Existing Arch installations receive it when opening a new
local session after the app update; no package upgrade, reset or `--no-daemon`
is required. This does not add systemd or privileged services.

The opt-in `ArchCodexDaemonDeviceTest` uses a separate pinned Arch/Codex `0.166.0`
fixture on the authorized S10e. It preserves the unmodified `ps` failure, starts
the real daemon through the production bootstrap, verifies it from a second
shell and stops it without signing in or calling a model.
It also verifies recovery after an owning shell exits without stopping the daemon.
Run `python3 scripts/run-local-arch-codex-daemon-test.py --serial <current-S10e-serial>`;
after the shared verification gate passes, add `--no-build` to reuse its APKs.
The runner verifies the model and hardware serial before installs and testing,
checks both downloaded fixtures, and removes its separate guest afterward.
If the phone retains a legacy debug certificate, use the recovered matching key
to sign copies of both verified APKs and pass them with `--app-apk` and `--test-apk`;
retain the canonical debug keystore and never uninstall to resolve a mismatch.
See [Android's clock contract](https://developer.android.com/reference/android/os/SystemClock)
and [the Linux `btime` format](https://www.man7.org/linux/man-pages/man5/proc_stat.5.html).
The required CPU header is checked by [libproc2's parser](https://gitlab.com/procps-ng/procps/-/blob/v4.0.4/library/stat.c).

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

### GitHub, Codex and Claude Code sign-in from Settings

Open **Settings → Local Arch Linux → Sign in to GitHub / Codex / Claude Code**
after installation. GitHub and Codex copy the one-time device code and open the
phone browser automatically. **Copy code and open browser** retries the launch;
**Choose another browser** opens Android's chooser. Enable **Ask which browser to
use for Local Linux links** to use the chooser for automatic launches too.
Approve in the browser, then press Back to see the CLI's completion result.
GitHub uses HTTPS and configures Git's credential helper after successful login.
Codex requires device-code login enabled in ChatGPT security settings or by the
workspace administrator: https://developers.openai.com/codex/auth/.
GitHub's browser flow is documented at https://cli.github.com/manual/gh_auth_login.
Claude Code uses `claude auth login`, with the subscription sign-in flow rather
than Console/API billing. Its loopback callback can complete on the same phone.
If the browser instead supplies an authorization code, paste it into the one-use
field and submit. See https://code.claude.com/docs/en/authentication.

### Browser links from other local command-line tools

New local shells receive `BROWSER=/usr/local/bin/spike-open`. The managed
`xdg-open` launcher also opens HTTP(S) links in Android; an existing user-owned
`/usr/local/bin/xdg-open` is preserved. `spike-open --choose URL` explicitly asks
which app to use. Helpers refresh whenever a new local shell/login starts;
existing running shells need `export BROWSER=/usr/local/bin/spike-open` or a new tab.
Node from the starter tools is required. Linux tools that ignore both `BROWSER`
and `xdg-open` still need their own browser/link option.

The bridge uses a separate same-Android-UID abstract Unix socket, independent of
the agent settings grant, with one bounded pending request. It opens only HTTP(S)
URLs while the app is unlocked and resumed, acknowledges actual Android launch,
and rejects background/locked attempts after a short timeout. It never stores
or logs authorization URLs and never reads the browser's cookies or credentials.
The browser calls the CLI's existing localhost listener directly: there is no
OAuth URL rewriting, token interception or new HTTP listener in the app.
A successful browser launch is not proof of account authorization; only the CLI
exit status completes a Settings login. Automatic focus back to Android is
provider-specific; press Back for tools whose callback leaves a browser page.
Remote SSH/Mosh hosts are outside this local bridge and may need port forwarding.
The opt-in `LocalBrowserDeviceTest` checks the production Android launch and a
fixture redirect to a phone loopback listener on an authorized S10e/S23. It does
not authorize real accounts or stand in for provider acceptance.

Verification on 2026-11-08: the shared local gate passed; the final affected app
checks passed 1,300 JVM tests, debug/release lint, APK builds and the release bundle.
Six focused dialog/editor checks passed on the authorized wireless SM-G9708.
The browser fixture successfully launched Samsung Internet, but its first-use
terms screen prevented navigation to the loopback fixture, so that callback gate
remains unverified until the user completes browser setup. Do not count this as
an OAuth success or repeatedly retry it without changing that prerequisite.
The final standard debug APK was installed without incremental installation on
SM-F976B and MainActivity was confirmed resumed; no tests ran on that phone.

Login runs under the existing foreground service, survives activity recreation,
can be cancelled, and times out after 16 minutes. Android receives only the
short-lived code and completion state; raw CLI output is not logged or displayed.
The CLIs retain their own credentials in the private Arch filesystem. Resetting
Arch deletes those credentials too. Completion describes this login attempt,
not a continuously refreshed account-status check. Existing environments can
use **Install starter tools** without resetting files; new installs automatically
include GitHub CLI, Codex, Claude Code, OpenCode and Pi at pinned stable versions.

Settings login verification (2026-09-09): the opt-in
`ArchLoginDeviceTest#settingsRequestRealCodesAndCancelAcrossRecreation` passed
on USB `RZCW81JZ9CP` / `SM-S911B` using the isolated `.archverify` app
(1 test, 20.532 seconds). Both real providers issued codes; recreation retained
the code, cancellation cleared it, and buttons became available again.
That original test did not open the approval page or complete account authorization;
the current flow automatically opens the browser and returns before recreation.
Initial device failures exposed UI-thread process startup and Codex's longer
code format; the final implementation dispatches startup to IO and waits for
a complete, whitespace-terminated code. Unit coverage includes one-byte
transport chunks and rejects truncated Codex codes.

## Starter tools version 4: install-time latest stable releases

The Android APK carries the installation policy, not downloaded coding CLI or AUR
binaries. Each **Install Arch Linux** / **Install / update tools** action resolves
current stable Codex, Claude Code, OpenCode, Pi, yay and herdr releases. A future
stable release can therefore be installed by the same APK. Prerelease npm tags and
GitHub draft/prerelease releases are rejected; official GitHub ARM64 asset hashes
are checked before execution. An unavailable version or checksum mismatch leaves
setup incomplete with a visible error rather than silently installing an old copy.
System packages are updated through signed Arch Linux ARM repositories with a full
`pacman -Syu`; Node follows the existing chosen package/LTS track.

This replaces version 3's fixed, missing-only installs. Explicit updates preserve
projects and login files. Updating or opening the APK alone does not run upgrades.
Existing environments expose the tools upgrade through the version-4 marker;
new environments receive the same defaults automatically. The checksum-pinned
bootstrap rootfs remains unchanged and is upgraded through pacman during setup.

New interactive app tabs use fish after it is installed, with bash retained as a
fallback for older guests. Fish's built-in highlighting, suggestions and completion
need no plugin. A separate fish profile adds zoxide without replacing user config.
Explicit bash commands used by bootstrap, login and compatibility fixtures stay bash.

Yay runs through a wrapper that delegates root-invoked AUR operations to a dedicated
`aur` guest account. Its passwordless sudo is guest-only under PRoot and never grants
Android root. Users still initiate AUR builds themselves. Herdr is installed but
not launched automatically. Upstream yay/herdr release licences are retained under
`/usr/local/share/licenses/`; see DEPENDENCIES.md for the architecture/licensing decision.

The download percentage/speed describes the rootfs archive. During later package
and CLI installation, the app now shows bounded live command output. On successful
setup it saves and displays actual installed tool versions, so installed state can
be checked independently of the Android app version. Existing data requires an
explicit tools update to obtain these new defaults; resetting Arch is unnecessary.

Existing-install repair: npm's postinstall hardlinks can point at a missing PRoot
`.l2s` file after upgrades, even when npm reports success. After all npm installs,
the updater verifies Claude/OpenCode executables and their versions. Only an already verified app-managed regular executable is reused; temporary
hardlink chains can work during setup and fail after PRoot exits. Native
commands are restored as regular files from the exact matching official npm ARM64
package archive, preserving user credentials and shell config. A regression fixture
models successful npm installs that leave both native commands as dangling links.

Codex's bubblewrap prerequisite is installed from signed Arch repositories. This
addresses the missing-program startup warning without suppressing diagnostics or
changing permissions. Android/PRoot kernel sandbox restrictions remain separate.
Restart an existing Codex/background server after updating; old sessions can retain
warnings from their original startup. The old-phone gate initializes the real local
Codex app-server and starts an ephemeral thread without a model turn, checking that
its startup does not report a bubblewrap warning.

Starter tools version 5 adds the native-launch repair and bubblewrap prerequisite;
existing version-4 installations therefore offer an explicit upgrade as well.

Version-5 verification (2026-11-08, debug 0.0.19 / code 31): an existing guest on
SM-G9708 reproduced missing native launcher state. The full production upgrade
then passed in 488.660 seconds, including regular Claude/OpenCode files executing
from a later PRoot process, the existing AUR query/build/sudo-install assertions,
and real Codex app-server initialization plus ephemeral thread creation with no
bubblewrap startup warning and no model turn. Host fixtures cover both legacy
array and npm-12 package-keyed `pack --json` output. Unit tests, lint and builds
passed; the final APK was installed normally on SM-F976B and MainActivity was
confirmed foregrounded, with no tests on that phone. Exact APK hashes and logs
are in ignored `build/verification/native-repair-evidence.json`.
