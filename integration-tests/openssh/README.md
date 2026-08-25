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

Android emulators reach a loopback-bound host through `10.0.2.2:22222`. For a physical phone on
a trusted LAN, explicitly expose the container and substitute the development machine's LAN IP:

```bash
TERMINAL_SPIKE_SSH_BIND_ADDRESS=0.0.0.0 ./integration-tests/openssh/smoke.sh
```

Do not expose this test account on an untrusted network. The server disables root login, agent,
TCP and X11 forwarding, and tunnelling.

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
