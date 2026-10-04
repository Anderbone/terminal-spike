# Port forwarding

Open **Settings → Port forwarding → Manage connections**, then edit a saved connection.
Under **Advanced**, add local or remote TCP forwarding rules. Each rule has a listen
address/port, destination host/port, and enable switch. Save the connection and connect.
Edits apply on the next connection; up to 16 rules can be saved per host.

For a computer website on port 5173, choose **Example: localhost:5173** in that
connection's forwarding editor. It prefills a local `127.0.0.1:5173 → 127.0.0.1:5173`
rule. Save the rule and connection, reconnect, then open `http://127.0.0.1:5173` on the
phone. Cancel leaves the connection unchanged; selecting the same example again edits
the existing identical rule instead of adding a duplicate.

- **Local:** a phone listener reaches the destination from the SSH server. For a server's
  web app on port 3000, use `127.0.0.1:8080 → 127.0.0.1:3000`, then open
  `http://127.0.0.1:8080` on the phone.
- **Remote:** a server listener reaches the destination from the phone. For a phone
  service on port 3000, use remote `127.0.0.1:8080 → 127.0.0.1:3000`.
- Loopback is the default. All-interface listeners (`0.0.0.0` / `::`) expose the listening
  port to other devices; remote listeners are also subject to OpenSSH `GatewayPorts`.

Rules use the session's existing host-key verification and credentials. A rule that
cannot bind or register fails connection setup with its rule number and closes all
listeners created exclusively for that attempt. A local listener can start even if its
destination is currently unavailable; destination connections are opened on demand.

Multiple authenticated windows for the exact same SSH host, port and username share
identical forwarding rules, including their direction, bind address and destination.
Closing the first window keeps its forwarding transport alive for the other windows;
the last user releases the listener and any otherwise unused transport. Different
accounts, servers or destinations are never silently substituted when a port is occupied.
Automatic SSH reconnect reapplies the rules, when authentication supports reconnect;
a disconnected transport is not reused for a new forwarding lease.

Mosh uses the retained SSH companion connection to forward TCP; terminal data still uses
Mosh/UDP. Forwarding is not transported by Mosh and does not inherit its roaming.
If the SSH companion is interrupted, reconnect the session to restore tunnels. These are
session-bound TCP rules, not a VPN, UDP forwarder, or SOCKS proxy.

Rules are included in backups. Database revision 4 adds an empty rules column to older
hosts without changing their connection behavior. No new dependencies are used.

## Verification

Run `scripts/verify-android.sh` for unit tests, lint and debug/release artifacts. The tests
cover validation, Room mapping, backup round trips and host-editor validation.
`SharedPortForwardsTest` covers simultaneous acquisition, both forwarding directions,
owner-first close, idempotent release, partial-failure rollback, endpoint/destination
isolation and replacement of a disconnected owner. The
instrumented gate checks migration from old databases, persisted rules after reopening,
and adding/editing/removing a rule through Compose.

With prebuilt debug and instrumentation APKs, run:

```sh
scripts/run-port-forward-device-tests.py \
  --serial '<current authorized USB SM_S911B serial>' \
  --host-address '<workstation trusted-LAN IPv4>' --trusted-lan
```

The runner refuses the foldable, non-USB targets, or an already-running disposable fixture.
It enables TCP forwarding only in its disposable OpenSSH fixture, verifies the model
before each device action, checks installed APK hashes, requires all seven tests without
skips, and tears down the fixture on exit. It proves real terminal input/output plus a
256 KiB request and reversed response through chained local and remote forwards, over
both SSH and native Mosh. A second fresh session reuses the same listener ports. Another
test occupies a port and proves that setup fails clearly and releases earlier listeners.
Evidence is written to ignored `build/port-forward-device-results/`.

### Device evidence — 2026-09-23

On the working tree based on `07705c8`, all seven instrumented tests passed without skips
on USB `SM-S911B` (`RZCW81JZ9CP`). Both SSH and native Mosh passed the terminal, bidirectional
256 KiB tunnel, disconnect, and port-reuse assertions. The occupied-port rollback, Compose
editor, and three database migration tests also passed.

Tested debug APK SHA-256:
`4073f0d6faaf5d2bc9351b5ef3196fa687a9be2f9e6638783e23ca0431f327e4`.

The same debug APK was installed and launched on the Wi-Fi `SM-F976B`;
`MainActivity` was confirmed resumed and focused. No tests ran on the foldable.

### 5173 example button — 2026-09-26

The shared-workspace gate encountered an internal lint `ArrayStoreException` while another
session was editing `ExtraKeysBar.kt`. An isolated copy passed the shared unit-test, lint,
and debug/release build gate. The USB `SM-S911B` editor test passed example prefill, cancel
without saving, and repeated selection without duplicate rules. The verified APK was
installed and launched on `SM-F976B`, with no tests on that device. Its SHA-256 was
`11e74451c6c16e118de8d2d23875fb331b4f6a415a77d9efe686d27cab1376f2`.
