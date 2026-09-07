# Privacy policy

Effective date: 21 August 2026

Terminal Spike Mosh Extension is a separately installed transport component used only by the
Terminal Spike Android application.

## Data handled

When the user starts a Mosh connection in Terminal Spike, the extension temporarily processes:

- a numeric server IP address and UDP port;
- a one-shot, ephemeral Mosh session key;
- terminal dimensions and reviewed connection options; and
- terminal input and output bytes for the active session.

This processing is required to provide the user-requested encrypted Mosh connection. Network data
is sent directly between the device and the server selected by the user. The project operator does
not receive it.

## Storage and sharing

The extension does not store hosts, credentials, session keys, or terminal transcripts. It does
not collect, sell, or share personal data with the developer or third parties. It contains no
analytics, advertising, tracking, account, cloud, or crash-reporting SDK.

The app disables Android backup. The active Mosh key and transport state are process-memory data
and end with the session or process.

## Permissions

- **Internet:** connects to the user-selected Mosh server.
- **Foreground service:** keeps an active, user-started terminal transport visible through an
  ongoing Android notification.

## Children

The extension is a technical companion for remote-terminal users and is not directed to children.

## Changes and contact

Material changes will be recorded in this repository before a corresponding app release. For
privacy questions, open a repository issue that contains no credentials, hostnames, IP addresses,
session keys, or terminal content. Report security vulnerabilities privately as described in
[SECURITY.md](SECURITY.md).
