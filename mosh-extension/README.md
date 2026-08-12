# Terminal Spike Mosh-compatible extension

This module builds a genuinely separate Android application with application ID
`com.yanjiyu.terminalspike.mosh`. It contains the GPL Mosh transport and is never linked into the
main `com.yanjiyu.terminalspike` APK. The main app performs SSH authentication and trusted
`mosh-server` bootstrap; this extension receives only a numeric UDP endpoint, a one-shot ephemeral
Mosh key, terminal dimensions, reviewed options, and terminal byte pipes.

The implementation uses pinned official Mosh 1.4.0 source. It does not contain source copied from
Termius, Termux, ConnectBot, LobiShell, or another terminal application. Four private Android worker
services isolate Mosh's process-global timestamp and terminal state, allowing up to four concurrent
sessions without claiming that upstream's CLI is thread-safe.

The upstream Mosh transport can retain a session while its network path changes. API v1 includes a
bounded `updateNetworkHint` control for connectivity generation and UI signaling, but the current
main application does not yet drive that hook from Android connectivity callbacks. This build does
not claim proactive Android network-change signaling or a verified roaming transition until that
integration and its device test are complete.

This application is free software under GPL-3.0-or-later and comes with no warranty. See `LICENSE`,
`THIRD_PARTY_NOTICES.md`, `DEPENDENCIES.md`, and `BUILDING.md`. It is a Mosh-compatible extension,
is not affiliated with the Mosh project, and is approved only for local/debug evaluation until the
public-distribution gate in `../docs/ADR-003-MOSH-EXTENSION-BOUNDARY.md` is cleared.

`scripts/create-source-bundle.sh` produces the deterministic, standalone Corresponding Source
archive for a built extension without including the private main application.
