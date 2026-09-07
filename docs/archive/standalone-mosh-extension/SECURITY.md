# Security policy

## Supported versions

Security fixes are provided for the latest release. Older versions may be unsupported after a
replacement is available.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository. Do not open a public issue for a
suspected vulnerability and do not include live credentials, Mosh keys, private host details, or
terminal transcripts.

Include the affected version, Android version and ABI, reproduction steps using a disposable test
server, expected behavior, and observed behavior. Reports should be acknowledged within seven
days; remediation timing depends on severity and reproducibility.

## Security boundary

The extension accepts calls only through its explicit, signature-protected Binder service. The
main application performs SSH authentication and host-key verification. See
[`docs/mosh-extension-protocol.md`](docs/mosh-extension-protocol.md) for the complete boundary and
threat model.
