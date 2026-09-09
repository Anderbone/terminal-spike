# Local OpenSSH integration server

This is a development-only OpenSSH server for Terminal Spike connection, authentication,
known-host, and SFTP file-operation tests. Nothing in this directory is included in an Android
source set or APK. The server uses OpenSSH's built-in `internal-sftp` subsystem.

The container is pinned to Alpine 3.24.1 by digest and OpenSSH 10.3p1-r0. It listens only on
`127.0.0.1:22222` by default. The generated Ed25519 client key and ECDSA P-256 host key live
under ignored `.state/`; no private key is tracked.

## Start and verify key authentication

```bash
./integration-tests/openssh/smoke.sh
```

The deterministic test account is:

- user: `terminal`
- password: `terminal-spike-test-only`
- private key: `integration-tests/openssh/.state/client/id_ed25519`

The password is deliberately public and valid only inside this disposable local test container.
Do not reuse it anywhere. Import the generated client key through Android's system picker when
testing key authentication.

Android emulators reach a loopback-bound host through `10.0.2.2:22222`.

The stable API 37 local-network gate deliberately uses that RFC1918 gateway. It proves the
platform blocks raw TCP and production SSH before protocol traffic while
`ACCESS_LOCAL_NETWORK` is absent, then completes SSH terminal and SFTP transfer traffic against
the identical live endpoint after the runtime grant:

Install stable SDK Command-line Tools 22.0 (`sdkmanager "cmdline-tools;22.0"`) first. The runners
prefer that version when installed. Older tools such as 12.0 misread API 37.0 metadata and create
an `android-0` AVD; the runner rejects that target before launching the emulator.

```bash
scripts/run-android-emulator-tests.sh --api 37 --suite lan-openssh \
  --output-dir build/api37-lan-openssh
```

Run `smoke.sh` first and always tear the fixture down afterward. The suite is fixed to API 37,
rejects skips or missing named methods, and refuses custom filters. It is LAN evidence, not a
substitute for public-Internet routing evidence.

For a physical phone on a trusted LAN, prefer the guarded acceptance runner from the repository
root:

```bash
scripts/run-real-mosh-device-tests.sh \
  --serial '<exact-SM-S911B-adb-serial>' \
  --host-address '<development-machine-trusted-LAN-ipv4>' \
  --trusted-lan
```

The runner resets the disposable container before testing so stale `mosh-server` processes cannot
exhaust ports 62000–62010. It binds TCP 22222 and that UDP range to the exact supplied address,
re-verifies the authorized old-phone serial/model before every install and instrumentation launch,
runs a fixed five-test password/private-key matrix, sanitizes durable output, and always tears the
container down. Set `JAVA_HOME` to the repository-compatible JDK first. Do not expose this test
account on an untrusted network. The server disables root login, agent, TCP and X11 forwarding,
and tunnelling.

For a manual trusted-LAN fixture only, bind to one exact development-machine address rather than a
wildcard:

```bash
TERMINAL_SPIKE_SSH_BIND_ADDRESS='<development-machine-trusted-LAN-ipv4>' \
TERMINAL_SPIKE_SSH_VERIFY_HOST='<development-machine-trusted-LAN-ipv4>' \
  ./integration-tests/openssh/smoke.sh
```

## First-contact and changed-key checks

1. Connect the app to the test endpoint. Verify the first-contact prompt shows the ECDSA
   algorithm and the fingerprint printed by `prepare.sh`.
2. Choose **Trust once** and prove a new connection prompts again.
3. Choose **Trust and save** and prove the next connection does not prompt.
4. Rotate only the disposable server host key:

   ```bash
   ./integration-tests/openssh/rotate-host-key.sh
   ./integration-tests/openssh/verify-host-mismatch.sh
   ```

5. Verify Terminal Spike blocks the connection and shows both the saved and new fingerprints.
   Replace/remove the known host only through the explicit Security flow, then reconnect.

`rotate-host-key.sh` keeps the previous key as `.previous`, so the test change is recoverable.

## Stop

```bash
docker compose --project-directory integration-tests/openssh \
  -f integration-tests/openssh/compose.yaml down
```

Remove `.state/` only when intentionally discarding the disposable test identities and saved
host-key fixture.

## Release-like clean install and update

The black-box release smoke accepts only a signed main-app APK and the named disposable emulator
`terminal-spike-release-test`. It verifies package/version/signature continuity, rejects physical or
differently named targets, resets the fixture, creates a host through the shipped Connections UI,
connects over real SSH with the extension absent, update-installs the same or newer signed APK,
restarts the app, proves the non-secret host survived and the password was not retained, and
connects over real SSH again. It always tears the fixture down:

```bash
JAVA_HOME='<compatible-jdk>' ANDROID_SDK_ROOT='<android-sdk>' \
  ./integration-tests/openssh/release-app-install-update-smoke.sh \
  emulator-5554 /absolute/path/to/signed-initial.apk [/absolute/path/to/signed-update.apk]
```

The script deliberately chooses **Open shell** at the tmux prompt because tmux has a separate
acceptance matrix. Supplying one APK proves same-certificate reinstall/data retention, not a
lower-version migration.

For the complete external process-death and reversible background-pressure gate, let the
repository runner own a wiped API 35 AVD and this fixture:

```bash
JAVA_HOME='<compatible-jdk>' ANDROID_SDK_ROOT='<android-sdk>' \
  scripts/run-app-lifecycle-e2e.py --api 35
```

It proves real terminal traffic before death, background/idle/standby/data-saver behavior with
exact state restoration, ownership and death of one exact app PID, clean service/notification
teardown, private host/recent metadata retention with no stored session-only password, a distinct
restart PID, password re-prompt, and real SSH afterward. Its optional `--physical-serial` mode
accepts only a freshly re-enumerated `SM-S911B` and deliberately skips battery-policy mutation.

The lifecycle and release-update emulators reach the host's loopback-only fixture through
`10.0.2.2`, just like the instrumented OpenSSH tests. SSH traffic stays independent of the ADB
connection used for UI input and inspection. Physical USB lifecycle tests still use `adb reverse`.
ADB can briefly disconnect or return truncated inspection output, including empty `exec-out`
output with exit zero. Read-only UI captures retry at most three times and require valid complete
XML. Lifecycle notification checks also require a completion marker before interpreting presence
or absence; incomplete reads never satisfy an assertion. App actions and assertions are not retried.

The release smoke also guards keyboard-triggered SSH window resizing. `ChannelSession.setPtySize`
writes to the network and must run on the SSH writer thread: the release main-thread network
policy can otherwise interrupt a packet and leave input stalled. Debug StrictMode logging can
hide this failure. A two-CPU local release reproduction failed before the writer-thread fix and
passed with the normal release policy afterward; keep the minified release acceptance gate.

For a CI-equivalent ephemeral-signed minified build plus same-version reinstall smoke:

```bash
JAVA_HOME='<compatible-jdk>' ANDROID_SDK_ROOT='<android-sdk>' \
  scripts/run-release-update-ci-smoke.py --api 35
```

That runner creates and removes a short-lived PKCS12 acceptance identity, verifies the APK and AAB
against it, owns the exact `terminal-spike-release-test` AVD, invokes the black-box smoke above,
deletes signed outputs/private logs, and retains only a sanitized status file. It is not production
signing and does not prove migration from an older version.

## Real Codex inside app-selected tmux

When this workstation's SSH service and authenticated Codex CLI are reachable from the authorized
old phone, run the direct-SSH actual-Codex reference, the strict app-selected-tmux
first-gesture/5,000-row/sub-row/fling/catch gate, and real GNU `less --mouse`, Vim, and htop
remote-input gates with:

```bash
JAVA_HOME='<compatible-jdk>' ANDROID_SDK_ROOT='<android-sdk>' \
  scripts/run-real-codex-tmux-device-test.sh \
  --serial '<exact-SM-S911B-adb-serial>' \
  --host-address '<development-machine-trusted-LAN-ipv4>' \
  --trusted-lan
```

The runner refuses every other device model, re-verifies the target before each install and the
test, authorizes one uniquely tagged generated SSH key for the current host account, runs exactly
the direct actual-Codex, tmux actual-Codex, and combined tmux less/Vim/htop method through Android Test
Orchestrator, sanitizes retained
output, and revokes
and shreds the temporary identity on every exit. Retained log evidence is generated through a
field whitelist and contains no terminal rows, prompts, host names, credentials, or raw logcat. It
never targets the Wi-Fi foldable.
