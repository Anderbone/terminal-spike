# Keyboard snippets

Swipe left across the keyboard helper area to reach Snippets. Snippets wrap to the available width
and scroll vertically when needed. Each action has a bordered button. Tap a button to use it.
Tap **Manage snippets** (✎), beside **Add snippet**, to enter management mode. In this mode,
tap a saved snippet to edit it, or long-press a button and drag it onto another button to
reorder it. Buttons never send commands in management mode. **New Codex · Built-in** can
be reordered but cannot be edited or executed in this mode. Tap **Done** or press Back
to return to normal use; double-tapping no longer opens the editor. The order,
including **New Codex**, is remembered on this device across app restarts. Accessibility
actions in management mode also allow moving a button earlier or later. **Add snippet** is a separate,
contrasting button at the top right. **Add snippet** opens the same snippet
editor used by the catalog. **New Codex** immediately runs the built-in sequence below in the active terminal,
without opening an editor or saving a snippet. It is disabled when terminal input is unavailable.
Saved snippets keep their existing confirmation policy.

Snippet names and commands are stored in the app-private database. They do not receive the
separate Keystore encryption used for saved passwords and private keys. Do not store passwords
or access tokens in snippets.

The Settings snippet editor waits for the saved catalog to acknowledge persistence before it
closes. While saving, its fields, Save, Cancel, and outside/Back dismissal are disabled. A rejected
or failed save keeps the draft available for retry. A built-in default is hidden only after its
replacement saves successfully; starting a save alone does not hide it.

`SettingsToolsScreen.kt` owns this Settings list and its editor. The main Connections catalog
uses `ConnectionsScreen`; terminal shortcut previews use `TerminalShortcutPreview`. The retired
combined tools page is no longer a second implementation of those screens.

When snippets are placed above the keyboard, **Hide snippets** and **Show snippets**
remember your choice on this device, including after returning to the main menu or
restarting the app.

Plain text remains literal, including multiline shell text. Only an exact first line
`#!keys` enables a sequence. Each following line is one action:

- `text ` followed by literal text (spaces are preserved)
- `ctrl+a` through `ctrl+z`: actual control bytes
- `enter`, `tab`, `escape`: actual terminal keys
- `wait 500`: pause in milliseconds (1–10000 per wait, at most 60000 total)

Example:

```text
#!keys
ctrl+c
wait 500
text /clear
enter
wait 500
text codex --yolo
enter
```

Leave “Press Enter after sending” off when the sequence already ends in `enter`.
Text steps use the terminal's paste handling; key steps bypass bracketed paste.
Waits are fixed delays, not remote prompt detection; adjust them for your workflow.
The sequence stops on a rejected send, a disconnected target, or a different active
session before its next step. Another snippet cannot start while a sequence is running.
