# ADR-002: Project-owned bounded VT engine

- Status: Accepted for the pre-Mosh product foundation
- Date: 2026-08-07

## Context

The Phase 0 renderer proved that a custom native Android `View` can draw bounded scrollback without per-line or per-cell Compose state. The first SSH slice used a line-oriented compatibility decoder, which could not correctly represent alternate screens, cursor addressing, scroll regions, resizing, application modes, or terminal-cell width.

ConnectBot and Termux-derived terminal components remain useful comparison references, but bringing GPL code or binaries into a possible closed-source commercial product requires a separate architecture and licensing decision. No such dependency is approved. Copying source from other terminal applications is prohibited.

## Decision

Keep the project-owned Canvas renderer and use the project-owned `VtTerminalEngine` as the production-direction parser/screen model for the pre-Mosh application.

The engine must remain:

- Android-independent and JVM-testable;
- bounded in screen dimensions, parameter counts, control-sequence length, scrollback delivery, and response size;
- separate from Compose, Android Views, SSH, persistence, and future Mosh transport code;
- exposed to rendering only as immutable visible lines, cursor state, modes, and completed scrollback;
- resilient to malformed, truncated, oversized, and randomly chunked byte streams.

The renderer remains responsible for drawing visible rows and interaction. `Connection` implementations remain responsible for transport lifecycle and bytes. `TerminalController` batches immutable engine updates onto display frames.

## Implemented coverage

The accepted baseline includes incremental UTF-8, primary and alternate screens, cursor movement/save/restore, scroll regions, insert/delete/erase operations, reverse index, autowrap/origin/cursor visibility modes, standard/bright/256/true-colour SGR, combining and common wide-cell tracking, application cursor keys, bracketed paste, focus reporting, terminal status/device queries, and bounded CSI/OSC discard-and-recovery behavior.

## Known gaps

Mouse button/drag reporting beyond the implemented wheel path, OSC clipboard operations, every DEC private mode, exact bidi/grapheme behavior, complete Unicode width tables, resize reflow, selection/copy UI, and external conformance-corpus comparison remain explicit follow-up work. Until those pass real-device and trace validation, compatibility is described as bounded VT/xterm support rather than complete xterm equivalence.

## Consequences

- SSH and future transports share one terminal state contract.
- Parser/model correctness and fuzz-like malformed-input tests run without a phone or network.
- No new runtime dependency or copyleft obligation is introduced.
- The team owns the maintenance and conformance burden. A future third-party engine replacement requires measured superiority plus an accepted licensing ADR and must preserve the renderer/workload regression tests.
