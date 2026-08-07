# ADR-001: Native renderer spike

- Status: Accepted for Phase 0 experiment
- Date: 2026-08-07

## Context

Terminal rendering, high-rate output, smooth pixel scrolling, and keyboard latency are core product risks. Selecting a complete terminal engine before measuring the Android rendering boundary would combine parser, transport, model, and renderer costs.

## Decision

Use native Kotlin. Compose owns UI outside the hot terminal area. A custom hardware-accelerated Canvas-based Android View renders the terminal experiment. Phase 0 includes no real terminal library, parser, SSH transport, or Mosh transport.

## Consequences

The project can measure its own bounded buffer, batching, Canvas drawing, touch/fling, and input path. It is not yet a terminal emulator and deliberately defers complex Unicode/cell semantics. The decision is temporary and will be revisited after measured comparisons with candidate open-source engines and renderers, including licensing review for a possible closed-source commercial product.
