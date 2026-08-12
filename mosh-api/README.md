# Terminal Spike Mosh API

This Android library is the project-owned, Apache-2.0 IPC contract shared by Terminal Spike and
its separately installed Mosh-compatible extension. It contains AIDL, bounded parcelables, stable
constants, and no transport implementation, native code, upstream Mosh code, credentials, or
network behavior.

The Binder interfaces are a low-frequency control plane. Terminal input/output and the one-shot
22-character session key use `ParcelFileDescriptor` pipes. A recipient owns every descriptor it
receives and must close it promptly. Adding or changing a field or method requires a protocol/model
version decision and an update to `api/mosh-api-v1.txt`.

Copyright 2026 Terminal Spike contributors. Licensed under the Apache License, Version 2.0; see
`LICENSE`.
