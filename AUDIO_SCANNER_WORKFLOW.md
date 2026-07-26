# Audio Scanner Workflow (scrcpy-isolated capture)
This workflow captures scanner audio from a connected Android phone, isolates that audio from desktop/system audio, and transcribes in chunks on CPU.

## What this setup does
- Uses `scrcpy` with `--no-video` to stream phone audio.
- Auto-routes scrcpy's sink input to a dedicated PulseAudio null sink (`scanner_sink`).
- Captures only `scanner_sink.monitor` using `ffmpeg`.
- Runs Whisper transcription on CPU (`int8`).
- Skips near-silence or heavily clipped chunks to reduce false positives.

## Prerequisites
- Phone connected by USB with USB debugging enabled and authorized (`adb devices` shows `device`).
- `adb`, `ffmpeg`, `pactl` installed.
- scrcpy 2.x+ (this setup uses local `scrcpy 4.1` at `~/.local/bin/scrcpy-4`).

## Run command
```bash
/home/gibi/Desktop/cop_pipeline/bin/python /home/gibi/Desktop/pipeline.py \
  --mode scrcpy \
  --start-scrcpy \
  --scrcpy-bin /home/gibi/.local/bin/scrcpy-4 \
  --serial ZT42269G82 \
  --duration 8
```

## Expected startup output
- `Starting scrcpy: ...`
- `Routed scrcpy sink-input #... to isolated sink 'scanner_sink'.`
- `Listening in mode: scrcpy`
- `Input source node: scanner_sink.monitor`

## Interpreting chunk output
- `[Captured Chatter]: ...` means non-silent audio was captured and transcribed.
- `[Skipped]: near-silence chunk` means silence/noise floor was filtered out.
- `[Skipped]: heavily clipped chunk ...` means invalid/overdriven chunk was filtered out.

## Stop behavior
- `Ctrl-C` performs graceful shutdown and prints `Stopping pipeline.`

## Troubleshooting
- If you see `unauthorized` in `adb devices`, unlock phone and accept USB debugging prompt.
- If scrcpy audio stream is not detected, verify you are using scrcpy 2.x+ (`scrcpy --version`).
- If no transcriptions appear, verify scanner audio is actively playing on phone.
- If false positives appear, reduce clipping at source and keep scanner volume moderate.

## Final architecture state
- `pipeline.py` is now a long-running daemon-style monitor that:
  - handles graceful termination (`SIGTERM`/`SIGINT`)
  - ignores terminal stop-control signals (`SIGTSTP`, `SIGTTIN`, `SIGTTOU`) to reduce accidental pauses
  - recovers from transient loop errors and keeps running until externally killed
- capture path remains isolated:
  - scrcpy audio -> dedicated Pulse sink -> `scanner_sink.monitor` -> ffmpeg wav chunks -> Whisper CPU inference
- logging now includes:
  - human-readable lines (`[Captured Chatter]`, `[Classification]`, `[ALERT_DEBUG]`, `[ALERT_LOG]`, `[FALLBACK_LOG]`, `[RUN_SUMMARY]`)
  - structured JSON integration stream (`[EVENT_JSON]`) for backend/frontend consumption

## Detection logic (final)
- LLM layer still evaluates each transcript (`llama3.1`) but rule logic has been strengthened.
- Dispatch detection now uses weighted cue scoring:
  - `primary_enforcement`: weight 3
  - `location_markers`: weight 2
  - `unit_ack`: weight 1
  - `coordination`: weight 1
- New tunables:
  - `--rule-score-threshold` (default `3`)
  - `--hard-rule-score-threshold` (default `4`)
- New hard alert promotion path:
  - emits `kind=rule_alert_high_confidence` when weighted score + strong enforcement context criteria are satisfied
- Soft fallback behavior tightened:
  - `kind=soft_alert_fallback` now requires stronger context (not only weak ack chatter)

## Integration/event contract
`pipeline.py` emits `[EVENT_JSON]` records with these event types:
- `pipeline_ready`
- `chunk_skipped_silence`
- `chunk_skipped_clipped`
- `chunk_captured`
- `alert_decision`
- `alert_triggered`
- `loop_error`
- `run_summary`

Each event includes UTC timestamp and typed payload fields intended for direct ingestion by a Java API/service layer and HTML frontend.

## Frontend state
`frontend/` now includes:
- `index.html`: dashboard shell
- `styles.css`: responsive layout and modal/visualizer styling
- `app.js`: live UI logic for:
  - route/map controls (Waze launch + embedded map)
  - weather-panel API hook (`/api/route/weather`)
  - alert list + transcript list
  - jurisdiction-edge notices
  - alert modal with visualizer popup
  - SSE integration (`/api/pipeline/stream`) with snapshot fallback (`/api/pipeline/snapshot`)

## Operational notes
- Alert volume remains sparse when feed content is mostly non-enforcement chatter/silence.
- The system is optimized for continuity and observability first (stable long runs + detailed diagnostics), with conservative hard-alert criteria to avoid noisy false positives.
