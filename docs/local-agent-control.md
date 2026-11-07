# Local agent settings control

Open **Settings → Advanced options → Local Arch Linux** and enable **Allow local
agents to manage app settings**. Open a new Local Arch tab, then start a fresh
Codex session. Existing Codex authentication and projects are preserved. The app
installs `spikectl` and a managed `terminal-spike-settings` agent skill whenever a
local shell opens; no package upgrade or agent configuration change is required.

Ask the agent naturally: "Save git status as a shortcut", "Move Esc to the first
key", or "Forward my server's port 5173 to this phone". The default skill is
written in English, accepts requests in any language, and handles the underlying
commands without requiring the user to mention `spikectl` or invoke a skill.
`spikectl --help`, `status`, and `capabilities`
describe the installed protocol. Other agents that can run shell commands can
use the same CLI. This version supports the app's own Local Arch environment.

Access defaults to off and expires when the application process exits. Turning
the switch off denies subsequent reads and writes. App lock blocks all settings
reads and writes even when this switch is on. Status and capability discovery do
not disclose saved records and remain available without a grant. The grant is
shared by local programs in this app's Linux environment, not a particular model
or agent login. Local Arch runs under the app UID; PRoot and this switch are not
a security boundary against malicious guest programs.

## Supported operations

- App appearance: get/update light, dark or system mode, system dynamic colours,
  and existing accent presets. Appearance revisions are safe-integer fingerprints
  of these three fields. The check occurs inside DataStore's atomic transform;
  unrelated preferences, account data, and security settings remain unchanged.
- Terminal appearance: list/get/update existing profiles' theme, available font,
  size, line height, letter spacing, bold/ligature/pinch settings, and cursor style
  or blink. Existing profile observers apply the selected profile to its terminals.
  Connection-specific terminalProfileId overrides the default profile.
- Colours: list/get built-in and custom palettes; add/update custom palettes.
  Built-ins are read-only. Add can clone a `base` and override just selected
  colours, preserving the rest of its 16-colour ANSI palette. Assign the new ID
  with terminal update. Colours are opaque `#RRGGBB`; ANSI arrays have 16 entries.
- Fonts: list bundled/system and already-imported fonts by ID and display name,
  without private file paths. Unknown IDs, arbitrary paths and out-of-range sizes
  are rejected. New font imports still use the app's existing document picker.
- Snippets: list/get/add/update/delete. Creation only saves text; it does not
  execute it. New snippets default to insert, without Enter, with multiline
  confirmation preserved. There is no terminal input or snippet execution API.
- Keyboard: list/get/update existing profiles, including ordered actions, row
  layout, modifier behavior, haptics, key repeat, input mode and tmux prefix.
  This changes the app keyboard, not the Android system IME. Hosts may select a
  profile instead of the default; read `keyboardProfileId` before choosing one.
- Connections: list non-secret endpoint metadata and forwarding rules. Passwords,
  private keys, credential references and startup commands are not returned.
- Forwarding: add/remove saved local or remote TCP rules, with loopback listeners
  only. Responses explicitly say `next_connection`; the tool neither reconnects
  a terminal nor claims that an existing tunnel changed. The normal app editor
  retains the broader listen-address options.

Record updates and deletions require a UUID and the `revision` from the latest read;
app appearance updates require the appearance revision without a record ID.
Forward removals also require a 1-based index. Conflicts fail without applying the
edit. Unknown fields and incorrect JSON types fail rather than being ignored.
`--actions` replaces a complete keyboard action list; preserve unrelated keys.
For multiline values use `--json-file PATH` or stdin with `--json-file -` to avoid
shell interpolation. Requests and responses contain user data; do not log them.

## Implementation and checks

The Android implementation is Kotlin and uses an application-owned abstract Unix
socket. Android peer credentials must match the app UID. No TCP/HTTP listener,
exported Android component, remote tunnel, cloud service, model SDK or new direct
dependency is added. The guest helper uses the already-installed Node runtime.
No OpenAI authentication files are read or modified by the installer.

The version-1 protocol is one UTF-8 JSON request and response per connection:
`{"api":1,"method":"snippets.list","params":{}}`. Responses contain `ok` and
either `result` or a redacted `error`. Requests are capped at 64 KiB and responses
at 1 MiB. Processing is serial, peer I/O is time bounded, and failed clients do
not terminate the accept loop. Writes use the process-wide data authority gate,
existing model validation and Room repositories. App-only appearance edits use the
existing DataStore update boundary instead of a Room transaction. Generation notification follows
the durable write without a cancellation gap, so existing UI flows refresh.

Run `scripts/verify-android.sh` for the shared gate. `ControlProtocolTest` covers
framing, schemas, type validation, stale revisions and keyboard preservation.
`scripts/tests/test_spikectl.py` checks the shipped CLI argument contract.
`LocalAgentControlInstrumentedTest` covers real Unix sockets, grant revocation,
repository persistence, stale writes, unsafe edits and guest symlink refusal.
Run device tests only on the authorized old-phone targets from `AGENTS.md`, never
on the Fold. A successful protocol test does not establish actual Codex login or
an agent-driven change; record those separately when physically verified.

Skill discovery follows [OpenAI's agent skill documentation](https://learn.chatgpt.com/docs/build-skills).
