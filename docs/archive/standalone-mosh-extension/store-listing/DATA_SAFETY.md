# Data safety declaration evidence

Complete the Play Console form for this package independently of the main application.

## Proposed answers for the current source

- Data collected by the developer: **No**.
- Data shared with third parties: **No**.
- Data encrypted in transit: **Yes** for Mosh transport traffic.
- User deletion request: **Not applicable** because the extension has no account or retained user
  data.
- Privacy policy: use the public HTML view of the repository's `PRIVACY.md`.

## Transient processing explanation

The app transiently processes a user-selected server IP address, UDP port, ephemeral Mosh key, and
terminal traffic solely on the user's device to provide the requested connection. Data travels
directly to the user's server; it is not sent to the developer. Confirm the current Play definition
of ephemeral processing immediately before submission.

Re-audit this file whenever code, permissions, SDKs, or network behavior changes.
