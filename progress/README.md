# Progress Log
Condensed history of major iteration milestones. This directory is now archival/supporting context; active direction is documented in top-level and subsystem READMEs.

## Current direction
- Maintain a current-only baseline branch and keep docs aligned with shipped behavior.
- Prioritize backend security hardening, explicit denial observability, and stable Android/mobile integrations.
- Keep scout model/runtime references synchronized with deployed versions.

## Historical milestone index
### Iteration 001 — Pipeline stability baseline
- Focus: long-running capture + alert loop resilience.
- Outcome: stable loop behavior with fallback alert paths and improved diagnostics.

### Iteration 002 — Frontend stream wiring
- Focus: reliable snapshot + SSE rendering path.
- Outcome: replay-safe event handling and live dashboard update behavior.

### Iteration 003 — Java backend contract foundation
- Focus: core API parity for health/snapshot/stream/weather paths.
- Outcome: backend endpoint contract became compile/runtime functional.

### Iteration 004 — Unified launcher + deployment hardening
- Focus: one-command runtime controls and supervision assets.
- Outcome: start/status/stop + health-gated cycle established.

## How to use this directory now
- Keep concise iteration notes only when new regressions or major operational shifts occur.
- Store comparative logs under `progress/logs/` with short, structured summaries.
- Use subsystem READMEs as source-of-truth for current behavior; use this file for timeline context.
