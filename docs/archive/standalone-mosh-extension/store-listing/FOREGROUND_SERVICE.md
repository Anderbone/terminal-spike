# Foreground-service declaration

## Type

`specialUse` — active user-started Mosh terminal transport.

## Functionality

When a user starts a Mosh connection from Terminal Spike, the extension maintains the encrypted UDP
transport and terminal byte pipes. An ongoing low-priority notification clearly states that a Mosh
session is active and opens the extension information screen.

## Why immediate and continuous execution is required

Terminal input and output are interactive. Deferral prevents the requested connection from opening.
Interruption disconnects or suspends the live terminal transport and can cause the user to lose
access to the current remote shell until reconnection.

## User initiation and stopping

The service starts only as part of a visible connection action in Terminal Spike. It enters the
foreground when the first session starts and removes the notification when the final session ends.
The user can disconnect from Terminal Spike or stop the apps through Android system controls.

## Demonstration video

[`video/foreground-service-demo.mp4`](video/foreground-service-demo.mp4) is a 20-second recording
made on an Android 16 phone with release-signed Terminal Spike and extension artifacts. It shows:

1. A connected, interactive Mosh terminal after an explicit user-started connection.
2. The Android notification shade and the ongoing “Remote terminal active” notification while the
   extension's `specialUse` foreground service maintains the Mosh transport.
3. A return to the active terminal session.

Terminal output was sanitized before recording. Other device notifications are covered by a labeled
privacy mask. The video contains no real hostnames, IP addresses, usernames, credentials, or
terminal secrets.
