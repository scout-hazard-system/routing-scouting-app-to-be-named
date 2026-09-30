# Progress Log
Condensed history of major iteration milestones. This directory is now archival/supporting context; active direction is documented in top-level and subsystem READMEs.

## Current direction
- Maintain a current-only baseline branch and keep docs aligned with shipped behavior.
- Prioritize backend security hardening, explicit denial observability, and stable Android/mobile integrations.
- Keep scout model/runtime references synchronized with deployed versions.

## Format
For each new iteration, record:
- Date/time (UTC)
- Goal
- Changes made
- Validation run
- Outcome and next action

## Historical milestone index

### Iteration 001 — Pipeline stability baseline
- Focus: long-running capture + alert loop resilience.
- Changes: fallback alert path, richer diagnostics, loop resilience.
- Validation: extended runtime monitoring with summary counters.
- Outcome: stable loop, mostly silence-skip workload, fallback alerts observed.
- Sample log: `progress/logs/iteration-001.log`

### Iteration 002 — Frontend stream wiring
- Focus: reliable snapshot + SSE rendering path.
- Changes: frontend data handling, dedupe, replay-safe behavior, dev bridge endpoint flow.
- Validation: snapshot response check and streamed event append test.
- Outcome: replay-safe event handling and live dashboard update behavior.
- Sample log: `progress/logs/iteration-002.log`

### Iteration 003 — Java backend contract foundation
- Focus: core API parity for health/snapshot/stream/weather paths.
- Changes: `/api/health`, `/api/pipeline/snapshot`, `/api/pipeline/stream`, `/api/route/weather`.
- Validation: compile + endpoint smoke tests + SSE verification.
- Outcome: backend endpoint contract became compile/runtime functional.
- Sample log: `progress/logs/iteration-003.log`

### Iteration 004 — Unified launcher + deployment hardening
- Focus: one-command runtime controls and supervision assets.
- Changes: `run_vehicle_stack.sh`, config externalization, systemd user service assets, log maintenance helper.
- Validation: full start/status/health/provider/mobile/stop cycle.
- Outcome: start/status/stop + health-gated cycle established.
- Sample log: `progress/logs/iteration-004.log`

## Notes
- Keep sample logs concise and structured so future debugging can compare iterations quickly.
- When behavior regresses, create a dedicated iteration and link both failing and fixed logs.

## How to use this directory now
- Keep concise iteration notes only when new regressions or major operational shifts occur.
- Store comparative logs under `progress/logs/` with short, structured summaries.
- Use subsystem READMEs as source-of-truth for current behavior; use this file for timeline context.
