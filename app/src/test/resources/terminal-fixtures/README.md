# Terminal trace fixtures

These compact byte traces are project-authored from the public ECMA-48 control-sequence model and
the xterm control-sequence reference. They are test inputs, not copied terminal-application source
or output. Hex encoding keeps control bytes reviewable in source control and lets the test replay
the same bytes under arbitrary network chunk boundaries.

- `insert-keypad.trace`: standard insert/replace mode plus DEC application-keypad toggles.
- `input-modes-cursor.trace`: IRM replacement boundary, DECKPAM, and DECSCUSR cursor state.
- `osc8-wrap.trace`: OSC 8 hyperlink open/close followed by a four-column autowrap.
- `alternate-mouse.trace`: alternate-screen and detailed DEC mouse-mode transitions.
- `unicode-grapheme.trace`: UTF-8 split boundaries, a joined emoji, flag, and box-drawing cells.

Each non-comment line is a contiguous byte segment. The tests concatenate all segments in order.
