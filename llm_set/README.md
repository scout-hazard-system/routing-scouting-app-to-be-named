# Scout LLM set

Purpose-built Ollama models for the scanner pipeline, derived from the local
`llama3.1` base. Each model is defined by a Modelfile in this directory:

- **`scout-alert`** (`Modelfile.scout-alert`) — enforcement alert decision.
  Input: one radio transcript. Output: a single line, `ALERT: <one sentence>`
  or `IGNORE: <one sentence>`.
- **`scout-intel`** (`Modelfile.scout-intel`) — structured dispatch intel.
  Input: one transcript. Output: strict JSON with `call_types`, `priority`,
  `codes`, `units`, `locations`, `pois`, `summary`. Run only on alert-worthy
  transcripts.
- **`scout-rank`** (`Modelfile.scout-rank`) — Broadcastify channel-selector
  reranking. Scores candidate channels 0.0–1.0 for relevance to the driver's
  location/context.

All models run with `temperature 0`, so outputs are deterministic per prompt.

## Build

```bash
# requires a local Ollama daemon with the llama3.1 base model pulled
llm_set/build_llm_set.sh            # builds all three + smoke tests
SKIP_SMOKE=1 llm_set/build_llm_set.sh   # skip smoke tests
```

## Evaluation harness

`eval_cases.json` holds 35 labeled transcripts (alert decisions plus 21
intel-extraction cases). `eval_llm_set.py` scores the built models against
them:

```bash
cop_pipeline/bin/python3 llm_set/eval_llm_set.py \
  --json /tmp/scout_eval.json --threshold 0.9
# useful flags: --alert-only / --intel-only, --alert-model, --intel-model, --timeout
```

Intel location scoring accepts a `places` union (locations ∪ pois) for
boundary cases like apartment complexes.

Results after prompt tuning (commit `6fb8953`):
- scout-alert: accuracy 0.60 → **1.00** (35/35, echo-format 13/13), mean 0.42 s/call
- scout-intel: score 0.52 → **1.00** (21/21), mean 1.28 s/call

Caution from tuning: SYSTEM prompt edits are whack-a-mole — fixing one case
can silently perturb others, so always re-run the full eval after any
Modelfile change. Few-shot examples in the Modelfiles are deliberately
disjoint from `eval_cases.json`; keep it that way.

## Pipeline integration (`pipeline.py` + `scanner_llm_set.py`)

`scanner_llm_set.py` is the client layer. `resolve_model(name)` returns the
scout model when it is installed in Ollama and falls back to `llama3.1` with
inline prompts (printing a notice) when it is not — the pipeline never hard
fails because the set is unbuilt.

Per transcript:
1. **Alert decision** — `scout-alert` classifies the transcript; hard rule
   checks provide a fallback/backstop (`--soft-alert-fallback`).
2. **Intel extraction** — for alert-worthy transcripts, `scout-intel`
   produces the structured intel object, attached as `llm_intel` to the
   `alert_triggered` event JSON written to the pipeline log.
3. **Channel selection** — `--selector-ollama-model` defaults to
   `scout-rank` (resolved via `resolve_model`); the selector reranks
   Broadcastify candidates with it unless `--no-selector-use-ollama-rerank`.

The Java backend (`frontend/java_backend/`) tails the pipeline log and fans
events out over SSE. `GET /api/platform/llm/status` reports Ollama
reachability and per-model availability.

## Android consumption

- **Stream log + popup** — the app parses `llm_intel` into a one-line intel
  summary (call types, priority, units, plus the summary sentence) shown in
  the alert popup and the stream log. Popups appear for `alert_triggered`
  events even when no location was mentioned (Route button disabled).
- **Audio visualizer** — `chunk_captured`/`alert_triggered` events carry an
  `audio_levels` per-window RMS envelope (250 ms windows, capped at 240) and
  the popup visualizer plays back that actual envelope in a loop, falling
  back to a static `rms`-derived amplitude for events without it.
- **Routing** — "Route to this location" picks the first *routable* mention:
  bare directional tokens (`northbound`, `southbound`, …) and road furniture
  (`shoulder`, `on-ramp`, …) are skipped, and directional words are stripped
  from the chosen query. The geocode request includes the device (or map)
  lat/lon so the backend biases Nominatim results to a local viewbox before
  falling back to a global search (commit `6886490`).
- **Android Auto** — alert CarToasts are prefixed with `[PRIORITY]` from
  `llm_intel`.

## End-to-end test procedure

Verified 2026-07-28 (commits `2f871ca` + `6886490`):
1. Synthesize a scanner-style WAV (no TTS engine needed): route `spd-say`
   through a temporary PulseAudio null sink and record the monitor with
   ffmpeg (16 kHz mono). Test line: "Unit 12, we are set up with radar on
   Commercial Avenue near the ferry terminal, clocking northbound traffic…"
2. Run a pipeline instance against the file:
   `pipeline.py --mode broadcastify --stream-url <wav> --log-file <log the backend tails>`
   (it loops the file, emitting an alert per pass).
3. Observed: Whisper transcribed correctly; `llm_alert=True` with
   `hard_rule_alert=True`; `alert_triggered` carried `llm_intel` with
   `call_types=["speed_enforcement"]`, `priority=medium`, `units=["Unit 12"]`,
   `locations=["Commercial Avenue"]`, `pois=["ferry terminal"]`.
4. On device: popup showed mentions + intel line; Route geocoded
   "Commercial Avenue" (junk mention "northbound" skipped) to Anacortes
   (48.507, -122.613) and drew the local route.
