# Security policy

Use [GitHub private vulnerability reporting](https://github.com/Anderbone/terminal-spike/security/advisories/new)
for suspected vulnerabilities. Do not include live credentials, Mosh session keys, private host
details or terminal transcripts in public issues.

Include the affected app version, Android version and ABI, reproduction steps using a disposable
server, expected behavior and observed behavior. This is a personal project; response and fix
times depend on availability. Use the latest source/release when assessing a suspected issue.

SSH host-key verification, protected credential storage and the Mosh IPC boundary are described
in [the architecture](docs/architecture.md) and [Mosh protocol](docs/mosh-extension-protocol.md).
