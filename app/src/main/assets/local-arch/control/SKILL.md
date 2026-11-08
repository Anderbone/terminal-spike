---
name: terminal-spike-settings
description: Configure the Terminal Spike app on this phone from natural-language requests. Use for colours, themes, fonts, text size, spacing, saved command snippets, shortcut buttons, app keyboard keys and behavior, saved SSH/Mosh connections, or their port forwarding rules. The user does not need to name this skill or its command-line tool.
---

<!-- Managed by Terminal Spike. Updated when a Local Arch shell opens. -->

Use this skill automatically when the user wants to change this phone's terminal
app settings, in any language. Translate the request into the appropriate local
operation; do not ask the user to learn a tool name, write commands, or supply
internal IDs. Keep replies brief, in the user's language, describing the applied
change and any action still needed. Requests about a project's code, the Android
system keyboard, or a remote server's own configuration are outside this skill.

Run `spikectl --help`, `spikectl status`, and `spikectl capabilities` to discover
the current local API. The app must be running, with agent access enabled under
Settings > Local Arch Linux. If access is disabled, ask the user to enable it;
do not work around the grant or App lock by editing app files.

Read the relevant records before editing. Use persistent UUIDs and the latest
`revision` for updates/removals. A conflict requires a fresh read and a decision
based on the user's requested change. Target the intended keyboard profile;
host-specific keyboardProfileId overrides the default. Preserve other actions
when reordering or adding a shortcut. `--actions` replaces the whole action list.
For an unqualified app keyboard request, use the default profile and say that
the default keyboard was changed. If the user names a connection, resolve its
keyboard override first. Resolve connection names from saved records; ask a
short question using display names only if the target remains ambiguous.

For appearance requests, discover available choices with `themes list`, `fonts
list`, and `terminal list`. App light/dark/system mode, dynamic colours, and
accent presets use `appearance get/update`; terminal palettes, font families,
size, spacing, and cursors use `terminal get/update`. For an unqualified terminal
request use the default terminal profile. A named connection's terminalProfileId
overrides that default. Preserve other typography, terminal behavior, and profiles.
When selecting a specific app accent, disable dynamic colours unless the user
explicitly wants system dynamic colours; otherwise the accent may not be visible.

Resolve palette and font names from the available records instead of guessing
IDs. Built-in palettes are read-only. For a background/text/cursor colour change
on one profile, create a custom theme with the current theme as `base`, override
only the requested colours, then select its returned ID on that profile. This
keeps ANSI colours and other profiles intact. Edit an existing custom theme only
when the user wants to change that shared named theme. Colour values are opaque
`#RRGGBB`; `ansi16` requires exactly sixteen values. Font choices include bundled
and already-imported fonts. If a requested font is unavailable, explain how to
import it in the app rather than changing files or downloading fonts silently.

For connection requests use connections list/get/add/update. Creation requires name,
hostname, and username; SSH and port 22 are defaults. Use credentials list to
resolve existing authentication by display name and kind; only metadata is exposed.
Omit credentialId on creation for a password prompt at connection time. On update,
omitting it preserves authentication; an empty string selects a new password prompt.
Never request passwords or private keys for this tool. Credentials must be created
or imported through the app. Empty terminalProfileId/keyboardProfileId inherits the
default; empty group/tag clears it. Preserve unspecified fields. Switching Mosh to
SSH clears Mosh-only overrides. Connection edits take effect on next connection;
the tool does not open or reconnect sessions.

For multiline commands, use a JSON file or stdin with `--json-file -` instead of
building shell source from user text. Snippet creation only saves text and never
executes it. Preserve confirmation settings. Port forwarding changes only saved
rules: report that the next connection applies them. Never claim an existing
tunnel is running based on a successful configuration write.

The tool exposes no passwords, private keys, credential exports, terminal input,
or automatic reconnect. It supports loopback listeners only. Read the operation
result back to verify the requested saved change, and report any limits or errors.

Input bar: `appearance.get` reports `inputButtonsOnRight` and `voiceInputEnabled`. Use `appearance.update` with the latest revision and `--input-buttons-on-right true|false` / `--voice-input-enabled true|false` to adjust the same saved preferences as Android Settings.
