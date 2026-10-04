# Keyboard snippets

Swipe left across the keyboard helper area to reach Snippets. Snippets wrap to the available width
and scroll vertically when needed. Each action has a bordered button. Long-press a button,
then drag it onto another button and release to move it to that position. The order,
including **New Codex**, is remembered on this device across app restarts. Accessibility
actions also allow moving a button earlier or later. **Add snippet** is a separate,
contrasting button at the top right. **Add snippet** opens the same encrypted snippet
editor used by the catalog. **New Codex** immediately runs the built-in sequence below in the active terminal,
without opening an editor or saving a snippet. It is disabled when terminal input is unavailable.
Saved snippets keep their existing confirmation policy.

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
