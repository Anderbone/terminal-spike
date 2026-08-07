# ADR-001: Native renderer spike

- Status: Superseded in part by ADR-002; renderer decision retained
- Date: 2026-08-07

## Context

Terminal rendering, high-rate output, smooth pixel scrolling, and keyboard latency are core product risks. Selecting a complete terminal engine before measuring the Android rendering boundary would combine parser, transport, model, and renderer costs.

## Decision

Use native Kotlin. Compose owns UI outside the hot terminal area. A custom hardware-accelerated Canvas-based Android View renders the terminal experiment. Phase 0 includes no real terminal library, parser, SSH transport, or Mosh transport.

## Consequences

The project can measure its own bounded buffer, batching, Canvas drawing, touch/fling, and input path. ADR-002 retains this renderer and ends the temporary no-parser constraint by selecting the project-owned bounded VT engine. Third-party engine adoption still requires measured comparison and a separate licensing decision.
